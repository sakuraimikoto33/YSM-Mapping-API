package net.okitsu.ysmmapping.internal.analysis;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Public-safe description of ignored regression inputs and aggregate expectations. */
public record FixtureCatalog(String minecraftVersion, List<Fixture> fixtures,
                             Expectations expectations) {
    private static final Gson GSON = new Gson();

    public static FixtureCatalog load(Path path, AnalysisProfile profile) throws IOException {
        RawCatalog raw = GSON.fromJson(Files.readString(path.toAbsolutePath().normalize(),
                StandardCharsets.UTF_8), RawCatalog.class);
        if (raw == null || raw.formatVersion != 1 || raw.minecraftVersion == null
                || !raw.minecraftVersion.equals(profile.minecraftVersion())) {
            throw new IllegalArgumentException("Fixture catalog does not match profile");
        }
        if (raw.fixtures == null || raw.fixtures.isEmpty()) {
            throw new IllegalArgumentException("Fixture catalog is empty");
        }
        Map<String, Fixture> values = new TreeMap<>();
        for (RawFixture value : raw.fixtures) {
            String fileName = requireText(value.fileName, "fixture fileName");
            if (!Path.of(fileName).getFileName().toString().equals(fileName)
                    || !fileName.endsWith(".jar")) {
                throw new IllegalArgumentException("Unsafe fixture fileName: " + fileName);
            }
            String loader = requireText(value.loader, "fixture loader")
                    .toLowerCase(Locale.ROOT);
            profile.loader(loader);
            Fixture fixture = new Fixture(fileName,
                    requireText(value.ysmVersion, "fixture ysmVersion"), loader);
            if (values.putIfAbsent(fileName, fixture) != null) {
                throw new IllegalArgumentException("Duplicate fixture fileName: " + fileName);
            }
        }
        Expectations expectations = Objects.requireNonNull(raw.expectations,
                "catalog expectations").validated();
        return new FixtureCatalog(raw.minecraftVersion,
                List.copyOf(values.values()), expectations);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + label);
        }
        return value;
    }

    public record Fixture(String fileName, String ysmVersion, String loader) {
        public String id(String minecraftVersion) {
            return minecraftVersion + '-' + loader + '-' + ysmVersion;
        }
    }

    public record Expectations(int registryTotal, Map<String, Integer> categories,
                               List<String> equipmentDirectRequiredLoaders,
                               List<String> equipmentFullRequiredYsmVersions) {
        private Expectations validated() {
            if (registryTotal <= 0 || categories == null || categories.isEmpty()) {
                throw new IllegalArgumentException("Invalid catalog aggregate expectations");
            }
            Map<String, Integer> categoryValues = new TreeMap<>();
            categories.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null || value < 0) {
                    throw new IllegalArgumentException("Invalid category expectation");
                }
                categoryValues.put(key, value);
            });
            return new Expectations(registryTotal,
                    Collections.unmodifiableMap(categoryValues),
                    normalized(equipmentDirectRequiredLoaders),
                    normalized(equipmentFullRequiredYsmVersions));
        }

        private static List<String> normalized(List<String> values) {
            if (values == null) return List.of();
            return values.stream().map(value -> requireText(value, "expectation value")
                    .toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        }
    }

    private static final class RawCatalog {
        int formatVersion;
        String minecraftVersion;
        List<RawFixture> fixtures;
        Expectations expectations;
    }

    private static final class RawFixture {
        String fileName;
        String ysmVersion;
        String loader;
    }
}
