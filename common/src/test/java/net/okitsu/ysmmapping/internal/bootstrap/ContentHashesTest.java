package net.okitsu.ysmmapping.internal.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ContentHashesTest {
    @TempDir
    Path temporary;

    @Test
    void hashesSortedYsmClassNamesAndBytecodeOnly() throws IOException {
        Path first = temporary.resolve("first.jar");
        Path second = temporary.resolve("second.jar");
        writeJar(first, true, "ignored-one");
        writeJar(second, false, "ignored-two");

        assertEquals(ContentHashes.ysmClassesSha512(first),
                ContentHashes.ysmClassesSha512(second));
    }

    private static void writeJar(Path target, boolean normalOrder, String ignored)
            throws IOException {
        byte[] left = classBytes("com/elfmcys/yesstevemodel/Left");
        byte[] right = classBytes("com/elfmcys/yesstevemodel/Right");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            if (normalOrder) {
                entry(output, "com/elfmcys/yesstevemodel/Left.class", left);
                entry(output, "com/elfmcys/yesstevemodel/Right.class", right);
            } else {
                entry(output, "com/elfmcys/yesstevemodel/Right.class", right);
                entry(output, "com/elfmcys/yesstevemodel/Left.class", left);
            }
            entry(output, "unrelated.txt", ignored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public static byte[] classBytes(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static void entry(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
