package com.dwinovo.bilibridge.bili;

import com.dwinovo.bilibridge.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The danmaku WebSocket connection. Owns a single scheduler thread for all
 * lifecycle work (REST prelude, connect, heartbeat, reconnect); JDK WebSocket
 * listener callbacks do the frame reassembly and command parsing on the
 * HttpClient's threads (the listener contract serializes them). Parsed messages
 * are handed to the {@code sink} — still off-thread; the game side is bridged by
 * the runtime's bounded queue. Nothing in this class may touch game state.
 */
public final class BiliDanmakuClient implements WebSocket.Listener {

    public enum State { IDLE, CONNECTING, CONNECTED, RECONNECTING }

    private static final long HEARTBEAT_PERIOD_S = 30;   // 60 s of silence gets the client kicked
    private static final int MAX_BACKOFF_S = 30;
    /** This many failures in a row → the cached token/host list is stale, redo the REST prelude. */
    private static final int FAILURES_BEFORE_REFRESH = 3;

    private final BiliApi api;
    private final Consumer<DanmakuMessage> sink;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bilibridge-net");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient wsHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ---- lifecycle state: touched only on the exec thread ----
    private boolean running;
    private long generation;            // bumps on every stop/reconnect; stale sockets are ignored
    private long roomId;                // as configured (possibly a short id)
    private BiliApi.DanmuInfo info;     // REST prelude result; null → prelude must (re)run
    private int hostIndex;
    private int consecutiveFailures;
    private WebSocket ws;
    private ScheduledFuture<?> heartbeatTask;
    private CompletableFuture<WebSocket> sendChain = CompletableFuture.completedFuture(null);

    // ---- frame reassembly: touched only in listener callbacks (serialized by contract) ----
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

    // ---- observability: read from the game thread by /bilibridge status ----
    private volatile State state = State.IDLE;
    private volatile String statusDetail = "";
    private volatile long connectedRoomId;
    private volatile String connectedHost = "";
    private final AtomicLong danmakuCount = new AtomicLong();
    private final AtomicLong superChatCount = new AtomicLong();
    private volatile long lastMessageAtMs;
    private boolean warnedBrotli;

    public BiliDanmakuClient(BiliApi api, Consumer<DanmakuMessage> sink) {
        this.api = api;
        this.sink = sink;
    }

    /** Begin connecting to {@code roomId} (short id fine). Idempotent while already running. */
    public void start(long roomId, String sessdata) {
        exec.execute(() -> {
            if (running) return;
            running = true;
            this.roomId = roomId;
            this.info = null;
            this.hostIndex = 0;
            this.consecutiveFailures = 0;
            api.setSessdata(sessdata);
            setState(State.CONNECTING, "resolving room " + roomId);
            connectNow(++generation);
        });
    }

    /** Tear the connection down and stop reconnecting. */
    public void stop() {
        exec.execute(() -> {
            running = false;
            generation++;
            cancelHeartbeat();
            if (ws != null) {
                ws.abort();
                ws = null;
            }
            setState(State.IDLE, "");
        });
    }

    public State state() {
        return state;
    }

    /** One human-readable line for the status command. */
    public String describe() {
        State s = state;
        StringBuilder sb = new StringBuilder(s.name());
        if (s == State.CONNECTED) {
            sb.append(" room=").append(connectedRoomId).append(" host=").append(connectedHost);
        } else if (!statusDetail.isEmpty()) {
            sb.append(" (").append(statusDetail).append(')');
        }
        sb.append(" | danmaku=").append(danmakuCount.get())
          .append(" superchat=").append(superChatCount.get());
        long last = lastMessageAtMs;
        if (last > 0) {
            sb.append(" last=").append((System.currentTimeMillis() - last) / 1000).append("s ago");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // connect / reconnect (exec thread)
    // ------------------------------------------------------------------

    private void connectNow(long gen) {
        if (!running || gen != generation) return;
        try {
            if (info == null) {
                info = api.prepare(roomId);
                hostIndex = 0;
                connectedRoomId = info.realRoomId();
            }
        } catch (Exception e) {
            Constants.LOG.warn("[bilibridge] REST prelude failed: {}", e.toString());
            api.invalidate();
            scheduleReconnect(gen, "prelude: " + e.getMessage());
            return;
        }
        BiliApi.Host host = info.hosts().get(hostIndex % info.hosts().size());
        URI uri = URI.create("wss://" + host.host() + ":" + host.wssPort() + "/sub");
        setState(State.CONNECTING, "connecting " + host.host());
        Constants.LOG.info("[bilibridge] connecting to room {} via {}", info.realRoomId(), host.host());
        wsHttp.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenCompleteAsync((socket, err) -> {
                    if (!running || gen != generation) {
                        if (socket != null) socket.abort();
                        return;
                    }
                    if (err != null) {
                        Constants.LOG.warn("[bilibridge] connect to {} failed: {}", host.host(), err.toString());
                        scheduleReconnect(gen, "connect: " + err.getMessage());
                        return;
                    }
                    ws = socket;
                    connectedHost = host.host();
                    sendChain = CompletableFuture.completedFuture(socket);
                    sendAuth(socket);
                }, exec);
    }

    private void scheduleReconnect(long gen, String reason) {
        if (!running || gen != generation) return;
        cancelHeartbeat();
        if (ws != null) {
            ws.abort();
            ws = null;
        }
        consecutiveFailures++;
        hostIndex++;                                     // rotate hosts on every retry
        if (consecutiveFailures >= FAILURES_BEFORE_REFRESH) {
            info = null;                                 // token/hosts presumed stale → redo steps 1–4
            api.invalidate();
        }
        long delayS = Math.min(MAX_BACKOFF_S, 1L << Math.min(consecutiveFailures, 5));
        setState(State.RECONNECTING, reason + " — retry in " + delayS + "s");
        long nextGen = ++generation;                     // orphan any in-flight callbacks
        exec.schedule(() -> connectNow(nextGen), delayS, TimeUnit.SECONDS);
    }

    private void sendAuth(WebSocket socket) {
        JsonObject auth = new JsonObject();
        auth.addProperty("uid", info.uid());
        auth.addProperty("roomid", info.realRoomId());
        auth.addProperty("protover", 2);                 // 2 → the server compresses with zlib only
        auth.addProperty("platform", "web");
        auth.addProperty("type", 2);
        auth.addProperty("buvid", info.buvid3());
        auth.addProperty("key", info.token());
        send(socket, BiliPacket.encode(BiliPacket.VER_HEARTBEAT, BiliPacket.OP_AUTH, auth.toString()));
    }

    private void onAuthenticated() {
        consecutiveFailures = 0;
        setState(State.CONNECTED, "");
        Constants.LOG.info("[bilibridge] authenticated in room {}", info.realRoomId());
        cancelHeartbeat();
        long gen = generation;
        heartbeatTask = exec.scheduleAtFixedRate(() -> {
            if (!running || gen != generation || ws == null) return;
            send(ws, BiliPacket.encode(BiliPacket.VER_HEARTBEAT, BiliPacket.OP_HEARTBEAT, "[object Object]"));
        }, 0, HEARTBEAT_PERIOD_S, TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    /** JDK WebSocket rejects overlapping sends — chain them so each waits for the previous. */
    private synchronized void send(WebSocket socket, ByteBuffer data) {
        sendChain = sendChain
                .thenCompose(w -> socket.sendBinary(data, true))
                .exceptionally(e -> {
                    Constants.LOG.debug("[bilibridge] send failed: {}", e.toString());
                    return socket;
                });
    }

    private void setState(State s, String detail) {
        state = s;
        statusDetail = detail;
    }

    // ------------------------------------------------------------------
    // WebSocket.Listener (HttpClient threads — hand lifecycle back to exec)
    // ------------------------------------------------------------------

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        // The JDK delivers partial messages: accumulate until last==true, then parse.
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        partial.write(chunk, 0, chunk.length);
        if (last) {
            byte[] whole = partial.toByteArray();
            partial.reset();
            try {
                handleFrames(BiliPacket.decode(whole));
            } catch (Exception e) {
                Constants.LOG.warn("[bilibridge] frame handling error: {}", e.toString());
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        exec.execute(() -> {
            if (ws == webSocket) {
                Constants.LOG.warn("[bilibridge] connection closed ({} {})", statusCode, reason);
                scheduleReconnect(generation, "closed " + statusCode);
            }
        });
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        exec.execute(() -> {
            if (ws == webSocket) {
                Constants.LOG.warn("[bilibridge] connection error: {}", error.toString());
                scheduleReconnect(generation, "error: " + error.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // frame + command handling (listener thread)
    // ------------------------------------------------------------------

    private void handleFrames(List<BiliPacket.Frame> frames) throws Exception {
        for (BiliPacket.Frame frame : frames) {
            switch (frame.operation()) {
                case BiliPacket.OP_AUTH_REPLY -> {
                    JsonObject reply = JsonParser.parseString(
                            new String(frame.body(), StandardCharsets.UTF_8)).getAsJsonObject();
                    int code = reply.has("code") ? reply.get("code").getAsInt() : -1;
                    exec.execute(() -> {
                        if (!running) return;
                        if (code == 0) {
                            onAuthenticated();
                        } else {
                            Constants.LOG.warn("[bilibridge] auth rejected, code {}", code);
                            info = null;   // token burned — redo the prelude
                            scheduleReconnect(generation, "auth code " + code);
                        }
                    });
                }
                case BiliPacket.OP_HEARTBEAT_REPLY -> {
                    // First 4 bytes are the room's popularity value; nothing to do with it.
                }
                case BiliPacket.OP_COMMAND -> handleCommandFrame(frame);
                default -> { /* ignore */ }
            }
        }
    }

    private void handleCommandFrame(BiliPacket.Frame frame) throws Exception {
        switch (frame.version()) {
            case BiliPacket.VER_ZLIB ->
                    // zlib body inflates to a packet CONCATENATION — recurse through the decoder.
                    handleFrames(BiliPacket.decode(BiliPacket.inflate(frame.body())));
            case BiliPacket.VER_PLAIN ->
                    handleCommand(new String(frame.body(), StandardCharsets.UTF_8));
            case BiliPacket.VER_BROTLI -> {
                if (!warnedBrotli) {
                    warnedBrotli = true;
                    Constants.LOG.warn("[bilibridge] server sent a brotli frame despite protover 2 — dropping");
                }
            }
            default -> Constants.LOG.debug("[bilibridge] command frame with unknown version {}", frame.version());
        }
    }

    private void handleCommand(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String cmd = obj.has("cmd") ? obj.get("cmd").getAsString() : "";
            // Some commands arrive with a suffix, e.g. "DANMU_MSG:4:0:2:2:2:0" — match by prefix.
            if (cmd.startsWith("DANMU_MSG")) {
                handleDanmu(obj);
            } else if (cmd.equals("SUPER_CHAT_MESSAGE")) {
                handleSuperChat(obj);
            }
            // TODO SEND_GIFT / WATCHED_CHANGE: parse and surface once the companion has a use for them.
        } catch (Exception e) {
            Constants.LOG.debug("[bilibridge] unparseable command: {}", e.toString());
        }
    }

    private void handleDanmu(JsonObject obj) {
        JsonArray info = obj.getAsJsonArray("info");
        String text = info.get(1).getAsString();
        JsonArray user = info.get(2).getAsJsonArray();
        long uid = user.get(0).getAsLong();
        String username = user.get(1).getAsString();
        long tsMs = System.currentTimeMillis();
        try {
            JsonElement meta = info.get(9);
            if (meta.isJsonObject() && meta.getAsJsonObject().has("ts")) {
                tsMs = meta.getAsJsonObject().get("ts").getAsLong() * 1000L;
            }
        } catch (Exception ignored) {
            // info[9] layout varies; the local clock is a fine fallback
        }
        danmakuCount.incrementAndGet();
        lastMessageAtMs = System.currentTimeMillis();
        sink.accept(DanmakuMessage.danmaku(uid, username, text, tsMs));
    }

    private void handleSuperChat(JsonObject obj) {
        JsonObject data = obj.getAsJsonObject("data");
        String text = data.get("message").getAsString();
        double price = data.get("price").getAsDouble();
        long uid = data.has("uid") ? data.get("uid").getAsLong() : 0;
        String username = data.has("user_info")
                ? data.getAsJsonObject("user_info").get("uname").getAsString() : "?";
        superChatCount.incrementAndGet();
        lastMessageAtMs = System.currentTimeMillis();
        sink.accept(DanmakuMessage.superChat(uid, username, text, price));
    }
}
