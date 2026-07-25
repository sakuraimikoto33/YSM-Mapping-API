package net.okitsu.ysmmapping.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.okitsu.ysmmapping.internal.bootstrap.MappingBootstrap;

public final class YsmMappingFabric implements PreLaunchEntrypoint, ModInitializer {
    @Override
    public void onPreLaunch() {
        MappingBootstrap.initialize();
    }

    @Override
    public void onInitialize() {
        MappingBootstrap.initialize();
    }
}
