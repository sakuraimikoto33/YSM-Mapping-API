package net.okitsu.ysmmapping.tool;

import com.google.gson.Gson;
import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import net.okitsu.ysmmapping.internal.analysis.AnalysisProfile;
import net.okitsu.ysmmapping.internal.analysis.EquipmentSemanticAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.JarStructureAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.YsmArtifact;
import net.okitsu.ysmmapping.internal.analysis.YsmClassIndex;
import net.okitsu.ysmmapping.internal.bootstrap.ContentHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Private-output candidate review driven entirely by an external candidate specification. */
final class NameCandidateReportGenerator {
    private static final Gson GSON = new Gson();
    private final AnalysisProfile profile;

    NameCandidateReportGenerator(AnalysisProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    void generate(Path specification, Path openYsmRoot, Path portRoot,
            Path jarDirectory, Path output) throws Exception {
        CandidateSpec spec = load(specification);
        if (!profile.minecraftVersion().equals(spec.minecraftVersion)) {
            throw new IllegalArgumentException("Candidate specification does not match profile");
        }
        Map<String, RuntimeIndex> runtime = new TreeMap<>();
        for (Map.Entry<String, String> fixture : spec.fixtures.entrySet()) {
            String loader = fixture.getKey();
            profile.loader(loader);
            runtime.put(loader, runtime(loader, spec.ysmVersion,
                    jarDirectory.resolve(fixture.getValue())));
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Candidate value : spec.candidates) {
            AnalysisProfile.Definition definition = profile.definitions().get(value.semanticKey);
            if (definition == null || definition.kind() != value.kind) {
                throw new IllegalArgumentException("Candidate is not in the analysis profile: "
                        + value.semanticKey);
            }
            SourceMatch open = sourceMatch(openYsmRoot, value.open);
            SourceMatch port = sourceMatch(portRoot, value.port);
            Map<String, Object> runtimeValues = new TreeMap<>();
            boolean runtimeComplete = true;
            for (Map.Entry<String, RuntimeIndex> entry : runtime.entrySet()) {
                YsmResolvedSymbol symbol = entry.getValue().symbols.get(value.semanticKey);
                runtimeValues.put(entry.getKey(), symbol == null ? null : MappingToolMain.json(symbol));
                runtimeComplete &= symbol != null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("semanticKey", value.semanticKey);
            result.put("kind", value.kind);
            result.put("accepted", open.matched && port.matched && runtimeComplete);
            result.put("openYsm", open);
            result.put("port", port);
            result.put("runtime", runtimeValues);
            candidates.add(result);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", candidates.stream().allMatch(value ->
                Boolean.TRUE.equals(value.get("accepted"))) ? "PASS" : "REVIEW");
        report.put("minecraftVersion", profile.minecraftVersion());
        report.put("profileSha256", profile.profileSha256());
        report.put("ysmVersion", spec.ysmVersion);
        report.put("sources", spec.sources);
        report.put("targets", runtime.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        value -> value.getValue().summary, (left, right) -> left, TreeMap::new)));
        report.put("candidates", candidates);
        Path normalized = output.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(normalized, MappingToolMain.gson().toJson(report)
                + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    static List<String> canonicalCandidateKeys(Path specification) throws IOException {
        CandidateSpec spec = load(specification);
        return spec.candidates.stream().map(value -> value.semanticKey).sorted().toList();
    }

    private RuntimeIndex runtime(String loader, String ysmVersion, Path jar) throws Exception {
        Path source = jar.toAbsolutePath().normalize();
        String sha512 = ContentHashes.ysmClassesSha512(source);
        YsmClassIndex index = YsmClassIndex.read(source);
        YsmArtifact artifact = new YsmArtifact("name-review-" + loader,
                profile.minecraftVersion(), loader, ysmVersion, sha512);
        Map<String, YsmResolvedSymbol> symbols = new TreeMap<>();
        new JarStructureAnalyzer(profile).analyzePartial(artifact, index).symbols()
                .forEach((key, value) -> symbols.put(key.id(), value));
        new EquipmentSemanticAnalyzer().analyze(index, profile, loader)
                .forEach((key, value) -> symbols.put(key.id(), value));
        return new RuntimeIndex(Map.copyOf(symbols),
                Map.of("loader", loader, "ysmVersion", ysmVersion,
                        "contentSha512", sha512, "resolved", symbols.size()));
    }

    private static SourceMatch sourceMatch(Path root, SourceReference reference)
            throws IOException {
        if (reference == null || reference.owner == null || reference.owner.isBlank()) {
            return new SourceMatch(false, null, "missing source reference");
        }
        String relative = reference.owner.replace('.', '/') + ".java";
        Path direct = root.resolve("src/main/java").resolve(relative).normalize();
        Path source = Files.isRegularFile(direct) ? direct : findBySuffix(root, relative);
        if (source == null) {
            return new SourceMatch(false, relative, "source file not found");
        }
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String member = reference.name == null ? "" : reference.name;
        boolean matched = member.isEmpty() || text.contains(member);
        return new SourceMatch(matched,
                root.toAbsolutePath().normalize().relativize(source.toAbsolutePath().normalize())
                        .toString().replace('\\', '/'),
                matched ? "matched" : "member name not found");
    }

    private static Path findBySuffix(Path root, String relative) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace('\\', '/').endsWith(relative))
                    .findFirst().orElse(null);
        }
    }

    private static CandidateSpec load(Path path) throws IOException {
        RawSpec raw = GSON.fromJson(Files.readString(path.toAbsolutePath().normalize(),
                StandardCharsets.UTF_8), RawSpec.class);
        if (raw == null || raw.formatVersion != 1 || raw.minecraftVersion == null
                || raw.minecraftVersion.isBlank() || raw.ysmVersion == null
                || raw.ysmVersion.isBlank() || raw.fixtures == null || raw.fixtures.isEmpty()
                || raw.candidates == null || raw.candidates.isEmpty()) {
            throw new IllegalArgumentException("Invalid candidate specification");
        }
        List<Candidate> candidates = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (RawCandidate value : raw.candidates) {
            SymbolKind kind = SymbolKind.valueOf(value.kind.toUpperCase(java.util.Locale.ROOT));
            if (value.semanticKey == null || !keys.add(value.semanticKey)
                    || value.open == null || value.port == null) {
                throw new IllegalArgumentException("Invalid or duplicate candidate");
            }
            candidates.add(new Candidate(value.semanticKey, kind, value.open, value.port));
        }
        return new CandidateSpec(raw.minecraftVersion, raw.ysmVersion,
                Map.copyOf(raw.fixtures), raw.sources == null ? Map.of() : Map.copyOf(raw.sources),
                List.copyOf(candidates));
    }

    private record CandidateSpec(String minecraftVersion, String ysmVersion,
                                 Map<String, String> fixtures, Map<String, String> sources,
                                 List<Candidate> candidates) {
    }

    private record Candidate(String semanticKey, SymbolKind kind,
                             SourceReference open, SourceReference port) {
    }

    private record SourceMatch(boolean matched, String path, String diagnostic) {
    }

    private record RuntimeIndex(Map<String, YsmResolvedSymbol> symbols,
                                Map<String, Object> summary) {
    }

    private static final class RawSpec {
        int formatVersion;
        String minecraftVersion;
        String ysmVersion;
        Map<String, String> fixtures;
        Map<String, String> sources;
        List<RawCandidate> candidates;
    }

    private static final class RawCandidate {
        String semanticKey;
        String kind;
        SourceReference open;
        SourceReference port;
    }

    private static final class SourceReference {
        String owner;
        String name;
    }
}
