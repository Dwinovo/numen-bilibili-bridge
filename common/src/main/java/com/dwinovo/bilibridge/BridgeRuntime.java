package com.dwinovo.bilibridge;

import com.dwinovo.bilibridge.bind.BindingRegistry;
import com.dwinovo.bilibridge.bind.BindingRouter;
import com.dwinovo.bilibridge.config.BridgeConfig;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wires the network side to the game side under the "one companion serves one
 * live room" model. The {@link BindingRegistry} pairs companion names with rooms
 * and drives one {@link RoomConnection} per distinct bound room (binding IS
 * connecting); every server tick each room drains its queue and, when a batch is
 * ready, the {@link BindingRouter} picks which live bound companions receive it
 * through the numen-api event channel. A bound companion that is absent gets the
 * batch dropped, announced once per absence streak. The companion's own model
 * reads and reacts — this mod calls no LLM.
 */
public final class BridgeRuntime {

    private static final BridgeRuntime INSTANCE = new BridgeRuntime();

    /** roomId → its shared connection. Server thread only. */
    private final Map<Long, RoomConnection> rooms = new LinkedHashMap<>();
    private final BindingRouter router = new BindingRouter();
    private final BindingRegistry registry = new BindingRegistry(new BindingRegistry.RoomLifecycle() {
        @Override
        public void open(long roomId) {
            rooms.computeIfAbsent(roomId, RoomConnection::new).start(BridgeConfig.get().sessdata);
        }

        @Override
        public void close(long roomId) {
            RoomConnection room = rooms.remove(roomId);
            if (room != null) room.stop();
            router.forgetRoom(roomId);
        }
    });

    private BridgeRuntime() {}

    public static BridgeRuntime get() {
        return INSTANCE;
    }

    public BindingRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------
    // bindings (server thread, driven by /bilibridge)
    // ------------------------------------------------------------------

    /** Bind + persist + connect. Returns the name's previous room, or null. */
    public Long bind(String name, long roomId) {
        Long prev = registry.bind(name, roomId);
        if (prev != null && prev != roomId) router.forget(prev, name);
        persistBindings();
        return prev;
    }

    /** Unbind + persist; the room disconnects when its last binding leaves. Null if unbound. */
    public Long unbind(String name) {
        Long prev = registry.unbind(name);
        if (prev != null) {
            router.forget(prev, name);
            persistBindings();
        }
        return prev;
    }

    private void persistBindings() {
        BridgeConfig cfg = BridgeConfig.get();
        cfg.bindings = registry.snapshot();
        cfg.save();
    }

    /** Inject one test danmaku into the room's pipeline. False when the room has no connection. */
    public boolean injectTest(long roomId, String text) {
        RoomConnection room = rooms.get(roomId);
        if (room == null) return false;
        room.inject(text);
        return true;
    }

    public String status() {
        BridgeConfig cfg = BridgeConfig.get();
        if (registry.isEmpty()) {
            return "尚未绑定任何同伴。/bilibridge bind <同伴名> <房间号> 建立绑定后即自动连接直播间，"
                    + "该房间的弹幕只送达这个同伴。";
        }
        StringBuilder sb = new StringBuilder();
        for (long roomId : registry.rooms()) {
            RoomConnection room = rooms.get(roomId);
            sb.append("房间 ").append(roomId)
              .append(" ↔ 同伴 ").append(String.join("、", registry.namesFor(roomId)))
              .append(" | ").append(room == null ? "未连接" : room.describe())
              .append('\n');
        }
        sb.append("window=").append(cfg.batchWindowSeconds).append("s max=").append(cfg.batchMaxCount)
          .append(" lines=").append(cfg.maxLines).append(" peruser=").append(cfg.perUserPerWindow)
          .append(" urgent=").append(cfg.urgent)
          .append(" | 身份: ").append(cfg.sessdata.isEmpty() ? "匿名（用户名打码）" : "已登录");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // server lifecycle
    // ------------------------------------------------------------------

    public void onServerStarted(MinecraftServer server) {
        BridgeConfig cfg = BridgeConfig.get();
        if (!cfg.bindings.isEmpty()) {
            Constants.LOG.info("[bilibridge] {} binding(s) configured — connecting bound rooms", cfg.bindings.size());
        }
        registry.resetFrom(cfg.bindings);
    }

    /** The world is going away; the network side must not outlive it. */
    public void onServerStopping() {
        if (!rooms.isEmpty()) {
            Constants.LOG.info("[bilibridge] server stopping — disconnecting {} room(s)", rooms.size());
        }
        registry.closeAll();
        // Defensive: no room may outlive the world even if the table went out of sync.
        rooms.values().forEach(RoomConnection::stop);
        rooms.clear();
        router.reset();
    }

    /** Every server tick: per room, drain the handoff queue and route a due batch. */
    public void onServerTick(MinecraftServer server) {
        if (rooms.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<NumenPlayer> live = null;
        for (RoomConnection room : rooms.values()) {
            String xml = room.poll(now);
            if (xml == null) continue;
            if (live == null) live = liveCompanions(server);
            deliver(server, room, xml, live);
        }
    }

    private static List<NumenPlayer> liveCompanions(MinecraftServer server) {
        List<NumenPlayer> live = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer companion) live.add(companion);
        }
        return live;
    }

    private void deliver(MinecraftServer server, RoomConnection room, String xml, List<NumenPlayer> live) {
        List<String> liveNames = new ArrayList<>(live.size());
        for (NumenPlayer companion : live) liveNames.add(companion.getName().getString());
        BindingRouter.Routing routing = router.route(room.roomId, registry.namesFor(room.roomId), liveNames);

        boolean urgent = BridgeConfig.get().urgent;
        boolean delivered = false;
        for (String name : routing.deliverTo()) {
            for (NumenPlayer companion : live) {
                if (companion.getName().getString().equals(name)) {
                    Companions.emitEvent(companion, xml, urgent);
                    delivered = true;
                }
            }
        }
        if (delivered) {
            room.countEmitted();
        } else {
            Constants.LOG.debug("[bilibridge] room {} batch ready but no bound companion live — dropped",
                    room.roomId);
        }
        for (String name : routing.newlyAbsent()) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                    "[bilibridge] 绑定的同伴 " + name + " 不在场，房间 " + room.roomId
                            + " 的本批弹幕已丢弃（它回来前不再重复提示）"), false);
        }
    }
}
