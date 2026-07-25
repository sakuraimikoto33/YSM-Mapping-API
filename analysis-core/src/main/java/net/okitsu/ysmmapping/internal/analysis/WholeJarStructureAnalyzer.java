package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmSymbolSignatures;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Builds name-independent fingerprints while retaining runtime names only as resolved values. */
public final class WholeJarStructureAnalyzer {
    private static final String FINGERPRINT_DEFINITION = String.join("\n",
            "ysm-whole-jar-structure-v1",
            "internal-types=@ysm",
            "internal-member-names=*",
            "debug-and-frames=ignored",
            "constant-values=sha256",
            "member-order=sorted");
    public static final String FINGERPRINT_DEFINITION_SHA256 =
            YsmSymbolSignatures.sha256(FINGERPRINT_DEFINITION);

    public WholeJarStructureGraph analyze(Path jarPath) throws IOException {
        Objects.requireNonNull(jarPath, "jarPath");
        return analyze(YsmClassIndex.read(jarPath));
    }

    public WholeJarStructureGraph analyze(YsmClassIndex index) {
        Objects.requireNonNull(index, "index");
        List<ClassNode> nodes = index.classes();
        Set<String> internalNames = new HashSet<>();
        nodes.forEach(node -> internalNames.add(node.name));
        List<WholeJarStructureGraph.ClassStructure> classes = nodes.stream()
                .map(node -> classStructure(node, internalNames))
                .sorted(Comparator.comparing(WholeJarStructureGraph.ClassStructure::runtimeName))
                .toList();
        return new WholeJarStructureGraph(FINGERPRINT_DEFINITION_SHA256, classes);
    }

    private static List<ClassNode> readClasses(Path jarPath) throws IOException {
        List<ClassNode> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<JarEntry> entries = jar.stream()
                    .filter(entry -> !entry.isDirectory()
                            && entry.getName().startsWith("com/elfmcys/yesstevemodel/")
                            && entry.getName().endsWith(".class"))
                    .sorted(Comparator.comparing(JarEntry::getName)).toList();
            for (JarEntry entry : entries) {
                try (InputStream input = jar.getInputStream(entry)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(input).accept(node,
                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    classes.add(node);
                }
            }
        }
        return classes;
    }

    private static WholeJarStructureGraph.ClassStructure classStructure(ClassNode node,
                                                                         Set<String> internal) {
        List<WholeJarStructureGraph.FieldStructure> fields = node.fields.stream()
                .map(field -> fieldStructure(field, internal))
                .sorted(Comparator.comparing(WholeJarStructureGraph.FieldStructure::fingerprint)
                        .thenComparing(WholeJarStructureGraph.FieldStructure::runtimeDescriptor)
                        .thenComparing(WholeJarStructureGraph.FieldStructure::runtimeName))
                .toList();
        List<WholeJarStructureGraph.MethodStructure> methods = node.methods.stream()
                .map(method -> methodStructure(method, internal))
                .sorted(Comparator.comparing(WholeJarStructureGraph.MethodStructure::fingerprint)
                        .thenComparing(WholeJarStructureGraph.MethodStructure::runtimeDescriptor)
                        .thenComparing(WholeJarStructureGraph.MethodStructure::runtimeName))
                .toList();
        List<String> interfaces = node.interfaces.stream().map(value -> typeShape(value, internal))
                .sorted().toList();
        Set<String> external = new TreeSet<>();
        addExternal(external, node.superName, internal);
        node.interfaces.forEach(value -> addExternal(external, value, internal));
        fields.forEach(field -> addDescriptorExternals(external, field.runtimeDescriptor(), internal));
        methods.forEach(method -> external.addAll(method.externalReferences()));

        StringBuilder canonical = new StringBuilder();
        canonical.append("class|").append(node.access).append('|')
                .append(typeShape(node.superName, internal)).append('|')
                .append(String.join(",", interfaces)).append('|')
                .append(typeShape(node.nestHostClass, internal)).append('|');
        fields.forEach(field -> canonical.append("F:").append(field.fingerprint()).append(';'));
        methods.forEach(method -> canonical.append("M:").append(method.fingerprint()).append(';'));
        String fingerprint = YsmSymbolSignatures.sha256(canonical.toString());
        return new WholeJarStructureGraph.ClassStructure(node.name,
                "@anon/sha256/" + fingerprint,
                fingerprint, node.access, typeShape(node.superName, internal), interfaces,
                typeShape(node.nestHostClass, internal), fields, methods, List.copyOf(external));
    }

    private static WholeJarStructureGraph.FieldStructure fieldStructure(FieldNode field,
                                                                         Set<String> internal) {
        String descriptor = descriptorShape(field.desc, internal);
        String constant = field.value == null ? "" : constantDigest(field.value, internal);
        String fingerprint = YsmSymbolSignatures.sha256("field|" + field.access + '|'
                + descriptor + '|' + constant);
        return new WholeJarStructureGraph.FieldStructure(field.name, field.desc, fingerprint,
                field.access, descriptor, constant);
    }

    private static WholeJarStructureGraph.MethodStructure methodStructure(MethodNode method,
                                                                           Set<String> internal) {
        String descriptor = descriptorShape(method.desc, internal);
        StringBuilder opcodes = new StringBuilder();
        List<String> constants = new ArrayList<>();
        Set<String> external = new TreeSet<>();
        Set<String> calls = new TreeSet<>();
        Set<String> fields = new TreeSet<>();
        addDescriptorExternals(external, method.desc, internal);
        if (method.exceptions != null) {
            method.exceptions.forEach(value -> addExternal(external, value, internal));
        }
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode >= 0) {
                opcodes.append(opcode);
            } else {
                continue;
            }
            switch (instruction) {
                case IntInsnNode value -> opcodes.append(':').append(value.operand);
                case TypeInsnNode value -> {
                    opcodes.append(':').append(typeShape(value.desc, internal));
                    addExternal(external, value.desc, internal);
                }
                case FieldInsnNode value -> {
                    String reference = memberReference(value.owner, value.name, value.desc,
                            internal, false);
                    fields.add(reference);
                    opcodes.append(':').append(reference);
                    addExternal(external, value.owner, internal);
                    addDescriptorExternals(external, value.desc, internal);
                }
                case MethodInsnNode value -> {
                    String reference = memberReference(value.owner, value.name, value.desc,
                            internal, true);
                    calls.add(reference);
                    opcodes.append(':').append(reference);
                    addExternal(external, value.owner, internal);
                    addDescriptorExternals(external, value.desc, internal);
                }
                case InvokeDynamicInsnNode value -> {
                    String dynamic = "indy|" + descriptorShape(value.desc, internal) + '|'
                            + handleShape(value.bsm, internal);
                    opcodes.append(':').append(dynamic);
                    calls.add(dynamic);
                    for (Object argument : value.bsmArgs) {
                        constants.add(constantDigest(argument, internal));
                    }
                }
                case LdcInsnNode value -> constants.add(constantDigest(value.cst, internal));
                case IincInsnNode value -> opcodes.append(':').append(value.incr);
                case MultiANewArrayInsnNode value -> opcodes.append(':')
                        .append(descriptorShape(value.desc, internal)).append(':')
                        .append(value.dims);
                case TableSwitchInsnNode value -> opcodes.append(':').append(value.min)
                        .append(':').append(value.max);
                case LookupSwitchInsnNode value -> opcodes.append(':')
                        .append(value.keys == null ? 0 : value.keys.hashCode());
                case JumpInsnNode ignored -> opcodes.append(":jump");
                default -> {
                }
            }
            opcodes.append(';');
        }
        constants.sort(String::compareTo);
        String opcodeDigest = YsmSymbolSignatures.sha256(opcodes.toString());
        String constantDigest = YsmSymbolSignatures.sha256(String.join("|", constants));
        String methodKind = method.name.equals("<init>") ? "constructor"
                : method.name.equals("<clinit>") ? "class-initializer" : "method";
        String fingerprint = YsmSymbolSignatures.sha256("method|" + methodKind + '|'
                + method.access + '|' + descriptor + '|' + opcodeDigest + '|'
                + constantDigest + '|' + String.join(",", calls) + '|'
                + String.join(",", fields));
        return new WholeJarStructureGraph.MethodStructure(method.name, method.desc, fingerprint,
                method.access, descriptor, opcodeDigest, constantDigest, List.copyOf(external),
                List.copyOf(calls), List.copyOf(fields));
    }

    private static String memberReference(String owner, String name, String descriptor,
                                          Set<String> internal, boolean method) {
        boolean isInternal = internal.contains(owner);
        return typeShape(owner, internal) + '#'
                + (isInternal && !name.startsWith("<") ? "*" : name)
                + (method ? descriptorShape(descriptor, internal)
                : ':' + descriptorShape(descriptor, internal));
    }

    private static String handleShape(Handle handle, Set<String> internal) {
        return handle.getTag() + "|" + memberReference(handle.getOwner(), handle.getName(),
                handle.getDesc(), internal, handle.getDesc().startsWith("("));
    }

    private static String constantDigest(Object value, Set<String> internal) {
        String shape = switch (value) {
            case Type type -> "type:" + descriptorShape(type.getDescriptor(), internal);
            case Handle handle -> "handle:" + handleShape(handle, internal);
            case ConstantDynamic dynamic -> "dynamic:" + dynamic.getName() + ':'
                    + descriptorShape(dynamic.getDescriptor(), internal) + ':'
                    + handleShape(dynamic.getBootstrapMethod(), internal);
            default -> value.getClass().getName() + ':' + value;
        };
        return YsmSymbolSignatures.sha256(shape);
    }

    private static String descriptorShape(String descriptor, Set<String> internal) {
        if (descriptor.startsWith("(")) {
            Type method = Type.getMethodType(descriptor);
            StringBuilder result = new StringBuilder("(");
            for (Type argument : method.getArgumentTypes()) {
                result.append(typeDescriptorShape(argument, internal));
            }
            return result.append(')').append(typeDescriptorShape(method.getReturnType(), internal))
                    .toString();
        }
        return typeDescriptorShape(Type.getType(descriptor), internal);
    }

    private static String typeDescriptorShape(Type type, Set<String> internal) {
        return switch (type.getSort()) {
            case Type.ARRAY -> "[".repeat(type.getDimensions())
                    + typeDescriptorShape(type.getElementType(), internal);
            case Type.OBJECT -> "L" + typeShape(type.getInternalName(), internal) + ";";
            default -> type.getDescriptor();
        };
    }

    private static String typeShape(String internalName, Set<String> internal) {
        if (internalName == null) {
            return "";
        }
        if (internal.contains(internalName)) {
            return "@ysm";
        }
        if (internalName.startsWith("net/minecraft/")) {
            return "@minecraft";
        }
        if (internalName.startsWith("net/neoforged/")
                || internalName.startsWith("net/minecraftforge/")
                || internalName.startsWith("net/fabricmc/")) {
            return "@loader";
        }
        return internalName;
    }

    private static void addDescriptorExternals(Set<String> target, String descriptor,
                                               Set<String> internal) {
        Type type = descriptor.startsWith("(") ? Type.getMethodType(descriptor)
                : Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            for (Type argument : type.getArgumentTypes()) {
                addTypeExternal(target, argument, internal);
            }
            addTypeExternal(target, type.getReturnType(), internal);
        } else {
            addTypeExternal(target, type, internal);
        }
    }

    private static void addTypeExternal(Set<String> target, Type type, Set<String> internal) {
        Type value = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (value.getSort() == Type.OBJECT) {
            addExternal(target, value.getInternalName(), internal);
        }
    }

    private static void addExternal(Set<String> target, String internalName,
                                    Set<String> internal) {
        if (internalName != null && !internal.contains(internalName)) {
            target.add(typeShape(internalName, internal));
        }
    }
}
