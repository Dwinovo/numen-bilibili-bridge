package com.dwinovo.bilibridge;

import com.dwinovo.bilibridge.batch.DanmakuBatcher;
import com.dwinovo.bilibridge.bili.BiliApi;
import com.dwinovo.bilibridge.bili.BiliDanmakuClient;
import com.dwinovo.bilibridge.bili.DanmakuMessage;

import java.util.ArrayDeque;

/**
 * Everything one live room owns: its danmaku client, its batcher, and the bounded
 * network → game handoff queue between them (full → oldest dropped). The runtime
 * keeps one instance per distinct bound room; several companions bound to the same
 * room share it. Batch parameters stay global ({@code BridgeConfig}), only the
 * stream state is per room.
 */
final class RoomConnection {

    private static final int QUEUE_CAPACITY = 200;

    final long roomId;

    private final BiliApi api = new BiliApi();
    private final BiliDanmakuClient client = new BiliDanmakuClient(api, this::enqueue);
    private final DanmakuBatcher batcher = new DanmakuBatcher();

    /** Network → game handoff. Guarded by its own monitor; both sides touch it briefly. */
    private final ArrayDeque<DanmakuMessage> queue = new ArrayDeque<>();
    private long droppedOverflow;
    private long batchesEmitted;

    RoomConnection(long roomId) {
        this.roomId = roomId;
    }

    /** Begin connecting (idempotent while already running). */
    void start(String sessdata) {
        client.start(roomId, sessdata);
    }

    /** Tear the connection down and drop everything buffered. */
    void stop() {
        client.stop();
        batcher.clear();
        synchronized (queue) {
            queue.clear();
        }
    }

    // ---- network side ----

    private void enqueue(DanmakuMessage msg) {
        synchronized (queue) {
            if (queue.size() >= QUEUE_CAPACITY) {
                queue.pollFirst();   // full → drop the oldest, the newest is worth more
                droppedOverflow++;
            }
            queue.addLast(msg);
        }
    }

    // ---- game side (server thread) ----

    /**
     * Debug entry for {@code /bilibridge test}: one fake danmaku (uid 0, fixed
     * username) dropped into the same handoff queue the network side feeds, so it
     * runs the full batch → filter/aggregate → route pipeline without any connection.
     */
    void inject(String text) {
        enqueue(DanmakuMessage.danmaku(0, "测试观众", text, System.currentTimeMillis()));
    }

    /**
     * Every server tick: drain the handoff queue into the batcher and flush a due
     * batch. Returns the rendered {@code <event>} XML, or null when nothing is due.
     */
    String poll(long nowMs) {
        while (true) {
            DanmakuMessage msg;
            synchronized (queue) {
                msg = queue.pollFirst();
            }
            if (msg == null) break;
            batcher.add(msg, nowMs);
        }
        return batcher.poll(nowMs);
    }

    void countEmitted() {
        batchesEmitted++;
    }

    /** One human-readable line for the status command. */
    String describe() {
        int queued;
        long dropped;
        synchronized (queue) {
            queued = queue.size();
            dropped = droppedOverflow;
        }
        return client.describe()
                + " | 待发批次 " + batcher.pendingCount() + " 条 (queued=" + queued
                + ", overflow_dropped=" + dropped + ", batches=" + batchesEmitted + ")";
    }
}
