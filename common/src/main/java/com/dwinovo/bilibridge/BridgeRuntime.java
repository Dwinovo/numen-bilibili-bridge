package com.dwinovo.bilibridge;

import com.dwinovo.bilibridge.batch.DanmakuBatcher;
import com.dwinovo.bilibridge.bili.BiliApi;
import com.dwinovo.bilibridge.bili.BiliDanmakuClient;
import com.dwinovo.bilibridge.bili.DanmakuMessage;
import com.dwinovo.bilibridge.config.BridgeConfig;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;

/**
 * Wires the network side to the game side. The danmaku client produces on its own
 * threads into a bounded handoff queue (full → oldest dropped); every server tick
 * drains the queue into the batcher and, when a batch is ready, emits it to every
 * live companion through the numen-api event channel. The companion's own model
 * reads and reacts — this mod calls no LLM.
 */
public final class BridgeRuntime {

    private static final int QUEUE_CAPACITY = 200;

    private static final BridgeRuntime INSTANCE = new BridgeRuntime();

    private final BiliApi api = new BiliApi();
    private final BiliDanmakuClient client = new BiliDanmakuClient(api, this::enqueue);
    private final DanmakuBatcher batcher = new DanmakuBatcher();

    /** Network → game handoff. Guarded by its own monitor; both sides touch it briefly. */
    private final ArrayDeque<DanmakuMessage> queue = new ArrayDeque<>();
    private long droppedOverflow;
    private long batchesEmitted;
    private boolean active;   // server thread only: connect requested and not yet disconnected

    private BridgeRuntime() {}

    public static BridgeRuntime get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // network side
    // ------------------------------------------------------------------

    private boolean queueIsEmpty() {
        synchronized (queue) {
            return queue.isEmpty();
        }
    }

    private void enqueue(DanmakuMessage msg) {
        synchronized (queue) {
            if (queue.size() >= QUEUE_CAPACITY) {
                queue.pollFirst();   // full → drop the oldest, the newest is worth more
                droppedOverflow++;
            }
            queue.addLast(msg);
        }
    }

    // ------------------------------------------------------------------
    // game side (server thread)
    // ------------------------------------------------------------------

    /**
     * Debug entry for {@code /bilibridge test}: one fake danmaku (uid 0, fixed username)
     * dropped into the same handoff queue the network side feeds, so it runs the full
     * batch → filter/aggregate → event pipeline without any connection.
     */
    public void injectTest(String text) {
        enqueue(DanmakuMessage.danmaku(0, "测试观众", text, System.currentTimeMillis()));
    }

    /** Start streaming the configured room. Returns null on success, else a user-facing error. */
    public String connect() {
        BridgeConfig cfg = BridgeConfig.get();
        if (cfg.roomId <= 0) {
            return "未配置直播间号：/bilibridge connect <房间号> 或编辑 config/numen-bilibili-bridge.json";
        }
        active = true;
        client.start(cfg.roomId, cfg.sessdata);
        return null;
    }

    public void disconnect() {
        active = false;
        client.stop();
        batcher.clear();
        synchronized (queue) {
            queue.clear();
        }
    }

    public String status() {
        BridgeConfig cfg = BridgeConfig.get();
        int queued;
        long dropped;
        synchronized (queue) {
            queued = queue.size();
            dropped = droppedOverflow;
        }
        return "房间 " + (cfg.roomId > 0 ? cfg.roomId : "(未配置)")
                + " | " + client.describe()
                + " | 待发批次 " + batcher.pendingCount() + " 条 (queued=" + queued
                + ", overflow_dropped=" + dropped + ", batches=" + batchesEmitted + ")"
                + " | window=" + cfg.batchWindowSeconds + "s max=" + cfg.batchMaxCount
                + " lines=" + cfg.maxLines + " peruser=" + cfg.perUserPerWindow
                + " urgent=" + cfg.urgent
                + " | 身份: " + (cfg.sessdata.isEmpty() ? "匿名（用户名打码）" : "已登录");
    }

    public void onServerStarted(MinecraftServer server) {
        BridgeConfig cfg = BridgeConfig.get();
        if (cfg.autoConnect && cfg.roomId > 0) {
            Constants.LOG.info("[bilibridge] autoConnect — connecting to room {}", cfg.roomId);
            connect();
        }
    }

    /** The world is going away; the network side must not outlive it. */
    public void onServerStopping() {
        if (active) {
            Constants.LOG.info("[bilibridge] server stopping — disconnecting");
        }
        disconnect();
    }

    /** Every server tick: drain the handoff queue, flush a due batch to the companions. */
    public void onServerTick(MinecraftServer server) {
        // Injected test danmaku must flow even without a connection — only skip when idle AND empty.
        if (!active && batcher.pendingCount() == 0 && queueIsEmpty()) return;
        long now = System.currentTimeMillis();
        while (true) {
            DanmakuMessage msg;
            synchronized (queue) {
                msg = queue.pollFirst();
            }
            if (msg == null) break;
            batcher.add(msg, now);
        }
        String xml = batcher.poll(now);
        if (xml == null) return;
        boolean delivered = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer companion) {
                Companions.emitEvent(companion, xml, BridgeConfig.get().urgent);
                delivered = true;
            }
        }
        if (delivered) {
            batchesEmitted++;
        } else {
            Constants.LOG.debug("[bilibridge] batch ready but no live companion — dropped");
        }
    }
}
