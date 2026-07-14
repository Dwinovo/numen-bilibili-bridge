package com.dwinovo.bilibridge.bili;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BiliPacketTest {

    private static byte[] bytes(ByteBuffer buf) {
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    @Test
    void encodeDecodeRoundTrip() {
        byte[] raw = bytes(BiliPacket.encode(1, BiliPacket.OP_AUTH, "{\"uid\":0}"));
        List<BiliPacket.Frame> frames = BiliPacket.decode(raw);
        assertEquals(1, frames.size());
        assertEquals(1, frames.get(0).version());
        assertEquals(BiliPacket.OP_AUTH, frames.get(0).operation());
        assertEquals("{\"uid\":0}", new String(frames.get(0).body(), StandardCharsets.UTF_8));
    }

    @Test
    void decodeSplitsConcatenatedPackets() {
        ByteArrayOutputStream concat = new ByteArrayOutputStream();
        concat.writeBytes(bytes(BiliPacket.encode(0, BiliPacket.OP_COMMAND, "{\"cmd\":\"A\"}")));
        concat.writeBytes(bytes(BiliPacket.encode(0, BiliPacket.OP_COMMAND, "{\"cmd\":\"B\"}")));
        List<BiliPacket.Frame> frames = BiliPacket.decode(concat.toByteArray());
        assertEquals(2, frames.size());
        assertEquals("{\"cmd\":\"B\"}", new String(frames.get(1).body(), StandardCharsets.UTF_8));
    }

    @Test
    void truncatedTailIsDroppedNotFatal() {
        byte[] whole = bytes(BiliPacket.encode(0, BiliPacket.OP_COMMAND, "{\"cmd\":\"A\"}"));
        byte[] plusJunk = new byte[whole.length + 7];
        System.arraycopy(whole, 0, plusJunk, 0, whole.length);
        assertEquals(1, BiliPacket.decode(plusJunk).size());
    }

    @Test
    void inflateRecoversPacketConcatenation() throws Exception {
        // A ver=2 body is a zlib stream that inflates to MORE packets — the real
        // nesting the server uses for op=5 commands.
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(bytes(BiliPacket.encode(0, BiliPacket.OP_COMMAND, "{\"cmd\":\"DANMU_MSG\"}")));
        inner.writeBytes(bytes(BiliPacket.encode(0, BiliPacket.OP_COMMAND, "{\"cmd\":\"X\"}")));
        byte[] plain = inner.toByteArray();

        Deflater deflater = new Deflater();
        deflater.setInput(plain);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        while (!deflater.finished()) {
            compressed.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();

        byte[] inflated = BiliPacket.inflate(compressed.toByteArray());
        List<BiliPacket.Frame> frames = BiliPacket.decode(inflated);
        assertEquals(2, frames.size());
        assertEquals("{\"cmd\":\"DANMU_MSG\"}", new String(frames.get(0).body(), StandardCharsets.UTF_8));
    }
}
