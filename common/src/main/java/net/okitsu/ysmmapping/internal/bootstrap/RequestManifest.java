package net.okitsu.ysmmapping.internal.bootstrap;

import com.google.gson.Gson;
import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmSourceAlias;
import net.okitsu.ysmmapping.api.YsmStructureConstraints;
import net.okitsu.ysmmapping.api.YsmStructurePattern;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbolSignatures;
import net.okitsu.ysmmapping.api.YsmSymbols;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One consumer-owned request manifest; validation failures never affect another consumer. */
public record RequestManifest(Map<YsmSymbolKey<?>, Boolean> symbols,
                              Map<String, YsmSourceAlias> sourceAliases,
                              Map<String, String> aliasProblems,
                              Map<String, List<String>> mixinRequirements) {
    public RequestManifest {
        symbols = Map.copyOf(symbols);
        sourceAliases = Map.copyOf(sourceAliases);
        aliasProblems = Map.copyOf(aliasProblems);
        mixinRequirements = Map.copyOf(mixinRequirements);
    }

    public RequestManifest(Map<YsmSymbolKey<?>, Boolean> symbols,
                           Map<String, List<String>> mixinRequirements) {
        this(symbols, aliasesFromKeys(symbols), Map.of(), mixinRequirements);
    }

    private static Map<String, YsmSourceAlias> aliasesFromKeys(
            Map<YsmSymbolKey<?>, Boolean> symbols) {
        Map<String, YsmSourceAlias> result = new LinkedHashMap<>();
        symbols.keySet().forEach(key -> {
            if (key.sourceAlias() != null) result.put(key.id(), key.sourceAlias());
        });
        return result;
    }

    public static RequestManifest read(RequestManifestSource source) throws IOException {
        return read(source, "fabric");
    }

    public static RequestManifest read(RequestManifestSource source, String loader)
            throws IOException {
        ManifestJson json;
        try (Reader reader = Files.newBufferedReader(source.path(), StandardCharsets.UTF_8)) {
            try {
                json = new Gson().fromJson(reader, ManifestJson.class);
            } catch (RuntimeException exception) {
                throw invalid(source, "Malformed JSON", exception);
            }
        }
        if (json == null || json.schemaVersion != 1 || json.symbols == null) {
            throw invalid(source, "schemaVersion must be 1 and symbols must be present", null);
        }
        String normalizedLoader = Objects.requireNonNull(loader, "loader")
                .toLowerCase(java.util.Locale.ROOT);
        if (!normalizedLoader.equals("fabric") && !normalizedLoader.equals("neoforge")) {
            throw invalid(source, "Unsupported loader " + loader, null);
        }

        Map<YsmSymbolKey<?>, Boolean> symbols = new LinkedHashMap<>();
        Map<String, YsmSymbolKey<?>> byDeclaredId = new LinkedHashMap<>();
        Map<String, YsmSourceAlias> aliases = new LinkedHashMap<>();
        Map<String, String> aliasProblems = new LinkedHashMap<>();
        for (SymbolJson requested : json.symbols) {
            if (requested == null || requested.key == null || requested.kind == null
                    || requested.required == null) {
                throw invalid(source, "Every symbol requires key, kind, and required", null);
            }
            SymbolKind kind;
            try {
                kind = SymbolKind.valueOf(requested.kind);
            } catch (IllegalArgumentException exception) {
                throw invalid(source, "Invalid symbol kind for " + requested.key, exception);
            }

            YsmSourceAlias alias = null;
            if (requested.sourceAlias != null) {
                try {
                    alias = sourceAlias(requested.sourceAlias, kind, normalizedLoader);
                    aliases.put(requested.key, alias);
                } catch (RuntimeException exception) {
                    aliasProblems.put(requested.key, "Invalid source alias: "
                            + Objects.toString(exception.getMessage(), exception.getClass().getSimpleName()));
                }
            }

            YsmSymbolKey<?> key = curated(requested, kind, source);
            if (key == null) {
                if (alias == null) {
                    throw invalid(source, "Consumer symbol requires a valid sourceAlias: "
                            + requested.key, null);
                }
                key = consumer(requested, kind, alias, source);
            }
            YsmSymbolKey<?> previous = byDeclaredId.putIfAbsent(requested.key, key);
            if (previous != null && !previous.equals(key)) {
                throw invalid(source, "Conflicting definitions for " + requested.key, null);
            }
            symbols.merge(key, requested.required, Boolean::logicalOr);
        }

        Map<String, List<String>> mixins = new LinkedHashMap<>();
        if (json.mixinRequirements != null) {
            for (Map.Entry<String, List<String>> requirement : json.mixinRequirements.entrySet()) {
                if (requirement.getKey() == null || requirement.getKey().isBlank()
                        || requirement.getValue() == null) {
                    throw invalid(source, "Invalid mixin requirement", null);
                }
                List<String> validated = new ArrayList<>();
                for (String id : requirement.getValue()) {
                    if (!byDeclaredId.containsKey(id)) {
                        throw invalid(source, "Mixin requirement " + id
                                + " is not declared in symbols", null);
                    }
                    if (!aliases.containsKey(id)) {
                        aliasProblems.putIfAbsent(id, "Mixin source alias is required");
                    }
                    if (!validated.contains(id)) validated.add(id);
                }
                mixins.put(requirement.getKey(), List.copyOf(validated));
            }
        }
        return new RequestManifest(symbols, aliases, aliasProblems, mixins);
    }

    private static YsmSymbolKey<?> curated(SymbolJson requested, SymbolKind kind,
            RequestManifestSource source) throws IOException {
        YsmSymbolKey<?> key = YsmSymbols.byId(requested.key).orElse(null);
        if (key == null) return null;
        if (key.kind() != kind || requested.definition != null) {
            throw invalid(source, "Curated symbol kind or ownership mismatch: " + requested.key,
                    null);
        }
        return key;
    }

    private static YsmSymbolKey<?> consumer(SymbolJson requested, SymbolKind kind,
            YsmSourceAlias alias, RequestManifestSource source) throws IOException {
        if (requested.key.startsWith("ysm.")) {
            throw invalid(source, "Unknown curated YSM symbol key " + requested.key, null);
        }
        if (requested.definition == null || requested.definition.common == null) {
            throw invalid(source, "Consumer symbol requires a common definition: "
                    + requested.key, null);
        }
        try {
            YsmStructurePattern pattern = new YsmStructurePattern(
                    constraints(requested.definition.common),
                    requested.definition.fabric == null ? null
                            : constraints(requested.definition.fabric),
                    requested.definition.neoforge == null ? null
                            : constraints(requested.definition.neoforge));
            return switch (kind) {
                case CLASS -> YsmSymbolKey.consumerClass(requested.key, alias, pattern);
                case METHOD -> YsmSymbolKey.consumerMethod(requested.key, alias, pattern);
                case FIELD -> YsmSymbolKey.consumerField(requested.key, alias, pattern);
            };
        } catch (RuntimeException exception) {
            throw invalid(source, "Invalid consumer definition: " + requested.key, exception);
        }
    }

    private static YsmSourceAlias sourceAlias(SourceAliasSetJson source, SymbolKind kind,
                                               String loader) {
        if (source.common == null) {
            throw new IllegalArgumentException("sourceAlias.common is required");
        }
        SourceAliasJson override = loader.equals("fabric") ? source.fabric : source.neoforge;
        String owner = override(override == null ? null : override.owner, source.common.owner);
        String name = override(override == null ? null : override.name, source.common.name);
        String descriptor = override(override == null ? null : override.descriptor,
                source.common.descriptor);
        owner = owner == null ? null : owner.replace('/', '.');
        return switch (kind) {
            case CLASS -> YsmSourceAlias.classAlias(owner);
            case METHOD -> YsmSourceAlias.methodAlias(owner, name, descriptor);
            case FIELD -> YsmSourceAlias.fieldAlias(owner, name, descriptor);
        };
    }

    private static String override(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static YsmStructureConstraints constraints(ConstraintsJson json) {
        var builder = YsmStructureConstraints.builder()
                .requiredAccess(json.requiredAccess == null ? 0 : json.requiredAccess)
                .forbiddenAccess(json.forbiddenAccess == null ? 0 : json.forbiddenAccess);
        if (json.superName != null) builder.superName(json.superName);
        builder.interfaces(orEmpty(json.interfaces));
        orEmpty(json.memberShapes).forEach(builder::memberShape);
        orEmpty(json.descriptorShapes).forEach(builder::descriptorShape);
        orEmpty(json.opcodeDigests).forEach(builder::opcodeDigest);
        orEmpty(json.constantDigests).forEach(builder::constantDigest);
        orEmpty(json.externalReferences).forEach(builder::externalReference);
        orEmpty(json.callGraph).forEach(builder::call);
        orEmpty(json.fieldGraph).forEach(builder::fieldAccess);
        return builder.build();
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static IOException invalid(RequestManifestSource source, String message,
            Throwable cause) {
        return new IOException("Invalid YSM mapping request manifest for "
                + source.consumerModId() + ": " + message, cause);
    }

    public String cacheId(String consumerModId, String declaredId) {
        YsmSymbolKey<?> key = symbols.keySet().stream().filter(value ->
                value.id().equals(declaredId)).findFirst().orElseThrow();
        return key.scopedId(consumerModId);
    }

    public String sourceAliasSha256(String declaredId) {
        YsmSourceAlias alias = sourceAliases.get(declaredId);
        if (alias == null) return null;
        String canonical = alias.kind() + "|" + alias.owner() + "|"
                + Objects.toString(alias.name(), "") + "|"
                + Objects.toString(alias.descriptor(), "");
        return YsmSymbolSignatures.sha256(canonical);
    }

    public String mixinAliasProblem(String mixinClassName) {
        for (String id : mixinRequirements.getOrDefault(mixinClassName, List.of())) {
            String problem = aliasProblems.get(id);
            if (problem != null) return id + ": " + problem;
        }
        return null;
    }

    public static RequestManifest merge(RequestManifest left, RequestManifest right) {
        Map<String, YsmSymbolKey<?>> byId = new LinkedHashMap<>();
        left.symbols.keySet().forEach(key -> byId.put(key.id(), key));
        for (YsmSymbolKey<?> key : right.symbols.keySet()) {
            YsmSymbolKey<?> previous = byId.putIfAbsent(key.id(), key);
            if (previous != null && !previous.equals(key)) {
                throw new IllegalArgumentException("Conflicting consumer definition: " + key.id());
            }
        }
        Map<YsmSymbolKey<?>, Boolean> symbols = new LinkedHashMap<>(left.symbols);
        right.symbols.forEach((key, required) -> symbols.merge(key, required, Boolean::logicalOr));

        Map<String, YsmSourceAlias> aliases = new LinkedHashMap<>(left.sourceAliases);
        right.sourceAliases.forEach((id, alias) -> {
            YsmSourceAlias previous = aliases.putIfAbsent(id, alias);
            if (previous != null && !previous.equals(alias)) {
                throw new IllegalArgumentException("Conflicting source alias: " + id);
            }
        });
        Map<String, String> aliasProblems = new LinkedHashMap<>(left.aliasProblems);
        right.aliasProblems.forEach(aliasProblems::putIfAbsent);

        Map<String, List<String>> mixins = new LinkedHashMap<>(left.mixinRequirements);
        right.mixinRequirements.forEach((name, requirements) -> {
            List<String> merged = new ArrayList<>(mixins.getOrDefault(name, List.of()));
            requirements.forEach(value -> { if (!merged.contains(value)) merged.add(value); });
            mixins.put(name, List.copyOf(merged));
        });
        return new RequestManifest(symbols, aliases, aliasProblems, mixins);
    }

    private static final class ManifestJson {
        private int schemaVersion;
        private List<SymbolJson> symbols;
        private Map<String, List<String>> mixinRequirements;
    }
    private static final class SymbolJson {
        private String key;
        private String kind;
        private Boolean required;
        private SourceAliasSetJson sourceAlias;
        private DefinitionJson definition;
    }
    private static final class SourceAliasSetJson {
        private SourceAliasJson common;
        private SourceAliasJson fabric;
        private SourceAliasJson neoforge;
    }
    private static final class SourceAliasJson {
        private String owner;
        private String name;
        private String descriptor;
    }
    private static final class DefinitionJson {
        private ConstraintsJson common;
        private ConstraintsJson fabric;
        private ConstraintsJson neoforge;
    }
    private static final class ConstraintsJson {
        private Integer requiredAccess;
        private Integer forbiddenAccess;
        private String superName;
        private List<String> interfaces;
        private List<String> memberShapes;
        private List<String> descriptorShapes;
        private List<String> opcodeDigests;
        private List<String> constantDigests;
        private List<String> externalReferences;
        private List<String> callGraph;
        private List<String> fieldGraph;
    }
}
