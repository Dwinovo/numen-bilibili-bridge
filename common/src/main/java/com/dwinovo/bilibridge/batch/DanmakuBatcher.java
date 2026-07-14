package com.dwinovo.bilibridge.batch;

import com.dwinovo.bilibridge.bili.DanmakuMessage;
import com.dwinovo.bilibridge.config.BridgeConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groups incoming danmaku into batches and renders each batch as ONE
 * {@code <event kind="danmaku">} for the companion. A batch flushes when its time
 * window elapses or it reaches the max count, whichever comes first; identical
 * texts within 10 seconds are dropped (spam waves compress to one line). All
 * methods run on the server thread — the network side never touches this class.
 */
public final class DanmakuBatcher {

    private static final long DEDUPE_WINDOW_MS = 10_000;

    private final List<DanmakuMessage> pending = new ArrayList<>();
    private final Map<String, Long> recentTexts = new HashMap<>();
    private long windowStartMs;
    private int duplicatesDropped;

    /** Offer one message; duplicates within the dedupe window are counted and discarded. */
    public void add(DanmakuMessage msg, long nowMs) {
        pruneRecent(nowMs);
        // Super Chats are paid — never dedupe them away.
        if (msg.kind() == DanmakuMessage.Kind.DANMAKU) {
            Long seenAt = recentTexts.get(msg.text());
            if (seenAt != null && nowMs - seenAt < DEDUPE_WINDOW_MS) {
                duplicatesDropped++;
                return;
            }
            recentTexts.put(msg.text(), nowMs);
        }
        if (pending.isEmpty()) windowStartMs = nowMs;
        pending.add(msg);
    }

    /**
     * Flush if a trigger fired: window elapsed or count reached. Returns the rendered
     * {@code <event>} XML, or null when there is nothing to ship yet.
     */
    public String poll(long nowMs) {
        BridgeConfig cfg = BridgeConfig.get();
        if (pending.isEmpty()) return null;
        boolean windowUp = nowMs - windowStartMs >= Math.max(1, cfg.batchWindowSeconds) * 1000L;
        boolean full = pending.size() >= Math.max(1, cfg.batchMaxCount);
        if (!windowUp && !full) return null;
        String xml = render(pending, (nowMs - windowStartMs + 999) / 1000);
        pending.clear();
        duplicatesDropped = 0;
        return xml;
    }

    /** Drop whatever is buffered (disconnect). */
    public void clear() {
        pending.clear();
        recentTexts.clear();
        duplicatesDropped = 0;
    }

    public int pendingCount() {
        return pending.size();
    }

    private void pruneRecent(long nowMs) {
        Iterator<Map.Entry<String, Long>> it = recentTexts.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue() >= DEDUPE_WINDOW_MS) it.remove();
        }
    }

    private String render(List<DanmakuMessage> batch, long windowSeconds) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<event kind=\"danmaku\" count=\"").append(batch.size())
          .append("\" window=\"").append(windowSeconds).append("s\">");
        sb.append("直播间观众发来 ").append(batch.size()).append(" 条弹幕");
        if (duplicatesDropped > 0) sb.append("（另有 ").append(duplicatesDropped).append(" 条重复已合并）");
        sb.append("：\n");
        for (DanmakuMessage m : batch) {
            if (m.kind() == DanmakuMessage.Kind.SUPER_CHAT) {
                sb.append("[SC ¥").append(formatPrice(m.priceYuan())).append("] ");
            }
            sb.append(escapeXml(m.username())).append(": ").append(escapeXml(m.text())).append('\n');
        }
        sb.append("</event>");
        return sb.toString();
    }

    private static String formatPrice(double yuan) {
        return yuan == Math.rint(yuan)
                ? Long.toString((long) yuan)
                : String.format(Locale.ROOT, "%.2f", yuan);
    }

    /** Viewer text goes inside an XML event — escape it so no danmaku can break the markup. */
    static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
