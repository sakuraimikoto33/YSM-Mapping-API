package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmSourceAlias;
import net.okitsu.ysmmapping.api.YsmStructureConstraints;
import net.okitsu.ysmmapping.api.YsmStructurePattern;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructurePatternResolverTest {
    @Test
    void resolvesEachDefinitionIndependentlyAndRequiresAUniqueHardMatch() {
        var method = new WholeJarStructureGraph.MethodStructure("a", "()V", "member", 9,
                "()V", "opcode", "constant", List.of("java/lang/String"),
                List.of("call"), List.of("field"));
        var duplicate = new WholeJarStructureGraph.MethodStructure("b", "()V", "member", 9,
                "()V", "opcode", "constant", List.of("java/lang/String"),
                List.of("call"), List.of("field"));
        var owner = new WholeJarStructureGraph.ClassStructure("ysm/A",
                "@anon/sha256/" + "a".repeat(64), "a".repeat(64), 1,
                "java/lang/Object", List.of(), "", List.of(), List.of(method), List.of());
        var other = new WholeJarStructureGraph.ClassStructure("ysm/B",
                "@anon/sha256/" + "b".repeat(64), "b".repeat(64), 1,
                "java/lang/Object", List.of(), "", List.of(), List.of(duplicate), List.of());
        var uniquePattern = YsmStructurePattern.common(YsmStructureConstraints.builder()
                .requiredAccess(8).superName("java/lang/Object").descriptorShape("()V")
                .opcodeDigest("opcode").constantDigest("constant")
                .externalReference("java/lang/String").call("call").fieldAccess("field")
                .build());
        var unique = YsmSymbolKey.consumerMethod("unique", YsmSourceAlias.methodAlias(
                "net.example.Alias", "run", "()V"), uniquePattern);
        var graph = new WholeJarStructureGraph("f".repeat(64), List.of(owner));
        assertEquals(1, resolve(unique, graph).size());

        var ambiguousGraph = new WholeJarStructureGraph("f".repeat(64), List.of(owner, other));
        assertEquals(2, resolve(unique, ambiguousGraph).size());

        var missing = YsmSymbolKey.consumerMethod("missing", YsmSourceAlias.methodAlias(
                "net.example.Missing", "run", "()V"), YsmStructurePattern.common(
                YsmStructureConstraints.builder().opcodeDigest("different").build()));
        assertTrue(resolve(missing, graph).isEmpty());
        assertEquals(1, resolve(unique, graph).size());
    }

    private static List<net.okitsu.ysmmapping.api.MappingCandidate> resolve(
            YsmSymbolKey<?> key, WholeJarStructureGraph graph) {
        return new StructurePatternResolver().resolve(key.kind(),
                key.structurePattern().common(), key.structurePattern().refinement("fabric"),
                graph);
    }
}
