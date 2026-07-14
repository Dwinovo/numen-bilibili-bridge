package com.dwinovo.bilibridge.batch;

import com.dwinovo.bilibridge.bili.DanmakuMessage;
import com.dwinovo.bilibridge.config.BridgeConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groups incoming danmaku into batches and renders each batch as ONE
 * {@code <event kind="danmaku">} for the companion. A batch flushes when its time
 * window elapses or the raw count reaches {@code batchMaxCount}, whichever comes
 * first. At flush time the raw messages run through a filter/aggregate pipeline
 * (in order) so a busy room stays within a bounded token budget:
 *
 * <ol>
 *   <li>per-user rate limit — each user keeps only the newest
 *       {@code perUserPerWindow} danmaku (uid 0 falls back to the masked username);</li>
 *   <li>count aggregation — texts identical after normalization (trim,
 *       fullwidth→halfwidth, runs of a repeated char capped at three) merge into
 *       one {@code 文本 ×N} line;</li>
 *   <li>Super Chats are paid — exempt from both steps, pinned on top with price;</li>
 *   <li>cap — at most {@code maxLines} ordinary lines survive, newest win, the rest
 *       collapse into one trailing “另有 N 条弹幕略去” line.</li>
 * </ol>
 *
 * All methods run on the server thread — the network side never touches this class.
 */
public final class DanmakuBatcher {

    /** Longest run of one repeated character that survives normalization ("666666" → "666"). */
    private static final int MAX_CHAR_RUN = 3;

    private final List<DanmakuMessage> pending = new ArrayList<>();
    private long windowStartMs;

    /** Offer one message. Filtering happens at flush time, so the raw count stays honest. */
    public void add(DanmakuMessage msg, long nowMs) {
        if (pending.isEmpty()) windowStartMs = nowMs;
        pending.add(msg);
    }

    /**
     * Flush if a trigger fired: window elapsed or raw count reached. Returns the rendered
     * {@code <event>} XML, or null when there is nothing to ship yet.
     */
    public String poll(long nowMs) {
        BridgeConfig cfg = BridgeConfig.get();
        if (pending.isEmpty()) return null;
        boolean windowUp = nowMs - windowStartMs >= Math.max(1, cfg.batchWindowSeconds) * 1000L;
        boolean full = pending.size() >= Math.max(1, cfg.batchMaxCount);
        if (!windowUp && !full) return null;
        String xml = render(pending, (nowMs - windowStartMs + 999) / 1000, cfg);
        pending.clear();
        return xml;
    }

    /** Drop whatever is buffered (disconnect). */
    public void clear() {
        pending.clear();
    }

    public int pendingCount() {
        return pending.size();
    }

    // ------------------------------------------------------------------
    // pipeline
    // ------------------------------------------------------------------

    /** One rendered line: the last message that produced it plus how many raw danmaku it stands for. */
    private record Line(DanmakuMessage last, int count) {}

    private static String render(List<DanmakuMessage> raw, long windowSeconds, BridgeConfig cfg) {
        List<DanmakuMessage> superChats = new ArrayList<>();
        List<DanmakuMessage> normals = new ArrayList<>();
        for (DanmakuMessage m : raw) {
            (m.kind() == DanmakuMessage.Kind.SUPER_CHAT ? superChats : normals).add(m);
        }

        List<Line> lines = aggregate(rateLimit(normals, cfg.perUserPerWindow));

        int maxLines = Math.max(1, cfg.maxLines);
        int omitted = 0;
        if (lines.size() > maxLines) {
            // Lines are ordered oldest → newest; keep the newest, tally what falls off.
            List<Line> dropped = lines.subList(0, lines.size() - maxLines);
            for (Line l : dropped) omitted += l.count();
            lines = lines.subList(lines.size() - maxLines, lines.size());
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("<event kind=\"danmaku\" count=\"").append(raw.size())
          .append("\" window=\"").append(windowSeconds).append("s\">");
        sb.append(windowSeconds).append(" 秒内共 ").append(raw.size()).append(" 条弹幕：\n");
        for (DanmakuMessage m : superChats) {
            sb.append("[SC ¥").append(formatPrice(m.priceYuan())).append("] ")
              .append(escapeXml(m.username())).append(": ").append(escapeXml(m.text())).append('\n');
        }
        for (Line l : lines) {
            if (l.count() == 1) {
                sb.append(escapeXml(l.last().username())).append(": ").append(escapeXml(l.last().text()));
            } else {
                sb.append(escapeXml(l.last().text())).append(" ×").append(l.count());
            }
            sb.append('\n');
        }
        if (omitted > 0) {
            sb.append("（另有 ").append(omitted).append(" 条弹幕略去）\n");
        }
        sb.append("</event>");
        return sb.toString();
    }

    /** Keep only the newest {@code perUser} danmaku per user; 0 disables the limit. */
    private static List<DanmakuMessage> rateLimit(List<DanmakuMessage> normals, int perUser) {
        if (perUser <= 0) return normals;
        Map<String, Integer> taken = new HashMap<>();
        List<DanmakuMessage> keptNewestFirst = new ArrayList<>(normals.size());
        for (int i = normals.size() - 1; i >= 0; i--) {
            DanmakuMessage m = normals.get(i);
            // Anonymous connections report uid 0 for everyone — fall back to the masked username.
            String key = m.uid() != 0 ? "u" + m.uid() : "n" + m.username();
            if (taken.merge(key, 1, Integer::sum) <= perUser) keptNewestFirst.add(m);
        }
        List<DanmakuMessage> kept = new ArrayList<>(keptNewestFirst.size());
        for (int i = keptNewestFirst.size() - 1; i >= 0; i--) kept.add(keptNewestFirst.get(i));
        return kept;
    }

    /**
     * Merge messages whose normalized text matches into one line carrying a count.
     * A merged line moves to the position of its newest member, so the later cap
     * naturally keeps what the room said last.
     */
    private static List<Line> aggregate(List<DanmakuMessage> messages) {
        LinkedHashMap<String, Line> byText = new LinkedHashMap<>();
        for (DanmakuMessage m : messages) {
            String key = normalize(m.text());
            Line prev = byText.remove(key);   // remove + put → the group jumps to the end (newest)
            byText.put(key, new Line(m, prev == null ? 1 : prev.count() + 1));
        }
        return new ArrayList<>(byText.values());
    }

    /**
     * Normalization for aggregation only — the rendered line keeps the original text.
     * Trim, fullwidth → halfwidth, and runs of one repeated character capped at
     * {@value MAX_CHAR_RUN} so "666666" and "666" count as the same cheer.
     */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '　') c = ' ';                                        // 全角空格
            else if (c >= '！' && c <= '～') c = (char) (c - 0xFEE0);  // ！-～ → !-~
            int len = sb.length();
            if (len >= MAX_CHAR_RUN
                    && sb.charAt(len - 1) == c && sb.charAt(len - 2) == c && sb.charAt(len - 3) == c) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
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
