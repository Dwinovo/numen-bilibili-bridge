package com.dwinovo.bilibridge.config;

import com.dwinovo.bilibridge.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain-JSON config at {@code config/numen-bilibili-bridge.json}. Loaded once at
 * mod init by each loader's entry point (which supplies the loader-specific config
 * dir); saved whenever a command changes a value. Fields are read from the server
 * thread and from the bridge's network thread, so the mutable ones are volatile.
 */
public final class BridgeConfig {

    private static final String FILE_NAME = "numen-bilibili-bridge.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile BridgeConfig instance = new BridgeConfig();
    private static volatile Path file;

    /** Live room id as typed in the browser URL — the short id is fine, the bridge resolves the real one. */
    public volatile long roomId = 0;
    /**
     * Optional login cookie. Empty = anonymous connection: danmaku text arrives intact
     * but usernames are masked by the server and uids are 0. Filling it in yields real
     * usernames. Never logged, never sent anywhere except api.live.bilibili.com.
     */
    public volatile String sessdata = "";
    /** A batch flushes when this many seconds have passed since its first message… */
    public volatile int batchWindowSeconds = 15;
    /** …or when it holds this many raw messages (before filtering), whichever comes first. */
    public volatile int batchMaxCount = 50;
    /** After filtering and aggregation, at most this many ordinary danmaku lines per batch — newest win. */
    public volatile int maxLines = 20;
    /** Per user per window, keep only the newest N danmaku; 0 disables. Super Chats are exempt. */
    public volatile int perUserPerWindow = 1;
    /** urgent=true wakes an idle companion to react immediately; false rides the owner's next turn. */
    public volatile boolean urgent = true;
    /** Reconnect to the configured room automatically when a world/server starts. */
    public volatile boolean autoConnect = false;

    public static BridgeConfig get() {
        return instance;
    }

    /** Load (or create with defaults) from {@code configDir}. Called once per game launch. */
    public static void load(Path configDir) {
        file = configDir.resolve(FILE_NAME);
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                BridgeConfig loaded = GSON.fromJson(json, BridgeConfig.class);
                if (loaded != null) {
                    instance = loaded;
                    Constants.LOG.info("[bilibridge] config loaded from {}", file);
                    return;
                }
            } catch (Exception e) {
                Constants.LOG.warn("[bilibridge] could not read {} — using defaults: {}", file, e.toString());
            }
        }
        instance = new BridgeConfig();
        instance.save();
    }

    /** Persist the current values. Failure is logged, never thrown — config is a convenience. */
    public void save() {
        Path f = file;
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Constants.LOG.warn("[bilibridge] could not save config: {}", e.toString());
        }
    }
}
