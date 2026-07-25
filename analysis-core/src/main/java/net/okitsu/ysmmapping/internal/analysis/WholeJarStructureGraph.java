package net.okitsu.ysmmapping.internal.analysis;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Complete, deterministically ordered structural index of every class entry in a YSM JAR. */
public record WholeJarStructureGraph(String fingerprintDefinitionSha256,
                                     List<ClassStructure> classes) {
    public WholeJarStructureGraph {
        Objects.requireNonNull(fingerprintDefinitionSha256, "fingerprintDefinitionSha256");
        classes = List.copyOf(classes);
    }

    public Map<String, ClassStructure> byRuntimeName() {
        Map<String, ClassStructure> values = new TreeMap<>();
        for (ClassStructure structure : classes) {
            values.put(structure.runtimeName(), structure);
        }
        return Map.copyOf(values);
    }

    public record ClassStructure(String runtimeName, String anonymousId, String fingerprint,
                                 int access, String superShape, List<String> interfaceShapes,
                                 String nestHostShape, List<FieldStructure> fields,
                                 List<MethodStructure> methods,
                                 List<String> externalReferences) {
        public ClassStructure {
            Objects.requireNonNull(runtimeName, "runtimeName");
            Objects.requireNonNull(anonymousId, "anonymousId");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(superShape, "superShape");
            interfaceShapes = List.copyOf(interfaceShapes);
            nestHostShape = nestHostShape == null ? "" : nestHostShape;
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
            externalReferences = List.copyOf(externalReferences);
        }
    }

    public record FieldStructure(String runtimeName, String runtimeDescriptor,
                                 String fingerprint, int access, String descriptorShape,
                                 String constantDigest) {
        public FieldStructure {
            Objects.requireNonNull(runtimeName, "runtimeName");
            Objects.requireNonNull(runtimeDescriptor, "runtimeDescriptor");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(descriptorShape, "descriptorShape");
            constantDigest = constantDigest == null ? "" : constantDigest;
        }
    }

    public record MethodStructure(String runtimeName, String runtimeDescriptor,
                                  String fingerprint, int access, String descriptorShape,
                                  String opcodeDigest, String constantDigest,
                                  List<String> externalReferences, List<String> calls,
                                  List<String> fieldAccesses) {
        public MethodStructure {
            Objects.requireNonNull(runtimeName, "runtimeName");
            Objects.requireNonNull(runtimeDescriptor, "runtimeDescriptor");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(descriptorShape, "descriptorShape");
            Objects.requireNonNull(opcodeDigest, "opcodeDigest");
            Objects.requireNonNull(constantDigest, "constantDigest");
            externalReferences = List.copyOf(externalReferences);
            calls = List.copyOf(calls);
            fieldAccesses = List.copyOf(fieldAccesses);
        }
    }
}
