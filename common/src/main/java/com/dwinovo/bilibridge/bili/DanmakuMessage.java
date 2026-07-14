package com.dwinovo.bilibridge.bili;

/**
 * One chat message lifted out of the live-room stream, already reduced to what the
 * companion needs. {@code priceYuan} is 0 for ordinary danmaku and the paid amount
 * for a Super Chat.
 */
public record DanmakuMessage(Kind kind, long uid, String username, String text,
                             double priceYuan, long timestampMs) {

    public enum Kind { DANMAKU, SUPER_CHAT }

    public static DanmakuMessage danmaku(long uid, String username, String text, long timestampMs) {
        return new DanmakuMessage(Kind.DANMAKU, uid, username, text, 0, timestampMs);
    }

    public static DanmakuMessage superChat(long uid, String username, String text, double priceYuan) {
        return new DanmakuMessage(Kind.SUPER_CHAT, uid, username, text, priceYuan, System.currentTimeMillis());
    }
}
