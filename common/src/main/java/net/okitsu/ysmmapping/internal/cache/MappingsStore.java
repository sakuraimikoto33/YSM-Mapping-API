package net.okitsu.ysmmapping.internal.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class MappingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().create();

    private final Path mappings;

    MappingsStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        mappings = directory.resolve("mappings.json");
    }

    MappingsDocument read() throws IOException {
        if (!Files.exists(mappings)) {
            return null;
        }
        try (var reader = Files.newBufferedReader(mappings, StandardCharsets.UTF_8)) {
            MappingsDocument document = GSON.fromJson(reader, MappingsDocument.class);
            return document != null && document.valid() ? document : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    void cleanTemporary() throws IOException {
        Files.deleteIfExists(mappings.resolveSibling("mappings.json.tmp"));
    }

    void write(MappingsDocument document) throws IOException {
        Path temporary = mappings.resolveSibling("mappings.json.tmp");
        byte[] bytes = (GSON.toJson(document) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
        try {
            Files.move(temporary, mappings, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }
}
