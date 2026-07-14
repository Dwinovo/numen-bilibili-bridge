package com.dwinovo.bilibridge.bili;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The live-stream binary framing: every packet is a 16-byte big-endian header
 * (u32 total length, u16 header length, u16 protocol version, u32 operation,
 * u32 sequence) followed by the body. A binary WebSocket message may carry
 * SEVERAL packets back to back, and a zlib body (version 2) inflates to yet
 * another packet concatenation — {@link #decode} handles the splitting, the
 * caller recurses after inflating. Details in {@code docs/protocol.md}.
 */
public final class BiliPacket {

    public static final int OP_HEARTBEAT = 2;        // client → server, every 30 s
    public static final int OP_HEARTBEAT_REPLY = 3;  // body starts with u32 popularity
    public static final int OP_COMMAND = 5;          // JSON command (danmaku live here)
    public static final int OP_AUTH = 7;             // client → server, within 5 s of connecting
    public static final int OP_AUTH_REPLY = 8;       // body {"code":0} on success

    public static final int VER_PLAIN = 0;    // body is plain JSON
    public static final int VER_HEARTBEAT = 1; // body is a bare integer (and what we send with)
    public static final int VER_ZLIB = 2;     // body is zlib-compressed packet concatenation
    public static final int VER_BROTLI = 3;   // never sent when the client declares protover 2

    private static final int HEADER_LEN = 16;

    /** One decoded packet. */
    public record Frame(int version, int operation, byte[] body) {}

    private BiliPacket() {}

    /** Encode one packet ready to send. */
    public static ByteBuffer encode(int version, int operation, byte[] body) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + body.length);
        buf.putInt(HEADER_LEN + body.length);
        buf.putShort((short) HEADER_LEN);
        buf.putShort((short) version);
        buf.putInt(operation);
        buf.putInt(1);
        buf.put(body);
        buf.flip();
        return buf;
    }

    public static ByteBuffer encode(int version, int operation, String body) {
        return encode(version, operation, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Split a byte run into its packets. Tolerant of a truncated tail (returns what
     * parsed cleanly) — the transport layer should never hand us one, but a corrupt
     * length field must not take the connection down.
     */
    public static List<Frame> decode(byte[] data) {
        List<Frame> frames = new ArrayList<>();
        int offset = 0;
        while (offset + HEADER_LEN <= data.length) {
            ByteBuffer buf = ByteBuffer.wrap(data, offset, HEADER_LEN);
            int packetLen = buf.getInt();
            int headerLen = buf.getShort() & 0xFFFF;
            int version = buf.getShort() & 0xFFFF;
            int operation = buf.getInt();
            if (packetLen < HEADER_LEN || headerLen < HEADER_LEN || offset + packetLen > data.length) {
                break;   // corrupt or truncated — keep what we have
            }
            byte[] body = new byte[packetLen - headerLen];
            System.arraycopy(data, offset + headerLen, body, 0, body.length);
            frames.add(new Frame(version, operation, body));
            offset += packetLen;
        }
        return frames;
    }

    /** Inflate a version-2 body back into a packet concatenation. */
    public static byte[] inflate(byte[] data) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 4));
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;   // truncated input
                } else {
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
