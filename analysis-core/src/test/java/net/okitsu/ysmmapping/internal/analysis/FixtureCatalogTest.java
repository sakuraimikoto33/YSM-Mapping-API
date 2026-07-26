package net.okitsu.ysmmapping.internal.analysis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixtureCatalogTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @TempDir
    Path temporary;

    @Test
    void rejectsCatalogForAnotherMinecraftVersion() throws Exception {
        AnalysisProfile profile = AnalysisProfile.load(write("profile.json", profile()));
        assertThrows(IllegalArgumentException.class, () -> FixtureCatalog.load(
                write("catalog.json", catalog("other-mc", "fixture.jar")), profile));
    }

    @Test
    void rejectsUnsupportedLoaderAndDuplicateFileName() throws Exception {
        AnalysisProfile profile = AnalysisProfile.load(write("profile.json", profile()));
        assertThrows(IllegalArgumentException.class, () -> FixtureCatalog.load(
                write("loader.json", catalog("test-mc", "fixture.jar", "unknown")),
                profile));
        assertThrows(IllegalArgumentException.class, () -> FixtureCatalog.load(
                write("duplicate.json", catalog("test-mc", "fixture.jar", "alpha",
                        "fixture.jar", "alpha")), profile));
    }

    @Test
    void acceptsCatalogDefinedFixtureSet() throws Exception {
        AnalysisProfile profile = AnalysisProfile.load(write("profile.json", profile()));
        FixtureCatalog catalog = FixtureCatalog.load(write("catalog.json",
                catalog("test-mc", "fixture.jar")), profile);
        assertEquals(1, catalog.fixtures().size());
        assertEquals(1, catalog.expectations().registryTotal());
    }

    private Path write(String name, String content) throws Exception {
        Path value = temporary.resolve(name);
        Files.writeString(value, content);
        return value;
    }

    private static String catalog(String minecraftVersion, String... fixtureValues) {
        List<Map<String, Object>> fixtures = new ArrayList<>();
        for (int index = 0; index < fixtureValues.length; index += 2) {
            String fileName = fixtureValues[index];
            String loader = index + 1 < fixtureValues.length ? fixtureValues[index + 1] : "alpha";
            fixtures.add(Map.of("fileName", fileName, "ysmVersion", "test", "loader", loader));
        }
        return GSON.toJson(Map.of(
                "formatVersion", 1,
                "minecraftVersion", minecraftVersion,
                "fixtures", fixtures,
                "expectations", Map.of(
                        "registryTotal", 1,
                        "categories", Map.of("SERVERLESS", 1),
                        "equipmentDirectRequiredLoaders", List.of(),
                        "equipmentFullRequiredYsmVersions", List.of())));
    }

    private static String profile() {
        Map<String, Object> loader = new LinkedHashMap<>();
        loader.put("livingEntity", "example/Living");
        loader.put("itemStack", "example/Stack");
        loader.put("equipmentSlot", "example/Slot");
        loader.put("items", "example/Items");
        loader.put("poseStack", "example/Pose");
        loader.put("multiBuffer", "example/Buffer");
        loader.put("entityTypes", List.of("example/Entity"));
        loader.put("playerTypes", List.of("example/Player"));
        loader.put("connectionTypes", List.of("example/Connection"));
        loader.put("componentTypes", List.of("example/Component"));
        List<Map<String, Object>> symbols = YsmSymbols.all().stream().map(key -> Map.of(
                "id", (Object) key.id(),
                "kind", key.kind().name(),
                "definitionRevision", 1)).toList();
        return GSON.toJson(Map.ofEntries(
                Map.entry("formatVersion", 1),
                Map.entry("minecraftVersion", "test-mc"),
                Map.entry("loaders", Map.of("alpha", loader)),
                Map.entry("channelIdentifiers", List.of("example:channel")),
                Map.entry("packets", List.of(Map.of(
                        "id", 1,
                        "name", "TEST",
                        "direction", "BOTH"))),
                Map.entry("symbols", symbols)));
    }
}
