package com.dwinovo.bilibridge.bind;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingRouterTest {

    private final BindingRouter router = new BindingRouter();

    @Test
    void liveBoundCompanionReceivesTheBatch() {
        BindingRouter.Routing r = router.route(100, List.of("Nia"), List.of("Nia", "Stranger"));
        assertEquals(List.of("Nia"), r.deliverTo());
        assertTrue(r.newlyAbsent().isEmpty());
    }

    @Test
    void severalBoundNamesOfOneRoomAllReceive() {
        BindingRouter.Routing r = router.route(100, List.of("Nia", "Momo"), List.of("Momo", "Nia"));
        assertEquals(List.of("Nia", "Momo"), r.deliverTo());
    }

    @Test
    void exactMatchWinsOverCaseInsensitive() {
        BindingRouter.Routing r = router.route(100, List.of("Nia"), List.of("nia", "Nia"));
        assertEquals(List.of("Nia"), r.deliverTo());
    }

    @Test
    void caseInsensitiveMatchIsTheFallback() {
        BindingRouter.Routing r = router.route(100, List.of("nia"), List.of("Nia"));
        assertEquals(List.of("Nia"), r.deliverTo());
    }

    @Test
    void twoBoundNamesMatchingTheSameLiveCompanionDeliverOnce() {
        BindingRouter.Routing r = router.route(100, List.of("Nia", "nia"), List.of("Nia"));
        assertEquals(List.of("Nia"), r.deliverTo());
    }

    @Test
    void absenceIsAnnouncedOnceThenStaysSilent() {
        BindingRouter.Routing first = router.route(100, List.of("Nia"), List.of());
        assertEquals(List.of("Nia"), first.newlyAbsent());

        BindingRouter.Routing second = router.route(100, List.of("Nia"), List.of());
        assertTrue(second.newlyAbsent().isEmpty());   // 去抖：同一缺席状态只提示一次
        assertTrue(second.deliverTo().isEmpty());
    }

    @Test
    void recoveryResetsTheDebounceSoTheNextAbsenceAnnouncesAgain() {
        router.route(100, List.of("Nia"), List.of());                 // absent → announced
        router.route(100, List.of("Nia"), List.of("Nia"));            // back → debounce reset
        BindingRouter.Routing again = router.route(100, List.of("Nia"), List.of());
        assertEquals(List.of("Nia"), again.newlyAbsent());
    }

    @Test
    void debounceIsPerRoomAndPerName() {
        router.route(100, List.of("Nia"), List.of());
        BindingRouter.Routing otherRoom = router.route(200, List.of("Nia"), List.of());
        assertEquals(List.of("Nia"), otherRoom.newlyAbsent());        // same name, other room → own notice

        BindingRouter.Routing otherName = router.route(100, List.of("Nia", "Momo"), List.of());
        assertEquals(List.of("Momo"), otherName.newlyAbsent());       // Nia already announced, Momo is new
    }

    @Test
    void forgetClearsTheAbsenceStateOfOneBinding() {
        router.route(100, List.of("Nia"), List.of());
        router.forget(100, "Nia");                                    // unbound / rebound elsewhere
        BindingRouter.Routing r = router.route(100, List.of("Nia"), List.of());
        assertEquals(List.of("Nia"), r.newlyAbsent());                // fresh binding announces again
    }

    @Test
    void forgetRoomClearsAllAbsenceStateOfTheRoom() {
        router.route(100, List.of("Nia", "Momo"), List.of());
        router.forgetRoom(100);
        BindingRouter.Routing r = router.route(100, List.of("Nia", "Momo"), List.of());
        assertEquals(List.of("Nia", "Momo"), r.newlyAbsent());
    }

    @Test
    void mixedRoutingDeliversToTheLiveAndAnnouncesTheAbsent() {
        BindingRouter.Routing r = router.route(100, List.of("Nia", "Momo"), List.of("Momo"));
        assertEquals(List.of("Momo"), r.deliverTo());
        assertEquals(List.of("Nia"), r.newlyAbsent());
    }
}
