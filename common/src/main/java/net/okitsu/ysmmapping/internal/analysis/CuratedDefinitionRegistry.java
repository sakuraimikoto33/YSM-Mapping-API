package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbols;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Minecraft-owned adapter between the public curated registry and a shared analysis profile. */
public final class CuratedDefinitionRegistry {
    private CuratedDefinitionRegistry() {
    }

    public static AnalysisProfile load(String minecraftVersion) throws IOException {
        String version = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        if (!version.matches("[0-9A-Za-z._-]+")) {
            throw new IllegalArgumentException("Unsafe Minecraft version: " + version);
        }
        String resource = "ysm-mapping/profiles/" + version + ".json";
        ClassLoader loader = CuratedDefinitionRegistry.class.getClassLoader();
        InputStream input = loader.getResourceAsStream(resource);
        if (input == null) {
            throw new IOException("Missing YSM analysis profile: " + resource);
        }
        AnalysisProfile profile = AnalysisProfile.load(input, resource);
        profile.requireExactSymbols(YsmSymbols.all().stream().map(YsmSymbolKey::id).toList());
        return profile;
    }

    public static AnalysisProfile.Definition get(AnalysisProfile profile, YsmSymbolKey<?> key) {
        AnalysisProfile.Definition value = profile.definitions().get(key.id());
        if (value == null || value.kind() != key.kind() || value.definitionRevision() != 1) {
            throw new IllegalArgumentException("Unknown curated definition: " + key.id());
        }
        return value;
    }

    public static boolean usesEquipmentAnalyzer(
            AnalysisProfile profile, YsmSymbolKey<?> key) {
        get(profile, key);
        return net.okitsu.ysmmapping.internal.analysis.YsmSymbols
                .usesEquipmentAnalyzer(key.id());
    }
}
