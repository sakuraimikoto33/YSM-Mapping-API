package net.okitsu.ysmmapping.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Loader-neutral hard constraints and weighted structural evidence for a consumer symbol. */
public record YsmStructureConstraints(int requiredAccess, int forbiddenAccess, String superName,
                                      List<String> interfaces, List<String> memberShapes,
                                      List<String> descriptorShapes, List<String> opcodeDigests,
                                      List<String> constantDigests,
                                      List<String> externalReferences,
                                      List<String> callGraph, List<String> fieldGraph) {
    public static final YsmStructureConstraints EMPTY = builder().build();

    public YsmStructureConstraints {
        if ((requiredAccess & forbiddenAccess) != 0) {
            throw new IllegalArgumentException("Required and forbidden access masks overlap");
        }
        superName = superName == null ? "" : superName;
        interfaces = sorted(interfaces, "interfaces");
        memberShapes = sorted(memberShapes, "memberShapes");
        descriptorShapes = sorted(descriptorShapes, "descriptorShapes");
        opcodeDigests = sorted(opcodeDigests, "opcodeDigests");
        constantDigests = sorted(constantDigests, "constantDigests");
        externalReferences = sorted(externalReferences, "externalReferences");
        callGraph = sorted(callGraph, "callGraph");
        fieldGraph = sorted(fieldGraph, "fieldGraph");
    }

    public static Builder builder() {
        return new Builder();
    }

    String canonicalForm() {
        StringBuilder value = new StringBuilder();
        append(value, "requiredAccess", Integer.toUnsignedString(requiredAccess));
        append(value, "forbiddenAccess", Integer.toUnsignedString(forbiddenAccess));
        append(value, "superName", superName);
        append(value, "interfaces", interfaces);
        append(value, "memberShapes", memberShapes);
        append(value, "descriptorShapes", descriptorShapes);
        append(value, "opcodeDigests", opcodeDigests);
        append(value, "constantDigests", constantDigests);
        append(value, "externalReferences", externalReferences);
        append(value, "callGraph", callGraph);
        append(value, "fieldGraph", fieldGraph);
        return value.toString();
    }

    private static List<String> sorted(List<String> values, String label) {
        Objects.requireNonNull(values, label);
        TreeSet<String> result = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " contains a blank value");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void append(StringBuilder target, String name, String value) {
        target.append(name.length()).append(':').append(name).append('=')
                .append(value.length()).append(':').append(value).append(';');
    }

    private static void append(StringBuilder target, String name, List<String> values) {
        target.append(name.length()).append(':').append(name).append('=')
                .append(values.size()).append('[');
        for (String value : values) {
            target.append(value.length()).append(':').append(value).append(';');
        }
        target.append(']');
    }

    public static final class Builder {
        private int requiredAccess;
        private int forbiddenAccess;
        private String superName;
        private final List<String> interfaces = new ArrayList<>();
        private final List<String> memberShapes = new ArrayList<>();
        private final List<String> descriptorShapes = new ArrayList<>();
        private final List<String> opcodeDigests = new ArrayList<>();
        private final List<String> constantDigests = new ArrayList<>();
        private final List<String> externalReferences = new ArrayList<>();
        private final List<String> callGraph = new ArrayList<>();
        private final List<String> fieldGraph = new ArrayList<>();

        private Builder() {
        }

        public Builder requiredAccess(int value) {
            requiredAccess = value;
            return this;
        }

        public Builder forbiddenAccess(int value) {
            forbiddenAccess = value;
            return this;
        }

        public Builder superName(String value) {
            superName = value;
            return this;
        }

        public Builder interfaces(Collection<String> values) {
            interfaces.addAll(Objects.requireNonNull(values, "values"));
            return this;
        }

        public Builder memberShape(String value) {
            memberShapes.add(value);
            return this;
        }

        public Builder descriptorShape(String value) {
            descriptorShapes.add(value);
            return this;
        }

        public Builder opcodeDigest(String value) {
            opcodeDigests.add(value);
            return this;
        }

        public Builder constantDigest(String value) {
            constantDigests.add(value);
            return this;
        }

        public Builder externalReference(String value) {
            externalReferences.add(value);
            return this;
        }

        public Builder call(String value) {
            callGraph.add(value);
            return this;
        }

        public Builder fieldAccess(String value) {
            fieldGraph.add(value);
            return this;
        }

        public YsmStructureConstraints build() {
            return new YsmStructureConstraints(requiredAccess, forbiddenAccess, superName,
                    interfaces, memberShapes, descriptorShapes, opcodeDigests, constantDigests,
                    externalReferences, callGraph, fieldGraph);
        }
    }
}
