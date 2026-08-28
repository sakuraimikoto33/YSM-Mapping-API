package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmStructureConstraints;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructurePatternResolverTest {
    @Test
    void methodDefinitionsCanConstrainTheOwningClassByMemberShape() {
        WholeJarStructureGraph.MethodStructure matchingMethod = method("candidate", "()V");
        WholeJarStructureGraph graph = new WholeJarStructureGraph("definition", List.of(
                owner("first", field("I"), matchingMethod),
                owner("second", field("J"), matchingMethod)));
        StructurePatternResolver resolver = new StructurePatternResolver();
        YsmStructureConstraints methodOnly = YsmStructureConstraints.builder()
                .descriptorShape("()V")
                .build();
        YsmStructureConstraints ownerAware = YsmStructureConstraints.builder()
                .descriptorShape("()V")
                .memberShape("I")
                .build();

        assertEquals(2, resolver.resolve(SymbolKind.METHOD, methodOnly,
                YsmStructureConstraints.EMPTY, graph).size());
        assertEquals(1, resolver.resolve(SymbolKind.METHOD, ownerAware,
                YsmStructureConstraints.EMPTY, graph).size());
    }

    private static WholeJarStructureGraph.ClassStructure owner(
            String name, WholeJarStructureGraph.FieldStructure field,
            WholeJarStructureGraph.MethodStructure method) {
        return new WholeJarStructureGraph.ClassStructure(
                name, "anonymous-" + name, "fingerprint-" + name,
                0, "java/lang/Object", List.of(), "",
                List.of(field), List.of(method), List.of());
    }

    private static WholeJarStructureGraph.FieldStructure field(String descriptor) {
        return new WholeJarStructureGraph.FieldStructure(
                "field", descriptor, "field-" + descriptor,
                0, descriptor, "");
    }

    private static WholeJarStructureGraph.MethodStructure method(
            String name, String descriptor) {
        return new WholeJarStructureGraph.MethodStructure(
                name, descriptor, "method-" + descriptor,
                0, descriptor, "opcode", "constant",
                List.of(), List.of(), List.of());
    }
}
