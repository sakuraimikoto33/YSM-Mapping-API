package net.okitsu.ysmmapping.neoforge;

import net.neoforged.fml.common.Mod;
import net.okitsu.ysmmapping.internal.bootstrap.MappingBootstrap;

@Mod("ysm_mapping_api")
public final class YsmMappingNeoForge {
    public YsmMappingNeoForge() {
        MappingBootstrap.initialize();
    }
}
