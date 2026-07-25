package net.okitsu.ysmmapping.internal.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.okitsu.ysmmapping.api.ResolutionPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class MappingSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MappingSettings() {
    }

    public static synchronized ResolutionPolicy load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("settings.json");
        if (!Files.exists(path)) {
            write(path, new SettingsJson(1, ResolutionPolicy.SAFE_ONLY.name()));
            return ResolutionPolicy.SAFE_ONLY;
        }
        SettingsJson json;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            json = GSON.fromJson(reader, SettingsJson.class);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid YSM Mapping API settings", exception);
        }
        if (json == null || json.schemaVersion != 1 || json.resolutionPolicy == null) {
            throw new IOException("Invalid YSM Mapping API settings schema");
        }
        try {
            return ResolutionPolicy.valueOf(json.resolutionPolicy);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unknown YSM mapping resolution policy: "
                    + json.resolutionPolicy, exception);
        }
    }

    private static void write(Path path, SettingsJson json) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(json) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                moveOrAcceptConcurrentDefault(temporary, path);
            } catch (IOException exception) {
                if (!Files.exists(path)) {
                    throw exception;
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveOrAcceptConcurrentDefault(Path temporary, Path path)
            throws IOException {
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            if (!Files.exists(path)) {
                throw exception;
            }
        }
    }

    private record SettingsJson(int schemaVersion, String resolutionPolicy) {
    }
}
