package com.dwinovo.bilibridge;

import com.dwinovo.bilibridge.config.BridgeConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge entry point: load the config, register {@code /bilibridge}, and drive
 * the runtime off the server lifecycle — tick to drain/flush, stopping to tear the
 * network connection down with the world.
 */
@Mod(Constants.MOD_ID)
public class BridgeNeoForge {

    public BridgeNeoForge(IEventBus eventBus, ModContainer container) {
        BridgeConfig.load(FMLPaths.CONFIGDIR.get());

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> BridgeCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> BridgeRuntime.get().onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> BridgeRuntime.get().onServerStopping());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> BridgeRuntime.get().onServerTick(e.getServer()));

        Constants.LOG.info("numen-bilibili-bridge initialised on NeoForge.");
    }
}
