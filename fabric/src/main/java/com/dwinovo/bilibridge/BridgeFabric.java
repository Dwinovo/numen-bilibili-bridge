package com.dwinovo.bilibridge;

import com.dwinovo.bilibridge.config.BridgeConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric entry point: load the config, register {@code /bilibridge}, and drive the
 * runtime off the server lifecycle — tick to drain/flush, stopping to tear the
 * network connection down with the world.
 */
public class BridgeFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        BridgeConfig.load(FabricLoader.getInstance().getConfigDir());

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, env) -> BridgeCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> BridgeRuntime.get().onServerStarted(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BridgeRuntime.get().onServerStopping());
        ServerTickEvents.END_SERVER_TICK.register(server -> BridgeRuntime.get().onServerTick(server));

        Constants.LOG.info("numen-bilibili-bridge initialised on Fabric.");
    }
}
