package com.dwinovo.bilibridge.batch;

import com.dwinovo.bilibridge.bili.DanmakuMessage;
import com.dwinovo.bilibridge.config.BridgeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DanmakuBatcherTest {

    private int savedWindow;
    private int savedMax;

    @BeforeEach
    void snapshotConfig() {
        savedWindow = BridgeConfig.get().batchWindowSeconds;
        savedMax = BridgeConfig.get().batchMaxCount;
        BridgeConfig.get().batchWindowSeconds = 5;
        BridgeConfig.get().batchMaxCount = 30;
    }

    @AfterEach
    void restoreConfig() {
        BridgeConfig.get().batchWindowSeconds = savedWindow;
        BridgeConfig.get().batchMaxCount = savedMax;
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
    void flushesOnMaxCountImmediately() {
        BridgeConfig.get().batchMaxCount = 2;
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(1, "a", "一", t0), t0);
        b.add(DanmakuMessage.danmaku(2, "b", "二", t0), t0);
        String xml = b.poll(t0);   // window not elapsed, count trigger fires
        assertNotNull(xml);
        assertTrue(xml.contains("count=\"2\""), xml);
    }

    @Test
    void duplicateTextWithinTenSecondsIsMerged() {
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.danmaku(1, "a", "666", t0), t0);
        b.add(DanmakuMessage.danmaku(2, "b", "666", t0 + 3_000), t0 + 3_000);
        b.add(DanmakuMessage.danmaku(3, "c", "666", t0 + 11_000), t0 + 11_000);  // outside window → kept
        String xml = b.poll(t0 + 11_000);
        assertNotNull(xml);
        assertTrue(xml.contains("count=\"2\""), xml);
    }

    @Test
    void superChatCarriesPriceAndSkipsDedupe() {
        DanmakuBatcher b = new DanmakuBatcher();
        long t0 = 1_000_000;
        b.add(DanmakuMessage.superChat(1, "rich", "加油", 30), t0);
        b.add(DanmakuMessage.superChat(2, "rich2", "加油", 30), t0);   // same text, both kept
        String xml = b.poll(t0 + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("[SC ¥30] rich: 加油"), xml);
        assertTrue(xml.contains("count=\"2\""), xml);
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
