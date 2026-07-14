package com.dwinovo.bilibridge.bind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides, per flushed batch, which live companions of a room's bindings receive it.
 * Works on plain names so it needs no game classes: the runtime supplies the room's
 * bound names and the names of companions currently live in the world, and delivers
 * to whatever comes back. A bound companion that is absent gets its batch dropped;
 * the first drop of an absence streak is reported once ({@link Routing#newlyAbsent})
 * and then stays silent until the companion is seen live again — a chat notice per
 * batch would spam the owner. Server thread only.
 */
public final class BindingRouter {

    /** {@code roomId \n boundName} pairs whose current absence streak was already announced. */
    private final Set<String> announcedAbsent = new HashSet<>();

    /**
     * @param deliverTo   live companion names to deliver the batch to (deduplicated)
     * @param newlyAbsent bound names whose absence starts now — announce once, drop the batch
     */
    public record Routing(List<String> deliverTo, List<String> newlyAbsent) {}

    /**
     * Route one batch of room {@code roomId}. Matching is exact first, then
     * case-insensitive. Seeing a bound name live resets its absence streak, so the
     * next absence announces again.
     */
    public Routing route(long roomId, Collection<String> boundNames, Collection<String> liveNames) {
        LinkedHashSet<String> deliverTo = new LinkedHashSet<>();
        List<String> newlyAbsent = new ArrayList<>();
        for (String bound : boundNames) {
            String live = match(bound, liveNames);
            String key = key(roomId, bound);
            if (live != null) {
                deliverTo.add(live);
                announcedAbsent.remove(key);
            } else if (announcedAbsent.add(key)) {
                newlyAbsent.add(bound);
            }
        }
        return new Routing(List.copyOf(deliverTo), newlyAbsent);
    }

    private static String match(String bound, Collection<String> liveNames) {
        for (String name : liveNames) {
            if (name.equals(bound)) return name;
        }
        for (String name : liveNames) {
            if (name.equalsIgnoreCase(bound)) return name;
        }
        return null;
    }

    /** The binding is gone (unbind / rebind elsewhere) — drop its absence state. */
    public void forget(long roomId, String name) {
        announcedAbsent.remove(key(roomId, name));
    }

    /** The room connection is gone — drop all its absence state. */
    public void forgetRoom(long roomId) {
        announcedAbsent.removeIf(k -> k.startsWith(roomId + "\n"));
    }

    public void reset() {
        announcedAbsent.clear();
    }

    private static String key(long roomId, String name) {
        return roomId + "\n" + name;
    }
}
