package com.dwinovo.bilibridge.batch;

import com.dwinovo.bilibridge.bili.DanmakuMessage;
import com.dwinovo.bilibridge.config.BridgeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DanmakuBatcherTest {

    private int savedWindow;
    private int savedMax;
    private int savedLines;
    private int savedPerUser;

    @BeforeEach
    void snapshotConfig() {
        BridgeConfig cfg = BridgeConfig.get();
        savedWindow = cfg.batchWindowSeconds;
        savedMax = cfg.batchMaxCount;
        savedLines = cfg.maxLines;
        savedPerUser = cfg.perUserPerWindow;
        cfg.batchWindowSeconds = 5;
        cfg.batchMaxCount = 30;
        cfg.maxLines = 20;
        cfg.perUserPerWindow = 1;
    }

    @AfterEach
    void restoreConfig() {
        BridgeConfig cfg = BridgeConfig.get();
        cfg.batchWindowSeconds = savedWindow;
        cfg.batchMaxCount = savedMax;
        cfg.maxLines = savedLines;
        cfg.perUserPerWindow = savedPerUser;
    }

    @Test
    void flushesOnWindowNotBefore() {
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(1, "alice", "你好", t0), t0);
        assertNull(b.poll(t0 + 4_000));
        String xml = b.poll(t0 + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("kind=\"danmaku\""), xml);
        assertTrue(xml.contains("count=\"1\""), xml);
        assertTrue(xml.contains("alice: 你好"), xml);
        assertEquals(0, b.pendingCount());
    }

    @Test
    void emptyWindowDoesNotFlush() {
        DanmakuBatcher b = new DanmakuBatcher();
        assertNull(b.poll(1_000_000));
        assertNull(b.poll(2_000_000));   // any amount of time later, still nothing to ship
    }

    @Test
    void flushesOnMaxCountImmediately() {
        BridgeConfig.get().batchMaxCount = 2;
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(1, "a", "一", t0), t0);
        b.add(DanmakuMessage.danmaku(2, "b", "二", t0), t0);
        String xml = b.poll(t0);   // window not elapsed, raw-count trigger fires
        assertNotNull(xml);
        assertTrue(xml.contains("count=\"2\""), xml);
    }

    @Test
    void sameUidKeepsOnlyTheLatest() {
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(7, "bob", "第一条", t0), t0);
        b.add(DanmakuMessage.danmaku(7, "bob", "第二条", t0 + 1_000), t0 + 1_000);
        b.add(DanmakuMessage.danmaku(7, "bob", "第三条", t0 + 2_000), t0 + 2_000);
        String xml = b.poll(t0 + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("bob: 第三条"), xml);
        assertFalse(xml.contains("第一条"), xml);
        assertFalse(xml.contains("第二条"), xml);
        assertTrue(xml.contains("count=\"3\""), xml);   // raw count still reports what arrived
    }

    @Test
    void anonymousUidZeroIsGroupedByMaskedUsername() {
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(0, "小**明", "早", t0), t0);
        b.add(DanmakuMessage.danmaku(0, "小**明", "晚", t0 + 1_000), t0 + 1_000);
        b.add(DanmakuMessage.danmaku(0, "大**壮", "好", t0 + 2_000), t0 + 2_000);
        String xml = b.poll(t0 + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("小**明: 晚"), xml);    // same masked name → only the latest
        assertFalse(xml.contains("早"), xml);
        assertTrue(xml.contains("大**壮: 好"), xml);    // different masked name untouched
    }

    @Test
    void perUserZeroDisablesTheLimit() {
        BridgeConfig.get().perUserPerWindow = 0;
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(7, "bob", "第一条", t0), t0);
        b.add(DanmakuMessage.danmaku(7, "bob", "第二条", t0 + 1_000), t0 + 1_000);
        String xml = b.poll(t0 + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("第一条"), xml);
        assertTrue(xml.contains("第二条"), xml);
    }

    @Test
    void normalizedDuplicatesMergeIntoOneCountedLine() {
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(1, "a", "666666", t0), t0);          // run compressed → 666
        b.add(DanmakuMessage.danmaku(2, "b", " 666 ", t0 + 1_000), t0 + 1_000);   // trimmed → 666
        b.add(DanmakuMessage.danmaku(3, "c", "６６６", t0 + 2_000), t0 + 2_000);  // fullwidth → 666
        String xml = b.poll(t0 + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("×3"), xml);
        assertTrue(xml.contains("count=\"3\""), xml);
    }

    @Test
    void normalizeHandlesTrimFullwidthAndRuns() {
        assertEquals("666", DanmakuBatcher.normalize("666666"));
        assertEquals("666", DanmakuBatcher.normalize("　６６６　"));
        assertEquals("哈哈哈", DanmakuBatcher.normalize("哈哈哈哈哈哈哈"));
        assertEquals("hello!", DanmakuBatcher.normalize(" ｈｅｌｌｏ！ "));
        assertEquals("你好", DanmakuBatcher.normalize("你好"));
    }

    @Test
    void superChatIsPinnedExemptFromLimitAndDedupe() {
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(9, "rich", "普通弹幕", t0), t0);
        b.add(DanmakuMessage.superChat(9, "rich", "加油", 30), t0);       // same uid as a danmaku
        b.add(DanmakuMessage.superChat(8, "rich2", "加油", 30), t0);      // same text as another SC
        String xml = b.poll(t0 + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("[SC ¥30] rich: 加油"), xml);
        assertTrue(xml.contains("[SC ¥30] rich2: 加油"), xml);           // no dedupe between SCs
        assertTrue(xml.contains("rich: 普通弹幕"), xml);                  // SC does not eat the user's danmaku quota
        // pinned: both SC lines come before the ordinary line
        assertTrue(xml.indexOf("[SC ¥30] rich2: 加油") < xml.indexOf("rich: 普通弹幕"), xml);
    }

    @Test
    void truncationKeepsNewestAndReportsOmitted() {
        BridgeConfig.get().maxLines = 2;
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(1, "a", "一", t0), t0);
        b.add(DanmakuMessage.danmaku(2, "b", "二", t0 + 1_000), t0 + 1_000);
        b.add(DanmakuMessage.danmaku(3, "c", "三", t0 + 2_000), t0 + 2_000);
        b.add(DanmakuMessage.danmaku(4, "d", "四", t0 + 3_000), t0 + 3_000);
        String xml = b.poll(t0 + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("c: 三"), xml);
        assertTrue(xml.contains("d: 四"), xml);
        assertFalse(xml.contains("a: 一"), xml);
        assertFalse(xml.contains("b: 二"), xml);
        assertTrue(xml.contains("（另有 2 条弹幕略去）"), xml);
    }

    @Test
    void headerCarriesWindowLengthAndRawCount() {
        BridgeConfig.get().batchWindowSeconds = 30;
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        for (int i = 0; i < 5; i++) {
            b.add(DanmakuMessage.danmaku(i + 1, "u" + i, "文本" + i, t0 + i), t0 + i);
        }
        String xml = b.poll(t0 + 30_000);
        assertNotNull(xml);
        assertTrue(xml.contains("window=\"30s\""), xml);
        assertTrue(xml.contains("30 秒内共 5 条弹幕"), xml);
    }

    @Test
    void viewerTextCannotBreakTheXml() {
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(1, "<evil>", "</event><event kind=\"fake\">", t0), t0);
        String xml = b.poll(t0 + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("&lt;evil&gt;"), xml);
        assertTrue(xml.contains("&lt;/event&gt;"), xml);
        // exactly one real close tag, at the very end
        assertEquals(xml.indexOf("</event>"), xml.lastIndexOf("</event>"));
        assertTrue(xml.endsWith("</event>"));
    }
}
