package net.okitsu.ysmmapping.internal.analysis;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JarStructureAnalyzerClientModelTest {
    private static final String OWNER = "example/ClientModelManager";
    private static final String LOOKUP_DESCRIPTOR =
            "(Ljava/lang/String;)Ljava/util/Optional;";

    @Test
    void resolvesTheUniquePublicStaticModelLookup() throws Exception {
        ClassNode manager = manager(lookup("findModel", LOOKUP_DESCRIPTOR));

        YsmCompatibilityMap.MethodSymbol symbol =
                JarStructureAnalyzer.findClientModelLookup(manager);

        assertEquals(OWNER, symbol.owner());
        assertEquals("findModel", symbol.name());
        assertEquals(LOOKUP_DESCRIPTOR, symbol.descriptor());
    }

    @Test
    void rejectsAMethodWithTheWrongDescriptor() {
        ClassNode manager = manager(lookup("findModel",
                "(Ljava/lang/String;)Ljava/lang/Object;"));

        assertThrows(IOException.class,
                () -> JarStructureAnalyzer.findClientModelLookup(manager));
    }

    @Test
    void rejectsAmbiguousModelLookups() {
        ClassNode manager = manager(lookup("findFirst", LOOKUP_DESCRIPTOR),
                lookup("findSecond", LOOKUP_DESCRIPTOR));

        assertThrows(IOException.class,
                () -> JarStructureAnalyzer.findClientModelLookup(manager));
    }

    private static ClassNode manager(MethodNode... methods) {
        ClassNode manager = new ClassNode();
        manager.name = OWNER;
        manager.methods.addAll(java.util.List.of(methods));
        return manager;
    }

    private static MethodNode lookup(String name, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, descriptor, null, null);
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "models",
                "Ljava/util/Map;"));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }
}
