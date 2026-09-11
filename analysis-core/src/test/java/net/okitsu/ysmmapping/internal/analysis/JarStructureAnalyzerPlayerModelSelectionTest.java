package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JarStructureAnalyzerPlayerModelSelectionTest {
    private static final String STRING = "Ljava/lang/String;";
    private static final String LOOKUP = "(" + STRING + ")Ljava/util/Optional;";

    @Test
    void resolvesInheritedGettersFromThePacketAndMappedLookupAfterRenaming() throws Exception {
        for (String variant : List.of("alpha", "beta")) {
            Fixture fixture = fixture(variant);

            JarStructureAnalyzer.PlayerModelSelectionSymbols result = resolve(fixture);

            assertEquals(symbol(fixture.base, fixture.modelGetter), result.modelIdGetter());
            assertEquals(symbol(fixture.base, fixture.disabledGetter), result.modelDisabledGetter());
        }
    }

    @Test
    void unrelatedTextureAndAnimationStringsDoNotCompeteWithTheModelSelection() throws Exception {
        Fixture fixture = fixture("decoys");
        fixture.pairSetter.instructions.insertBefore(fixture.pairSetter.instructions.getLast(),
                new VarInsnNode(Opcodes.ALOAD, 0));
        fixture.pairSetter.instructions.insertBefore(fixture.pairSetter.instructions.getLast(),
                new VarInsnNode(Opcodes.ALOAD, 2));
        fixture.pairSetter.instructions.insertBefore(fixture.pairSetter.instructions.getLast(),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, fixture.base.name,
                        "writeTexture", "(" + STRING + ")V", false));

        assertEquals(symbol(fixture.base, fixture.modelGetter), resolve(fixture).modelIdGetter());
    }

    @Test
    void rejectsAWriteChainWithoutTheExactMappedLookupCall() {
        Fixture fixture = fixture("differentLookup");
        for (AbstractInsnNode instruction : fixture.refresh.instructions) {
            if (instruction instanceof MethodInsnNode call) call.name = "anotherLookup";
        }

        assertThrows(IOException.class, () -> resolve(fixture));
    }

    @Test
    void rejectsALookupOfADifferentStringField() {
        Fixture fixture = fixture("differentField");
        for (AbstractInsnNode instruction : fixture.refresh.instructions) {
            if (instruction instanceof FieldInsnNode field) field.name = fixture.textureField;
        }

        assertThrows(IOException.class, () -> resolve(fixture));
    }

    @Test
    void rejectsMissingAndAmbiguousGettersForEitherSelectedField() {
        for (boolean disabled : List.of(false, true)) {
            Fixture missing = fixture("missingGetter");
            missing.base.methods.remove(disabled ? missing.disabledGetter : missing.modelGetter);
            assertThrows(IOException.class, () -> resolve(missing));

            Fixture ambiguous = fixture("ambiguousGetter");
            MethodNode original = disabled ? ambiguous.disabledGetter : ambiguous.modelGetter;
            MethodNode duplicate = copy(original, "anotherGetter");
            ambiguous.base.methods.add(duplicate);
            assertThrows(IOException.class, () -> resolve(ambiguous));
        }
    }

    @Test
    void rejectsNonPublicGettersForEitherSelectedField() {
        for (boolean disabled : List.of(false, true)) {
            Fixture fixture = fixture("nonPublicGetter");
            MethodNode getter = disabled ? fixture.disabledGetter : fixture.modelGetter;
            getter.access = Opcodes.ACC_PRIVATE;

            assertThrows(IOException.class, () -> resolve(fixture));
        }
    }

    @Test
    void rejectsGettersThatTransformTheStoredValue() {
        Fixture text = fixture("trimmedGetter");
        text.modelGetter.instructions.insertBefore(text.modelGetter.instructions.getLast(),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim",
                        "()" + STRING, false));
        assertThrows(IOException.class, () -> resolve(text));

        Fixture flag = fixture("invertedGetter");
        flag.disabledGetter.instructions.insertBefore(flag.disabledGetter.instructions.getLast(),
                new InsnNode(Opcodes.ICONST_1));
        flag.disabledGetter.instructions.insertBefore(flag.disabledGetter.instructions.getLast(),
                new InsnNode(Opcodes.IXOR));
        assertThrows(IOException.class, () -> resolve(flag));
    }

    @Test
    void rejectsAMissingPacketUpdateOrAmbiguousPacketSetters() {
        Fixture missing = fixture("missingPacketCall");
        missing.packet.methods.clear();
        assertThrows(IOException.class, () -> resolve(missing));

        Fixture ambiguous = fixture("ambiguousPacketCall");
        MethodNode another = copy(ambiguous.pairSetter, "anotherPairSetter");
        ambiguous.base.methods.add(another);
        MethodNode handler = copy(ambiguous.packetHandler, "anotherHandler");
        for (AbstractInsnNode instruction : handler.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.desc.equals(ambiguous.pairSetter.desc)) {
                call.name = another.name;
            }
        }
        ambiguous.packet.methods.add(handler);
        assertThrows(IOException.class, () -> resolve(ambiguous));
    }

    @Test
    void rejectsAComputedDisabledWriteAndTheWrongForwardedModelArgument() {
        Fixture flag = fixture("computedDisabledWrite");
        AbstractInsnNode put = flag.disabledSetter.instructions.get(2);
        flag.disabledSetter.instructions.insertBefore(put, new InsnNode(Opcodes.ICONST_1));
        flag.disabledSetter.instructions.insertBefore(put, new InsnNode(Opcodes.IXOR));
        assertThrows(IOException.class, () -> resolve(flag));

        Fixture argument = fixture("wrongForwardedArgument");
        ((VarInsnNode) argument.pairSetter.instructions.get(1)).var = 2;
        assertThrows(IOException.class, () -> resolve(argument));
    }

    private static JarStructureAnalyzer.PlayerModelSelectionSymbols resolve(Fixture fixture)
            throws IOException {
        return JarStructureAnalyzer.findPlayerModelSelectionSymbols(fixture.classes,
                new YsmClassSymbol(fixture.capability.name),
                new YsmClassSymbol(fixture.packet.name), fixture.lookup);
    }

    private static Fixture fixture(String variant) {
        String prefix = "synthetic/" + variant + '/';
        ClassNode base = type(prefix + "BaseState");
        ClassNode capability = type(prefix + "ClientState");
        capability.superName = base.name;
        ClassNode packet = type(prefix + "SelectionPacket");
        ClassNode models = type(prefix + "ModelLibrary");
        String modelField = "selected_" + variant;
        String textureField = "texture_" + variant;
        String disabledField = "disabled_" + variant;
        base.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, modelField, STRING, null, null));
        base.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, textureField, STRING, null, null));
        base.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "animation", STRING, null, null));
        base.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, disabledField, "Z", null, null));

        MethodNode modelGetter = getter(base, "read_" + variant, modelField, STRING);
        MethodNode disabledGetter = getter(base, "hidden_" + variant, disabledField, "Z");
        getter(base, "texture", textureField, STRING);
        getter(base, "animation", "animation", STRING);

        YsmMethodSymbol lookup = new YsmMethodSymbol(models.name, "find_" + variant, LOOKUP);
        method(models, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, lookup.name(), LOOKUP,
                new InsnNode(Opcodes.ACONST_NULL), new InsnNode(Opcodes.ARETURN));
        MethodNode refresh = method(base, Opcodes.ACC_PUBLIC, "refresh_" + variant, "()V",
                new VarInsnNode(Opcodes.ALOAD, 0),
                new FieldInsnNode(Opcodes.GETFIELD, base.name, modelField, STRING),
                new MethodInsnNode(Opcodes.INVOKESTATIC, lookup.owner(), lookup.name(), LOOKUP, false),
                new InsnNode(Opcodes.POP), new InsnNode(Opcodes.RETURN));
        MethodNode modelWriter = method(base, Opcodes.ACC_PUBLIC,
                "write_" + variant, "(" + STRING + ")V",
                new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1),
                new FieldInsnNode(Opcodes.PUTFIELD, base.name, modelField, STRING),
                new VarInsnNode(Opcodes.ALOAD, 0),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, base.name, refresh.name, refresh.desc, false),
                new InsnNode(Opcodes.RETURN));
        method(base, Opcodes.ACC_PUBLIC, "writeTexture", "(" + STRING + ")V",
                new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1),
                new FieldInsnNode(Opcodes.PUTFIELD, base.name, textureField, STRING),
                new InsnNode(Opcodes.RETURN));
        MethodNode pairSetter = method(base, Opcodes.ACC_PUBLIC,
                "select_" + variant, "(" + STRING + STRING + ")V",
                new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, base.name,
                        modelWriter.name, modelWriter.desc, false),
                new InsnNode(Opcodes.RETURN));
        MethodNode disabledSetter = method(base, Opcodes.ACC_PUBLIC,
                "disable_" + variant, "(Z)V",
                new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ILOAD, 1),
                new FieldInsnNode(Opcodes.PUTFIELD, base.name, disabledField, "Z"),
                new InsnNode(Opcodes.RETURN));
        MethodNode handler = method(packet, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "apply_" + variant, "(L" + capability.name + ';' + STRING + STRING + "Z)V",
                new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1),
                new VarInsnNode(Opcodes.ALOAD, 2),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, capability.name,
                        pairSetter.name, pairSetter.desc, false),
                new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ILOAD, 3),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, capability.name,
                        disabledSetter.name, disabledSetter.desc, false),
                new InsnNode(Opcodes.RETURN));
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (ClassNode node : List.of(base, capability, packet, models)) classes.put(node.name, node);
        return new Fixture(classes, base, capability, packet, lookup, modelGetter,
                disabledGetter, pairSetter, disabledSetter, handler, refresh, textureField);
    }

    private static MethodNode getter(ClassNode owner, String name, String field, String descriptor) {
        return method(owner, Opcodes.ACC_PUBLIC, name, "()" + descriptor,
                new VarInsnNode(Opcodes.ALOAD, 0),
                new FieldInsnNode(Opcodes.GETFIELD, owner.name, field, descriptor),
                new InsnNode(descriptor.equals("Z") ? Opcodes.IRETURN : Opcodes.ARETURN));
    }

    private static MethodNode method(ClassNode owner, int access, String name, String descriptor,
                                      AbstractInsnNode... instructions) {
        MethodNode method = new MethodNode(access, name, descriptor, null, null);
        for (AbstractInsnNode instruction : instructions) method.instructions.add(instruction);
        method.maxLocals = 4;
        method.maxStack = 4;
        owner.methods.add(method);
        return method;
    }

    private static MethodNode copy(MethodNode original, String name) {
        MethodNode result = new MethodNode(original.access, name, original.desc, null, null);
        original.accept(result);
        return result;
    }

    private static YsmCompatibilityMap.MethodSymbol symbol(ClassNode owner, MethodNode method) {
        return new YsmCompatibilityMap.MethodSymbol(owner.name, method.name, method.desc);
    }

    private static ClassNode type(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    private record Fixture(Map<String, ClassNode> classes, ClassNode base, ClassNode capability,
                           ClassNode packet, YsmMethodSymbol lookup, MethodNode modelGetter,
                           MethodNode disabledGetter, MethodNode pairSetter, MethodNode disabledSetter,
                           MethodNode packetHandler, MethodNode refresh, String textureField) { }
}
