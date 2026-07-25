package net.okitsu.ysmmapping.internal.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarFile;

public final class ContentHashes {
    private static final String YSM_PREFIX = "com/elfmcys/yesstevemodel/";

    private ContentHashes() {
    }

    public static String ysmClassesSha512(Path source) throws IOException {
        MessageDigest digest = sha512();
        int count;
        if (Files.isDirectory(source)) {
            count = digestDirectory(source, digest);
        } else {
            count = digestJar(source, digest);
        }
        if (count == 0) {
            throw new IOException("No YSM classes found in " + source);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int digestDirectory(Path root, MessageDigest digest) throws IOException {
        List<Path> classes;
        try (var paths = Files.walk(root)) {
            classes = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> root.relativize(path).toString().replace('\\', '/')
                            .startsWith(YSM_PREFIX))
                    .sorted((left, right) -> normalized(root, left).compareTo(normalized(root, right)))
                    .toList();
        }
        for (Path path : classes) {
            updateName(digest, normalized(root, path));
            try (InputStream input = Files.newInputStream(path)) {
                updateBytes(digest, input);
            }
        }
        return classes.size();
    }

    private static int digestJar(Path source, MessageDigest digest) throws IOException {
        try (JarFile jar = new JarFile(source.toFile())) {
            var entries = jar.entries().asIterator();
            List<java.util.jar.JarEntry> classes = new java.util.ArrayList<>();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (!entry.isDirectory() && entry.getName().startsWith(YSM_PREFIX)
                        && entry.getName().endsWith(".class")) {
                    classes.add(entry);
                }
            }
            classes.sort(java.util.Comparator.comparing(java.util.jar.JarEntry::getName));
            for (var entry : classes) {
                updateName(digest, entry.getName());
                try (InputStream input = jar.getInputStream(entry)) {
                    updateBytes(digest, input);
                }
            }
            return classes.size();
        }
    }

    private static String normalized(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static void updateName(MessageDigest digest, String name) {
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void updateBytes(MessageDigest digest, InputStream input) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, count);
        }
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-512 is unavailable", exception);
        }
    }
}
