package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.MappingCandidate;
import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmFieldSymbol;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmStructureConstraints;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies profile or consumer constraints independently to a whole-JAR graph. */
public final class StructurePatternResolver {
    public List<MappingCandidate> resolve(SymbolKind kind, YsmStructureConstraints common,
            YsmStructureConstraints refinement, WholeJarStructureGraph graph) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(common, "common");
        Objects.requireNonNull(refinement, "refinement");
        Objects.requireNonNull(graph, "graph");
        List<MappingCandidate> result = new ArrayList<>();
        for (WholeJarStructureGraph.ClassStructure owner : graph.classes()) {
            if (kind == SymbolKind.CLASS) {
                if (matchesClass(owner, common) && matchesClass(owner, refinement)) {
                    result.add(new MappingCandidate(new YsmClassSymbol(owner.runtimeName()), 1.0));
                }
                continue;
            }
            if (!matchesOwner(owner, common) || !matchesOwner(owner, refinement)) continue;
            if (kind == SymbolKind.METHOD) {
                owner.methods().stream().filter(method -> matches(method, common)
                                && matches(method, refinement))
                        .map(method -> new MappingCandidate(new YsmMethodSymbol(owner.runtimeName(),
                                method.runtimeName(), method.runtimeDescriptor()), 1.0))
                        .forEach(result::add);
            } else {
                owner.fields().stream().filter(field -> matches(field, common)
                                && matches(field, refinement))
                        .map(field -> new MappingCandidate(new YsmFieldSymbol(owner.runtimeName(),
                                field.runtimeName(), field.runtimeDescriptor()), 1.0))
                        .forEach(result::add);
            }
        }
        return List.copyOf(result);
    }

    private static boolean matchesClass(WholeJarStructureGraph.ClassStructure value,
            YsmStructureConstraints constraints) {
        if (!access(value.access(), constraints) || !matchesOwner(value, constraints)) return false;
        List<String> members = new ArrayList<>();
        value.fields().forEach(field -> {
            members.add(field.fingerprint());
            members.add(field.descriptorShape());
        });
        value.methods().forEach(method -> {
            members.add(method.fingerprint());
            members.add(method.descriptorShape());
        });
        return members.containsAll(constraints.memberShapes())
                && value.externalReferences().containsAll(constraints.externalReferences());
    }

    private static boolean matchesOwner(WholeJarStructureGraph.ClassStructure owner,
            YsmStructureConstraints constraints) {
        return (constraints.superName().isEmpty()
                || constraints.superName().equals(owner.superShape()))
                && owner.interfaceShapes().containsAll(constraints.interfaces());
    }

    private static boolean matches(WholeJarStructureGraph.MethodStructure value,
            YsmStructureConstraints constraints) {
        return access(value.access(), constraints)
                && accepted(constraints.descriptorShapes(), value.descriptorShape())
                && accepted(constraints.opcodeDigests(), value.opcodeDigest())
                && accepted(constraints.constantDigests(), value.constantDigest())
                && value.externalReferences().containsAll(constraints.externalReferences())
                && value.calls().containsAll(constraints.callGraph())
                && value.fieldAccesses().containsAll(constraints.fieldGraph());
    }

    private static boolean matches(WholeJarStructureGraph.FieldStructure value,
            YsmStructureConstraints constraints) {
        return access(value.access(), constraints)
                && accepted(constraints.descriptorShapes(), value.descriptorShape())
                && accepted(constraints.constantDigests(), value.constantDigest())
                && constraints.opcodeDigests().isEmpty()
                && constraints.externalReferences().isEmpty()
                && constraints.callGraph().isEmpty() && constraints.fieldGraph().isEmpty();
    }

    private static boolean access(int value, YsmStructureConstraints constraints) {
        return (value & constraints.requiredAccess()) == constraints.requiredAccess()
                && (value & constraints.forbiddenAccess()) == 0;
    }

    private static boolean accepted(List<String> accepted, String actual) {
        return accepted.isEmpty() || accepted.contains(actual);
    }
}
