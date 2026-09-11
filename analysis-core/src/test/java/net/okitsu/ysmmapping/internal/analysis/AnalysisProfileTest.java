package net.okitsu.ysmmapping.internal.analysis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.okitsu.ysmmapping.api.SymbolKind;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisProfileTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<YsmSymbolKey<?>> MOLANG_QUERY_GROUP = List.of(
            YsmSymbols.MOLANG_GROUND_SPEED2_QUERY, YsmSymbols.MOLANG_QUERY_CONTEXT_GET);

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
        assertEquals(86, left.definitions().values().stream()
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
    void legacyProfileKeepsItsExactRegistryAndDigests() throws Exception {
        AnalysisProfile legacy = AnalysisProfile.load(
                write("legacy-registry.json", profile("test-mc", false, true)));

        assertEquals(121, YsmSymbols.all().size());
        assertEquals(118, legacy.definitions().size());
        assertTrue(MOLANG_QUERY_GROUP.stream().allMatch(key -> key.kind() == SymbolKind.METHOD));
        assertTrue(MOLANG_QUERY_GROUP.stream().allMatch(key -> YsmSymbols.byId(key.id())
                .orElseThrow().equals(key)));
        assertTrue(MOLANG_QUERY_GROUP.stream().noneMatch(key -> legacy.definitions()
                .containsKey(key.id())));
        legacy.requireExactSymbols(legacyKeys().stream().map(YsmSymbolKey::id).toList());
        assertThrows(IllegalStateException.class, () -> legacy.requireExactSymbols(
                YsmSymbols.all().stream().map(YsmSymbolKey::id).toList()));
        // Golden values from the original 118-symbol profile, before the optional group existed.
        assertEquals("e85daad1745def57bbb19edbc89f78589bb618f460dec2be67c46cdf38d6047a",
                legacy.profileSha256());
        assertEquals("51bad69483d52d6fb82d42c7fa5025ef4ca1d34cbc79a1d14e10d4a3e3c9c94d",
                legacy.registryDefinitionSha256());
    }

    @Test
    void completeOptionalGroupOptsInWithoutChangingExistingDefinitions() throws Exception {
        AnalysisProfile legacy = AnalysisProfile.load(
                write("legacy.json", profile("test-mc", false, true)));
        JsonObject value = JsonParser.parseString(profile("test-mc", false, true))
                .getAsJsonObject();
        MOLANG_QUERY_GROUP.forEach(key -> addSymbol(value, key));

        AnalysisProfile extended = AnalysisProfile.load(
                write("extended.json", GSON.toJson(value)));

        assertEquals(120, extended.definitions().size());
        extended.requireExactSymbols(YsmSymbols.all().stream()
                .filter(key -> !key.equals(YsmSymbols.ANIMATION_CONTEXT_ROAMING_PROVIDER_BINDER))
                .map(YsmSymbolKey::id).toList());
        assertThrows(IllegalStateException.class, () -> extended.requireExactSymbols(
                legacyKeys().stream().map(YsmSymbolKey::id).toList()));
        for (YsmSymbolKey<?> key : MOLANG_QUERY_GROUP) {
            AnalysisProfile.Definition definition = extended.definitions().get(key.id());
            assertEquals(SymbolKind.METHOD, definition.kind());
            assertEquals(1, definition.definitionRevision());
        }
        legacy.definitions().forEach((id, definition) ->
                assertEquals(definition, extended.definitions().get(id)));
        assertNotEquals(legacy.profileSha256(), extended.profileSha256());
        assertNotEquals(legacy.registryDefinitionSha256(), extended.registryDefinitionSha256());
        assertEquals(legacy.fingerprintDefinitionSha256(), extended.fingerprintDefinitionSha256());
    }

    @Test
    void roamingBinderOptsInWithoutChangingExistingProfileDefinitions() throws Exception {
        JsonObject value = JsonParser.parseString(profile("test-mc", false, true))
                .getAsJsonObject();
        MOLANG_QUERY_GROUP.forEach(key -> addSymbol(value, key));
        AnalysisProfile before = AnalysisProfile.load(write("before-binder.json", GSON.toJson(value)));
        addSymbol(value, YsmSymbols.ANIMATION_CONTEXT_ROAMING_PROVIDER_BINDER);
        AnalysisProfile after = AnalysisProfile.load(write("with-binder.json", GSON.toJson(value)));

        assertEquals(121, after.definitions().size());
        after.requireExactSymbols(YsmSymbols.all().stream().map(YsmSymbolKey::id).toList());
        before.definitions().forEach((id, definition) ->
                assertEquals(definition, after.definitions().get(id)));
        assertNotEquals(before.registryDefinitionSha256(), after.registryDefinitionSha256());
        assertEquals(before.fingerprintDefinitionSha256(), after.fingerprintDefinitionSha256());
        assertEquals(1, after.definitions().get(
                YsmSymbols.ANIMATION_CONTEXT_ROAMING_PROVIDER_BINDER.id()).definitionRevision());
    }

    @Test
    void eitherIncompleteOptionalGroupFailsClosed() throws Exception {
        for (int index = 0; index < MOLANG_QUERY_GROUP.size(); index++) {
            JsonObject value = JsonParser.parseString(profile("test-mc", false, true))
                    .getAsJsonObject();
            addSymbol(value, MOLANG_QUERY_GROUP.get(index));
            Path path = write("partial-" + index + ".json", GSON.toJson(value));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> AnalysisProfile.load(path));

            assertTrue(failure.getMessage().contains("Incomplete optional profile symbol group"));
        }
    }

    @Test
    void optionalGroupDoesNotReplaceMissingRequiredSymbols() throws Exception {
        JsonObject value = JsonParser.parseString(profile("test-mc", false, true))
                .getAsJsonObject();
        value.getAsJsonArray("symbols").remove(0);
        MOLANG_QUERY_GROUP.forEach(key -> addSymbol(value, key));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AnalysisProfile.load(write("missing-required.json", GSON.toJson(value))));

        assertTrue(failure.getMessage().contains("Profile symbol mismatch"));
    }

    @Test
    void unknownSymbolFailsClosedEvenWithCompleteOptionalGroup() throws Exception {
        JsonObject value = JsonParser.parseString(profile("test-mc", false, true))
                .getAsJsonObject();
        MOLANG_QUERY_GROUP.forEach(key -> addSymbol(value, key));
        value.getAsJsonArray("symbols").add(GSON.toJsonTree(Map.of(
                "id", "ysm.molang.unapproved.method",
                "kind", "METHOD",
                "definitionRevision", 1)));

        assertThrows(IllegalArgumentException.class,
                () -> AnalysisProfile.load(write("unknown.json", GSON.toJson(value))));
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
        List<YsmSymbolKey<?>> keys = new ArrayList<>(legacyKeys());
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

    private static List<YsmSymbolKey<?>> legacyKeys() {
        return YsmSymbols.all().stream().filter(key -> !MOLANG_QUERY_GROUP.contains(key)
                && !key.equals(YsmSymbols.ANIMATION_CONTEXT_ROAMING_PROVIDER_BINDER)).toList();
    }

    private static void addSymbol(JsonObject profile, YsmSymbolKey<?> key) {
        profile.getAsJsonArray("symbols").add(GSON.toJsonTree(Map.of(
                "id", key.id(),
                "kind", key.kind().name(),
                "definitionRevision", 1)));
    }

}
