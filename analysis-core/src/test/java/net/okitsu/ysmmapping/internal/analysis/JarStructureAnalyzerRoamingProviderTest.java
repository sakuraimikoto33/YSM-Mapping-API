package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JarStructureAnalyzerRoamingProviderTest {
    @Test
    void resolvesTheGetterLinkedBindingAfterClassesAndMembersAreRenamed() throws Exception {
        for (String variant : List.of("alpha", "beta")) {
            Fixture fixture = fixture(variant);

            YsmCompatibilityMap.MethodSymbol result = resolve(fixture);

            assertEquals(fixture.context.name, result.owner());
            assertEquals(fixture.binder.name, result.name());
            assertEquals(fixture.binder.desc, result.descriptor());
        }
    }

    @Test
    void ignoresAGetterLinkedMethodWithTheSameDescriptorButNoRoamingBinding() throws Exception {
        Fixture fixture = fixture("matchingDescriptor");
        MethodNode unrelated = new MethodNode(Opcodes.ACC_PUBLIC,
                "unrelated", fixture.binder.desc, null, null);
        unrelated.instructions.add(new InsnNode(Opcodes.RETURN));
        unrelated.maxLocals = 2;
        fixture.context.methods.add(unrelated);
        link(fixture, unrelated.name, "prepareUnrelated");

        assertEquals(fixture.binder.name, resolve(fixture).name());
    }

    @Test
    void rejectsAMatchingDescriptorWhenItDoesNotBindTheProvider() {
        Fixture fixture = fixture("emptyBinding");
        fixture.binder.instructions.clear();
        fixture.binder.instructions.add(new InsnNode(Opcodes.RETURN));

        assertThrows(IOException.class, () -> resolve(fixture));
    }

    @Test
    void rejectsTwoDifferentGetterLinkedRoamingBinders() {
        Fixture fixture = fixture("ambiguous");
        MethodNode second = binder(fixture.context, fixture.provider,
                fixture.store, "anotherBinding", fixture.keyField, fixture.storeField,
                fixture.storeMethod);
        fixture.context.methods.add(second);
        link(fixture, second.name, "prepareAnother");

        assertThrows(IOException.class, () -> resolve(fixture));
    }

    @Test
    void rejectsANonPublicProviderInterface() {
        Fixture fixture = fixture("hiddenProvider");
        fixture.provider.access &= ~Opcodes.ACC_PUBLIC;

        assertThrows(IOException.class, () -> resolve(fixture));
    }

    @Test
    void rejectsAnAbstractProviderClassWithTheSameMethods() {
        Fixture fixture = fixture("providerClass");
        fixture.provider.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER;

        assertThrows(IOException.class, () -> resolve(fixture));
    }

    @Test
    void rejectsAnOtherwiseValidBinderWithoutTheExactMappedGetterCall() {
        for (boolean differentGetter : List.of(false, true)) {
            Fixture fixture = fixture(differentGetter ? "otherGetter" : "unlinked");
            if (differentGetter) {
                String otherName = "anotherProvider";
                ClassNode capability = fixture.classes.get(fixture.providerGetter.owner());
                MethodNode other = new MethodNode(Opcodes.ACC_PUBLIC, otherName,
                        fixture.providerGetter.descriptor(), null, null);
                other.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                other.instructions.add(new InsnNode(Opcodes.ARETURN));
                other.maxStack = 1;
                other.maxLocals = 1;
                capability.methods.add(other);
                for (MethodNode caller : fixture.caller.methods) {
                    for (var instruction : caller.instructions) {
                        if (instruction instanceof MethodInsnNode call
                                && call.owner.equals(fixture.providerGetter.owner())
                                && call.name.equals(fixture.providerGetter.name())
                                && call.desc.equals(fixture.providerGetter.descriptor())) {
                            call.name = otherName;
                        }
                    }
                }
            } else {
                fixture.caller.methods.clear();
            }

            assertThrows(IOException.class, () -> resolve(fixture));
        }
    }

    @Test
    void rejectsAProviderWithoutBothRequiredObjectAccessors() {
        for (String missingDescriptor : List.of("(I)Ljava/lang/Object;", "(ILjava/lang/Object;)V")) {
            Fixture fixture = fixture("missingAccessor");
            fixture.provider.methods.removeIf(method -> method.desc.equals(missingDescriptor));

            assertThrows(IOException.class, () -> resolve(fixture));
        }
    }

    private static YsmCompatibilityMap.MethodSymbol resolve(Fixture fixture) throws IOException {
        return JarStructureAnalyzer.findAnimationContextRoamingProviderBinder(
                fixture.classes, fixture.providerGetter, fixture.nameHasher);
    }

    private static Fixture fixture(String variant) {
        String prefix = "synthetic/" + variant + '/';
        ClassNode provider = type(prefix + "Values", Opcodes.ACC_PUBLIC
                | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT);
        provider.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "read_" + variant, "(I)Ljava/lang/Object;", null, null));
        provider.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "write_" + variant, "(ILjava/lang/Object;)V", null, null));

        ClassNode capability = type(prefix + "State");
        String providerField = "values_" + variant;
        capability.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, providerField,
                'L' + provider.name + ';', null, null));
        YsmMethodSymbol providerGetter = new YsmMethodSymbol(capability.name,
                "supply_" + variant, "()L" + provider.name + ';');
        MethodNode getter = new MethodNode(Opcodes.ACC_PUBLIC, providerGetter.name(),
                providerGetter.descriptor(), null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, capability.name,
                providerField, 'L' + provider.name + ';'));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.maxStack = 1;
        getter.maxLocals = 1;
        capability.methods.add(getter);

        ClassNode hasher = type(prefix + "Names");
        YsmMethodSymbol nameHasher = new YsmMethodSymbol(hasher.name,
                "index_" + variant, "(Ljava/lang/String;)I");
        MethodNode hash = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                nameHasher.name(), nameHasher.descriptor(), null, null);
        hash.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hash.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "hashCode", "()I", false));
        hash.instructions.add(new InsnNode(Opcodes.IRETURN));
        hash.maxStack = 1;
        hash.maxLocals = 1;
        hasher.methods.add(hash);

        ClassNode store = type(prefix + "ObjectTable");
        String storeMethod = "insert_" + variant;
        MethodNode put = new MethodNode(Opcodes.ACC_PUBLIC, storeMethod,
                "(ILjava/lang/Object;)V", null, null);
        put.instructions.add(new InsnNode(Opcodes.RETURN));
        put.maxLocals = 3;
        store.methods.add(put);

        ClassNode context = type(prefix + "Environment");
        String keyField = "namespace_" + variant;
        String storeField = "table_" + variant;
        context.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                keyField, "I", null, null));
        context.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, storeField,
                'L' + store.name + ';', null, null));
        MethodNode initialize = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initialize.instructions.add(new LdcInsnNode("roaming"));
        initialize.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                nameHasher.owner(), nameHasher.name(), nameHasher.descriptor(), false));
        initialize.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, context.name, keyField, "I"));
        initialize.instructions.add(new InsnNode(Opcodes.RETURN));
        initialize.maxStack = 1;
        context.methods.add(initialize);
        MethodNode binder = binder(context, provider, store, "install_" + variant,
                keyField, storeField, storeMethod);
        context.methods.add(binder);

        ClassNode caller = type(prefix + "Preparation");
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (ClassNode node : List.of(provider, capability, hasher, store, context, caller)) {
            classes.put(node.name, node);
        }
        Fixture fixture = new Fixture(classes, provider, context, store, caller, binder,
                keyField, storeField, storeMethod, providerGetter, nameHasher);
        link(fixture, binder.name, "prepare_" + variant);
        return fixture;
    }

    private static MethodNode binder(ClassNode context, ClassNode provider, ClassNode store,
                                     String name, String keyField, String storeField, String storeMethod) {
        MethodNode binder = new MethodNode(Opcodes.ACC_PUBLIC, name,
                "(L" + provider.name + ";)V", null, null);
        binder.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        binder.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, context.name,
                storeField, 'L' + store.name + ';'));
        binder.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, context.name, keyField, "I"));
        binder.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        binder.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, store.name,
                storeMethod, "(ILjava/lang/Object;)V", false));
        binder.instructions.add(new InsnNode(Opcodes.RETURN));
        binder.maxStack = 3;
        binder.maxLocals = 2;
        return binder;
    }

    private static void link(Fixture fixture, String binderName, String callerName) {
        MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, callerName,
                "(L" + fixture.context.name + ";L" + fixture.providerGetter.owner() + ";)V", null, null);
        caller.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        caller.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        caller.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                fixture.providerGetter.owner(), fixture.providerGetter.name(),
                fixture.providerGetter.descriptor(), false));
        caller.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, fixture.context.name,
                binderName, "(L" + fixture.provider.name + ";)V", false));
        caller.instructions.add(new InsnNode(Opcodes.RETURN));
        caller.maxStack = 2;
        caller.maxLocals = 2;
        fixture.caller.methods.add(caller);
    }

    private static ClassNode type(String name) {
        return type(name, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER);
    }

    private static ClassNode type(String name, int access) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = access;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    private record Fixture(Map<String, ClassNode> classes, ClassNode provider, ClassNode context,
                           ClassNode store, ClassNode caller, MethodNode binder,
                           String keyField, String storeField, String storeMethod,
                           YsmMethodSymbol providerGetter, YsmMethodSymbol nameHasher) { }
}
