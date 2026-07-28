package net.okitsu.ysmmapping.internal.analysis;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JarStructureAnalyzerForgeTest {
    @Test
    void acceptsForgeSupplierClientHandler() {
        Type[] arguments = Type.getArgumentTypes(
                "(Lexample/Packet;Ljava/util/function/Supplier;)V");

        assertTrue(JarStructureAnalyzer.isForgeSupplierClientHandler(arguments));
    }

    @Test
    void rejectsNonSupplierSecondArgument() {
        Type[] arguments = Type.getArgumentTypes(
                "(Lexample/Packet;Ljava/lang/String;)V");

        assertFalse(JarStructureAnalyzer.isForgeSupplierClientHandler(arguments));
    }
}
