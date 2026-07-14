package com.dwinovo.bilibridge.bind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingRegistryTest {

    /** Records lifecycle calls in order, e.g. "open 100", "close 100". */
    private final List<String> events = new ArrayList<>();
    private BindingRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new BindingRegistry(new BindingRegistry.RoomLifecycle() {
            @Override public void open(long roomId) { events.add("open " + roomId); }
            @Override public void close(long roomId) { events.add("close " + roomId); }
        });
    }

    @Test
    void firstBindOpensTheRoom() {
        assertNull(registry.bind("Nia", 100));
        assertEquals(List.of("open 100"), events);
        assertEquals(Map.of("Nia", 100L), registry.snapshot());
    }

    @Test
    void secondBindToTheSameRoomSharesTheConnection() {
        registry.bind("Nia", 100);
        registry.bind("Momo", 100);
        assertEquals(List.of("open 100"), events);   // one connection, no second open
        assertEquals(List.of("Nia", "Momo"), registry.namesFor(100));
    }

    @Test
    void unbindKeepsASharedRoomUntilTheLastBindingLeaves() {
        registry.bind("Nia", 100);
        registry.bind("Momo", 100);
        assertEquals(100L, registry.unbind("Nia"));
        assertEquals(List.of("open 100"), events);   // Momo still bound → no close
        assertEquals(100L, registry.unbind("Momo"));
        assertEquals(List.of("open 100", "close 100"), events);
        assertTrue(registry.isEmpty());
    }

    @Test
    void rebindingToAnotherRoomOpensItAndClosesTheAbandonedOne() {
        registry.bind("Nia", 100);
        assertEquals(100L, registry.bind("Nia", 200));
        assertEquals(List.of("open 100", "open 200", "close 100"), events);
        assertEquals(200L, registry.snapshot().get("Nia"));
    }

    @Test
    void rebindingToTheSameRoomIsANoOp() {
        registry.bind("Nia", 100);
        assertEquals(100L, registry.bind("Nia", 100));
        assertEquals(List.of("open 100"), events);
    }

    @Test
    void unbindingAnUnknownNameDoesNothing() {
        assertNull(registry.unbind("Ghost"));
        assertTrue(events.isEmpty());
    }

    @Test
    void resetFromOpensEachDistinctRoomOnce() {
        registry.resetFrom(Map.of("Nia", 100L, "Momo", 100L, "Kiki", 200L));
        assertEquals(2, events.size());
        assertTrue(events.contains("open 100"));
        assertTrue(events.contains("open 200"));
    }

    @Test
    void closeAllClosesConnectionsButKeepsTheTable() {
        registry.bind("Nia", 100);
        registry.bind("Kiki", 200);
        events.clear();
        registry.closeAll();
        assertEquals(List.of("close 100", "close 200"), events);
        assertEquals(2, registry.size());   // bindings persist across world restarts
    }

    @Test
    void resolveNamePrefersExactOverCaseInsensitive() {
        registry.bind("nia", 100);
        registry.bind("Nia", 200);
        assertEquals("Nia", registry.resolveName("Nia"));    // exact wins
        assertEquals("nia", registry.resolveName("NIA"));    // else first case-insensitive
        assertNull(registry.resolveName("Momo"));
    }

    @Test
    void resolveTestWithLeadingBoundNameTargetsIt() {
        registry.bind("Nia", 100);
        registry.bind("Kiki", 200);
        BindingRegistry.TestTarget t = registry.resolveTest("Kiki 主播晚上好");
        assertNotNull(t);
        assertEquals("Kiki", t.name());
        assertEquals(200, t.roomId());
        assertEquals("主播晚上好", t.text());
    }

    @Test
    void resolveTestNameMatchIsCaseInsensitive() {
        registry.bind("Nia", 100);
        registry.bind("Kiki", 200);
        BindingRegistry.TestTarget t = registry.resolveTest("kiki 666");
        assertNotNull(t);
        assertEquals("Kiki", t.name());
        assertEquals("666", t.text());
    }

    @Test
    void resolveTestWithASingleBindingNeedsNoName() {
        registry.bind("Nia", 100);
        BindingRegistry.TestTarget t = registry.resolveTest("主播晚上好 来了来了");
        assertNotNull(t);
        assertEquals("Nia", t.name());
        assertEquals(100, t.roomId());
        assertEquals("主播晚上好 来了来了", t.text());   // first word is no bound name → all text
    }

    @Test
    void resolveTestIsAmbiguousWithSeveralBindingsAndNoName() {
        registry.bind("Nia", 100);
        registry.bind("Kiki", 200);
        assertNull(registry.resolveTest("主播晚上好"));
    }

    @Test
    void resolveTestSingleBindingKeepsNameLikeTextAsText() {
        registry.bind("Nia", 100);
        BindingRegistry.TestTarget t = registry.resolveTest("Nia");   // no text after the name
        assertNotNull(t);
        assertEquals("Nia", t.text());   // treated as the danmaku text, not an empty injection
    }

    @Test
    void hasRoomTracksReferences() {
        registry.bind("Nia", 100);
        assertTrue(registry.hasRoom(100));
        assertFalse(registry.hasRoom(200));
        registry.unbind("Nia");
        assertFalse(registry.hasRoom(100));
    }
}
