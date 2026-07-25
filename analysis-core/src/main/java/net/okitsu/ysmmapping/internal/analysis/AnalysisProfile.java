package net.okitsu.ysmmapping.internal.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmStructureConstraints;
import net.okitsu.ysmmapping.api.YsmSymbolSignatures;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Validated Minecraft profile consumed by both runtime adapters and offline tools. */
public final class AnalysisProfile {
    private static final Gson GSON = new Gson();

    private final String minecraftVersion;
    private final String ysmClassPrefix;
    private final Map<String, LoaderTypes> loaders;
    private final List<String> channelIdentifiers;
    private final List<Source> sources;
    private final Map<Integer, PacketDefinition> packets;
    private final Map<String, Definition> definitions;
    private final String profileSha256;
    private final String registryDefinitionSha256;

    private AnalysisProfile(RawProfile raw, String profileSha256) {
        if (raw.formatVersion != 1) {
            throw new IllegalArgumentException("Unsupported analysis profile format: "
                    + raw.formatVersion);
        }
        minecraftVersion = requireText(raw.minecraftVersion, "minecraftVersion");
        ysmClassPrefix = requireInternalPrefix(raw.ysmClassPrefix);
        if (raw.loaders == null || raw.loaders.isEmpty()) {
            throw new IllegalArgumentException("Analysis profile has no loaders");
        }
        Map<String, LoaderTypes> loaderValues = new TreeMap<>();
        raw.loaders.forEach((id, value) -> {
            String normalized = requireText(id, "loader").toLowerCase(Locale.ROOT);
            if (loaderValues.putIfAbsent(normalized,
                    Objects.requireNonNull(value, "loader types").validated()) != null) {
                throw new IllegalArgumentException("Duplicate loader: " + id);
            }
        });
        loaders = Collections.unmodifiableMap(loaderValues);
        channelIdentifiers = requireList(raw.channelIdentifiers, "channelIdentifiers").stream()
                .map(value -> requireText(value, "channel identifier"))
                .distinct()
                .toList();
        if (channelIdentifiers.size() != raw.channelIdentifiers.size()) {
            throw new IllegalArgumentException("Duplicate channel identifier");
        }
        sources = requireList(raw.sources, "sources").stream().map(value ->
                new Source(requireText(value.repository, "source repository"),
                        requireText(value.commit, "source commit"))).distinct().toList();
        if (sources.size() != raw.sources.size()) {
            throw new IllegalArgumentException("Duplicate source provenance");
        }

        Map<Integer, PacketDefinition> packetValues = new TreeMap<>();
        for (RawPacket value : requireList(raw.packets, "packets")) {
            PacketDefinition packet = new PacketDefinition(value.id,
                    requireText(value.name, "packet name"),
                    requireText(value.direction, "packet direction"));
            if (packet.id() < 0 || packetValues.putIfAbsent(packet.id(), packet) != null) {
                throw new IllegalArgumentException("Invalid or duplicate packet id: " + packet.id());
            }
        }
        packets = Collections.unmodifiableMap(packetValues);

        Map<String, Definition> definitionValues = new TreeMap<>();
        for (RawDefinition value : requireList(raw.symbols, "symbols")) {
            String id = requireText(value.id, "symbol id");
            SymbolKind kind;
            Category category;
            try {
                kind = SymbolKind.valueOf(requireText(value.kind, "symbol kind")
                        .toUpperCase(Locale.ROOT));
                category = Category.valueOf(requireText(value.category, "symbol category")
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid symbol metadata: " + id, exception);
            }
            String expectedSuffix = "." + kind.name().toLowerCase(Locale.ROOT);
            if (!id.startsWith("ysm.") || !id.endsWith(expectedSuffix)
                    || value.definitionRevision != 1) {
                throw new IllegalArgumentException("Invalid symbol definition: " + id);
            }
            String role = requireText(value.role, "symbol role");
            String provenance = requireText(value.provenance, "symbol provenance");
            String analysisRule = requireText(value.analysisRule, "symbol analysis rule");
            StructureDefinition structure = Objects.requireNonNull(value.structure,
                    "symbol structure").validated(loaders.keySet());
            String digest = YsmSymbolSignatures.sha256("profile-v1|" + id + '|' + kind + '|'
                    + category + '|' + role + '|' + provenance + '|' + analysisRule);
            Definition definition = new Definition(id, kind, category, role, provenance,
                    analysisRule, structure, value.definitionRevision, digest);
            if (definitionValues.putIfAbsent(id, definition) != null) {
                throw new IllegalArgumentException("Duplicate symbol definition: " + id);
            }
        }
        if (raw.expectedSymbolCount <= 0 || definitionValues.size() != raw.expectedSymbolCount) {
            throw new IllegalArgumentException("Profile symbol count is "
                    + definitionValues.size() + ", expected " + raw.expectedSymbolCount);
        }
        Set<String> builtIns = YsmSymbols.all().stream().map(YsmSymbolKey::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!definitionValues.keySet().containsAll(builtIns)) {
            Set<String> missing = new java.util.TreeSet<>(builtIns);
            missing.removeAll(definitionValues.keySet());
            throw new IllegalArgumentException("Profile is missing built-in analyzer roles: "
                    + missing);
        }
        definitions = Collections.unmodifiableMap(definitionValues);
        this.profileSha256 = profileSha256;
        StringBuilder registry = new StringBuilder("ysm-profile-registry-v1|")
                .append(profileSha256).append('|')
                .append(WholeJarStructureAnalyzer.FINGERPRINT_DEFINITION_SHA256).append('\n');
        definitions.forEach((id, value) -> registry.append(id).append('|')
                .append(value.definitionSha256()).append('\n'));
        registryDefinitionSha256 = YsmSymbolSignatures.sha256(registry.toString());
    }

    public static AnalysisProfile load(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        JsonElement json = JsonParser.parseString(Files.readString(normalized,
                StandardCharsets.UTF_8));
        return load(json, normalized.toString());
    }

    public static AnalysisProfile load(InputStream input, String source) throws IOException {
        Objects.requireNonNull(input, "input");
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return load(JsonParser.parseReader(reader), requireText(source, "profile source"));
        }
    }

    private static AnalysisProfile load(JsonElement json, String source) {
        String canonical = canonical(json);
        RawProfile raw = GSON.fromJson(json, RawProfile.class);
        if (raw == null) {
            throw new IllegalArgumentException("Analysis profile is empty: " + source);
        }
        return new AnalysisProfile(raw, YsmSymbolSignatures.sha256(canonical));
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public String ysmClassPrefix() {
        return ysmClassPrefix;
    }

    public LoaderTypes loader(String id) {
        LoaderTypes value = loaders.get(requireText(id, "loader").toLowerCase(Locale.ROOT));
        if (value == null) {
            throw new IllegalArgumentException("Profile does not support loader: " + id);
        }
        return value;
    }

    public Map<String, LoaderTypes> loaders() {
        return loaders;
    }

    public List<String> channelIdentifiers() {
        return channelIdentifiers;
    }

    public List<Source> sources() {
        return sources;
    }

    public Map<Integer, PacketDefinition> packets() {
        return packets;
    }

    public Map<String, Definition> definitions() {
        return definitions;
    }

    public String profileSha256() {
        return profileSha256;
    }

    public String registryDefinitionSha256() {
        return registryDefinitionSha256;
    }

    public String fingerprintDefinitionSha256() {
        return WholeJarStructureAnalyzer.FINGERPRINT_DEFINITION_SHA256;
    }

    public void requireExactSymbols(Iterable<String> ids) {
        Set<String> actual = new java.util.TreeSet<>();
        ids.forEach(actual::add);
        if (!actual.equals(definitions.keySet())) {
            Set<String> missing = new java.util.TreeSet<>(definitions.keySet());
            missing.removeAll(actual);
            Set<String> extra = new java.util.TreeSet<>(actual);
            extra.removeAll(definitions.keySet());
            throw new IllegalStateException("API/profile symbol mismatch: missing=" + missing
                    + ", extra=" + extra);
        }
    }

    private static String canonical(JsonElement value) {
        StringBuilder result = new StringBuilder();
        appendCanonical(value, result);
        return result.toString();
    }

    private static void appendCanonical(JsonElement value, StringBuilder result) {
        if (value == null || value.isJsonNull()) {
            result.append("null");
        } else if (value.isJsonArray()) {
            result.append('[');
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                if (index > 0) result.append(',');
                appendCanonical(array.get(index), result);
            }
            result.append(']');
        } else if (value.isJsonObject()) {
            result.append('{');
            JsonObject object = value.getAsJsonObject();
            List<String> names = new ArrayList<>(object.keySet());
            Collections.sort(names);
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) result.append(',');
                result.append(GSON.toJson(names.get(index))).append(':');
                appendCanonical(object.get(names.get(index)), result);
            }
            result.append('}');
        } else {
            result.append(GSON.toJson(value));
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + label);
        }
        return value;
    }

    private static String requireInternalPrefix(String value) {
        String result = requireText(value, "ysmClassPrefix");
        if (!result.endsWith("/") || result.startsWith("/") || result.indexOf('.') >= 0) {
            throw new IllegalArgumentException("Invalid YSM class prefix: " + result);
        }
        return result;
    }

    private static <T> List<T> requireList(List<T> values, String label) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Analysis profile has no " + label);
        }
        return List.copyOf(values);
    }

    public enum Category {
        SERVERLESS,
        EQUIPMENT_DIRECT,
        EQUIPMENT_RELATED
    }

    public record PacketDefinition(int id, String name, String direction) {
    }

    public record Definition(String id, SymbolKind kind, Category category, String role,
                             String provenance, String analysisRule,
                             StructureDefinition structure, int definitionRevision,
                             String definitionSha256) {
    }

    public record Source(String repository, String commit) {
    }

    public record StructureDefinition(YsmStructureConstraints common,
                                      Map<String, YsmStructureConstraints> loaders) {
        private StructureDefinition validated(Set<String> supportedLoaders) {
            YsmStructureConstraints commonValue = common == null
                    ? YsmStructureConstraints.EMPTY : common;
            Map<String, YsmStructureConstraints> loaderValues = new TreeMap<>();
            if (loaders != null) {
                loaders.forEach((loader, constraints) -> {
                    String normalized = requireText(loader, "structure loader")
                            .toLowerCase(Locale.ROOT);
                    if (!supportedLoaders.contains(normalized)) {
                        throw new IllegalArgumentException(
                                "Structure uses unsupported loader: " + loader);
                    }
                    loaderValues.put(normalized,
                            Objects.requireNonNull(constraints, "loader constraints"));
                });
            }
            return new StructureDefinition(commonValue,
                    Collections.unmodifiableMap(loaderValues));
        }
    }

    public record LoaderTypes(String livingEntity, String itemStack, String equipmentSlot,
                              String items, String poseStack, String multiBuffer,
                              List<String> entityTypes, List<String> playerTypes,
                              List<String> connectionTypes, List<String> componentTypes) {
        private LoaderTypes validated() {
            List<String> entities = List.copyOf(Objects.requireNonNull(entityTypes,
                    "entityTypes"));
            List<String> players = List.copyOf(Objects.requireNonNull(playerTypes,
                    "playerTypes"));
            List<String> connections = List.copyOf(Objects.requireNonNull(connectionTypes,
                    "connectionTypes"));
            List<String> components = List.copyOf(Objects.requireNonNull(componentTypes,
                    "componentTypes"));
            requireNonEmpty(entities, "entityTypes");
            requireNonEmpty(players, "playerTypes");
            requireNonEmpty(connections, "connectionTypes");
            requireNonEmpty(components, "componentTypes");
            return new LoaderTypes(internal(livingEntity), internal(itemStack),
                    internal(equipmentSlot), internal(items), internal(poseStack),
                    internal(multiBuffer),
                    entities.stream().map(LoaderTypes::internal).distinct().toList(),
                    players.stream().map(LoaderTypes::internal).distinct().toList(),
                    connections.stream().map(LoaderTypes::internal).distinct().toList(),
                    components.stream().map(LoaderTypes::internal).distinct().toList());
        }

        private static void requireNonEmpty(List<String> values, String label) {
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Loader " + label + " is empty");
            }
        }

        private static String internal(String value) {
            String result = requireText(value, "loader type");
            if (result.startsWith("/") || result.endsWith("/") || result.indexOf('.') >= 0) {
                throw new IllegalArgumentException("Invalid internal type name: " + result);
            }
            return result;
        }
    }

    @SuppressWarnings("unused")
    private static final class RawProfile {
        int formatVersion;
        String minecraftVersion;
        String ysmClassPrefix;
        int expectedSymbolCount;
        Map<String, LoaderTypes> loaders;
        List<String> channelIdentifiers;
        List<RawSource> sources;
        List<RawPacket> packets;
        List<RawDefinition> symbols;
    }

    @SuppressWarnings("unused")
    private static final class RawPacket {
        int id;
        String name;
        String direction;
    }

    @SuppressWarnings("unused")
    private static final class RawDefinition {
        String id;
        String kind;
        String category;
        String role;
        String provenance;
        String analysisRule;
        StructureDefinition structure;
        int definitionRevision;
    }

    @SuppressWarnings("unused")
    private static final class RawSource {
        String repository;
        String commit;
    }
}
