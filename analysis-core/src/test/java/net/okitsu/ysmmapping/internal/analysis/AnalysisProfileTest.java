package net.okitsu.ysmmapping.internal.analysis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisProfileTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @TempDir
    Path temporary;

    @Test
    void canonicalDigestIgnoresObjectOrderAndFormatting() throws Exception {
        Path first = write("first.json", profile("test-mc", false, true));
        Path second = write("second.json", profile("test-mc", true, true));

        AnalysisProfile left = AnalysisProfile.load(first);
        AnalysisProfile right = AnalysisProfile.load(second);

        assertEquals(left.profileSha256(), right.profileSha256());
        assertEquals(left.registryDefinitionSha256(), right.registryDefinitionSha256());
        assertEquals("example/Living", left.loader("alpha").livingEntity());
        assertEquals("TEST", left.packets().get(1).name());
        assertEquals(72, left.definitions().values().stream()
                .filter(value -> YsmSymbols.usesServerlessAnalyzer(value.id()))
                .count());
        assertEquals(9, left.definitions().values().stream()
                .filter(value -> YsmSymbols.isEquipmentDirect(value.id()))
                .count());
        assertEquals(23, left.definitions().values().stream()
                .filter(value -> YsmSymbols.isEquipmentRelated(value.id()))
                .count());
        assertThrows(IllegalArgumentException.class, () -> left.loader("missing"));
    }

    @Test
    void profileChangeInvalidatesEffectiveRegistryDigest() throws Exception {
        AnalysisProfile first = AnalysisProfile.load(
                write("first.json", profile("test-mc", false, true)));
        AnalysisProfile changed = AnalysisProfile.load(
                write("changed.json", profile("other-mc", false, true)));

        assertNotEquals(first.profileSha256(), changed.profileSha256());
        assertNotEquals(first.registryDefinitionSha256(),
                changed.registryDefinitionSha256());
    }

    @Test
    void missingBuiltInSymbolFailsClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisProfile.load(write("invalid.json",
                        profile("test-mc", false, false))));
    }

    @Test
    void duplicateSymbolFailsClosed() throws Exception {
        JsonObject value = JsonParser.parseString(profile("test-mc", false, true))
                .getAsJsonObject();
        value.getAsJsonArray("symbols").add(
                value.getAsJsonArray("symbols").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisProfile.load(write("duplicate.json", GSON.toJson(value))));
    }

    @Test
    void legacyLoaderProfileWithoutScreenRemainsSupported() throws Exception {
        JsonObject value = JsonParser.parseString(profile("test-mc", false, true))
                .getAsJsonObject();
        value.getAsJsonObject("loaders").getAsJsonObject("alpha").remove("screen");

        AnalysisProfile loaded = AnalysisProfile.load(
                write("legacy.json", GSON.toJson(value)));

        assertEquals("net/minecraft/client/gui/screens/Screen",
                loaded.loader("alpha").screen());
        AnalysisProfile.LoaderTypes constructed = new AnalysisProfile.LoaderTypes(
                "example/Living", "example/Stack", "example/Slot", "example/Items",
                "example/Pose", "example/Buffer", List.of("example/Entity"),
                List.of("example/Player"), List.of("example/Connection"),
                List.of("example/Component"));
        assertNull(constructed.screen());
    }

    private Path write(String name, String value) throws Exception {
        Path path = temporary.resolve(name);
        Files.writeString(path, value);
        return path;
    }

    private static String profile(String minecraftVersion, boolean reverseTopLevel,
            boolean includeAll) {
        List<Map<String, Object>> symbols = new ArrayList<>();
        List<YsmSymbolKey<?>> keys = new ArrayList<>(YsmSymbols.all());
        if (!includeAll) keys.remove(keys.size() - 1);
        for (YsmSymbolKey<?> key : keys) {
            symbols.add(Map.of(
                    "id", key.id(),
                    "kind", key.kind().name(),
                    "definitionRevision", 1));
        }
        Map<String, Object> loader = new LinkedHashMap<>();
        loader.put("livingEntity", "example/Living");
        loader.put("itemStack", "example/Stack");
        loader.put("equipmentSlot", "example/Slot");
        loader.put("items", "example/Items");
        loader.put("poseStack", "example/Pose");
        loader.put("multiBuffer", "example/Buffer");
        loader.put("screen", "example/Screen");
        loader.put("entityTypes", List.of("example/Entity"));
        loader.put("playerTypes", List.of("example/Player"));
        loader.put("connectionTypes", List.of("example/Connection"));
        loader.put("componentTypes", List.of("example/Component"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("formatVersion", 1);
        value.put("minecraftVersion", minecraftVersion);
        value.put("loaders", Map.of("alpha", loader));
        value.put("channelIdentifiers", List.of("example:channel", "example-channel"));
        value.put("packets", List.of(Map.of(
                "id", 1,
                "name", "TEST",
                "direction", "BOTH")));
        value.put("symbols", symbols);
        if (reverseTopLevel) {
            List<Map.Entry<String, Object>> entries = new ArrayList<>(value.entrySet());
            java.util.Collections.reverse(entries);
            Map<String, Object> reversed = new LinkedHashMap<>();
            entries.forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
            value = reversed;
        }
        return GSON.toJson(value);
    }

}
