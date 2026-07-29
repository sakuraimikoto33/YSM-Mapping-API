package net.okitsu.ysmmapping.internal.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WholeJarStructureAnalyzerTest {
    @TempDir
    Path temporary;

    @Test
    void indexesEveryClassAndIgnoresInternalNamesAndMemberOrder() throws Exception {
        Path first = jar("first.jar", List.of(
                clazz("com/elfmcys/yesstevemodel/o0O0", "fieldA", "methodA", 41),
                clazz("com/elfmcys/yesstevemodel/HelperA", "fieldB", "methodB", 7)));
        Path renamed = jar("renamed.jar", List.of(
                clazz("com/elfmcys/yesstevemodel/HelperB", "otherField", "otherMethod", 7),
                clazz("com/elfmcys/yesstevemodel/Oo00", "renamedField", "renamedMethod", 41)));

        WholeJarStructureGraph firstGraph = new WholeJarStructureAnalyzer().analyze(first);
        WholeJarStructureGraph renamedGraph = new WholeJarStructureAnalyzer().analyze(renamed);

        assertEquals(2, firstGraph.classes().size());
        assertEquals(WholeJarStructureAnalyzer.FINGERPRINT_DEFINITION_SHA256,
                firstGraph.fingerprintDefinitionSha256());
        List<String> firstFingerprints = firstGraph.classes().stream()
                .map(WholeJarStructureGraph.ClassStructure::fingerprint).sorted().toList();
        List<String> renamedFingerprints = renamedGraph.classes().stream()
                .map(WholeJarStructureGraph.ClassStructure::fingerprint).sorted().toList();
        assertEquals(firstFingerprints, renamedFingerprints);
        assertTrue(firstGraph.classes().stream().allMatch(value ->
                value.anonymousId().matches("@anon/sha256/[0-9a-f]{64}")));
        assertFalse(firstGraph.classes().stream().anyMatch(value ->
                value.anonymousId().contains("fabric") || value.anonymousId().matches(".*\\d{6}")));
    }

    @Test
    void constantChangesAlterTheOwningMethodAndClassFingerprints() throws Exception {
        WholeJarStructureGraph left = new WholeJarStructureAnalyzer().analyze(jar("left.jar",
                List.of(clazz("com/elfmcys/yesstevemodel/A", "f", "m", 1))));
        WholeJarStructureGraph right = new WholeJarStructureAnalyzer().analyze(jar("right.jar",
                List.of(clazz("com/elfmcys/yesstevemodel/B", "g", "n", 2))));

        var leftClass = left.classes().get(0);
        var rightClass = right.classes().get(0);
        assertNotEquals(leftClass.fingerprint(), rightClass.fingerprint());
        assertNotEquals(method(leftClass).constantDigest(), method(rightClass).constantDigest());
    }

    private WholeJarStructureGraph.MethodStructure method(
            WholeJarStructureGraph.ClassStructure value) {
        return value.methods().stream().filter(method -> !method.runtimeName().startsWith("<"))
                .findFirst().orElseThrow();
    }

    private Path jar(String name, List<byte[]> classes) throws IOException {
        Path path = temporary.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            int index = 0;
            for (byte[] value : classes) {
                String className = new org.objectweb.asm.ClassReader(value).getClassName();
                output.putNextEntry(new JarEntry(className + ".class"));
                output.write(value);
                output.closeEntry();
                index++;
            }
            assertEquals(classes.size(), index);
        }
        return path;
    }

    private static byte[] clazz(String name, String fieldName, String methodName,
                                int constant) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES
                | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, fieldName, "I", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
                null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V",
                false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName,
                Type.getMethodDescriptor(Type.INT_TYPE), null, null);
        method.visitCode();
        method.visitLdcInsn(constant);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
