package com.dwinovo.bilibridge;

import com.dwinovo.bilibridge.config.BridgeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /bilibridge test} injection: a fake danmaku dropped into a room's handoff
 * queue must ride the full batch → filter/aggregate → render pipeline without any
 * network connection.
 */
class RoomConnectionTest {

    private int savedWindow;
    private int savedMax;

    @BeforeEach
    void snapshotConfig() {
        BridgeConfig cfg = BridgeConfig.get();
        savedWindow = cfg.batchWindowSeconds;
        savedMax = cfg.batchMaxCount;
        cfg.batchWindowSeconds = 5;
        cfg.batchMaxCount = 30;
    }

    @AfterEach
    void restoreConfig() {
        BridgeConfig cfg = BridgeConfig.get();
        cfg.batchWindowSeconds = savedWindow;
        cfg.batchMaxCount = savedMax;
    }

    @Test
    void injectedDanmakuFlowsThroughTheFullPipeline() {
        RoomConnection room = new RoomConnection(100);
        room.inject("主播晚上好");
        long now = System.currentTimeMillis();
        assertNull(room.poll(now));                     // window not elapsed yet
        String xml = room.poll(now + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("kind=\"danmaku\""), xml);
        assertTrue(xml.contains("测试观众: 主播晚上好"), xml);
    }

    @Test
    void injectionsAccumulateIntoOneBatch() {
        RoomConnection room = new RoomConnection(100);
        room.inject("第一条");
        room.inject("第二条");
        long now = System.currentTimeMillis();
        assertNull(room.poll(now));                     // drains both into the open window
        String xml = room.poll(now + 5_000);
        assertNotNull(xml);
        assertTrue(xml.contains("count=\"2\""), xml);   // one batch carries both
    }

    @Test
    void stopDropsEverythingBuffered() {
        RoomConnection room = new RoomConnection(100);
        room.inject("被丢弃");
        room.stop();
        assertNull(room.poll(System.currentTimeMillis() + 60_000));
    }
}
