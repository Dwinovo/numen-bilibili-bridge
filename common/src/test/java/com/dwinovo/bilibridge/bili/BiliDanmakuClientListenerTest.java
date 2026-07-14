package com.dwinovo.bilibridge.bili;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the receive side of {@link BiliDanmakuClient}. The JDK
 * WebSocket delivers exactly as many callback invocations as were requested via
 * {@code request(n)}; if any callback path fails to re-request, the connection
 * starves silently and permanently ("authenticated, then nothing, forever").
 * These tests drive every callback path against a fake socket and assert each
 * one renews demand by exactly 1 — plus the fragment reassembly and the
 * zlib-nested command parsing the paths depend on.
 */
class BiliDanmakuClientListenerTest {

    /** Records demand instead of doing I/O. */
    private static final class FakeWebSocket implements WebSocket {
        long requested;

        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long n) { requested += n; }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() {}
    }

    private final List<DanmakuMessage> received = new ArrayList<>();
    private final FakeWebSocket socket = new FakeWebSocket();
    private WebSocket.Listener listener;

    @BeforeEach
    void setUp() {
        BiliDanmakuClient client = new BiliDanmakuClient(new BiliApi(), received::add);
        listener = client.newListener(0);
    }

    private static ByteBuffer danmuPacket(String text) {
        String json = "{\"cmd\":\"DANMU_MSG\",\"info\":[[],\"" + text + "\",[42,\"tester\"]]}";
        return BiliPacket.encode(BiliPacket.VER_PLAIN, BiliPacket.OP_COMMAND, json);
    }

    // ------------------------------------------------------------------
    // request(n) accounting: every callback path must renew demand
    // ------------------------------------------------------------------

    @Test
    void onOpenRequestsFirstMessage() {
        listener.onOpen(socket);
        assertEquals(1, socket.requested);
    }

    @Test
    void everyBinaryFragmentRenewsDemand() {
        ByteBuffer whole = danmuPacket("hello");
        byte[] bytes = new byte[whole.remaining()];
        whole.get(bytes);
        int cut = bytes.length / 2;

        listener.onBinary(socket, ByteBuffer.wrap(bytes, 0, cut), false);
        assertEquals(1, socket.requested, "partial fragment (last=false) must re-request");

        listener.onBinary(socket, ByteBuffer.wrap(bytes, cut, bytes.length - cut), true);
        assertEquals(2, socket.requested, "final fragment (last=true) must re-request");

        assertEquals(1, received.size(), "fragments must reassemble into one message");
        assertEquals("hello", received.get(0).text());
    }

    @Test
    void corruptFrameStillRenewsDemand() {
        byte[] garbage = new byte[64];   // decodes to nothing / nonsense — must not starve demand
        listener.onBinary(socket, ByteBuffer.wrap(garbage), true);
        assertEquals(1, socket.requested);
        assertTrue(received.isEmpty());
    }

    @Test
    void textPingPongAllRenewDemand() {
        listener.onText(socket, "ignored", true);
        assertEquals(1, socket.requested, "onText must re-request");

        listener.onPing(socket, ByteBuffer.allocate(0));
        assertEquals(2, socket.requested, "onPing must re-request");

        listener.onPong(socket, ByteBuffer.allocate(0));
        assertEquals(3, socket.requested, "onPong must re-request");
    }

    // ------------------------------------------------------------------
    // frame handling the accounting protects
    // ------------------------------------------------------------------

    @Test
    void zlibCommandFrameParsesNestedDanmaku() throws Exception {
        ByteArrayOutputStream concat = new ByteArrayOutputStream();
        ByteBuffer p1 = danmuPacket("first");
        ByteBuffer p2 = danmuPacket("second");
        while (p1.hasRemaining()) concat.write(p1.get());
        while (p2.hasRemaining()) concat.write(p2.get());

        Deflater deflater = new Deflater();
        deflater.setInput(concat.toByteArray());
        deflater.finish();
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            zipped.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        ByteBuffer frame = BiliPacket.encode(BiliPacket.VER_ZLIB, BiliPacket.OP_COMMAND, zipped.toByteArray());

        listener.onBinary(socket, frame, true);

        assertEquals(1, socket.requested);
        assertEquals(2, received.size());
        assertEquals("first", received.get(0).text());
        assertEquals("second", received.get(1).text());
    }

    @Test
    void multiplePacketsInOneMessageAllParse() {
        ByteBuffer p1 = danmuPacket("a");
        ByteBuffer heartbeatReply = BiliPacket.encode(BiliPacket.VER_HEARTBEAT, BiliPacket.OP_HEARTBEAT_REPLY,
                new byte[] {0, 0, 0, 9});
        ByteBuffer p2 = danmuPacket("b");
        ByteBuffer all = ByteBuffer.allocate(p1.remaining() + heartbeatReply.remaining() + p2.remaining());
        all.put(p1).put(heartbeatReply).put(p2).flip();

        listener.onBinary(socket, all, true);

        assertEquals(1, socket.requested);
        assertEquals(2, received.size());
        assertEquals("a", received.get(0).text());
        assertEquals("b", received.get(1).text());
    }

    @Test
    void listenersDoNotShareReassemblyState() {
        BiliDanmakuClient client = new BiliDanmakuClient(new BiliApi(), received::add);
        WebSocket.Listener first = client.newListener(0);
        WebSocket.Listener second = client.newListener(1);

        // First connection dies mid-message: a dangling fragment stays behind.
        ByteBuffer whole = danmuPacket("dead");
        byte[] bytes = new byte[whole.remaining()];
        whole.get(bytes);
        first.onBinary(socket, ByteBuffer.wrap(bytes, 0, bytes.length / 2), false);

        // The next connection must start from a clean buffer, not inherit the garbage.
        second.onBinary(socket, danmuPacket("alive"), true);
        assertEquals(1, received.size());
        assertEquals("alive", received.get(0).text());
    }

    @Test
    void utf8DanmakuSurvivesFragmentationInsideACharacter() {
        ByteBuffer whole = danmuPacket("你好世界");
        byte[] bytes = new byte[whole.remaining()];
        whole.get(bytes);
        // Cut inside the multi-byte payload: reassembly must be byte-exact.
        int cut = bytes.length - 5;
        listener.onBinary(socket, ByteBuffer.wrap(bytes, 0, cut), false);
        listener.onBinary(socket, ByteBuffer.wrap(bytes, cut, bytes.length - cut), true);

        assertEquals(1, received.size());
        assertEquals("你好世界", received.get(0).text());
        assertEquals(new String("你好世界".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                received.get(0).text());
    }
}
