package net.okitsu.ysmmapping.internal.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** A single parsed view shared by every resolver during one YSM analysis. */
public final class YsmClassIndex {
    private static final String YSM_PACKAGE = "com/elfmcys/yesstevemodel/";

    private final List<ClassNode> classes;
    private final Map<String, ClassNode> byName;

    private YsmClassIndex(List<ClassNode> classes) {
        this.classes = List.copyOf(classes);
        Map<String, ClassNode> names = new TreeMap<>();
        for (ClassNode node : classes) {
            if (names.put(node.name, node) != null) {
                throw new IllegalArgumentException("Duplicate YSM class " + node.name);
            }
        }
        byName = Collections.unmodifiableMap(names);
    }

    public static YsmClassIndex read(Path source) throws IOException {
        List<ClassBytes> entries = Files.isDirectory(source)
                ? directoryEntries(source) : jarEntries(source);
        List<ClassNode> classes = new ArrayList<>(entries.size());
        for (ClassBytes entry : entries) {
            try {
                ClassNode node = new ClassNode();
                new ClassReader(entry.bytes()).accept(node,
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classes.add(node);
            } catch (RuntimeException exception) {
                throw new IOException("Failed to parse " + entry.name(), exception);
            }
        }
        return new YsmClassIndex(classes);
    }

    public List<ClassNode> classes() {
        return classes;
    }

    public Map<String, ClassNode> byName() {
        return byName;
    }

    private static List<ClassBytes> directoryEntries(Path root) throws IOException {
        List<ClassBytes> entries = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = root.relativize(path).toString().replace('\\', '/');
                if (isYsmClass(name)) {
                    entries.add(new ClassBytes(name, Files.readAllBytes(path)));
                }
            }
        }
        return entries;
    }

    private static List<ClassBytes> jarEntries(Path source) throws IOException {
        List<ClassBytes> entries = new ArrayList<>();
        try (JarFile jar = new JarFile(source.toFile())) {
            for (JarEntry entry : jar.stream().filter(value -> !value.isDirectory())
                    .filter(value -> isYsmClass(value.getName()))
                    .sorted(java.util.Comparator.comparing(JarEntry::getName)).toList()) {
                try (InputStream input = jar.getInputStream(entry)) {
                    entries.add(new ClassBytes(entry.getName(), input.readAllBytes()));
                }
            }
        }
        return entries;
    }

    private static boolean isYsmClass(String name) {
        return name.startsWith(YSM_PACKAGE) && name.endsWith(".class")
                && !name.endsWith("module-info.class");
    }

    private record ClassBytes(String name, byte[] bytes) {
    }
}
