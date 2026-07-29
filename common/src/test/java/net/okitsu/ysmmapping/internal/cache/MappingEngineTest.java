package net.okitsu.ysmmapping.internal.cache;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.MappingCandidate;
import net.okitsu.ysmmapping.api.MappingTarget;
import net.okitsu.ysmmapping.api.ResolutionPolicy;
import net.okitsu.ysmmapping.api.ResolutionStatus;
import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmFieldSymbol;
import net.okitsu.ysmmapping.api.YsmSourceAlias;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbols;
import net.okitsu.ysmmapping.internal.bootstrap.ContentHashesTest;
import net.okitsu.ysmmapping.internal.bootstrap.ContentHashes;
import net.okitsu.ysmmapping.internal.bootstrap.PlatformAdapter;
import net.okitsu.ysmmapping.internal.bootstrap.RequestManifest;
import net.okitsu.ysmmapping.internal.bootstrap.RequestManifestSource;
import net.okitsu.ysmmapping.internal.bootstrap.YsmInstallation;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingEngineTest {
    @TempDir
    Path temporary;

    @Test
    void cacheHitDoesNotRewriteAndAdditionalConsumerMerges() throws Exception {
        Path jar = ysmJar("One");
        FakePlatform platform = new FakePlatform(jar, temporary.resolve("config"), "2.6.99");
        Map<String, RequestManifest> firstRequest = Map.of("first_mod",
                request(YsmSymbols.REGISTRATION_CLASS, true));
        new MappingEngine(platform, firstRequest).initialize();
        Path mappings = platform.configDirectory().resolve("ysm_mapping_api/mappings.json");
        FileTime sentinel = FileTime.fromMillis(1_234_000L);
        Files.setLastModifiedTime(mappings, sentinel);

        new MappingEngine(platform, firstRequest).initialize();
        assertEquals(sentinel, Files.getLastModifiedTime(mappings));

        Map<String, RequestManifest> merged = Map.of(
                "first_mod", firstRequest.get("first_mod"),
                "second_mod", request(YsmSymbols.CLIENT_SEND_METHOD, false));
        new MappingEngine(platform, merged).initialize();
        JsonObject json = read(mappings);
        assertTrue(json.get("registryDefinitionSha256").getAsString().matches("[0-9a-f]{64}"));
        assertFalse(json.has("baselineContentSha256"));
        assertTrue(json.get("fingerprintDefinitionSha256").getAsString()
                .matches("[0-9a-f]{64}"));
        assertTrue(json.getAsJsonObject("consumers").has("first_mod"));
        assertTrue(json.getAsJsonObject("consumers").has("second_mod"));
        assertTrue(json.getAsJsonObject("entries").has(YsmSymbols.REGISTRATION_CLASS.id()));
        assertTrue(json.getAsJsonObject("entries").has(YsmSymbols.CLIENT_SEND_METHOD.id()));
        JsonObject entry = json.getAsJsonObject("entries")
                .getAsJsonObject(YsmSymbols.REGISTRATION_CLASS.id());
        assertEquals("CURATED", entry.get("origin").getAsString());
        assertTrue(entry.get("definitionSha256").getAsString().matches("[0-9a-f]{64}"));
        JsonObject request = json.getAsJsonObject("consumers").getAsJsonObject("first_mod")
                .getAsJsonObject("requests")
                .getAsJsonObject(YsmSymbols.REGISTRATION_CLASS.id());
        assertEquals("CLASS", request.get("kind").getAsString());
        assertTrue(request.get("definitionSha256").getAsString().matches("[0-9a-f]{64}"));
        assertFalse(Files.exists(platform.configDirectory()
                .resolve("ysm_mapping_api/mappings.lock")));
    }

    @Test
    void staleTemporaryFileIsRemovedOnCacheHitWithoutRewritingMappings() throws Exception {
        Path jar = ysmJar("StaleTemporary");
        Path config = temporary.resolve("stale-temporary-config");
        FakePlatform platform = new FakePlatform(jar, config, "2.6.99");
        Map<String, RequestManifest> requests = Map.of("owner_mod",
                request(YsmSymbols.REGISTRATION_CLASS, true));
        new MappingEngine(platform, requests).initialize();
        Path directory = config.resolve("ysm_mapping_api");
        Path mappings = directory.resolve("mappings.json");
        Path stale = directory.resolve("mappings.json.tmp");
        Files.writeString(stale, "stale");
        FileTime sentinel = FileTime.fromMillis(2_345_000L);
        Files.setLastModifiedTime(mappings, sentinel);

        new MappingEngine(platform, requests).initialize();

        assertFalse(Files.exists(stale));
        assertEquals(sentinel, Files.getLastModifiedTime(mappings));
        assertFalse(Files.exists(directory.resolve("mappings.lock")));
    }

    @Test
    void legacyBaselineCacheIsReplacedByTheStructuralRegistrySchema() throws Exception {
        Path config = temporary.resolve("legacy-cache-config");
        Path jar = ysmJar("LegacyCache");
        Path directory = config.resolve("ysm_mapping_api");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("mappings.json"), """
                {
                  "schemaVersion":1,
                  "fingerprintAlgorithm":1,
                  "baselineContentSha256":"%s",
                  "fingerprintDefinitionSha256":"%s",
                  "resolutionPolicy":"SAFE_ONLY",
                  "target":{
                    "minecraftVersion":"1.20.1","loader":"fabric",
                    "ysmVersion":"2.6.0","contentSha512":"%s"
                  },
                  "entries":{},"consumers":{}
                }
                """.formatted("a".repeat(64), "b".repeat(64),
                ContentHashes.ysmClassesSha512(jar)));
        FakePlatform platform = new FakePlatform(jar, config, "2.6.0");

        new MappingEngine(platform, Map.of("owner_mod",
                request(YsmSymbols.REGISTRATION_CLASS, true))).initialize();

        JsonObject json = read(directory.resolve("mappings.json"));
        assertTrue(json.has("registryDefinitionSha256"));
        assertFalse(json.has("baselineContentSha256"));
        assertTrue(json.getAsJsonObject("consumers").has("owner_mod"));
    }

    @Test
    void aliasChangeUpdatesConsumerMetadataWithoutInvalidatingTheResolvedEntry() throws Exception {
        Path config = temporary.resolve("alias-cache-config");
        FakePlatform platform = new FakePlatform(ysmJar("AliasCache"), config, "2.6.99");
        RequestManifest first = requestWithAlias("net.okitsu.example.ysmref.FirstRegistration");
        new MappingEngine(platform, Map.of("owner_mod", first)).initialize();
        Path mappings = config.resolve("ysm_mapping_api/mappings.json");
        JsonObject before = read(mappings).getAsJsonObject("entries")
                .getAsJsonObject(YsmSymbols.REGISTRATION_CLASS.id()).deepCopy();

        RequestManifest second = requestWithAlias("net.okitsu.example.ysmref.SecondRegistration");
        new MappingEngine(platform, Map.of("owner_mod", second)).initialize();
        JsonObject afterDocument = read(mappings);
        JsonObject after = afterDocument.getAsJsonObject("entries")
                .getAsJsonObject(YsmSymbols.REGISTRATION_CLASS.id());
        JsonObject storedRequest = afterDocument.getAsJsonObject("consumers")
                .getAsJsonObject("owner_mod").getAsJsonObject("requests")
                .getAsJsonObject(YsmSymbols.REGISTRATION_CLASS.id());

        assertEquals(before, after);
        assertEquals(second.sourceAliasSha256(YsmSymbols.REGISTRATION_CLASS.id()),
                storedRequest.get("sourceAliasSha256").getAsString());
    }

    @Test
    void changedYsmReplacesTheSingleTargetAndAllOldConsumers() throws Exception {
        Path firstJar = ysmJar("Before");
        Path config = temporary.resolve("config");
        FakePlatform first = new FakePlatform(firstJar, config, "2.6.1");
        MappingSnapshot before = new MappingEngine(first, Map.of("old_mod",
                request(YsmSymbols.REGISTRATION_CLASS, true))).initialize();

        Path secondJar = ysmJar("After");
        FakePlatform second = new FakePlatform(secondJar, config, "2.7.0");
        MappingSnapshot after = new MappingEngine(second, Map.of("new_mod",
                request(YsmSymbols.CLIENT_SEND_METHOD, true))).initialize();
        JsonObject json = read(config.resolve("ysm_mapping_api/mappings.json"));

        assertNotEquals(before.target().contentSha512(), after.target().contentSha512());
        assertFalse(json.getAsJsonObject("consumers").has("old_mod"));
        assertTrue(json.getAsJsonObject("consumers").has("new_mod"));
        assertFalse(json.getAsJsonObject("entries").has(YsmSymbols.REGISTRATION_CLASS.id()));
        assertEquals(1, json.getAsJsonObject("target").entrySet().stream()
                .filter(entry -> entry.getKey().equals("contentSha512")).count());
    }

    @Test
    void failedFingerprintLeavesPreviousMappingUntouched() throws Exception {
        Path config = temporary.resolve("config");
        FakePlatform valid = new FakePlatform(ysmJar("Valid"), config, "2.6.1");
        new MappingEngine(valid, Map.of("valid_mod",
                request(YsmSymbols.REGISTRATION_CLASS, true))).initialize();
        Path mappings = config.resolve("ysm_mapping_api/mappings.json");
        String previous = Files.readString(mappings);

        FakePlatform missing = new FakePlatform(temporary.resolve("missing.jar"), config, "3.0.0");
        assertThrows(IOException.class, () -> new MappingEngine(missing, Map.of()));
        assertEquals(previous, Files.readString(mappings));
    }

    @Test
    void failedUpdateRemovesReleasedLockFile() throws Exception {
        Path config = temporary.resolve("failed-update-config");
        FakePlatform delegate = new FakePlatform(ysmJar("FailedUpdate"), config, "2.6.99");
        PlatformAdapter failing = new PlatformAdapter() {
            @Override
            public String loader() {
                return delegate.loader();
            }

            @Override
            public Path configDirectory() {
                return delegate.configDirectory();
            }

            @Override
            public YsmInstallation ysmInstallation() {
                return delegate.ysmInstallation();
            }

            @Override
            public List<RequestManifestSource> requestManifests() {
                return List.of();
            }

            @Override
            public void info(String message) {
                throw new IllegalStateException("synthetic logging failure");
            }

            @Override
            public void warn(String message, Throwable failure) {
            }
        };

        assertThrows(IllegalStateException.class, () -> new MappingEngine(failing, Map.of(
                "failed_mod", request(YsmSymbols.REGISTRATION_CLASS, true))).initialize());
        assertFalse(Files.exists(config.resolve("ysm_mapping_api/mappings.lock")));
    }

    @Test
    void resolvedJsonMatchesThePublicShapeWithoutNestedConfidence() throws Exception {
        Path directory = temporary.resolve("shape");
        MappingsDocument document = MappingsDocument.fresh(new MappingTarget(
                "1.20.1", "fabric", "2.6.0", "a".repeat(128)),
                ResolutionPolicy.SAFE_ONLY, "b".repeat(64), "c".repeat(64));
        document.entries.put(YsmSymbols.REGISTRATION_CLASS.id(),
                MappingsDocument.EntryJson.resolved(YsmSymbols.REGISTRATION_CLASS,
                        "d".repeat(64), ResolutionStatus.STRUCTURAL,
                        new YsmClassSymbol("com/elfmcys/yesstevemodel/Example")));
        new MappingsStore(directory).write(document);

        JsonObject entry = read(directory.resolve("mappings.json"))
                .getAsJsonObject("entries")
                .getAsJsonObject(YsmSymbols.REGISTRATION_CLASS.id());
        assertTrue(entry.has("confidence"));
        assertFalse(entry.getAsJsonObject("resolved").has("confidence"));
    }

    @Test
    void ambiguousCandidatesAreSafeOnlyUnlessBestEffortIsExplicit() throws Exception {
        var candidates = List.of(
                new MappingCandidate(new YsmFieldSymbol("example/First", "flags", "S"), 0.84),
                new MappingCandidate(new YsmFieldSymbol("example/Second", "flags", "S"), 0.81));
        MappingsDocument safe = MappingsDocument.fresh(new MappingTarget(
                "1.20.1", "fabric", "future", "c".repeat(128)),
                ResolutionPolicy.SAFE_ONLY, "b".repeat(64), "c".repeat(64));
        safe.entries.put(YsmSymbols.PLAYER_STATE_FLAGS_FIELD.id(),
                MappingsDocument.EntryJson.candidates(YsmSymbols.PLAYER_STATE_FLAGS_FIELD,
                        "d".repeat(64), candidates, ResolutionPolicy.SAFE_ONLY,
                        "two candidates passed"));
        var safeEntry = safe.snapshot(Map.of(YsmSymbols.PLAYER_STATE_FLAGS_FIELD.id(),
                YsmSymbols.PLAYER_STATE_FLAGS_FIELD)).entries().get(
                YsmSymbols.PLAYER_STATE_FLAGS_FIELD.id());
        assertEquals(ResolutionStatus.AMBIGUOUS, safeEntry.status());
        assertEquals(null, safeEntry.resolved());
        assertEquals(2, safeEntry.candidates().size());

        MappingsDocument bestEffort = MappingsDocument.fresh(new MappingTarget(
                "1.20.1", "fabric", "future", "c".repeat(128)),
                ResolutionPolicy.BEST_EFFORT, "b".repeat(64), "c".repeat(64));
        bestEffort.entries.put(YsmSymbols.PLAYER_STATE_FLAGS_FIELD.id(),
                MappingsDocument.EntryJson.candidates(YsmSymbols.PLAYER_STATE_FLAGS_FIELD,
                        "d".repeat(64), candidates, ResolutionPolicy.BEST_EFFORT,
                        "best-effort selection from ambiguous candidates"));
        var bestEntry = bestEffort.snapshot(Map.of(YsmSymbols.PLAYER_STATE_FLAGS_FIELD.id(),
                YsmSymbols.PLAYER_STATE_FLAGS_FIELD)).entries().get(
                YsmSymbols.PLAYER_STATE_FLAGS_FIELD.id());
        assertEquals(ResolutionStatus.BEST_EFFORT, bestEntry.status());
        assertEquals(candidates.get(0).symbol(), bestEntry.resolved());
        assertEquals(0.84, bestEntry.confidence());
    }

    @RepeatedTest(5)
    void concurrentConsumersSerializeOnTheLockAndBothRemain() throws Exception {
        Path jar = ysmJar("Concurrent");
        Path config = temporary.resolve("concurrent-config");
        FakePlatform platform = new FakePlatform(jar, config, "2.6.99");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return new MappingEngine(platform, Map.of("alpha_mod",
                        request(YsmSymbols.REGISTRATION_CLASS, true))).initialize();
            });
            var second = executor.submit(() -> {
                start.await();
                return new MappingEngine(platform, Map.of("beta_mod",
                        request(YsmSymbols.CLIENT_SEND_METHOD, true))).initialize();
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        JsonObject consumers = read(config.resolve("ysm_mapping_api/mappings.json"))
                .getAsJsonObject("consumers");
        assertTrue(consumers.has("alpha_mod"));
        assertTrue(consumers.has("beta_mod"));
        assertFalse(Files.exists(config.resolve("ysm_mapping_api/mappings.lock")));
    }

    private Path ysmJar(String suffix) throws IOException {
        Path path = temporary.resolve("ysm-" + suffix + ".jar");
        String name = "com/elfmcys/yesstevemodel/" + suffix;
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            ContentHashesTest.entry(output, name + ".class",
                    ContentHashesTest.classBytes(name));
        }
        return path;
    }

    private static RequestManifest request(YsmSymbolKey<?> key, boolean required) {
        return new RequestManifest(Map.of(key, required), Map.of());
    }

    private static RequestManifest requestWithAlias(String owner) {
        return new RequestManifest(Map.of(YsmSymbols.REGISTRATION_CLASS, true),
                Map.of(YsmSymbols.REGISTRATION_CLASS.id(), YsmSourceAlias.classAlias(owner)),
                Map.of(), Map.of());
    }

    private static JsonObject read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private record FakePlatform(Path source, Path configDirectory, String ysmVersion)
            implements PlatformAdapter {
        @Override
        public String loader() {
            return "fabric";
        }

        @Override
        public YsmInstallation ysmInstallation() {
            return new YsmInstallation("1.20.1", loader(), ysmVersion, source);
        }

        @Override
        public List<RequestManifestSource> requestManifests() {
            return List.of();
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message, Throwable failure) {
        }
    }
}
