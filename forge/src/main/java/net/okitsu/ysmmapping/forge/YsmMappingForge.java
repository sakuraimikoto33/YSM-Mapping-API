package net.okitsu.ysmmapping.forge;

import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmmapping.internal.bootstrap.MappingBootstrap;

@Mod("ysm_mapping_api")
public final class YsmMappingForge {
    public YsmMappingForge() {
        MappingBootstrap.initialize();
    }
}
