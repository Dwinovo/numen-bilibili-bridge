package com.dwinovo.bilibridge.bind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The companion ↔ live-room pairing table. One companion serves one room; several
 * companions may serve the same room, in which case they share one connection.
 * Binding IS connecting: the registry derives room reference counts from the table
 * and fires {@link RoomLifecycle#open} exactly when a room gains its first binding
 * and {@link RoomLifecycle#close} exactly when it loses its last. All connection
 * side effects go through the lifecycle callback, so this class stays pure
 * bookkeeping — unit-testable with a fake. Server thread only.
 */
public final class BindingRegistry {

    /** Connection side effects, supplied by the runtime (opens/stops real sockets). */
    public interface RoomLifecycle {
        void open(long roomId);
        void close(long roomId);
    }

    private final RoomLifecycle lifecycle;
    /** companion name → room id, in binding order. */
    private final LinkedHashMap<String, Long> bindings = new LinkedHashMap<>();

    public BindingRegistry(RoomLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Bind {@code name} to {@code roomId}, opening the room if this is its first
     * binding and closing the name's previous room if it just lost its last one.
     * Returns the previous room of this name, or null if it was unbound.
     */
    public Long bind(String name, long roomId) {
        Long prev = bindings.get(name);
        if (prev != null && prev == roomId) return prev;   // rebinding to the same room — nothing to do
        boolean roomIsNew = !bindings.containsValue(roomId);
        bindings.put(name, roomId);
        if (roomIsNew) lifecycle.open(roomId);
        if (prev != null && !bindings.containsValue(prev)) lifecycle.close(prev);
        return prev;
    }

    /**
     * Remove the binding of {@code name} (exact key). Closes its room when no other
     * binding references it. Returns the room it was bound to, or null if unbound.
     */
    public Long unbind(String name) {
        Long prev = bindings.remove(name);
        if (prev != null && !bindings.containsValue(prev)) lifecycle.close(prev);
        return prev;
    }

    /** Replace the whole table from the persisted map and open each distinct room. */
    public void resetFrom(Map<String, Long> persisted) {
        bindings.clear();
        if (persisted != null) bindings.putAll(persisted);
        for (long roomId : rooms()) lifecycle.open(roomId);
    }

    /** Close every room connection. The table survives — {@link #resetFrom} reopens it. */
    public void closeAll() {
        for (long roomId : rooms()) lifecycle.close(roomId);
    }

    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    public int size() {
        return bindings.size();
    }

    /** True while at least one binding references {@code roomId}. */
    public boolean hasRoom(long roomId) {
        return bindings.containsValue(roomId);
    }

    /** Distinct bound rooms, in first-binding order. */
    public Set<Long> rooms() {
        return new LinkedHashSet<>(bindings.values());
    }

    /** Names bound to {@code roomId}, in binding order. */
    public List<String> namesFor(long roomId) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Long> e : bindings.entrySet()) {
            if (e.getValue() == roomId) names.add(e.getKey());
        }
        return names;
    }

    /** Copy for persisting to the config file. */
    public Map<String, Long> snapshot() {
        return new LinkedHashMap<>(bindings);
    }

    /** The bound name matching {@code input} — exact first, else first case-insensitive; null if none. */
    public String resolveName(String input) {
        if (bindings.containsKey(input)) return input;
        for (String name : bindings.keySet()) {
            if (name.equalsIgnoreCase(input)) return name;
        }
        return null;
    }

    /** A resolved {@code /bilibridge test} target: which companion, its room, and the text to inject. */
    public record TestTarget(String name, long roomId, String text) {}

    /**
     * Resolve the raw argument of {@code /bilibridge test [同伴名] <文本>}. When the
     * first whitespace-separated token names a bound companion (exact, then
     * case-insensitive) and text remains, that binding is the target; otherwise the
     * whole string is text and works only while exactly one binding exists.
     * Returns null when the target is ambiguous (several bindings, no name given).
     */
    public TestTarget resolveTest(String raw) {
        String s = raw.trim();
        int sp = indexOfWhitespace(s);
        if (sp > 0) {
            String name = resolveName(s.substring(0, sp));
            String rest = s.substring(sp).trim();
            if (name != null && !rest.isEmpty()) {
                return new TestTarget(name, bindings.get(name), rest);
            }
        }
        if (bindings.size() == 1) {
            Map.Entry<String, Long> only = bindings.entrySet().iterator().next();
            return new TestTarget(only.getKey(), only.getValue(), s);
        }
        return null;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}
