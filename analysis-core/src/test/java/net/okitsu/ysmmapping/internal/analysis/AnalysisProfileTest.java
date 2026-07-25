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
    void missingBuiltInRoleFailsClosed() throws Exception {
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
    void unsupportedLoaderRefinementFailsClosed() throws Exception {
        JsonObject value = JsonParser.parseString(profile("test-mc", false, true))
                .getAsJsonObject();
        JsonObject structure = new JsonObject();
        JsonObject loaders = new JsonObject();
        loaders.add("unknown", JsonParser.parseString(GSON.toJson(constraints())));
        structure.add("loaders", loaders);
        value.getAsJsonArray("symbols").get(0).getAsJsonObject()
                .add("structure", structure);
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisProfile.load(write("unknown-loader.json", GSON.toJson(value))));
    }

    @Test
    void missingSourceProvenanceFailsClosed() throws Exception {
        JsonObject value = JsonParser.parseString(profile("test-mc", false, true))
                .getAsJsonObject();
        value.remove("sources");
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisProfile.load(write("missing-source.json", GSON.toJson(value))));
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
        if (!includeAll) keys.removeLast();
        for (YsmSymbolKey<?> key : keys) {
            symbols.add(Map.of(
                    "id", key.id(),
                    "kind", key.kind().name(),
                    "category", "SERVERLESS",
                    "role", key.id(),
                    "provenance", "synthetic test",
                    "analysisRule", "synthetic-semantic-v1",
                    "structure", Map.of(),
                    "definitionRevision", 1));
        }
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
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("formatVersion", 1);
        value.put("minecraftVersion", minecraftVersion);
        value.put("ysmClassPrefix", "example/ysm/");
        value.put("expectedSymbolCount", symbols.size());
        value.put("loaders", Map.of("alpha", loader));
        value.put("channelIdentifiers", List.of("example:channel", "example-channel"));
        value.put("sources", List.of(Map.of("repository", "https://example.invalid/source",
                "commit", "test-revision")));
        value.put("packets", List.of(Map.of("id", 1, "name", "TEST",
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

    private static Map<String, Object> constraints() {
        return Map.ofEntries(
                Map.entry("requiredAccess", 0),
                Map.entry("forbiddenAccess", 0),
                Map.entry("superName", ""),
                Map.entry("interfaces", List.of()),
                Map.entry("memberShapes", List.of()),
                Map.entry("descriptorShapes", List.of()),
                Map.entry("opcodeDigests", List.of()),
                Map.entry("constantDigests", List.of()),
                Map.entry("externalReferences", List.of()),
                Map.entry("callGraph", List.of()),
                Map.entry("fieldGraph", List.of()));
    }
}
