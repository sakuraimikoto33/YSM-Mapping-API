package net.okitsu.ysmmapping.internal.cache;

import net.okitsu.ysmmapping.api.MappingCandidate;
import net.okitsu.ysmmapping.api.MappingEntry;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.MappingTarget;
import net.okitsu.ysmmapping.api.ResolutionPolicy;
import net.okitsu.ysmmapping.api.ResolutionStatus;
import net.okitsu.ysmmapping.api.SymbolOrigin;
import net.okitsu.ysmmapping.api.YsmMappingProvider;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbols;
import net.okitsu.ysmmapping.internal.analysis.AnalysisProfile;
import net.okitsu.ysmmapping.internal.analysis.CuratedDefinitionRegistry;
import net.okitsu.ysmmapping.internal.analysis.EquipmentSemanticAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.JarStructureAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.StructurePatternResolver;
import net.okitsu.ysmmapping.internal.analysis.WholeJarStructureAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.WholeJarStructureGraph;
import net.okitsu.ysmmapping.internal.analysis.YsmArtifact;
import net.okitsu.ysmmapping.internal.analysis.YsmClassIndex;
import net.okitsu.ysmmapping.internal.bootstrap.ContentHashes;
import net.okitsu.ysmmapping.internal.bootstrap.PlatformAdapter;
import net.okitsu.ysmmapping.internal.bootstrap.RequestManifest;
import net.okitsu.ysmmapping.internal.bootstrap.YsmInstallation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Owns the single current-target cache and resolves every requested symbol independently. */
public final class MappingEngine implements YsmMappingProvider {
    public static final int FINGERPRINT_ALGORITHM = 1;
    private static final Object PROCESS_LOCK = new Object();

    private final PlatformAdapter platform;
    private final Path directory;
    private final YsmInstallation installation;
    private final AnalysisProfile profile;
    private final MappingTarget target;
    private final ResolutionPolicy policy;
    private final Map<String, RequestManifest> knownRequests = new TreeMap<>();
    private volatile MappingSnapshot snapshot;

    public MappingEngine(PlatformAdapter platform, Map<String, RequestManifest> startupRequests)
            throws IOException {
        this.platform = Objects.requireNonNull(platform, "platform");
        knownRequests.putAll(startupRequests);
        installation = platform.ysmInstallation();
        profile = CuratedDefinitionRegistry.load(installation.minecraftVersion());
        profile.loader(installation.loader());
        directory = platform.configDirectory().resolve("ysm_mapping_api");
        policy = MappingSettings.load(directory);
        String contentSha512 = ContentHashes.ysmClassesSha512(installation.source());
        target = new MappingTarget(installation.minecraftVersion(), installation.loader(),
                installation.ysmVersion(), contentSha512);
    }

    public MappingSnapshot initialize() throws IOException {
        synchronized (PROCESS_LOCK) {
            return updateLocked(Map.copyOf(knownRequests));
        }
    }

    @Override
    public MappingSnapshot resolve(String consumerModId, Collection<YsmSymbolKey<?>> keys)
            throws IOException {
        Objects.requireNonNull(consumerModId, "consumerModId");
        Map<YsmSymbolKey<?>, Boolean> requested = new LinkedHashMap<>();
        keys.forEach(key -> requested.put(Objects.requireNonNull(key, "key"), true));
        RequestManifest dynamic = new RequestManifest(requested, Map.of());
        synchronized (PROCESS_LOCK) {
            knownRequests.merge(consumerModId, dynamic, RequestManifest::merge);
            MappingSnapshot global = updateLocked(Map.copyOf(knownRequests));
            return global.forConsumer(consumerModId);
        }
    }

    @Override
    public MappingSnapshot current() throws IOException {
        MappingSnapshot current = snapshot;
        return current == null ? initialize() : current;
    }

    public ResolutionPolicy policy() { return policy; }

    private MappingSnapshot updateLocked(Map<String, RequestManifest> requests) throws IOException {
        Files.createDirectories(directory);
        Path lockPath = directory.resolve("mappings.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("Unable to acquire YSM mapping cache lock");
            MappingsStore store = new MappingsStore(directory);
            store.cleanTemporary();
            MappingsDocument document = store.read();
            boolean sameTarget = document != null && document.matches(target, policy,
                    profile.registryDefinitionSha256(),
                    profile.fingerprintDefinitionSha256());
            if (!sameTarget) {
                document = MappingsDocument.fresh(target, policy,
                        profile.registryDefinitionSha256(),
                        profile.fingerprintDefinitionSha256());
            }

            Map<String, Requested> union = unionRequests(requests);
            boolean changed = mergeConsumers(document, requests);
            MappingsDocument currentDocument = document;
            List<Requested> missing = union.values().stream().filter(request -> {
                MappingsDocument.EntryJson cached = currentDocument.entries.get(request.cacheId);
                return cached == null || cached.definitionRevision != 1
                        || !request.definitionSha256.equals(cached.definitionSha256)
                        || !request.key.kind().name().equals(cached.kind)
                        || !request.key.origin().name().equals(cached.origin);
            }).toList();

            if (!missing.isEmpty()) {
                Analysis analysis = analyze(missing);
                for (Requested request : missing) {
                    MappingsDocument.EntryJson entry = analysis.entries.get(request.cacheId);
                    if (entry == null) {
                        entry = MappingsDocument.EntryJson.candidates(request.key,
                                request.definitionSha256, List.of(), policy,
                                "No structurally valid candidate");
                    }
                    document.entries.put(request.cacheId, entry);
                    changed = true;
                }
            }

            Map<String, YsmSymbolKey<?>> activeKeys = new TreeMap<>();
            union.values().forEach(value -> activeKeys.put(value.cacheId, value.key));
            MappingSnapshot result = document.snapshot(activeKeys);
            if (!sameTarget || changed) {
                store.write(document);
                platform.info("YSM mappings updated: version=" + target.ysmVersion()
                        + ", sha512=" + target.contentSha512());
            } else {
                platform.info("YSM mapping cache hit: version=" + target.ysmVersion()
                        + ", sha512=" + target.contentSha512());
            }
            logUnresolved(result);
            snapshot = result;
            return result;
        } finally {
            removeReleasedLockFile(lockPath);
        }
    }

    private Analysis analyze(List<Requested> requests) throws IOException {
        YsmClassIndex classIndex = YsmClassIndex.read(installation.source());
        WholeJarStructureGraph graph = new WholeJarStructureAnalyzer().analyze(classIndex);
        Map<YsmSymbolKey<?>, YsmResolvedSymbol> equipment = Map.of();
        Map<YsmSymbolKey<?>, YsmResolvedSymbol> serverlessStructural = Map.of();
        Map<YsmSymbolKey<?>, String> structuralDiagnostics = Map.of();
        boolean needsEquipment = requests.stream().anyMatch(value -> value.key.origin()
                == SymbolOrigin.CURATED
                && CuratedDefinitionRegistry.get(profile, value.key).category()
                != AnalysisProfile.Category.SERVERLESS);
        if (needsEquipment) {
            equipment = publicSymbols(new EquipmentSemanticAnalyzer().analyze(classIndex,
                    profile, installation.loader()));
        }
        boolean needsServerless = requests.stream().anyMatch(value -> value.key.origin()
                == SymbolOrigin.CURATED
                && CuratedDefinitionRegistry.get(profile, value.key).category()
                == AnalysisProfile.Category.SERVERLESS);
        if (needsServerless) {
            var artifact = new YsmArtifact("runtime", installation.minecraftVersion(),
                    installation.loader(), installation.ysmVersion(), target.contentSha512());
            JarStructureAnalyzer.PartialAnalysis partial = new JarStructureAnalyzer(profile)
                    .analyzePartial(artifact, classIndex);
            serverlessStructural = publicSymbols(partial.symbols());
            structuralDiagnostics = publicDiagnostics(partial.diagnostics());
        }

        Map<String, MappingsDocument.EntryJson> entries = new TreeMap<>();
        StructurePatternResolver patternResolver = new StructurePatternResolver();
        for (Requested request : requests) {
            YsmSymbolKey<?> key = request.key;
            if (key.origin() == SymbolOrigin.CONSUMER_DEFINED) {
                var pattern = key.structurePattern();
                List<MappingCandidate> candidates = patternResolver.resolve(key.kind(),
                        pattern.common(), pattern.refinement(installation.loader()), graph);
                if (candidates.size() == 1) {
                    entries.put(request.cacheId, MappingsDocument.EntryJson.resolved(key,
                            request.definitionSha256, ResolutionStatus.STRUCTURAL,
                            candidates.getFirst().symbol()));
                } else {
                    entries.put(request.cacheId, MappingsDocument.EntryJson.candidates(key,
                            request.definitionSha256, candidates, policy,
                            candidates.isEmpty() ? "No candidate passed every required constraint"
                                    : candidates.size() + " candidates passed every required constraint"));
                }
                continue;
            }
            YsmResolvedSymbol value = equipment.get(key);
            if (value == null) value = serverlessStructural.get(key);
            if (value != null) {
                entries.put(request.cacheId, MappingsDocument.EntryJson.resolved(key,
                        request.definitionSha256, ResolutionStatus.STRUCTURAL, value));
            } else {
                String diagnostic = structuralDiagnostics.getOrDefault(key,
                        "No structurally valid candidate");
                entries.put(request.cacheId, MappingsDocument.EntryJson.candidates(key,
                        request.definitionSha256, List.of(), policy, diagnostic));
            }
        }
        return new Analysis(entries);
    }

    private static Map<YsmSymbolKey<?>, YsmResolvedSymbol> publicSymbols(
            Map<net.okitsu.ysmmapping.internal.analysis.YsmSymbolKey<?>,
                    YsmResolvedSymbol> values) {
        Map<YsmSymbolKey<?>, YsmResolvedSymbol> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(YsmSymbols.byId(key.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Profile analyzer returned unknown symbol: " + key.id())), value));
        return Map.copyOf(result);
    }

    private static Map<YsmSymbolKey<?>, String> publicDiagnostics(
            Map<net.okitsu.ysmmapping.internal.analysis.YsmSymbolKey<?>, String> values) {
        Map<YsmSymbolKey<?>, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(YsmSymbols.byId(key.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Profile analyzer returned unknown symbol: " + key.id())), value));
        return Map.copyOf(result);
    }

    private boolean mergeConsumers(MappingsDocument document,
            Map<String, RequestManifest> manifests) {
        boolean changed = false;
        for (Map.Entry<String, RequestManifest> consumer : new TreeMap<>(manifests).entrySet()) {
            MappingsDocument.ConsumerJson stored = document.consumers.computeIfAbsent(
                    consumer.getKey(), ignored -> new MappingsDocument.ConsumerJson());
            for (Map.Entry<YsmSymbolKey<?>, Boolean> request : consumer.getValue().symbols().entrySet()) {
                String cacheId = request.getKey().scopedId(consumer.getKey());
                String digest = definitionSha256(request.getKey());
                String aliasDigest = consumer.getValue().sourceAliasSha256(request.getKey().id());
                MappingsDocument.RequestJson previous = stored.requests.get(cacheId);
                if (previous == null || previous.definitionRevision != 1
                        || previous.required != request.getValue()
                        || !request.getKey().kind().name().equals(previous.kind)
                        || !digest.equals(previous.definitionSha256)
                        || !Objects.equals(aliasDigest, previous.sourceAliasSha256)) {
                    stored.requests.put(cacheId, MappingsDocument.RequestJson.from(
                            request.getKey(), digest, aliasDigest, request.getValue()));
                    changed = true;
                }
            }
        }
        return changed;
    }

    private Map<String, Requested> unionRequests(Map<String, RequestManifest> manifests) {
        Map<String, Requested> union = new TreeMap<>();
        new TreeMap<>(manifests).forEach((consumer, manifest) -> manifest.symbols()
                .forEach((key, required) -> {
                    String cacheId = key.scopedId(consumer);
                    String digest = definitionSha256(key);
                    union.merge(cacheId, new Requested(key, cacheId, digest, required),
                            (left, right) -> new Requested(left.key, cacheId, digest,
                                    left.required || right.required));
                }));
        return union;
    }

    private String definitionSha256(YsmSymbolKey<?> key) {
        return key.origin() == SymbolOrigin.CURATED
                ? CuratedDefinitionRegistry.get(profile, key).definitionSha256()
                : key.definitionSha256();
    }

    private void removeReleasedLockFile(Path lockPath) {
        try {
            Files.deleteIfExists(lockPath);
        } catch (IOException exception) {
            platform.warn("Unable to remove released YSM mapping lock", exception);
        }
    }

    private void logUnresolved(MappingSnapshot result) {
        for (MappingEntry entry : result.entries().values()) if (!entry.status().resolved()) {
            platform.warn("Unresolved YSM symbol " + entry.key().id() + ": "
                    + entry.status() + (entry.diagnostic() == null ? "" : " ("
                    + entry.diagnostic() + ")"), null);
        }
    }

    private record Requested(YsmSymbolKey<?> key, String cacheId,
                             String definitionSha256, boolean required) {}
    private record Analysis(Map<String, MappingsDocument.EntryJson> entries) {}
}
