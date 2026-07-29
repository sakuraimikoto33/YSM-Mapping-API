package net.okitsu.ysmmapping.internal.analysis;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JarStructureAnalyzerTest {
    @Test
    void discoversCompleteFeedbackFieldsFromCodecAndEntityLookup() throws Exception {
        Fixture fixture = fixture(true);

        JarStructureAnalyzer.CompleteFeedbackSymbols symbols =
                analyzer().findCompleteFeedbackSymbols(fixture.classes(),
                        Map.of(15, fixture.packet().name));

        assertEquals("feedback", symbols.payloadField().name());
        assertEquals("sequence", symbols.modelKeyField().name());
        assertEquals("target", symbols.targetEntityIdField().name());
        assertEquals("variables", symbols.variablesField().name());
    }

    @Test
    void rejectsAnUnprovenTargetEntityField() {
        Fixture fixture = fixture(false);

        assertThrows(IOException.class, () -> analyzer().findCompleteFeedbackSymbols(
                fixture.classes(), Map.of(15, fixture.packet().name)));
    }

    @Test
    void ignoresMemberOrderAndUnrelatedAddedMembers() throws Exception {
        Fixture fixture = fixture(true);
        fixture.packet().fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "unrelated", "J",
                null, null));
        fixture.packet().methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "overload",
                "(I)V", null, null));
        Collections.reverse(fixture.packet().fields);
        Collections.reverse(fixture.packet().methods);
        ClassNode feedback = fixture.classes().get("test/Feedback");
        Collections.reverse(feedback.fields);
        Collections.reverse(feedback.methods);

        JarStructureAnalyzer.CompleteFeedbackSymbols symbols =
                analyzer().findCompleteFeedbackSymbols(fixture.classes(),
                        Map.of(15, fixture.packet().name));

        assertEquals("feedback", symbols.payloadField().name());
        assertEquals("target", symbols.targetEntityIdField().name());
    }

    @Test
    void rejectsAmbiguousGraphCandidatesInsteadOfChoosingByOrder() {
        Fixture fixture = fixture(true);
        MethodNode secondLookup = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "secondLookup", "(Ltest/Feedback;Ljava/lang/Object;)V", null, null);
        secondLookup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        secondLookup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        secondLookup.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "test/Feedback",
                "sequence", "I"));
        secondLookup.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel", "getEntity",
                "(I)Lnet/minecraft/world/entity/Entity;", false));
        secondLookup.instructions.add(new InsnNode(Opcodes.POP));
        secondLookup.instructions.add(new InsnNode(Opcodes.RETURN));
        fixture.packet().methods.add(secondLookup);

        assertThrows(IOException.class, () -> analyzer().findCompleteFeedbackSymbols(
                fixture.classes(), Map.of(15, fixture.packet().name)));
    }

    @Test
    void discoversFullPlayerStateCodecAndCapabilityInitializer() throws Exception {
        PlayerStateFixture fixture = playerStateFixture(true);

        JarStructureAnalyzer.PlayerStateSymbols symbols =
                analyzer().findPlayerStateSymbols(fixture.classes(),
                        Map.of(21, fixture.packet().name));

        assertEquals("write", symbols.codecWriter().name());
        assertEquals("decode", symbols.codecDecoder().name());
        assertEquals("handle", symbols.clientHandler().name());
        assertEquals("flags", symbols.flagsField().name());
        assertEquals("decodedRoaming", symbols.decodedRoamingField().name());
        assertEquals("initialize", symbols.fullRoamingInitializer().name());
    }

    @Test
    void rejectsPlayerStateDecoderWithoutHashedFullMapConversion() {
        PlayerStateFixture fixture = playerStateFixture(false);

        assertThrows(IOException.class, () -> analyzer().findPlayerStateSymbols(
                fixture.classes(), Map.of(21, fixture.packet().name)));
    }

    @Test
    void selectsPlayerStateMembersByShapeDespiteOverloads() throws Exception {
        PlayerStateFixture fixture = playerStateFixture(true);
        fixture.packet().methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "write", "()V", null, null));
        fixture.packet().methods.add(new MethodNode(Opcodes.ACC_PUBLIC,
                "animation", "(I)V", null, null));
        Collections.reverse(fixture.packet().methods);

        JarStructureAnalyzer.PlayerStateSymbols symbols =
                analyzer().findPlayerStateSymbols(fixture.classes(),
                        Map.of(21, fixture.packet().name));

        assertEquals("write", symbols.codecWriter().name());
        assertEquals("(Ltest/PlayerState;Lnet/minecraft/network/RegistryFriendlyByteBuf;)V",
                symbols.codecWriter().descriptor());
    }

    private static JarStructureAnalyzer analyzer() throws IOException {
        return new JarStructureAnalyzer(CuratedDefinitionRegistry.load("1.20.1"));
    }

    private static Fixture fixture(boolean entityLookup) {
        ClassNode packet = classNode("test/CompleteFeedbackPacket");
        packet.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "feedback",
                "Ltest/Feedback;", null, null));

        ClassNode feedback = classNode("test/Feedback");
        feedback.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "sequence",
                "I", null, null));
        feedback.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "target",
                "I", null, null));
        feedback.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "variables",
                "Lit/unimi/dsi/fastutil/objects/Object2FloatArrayMap;", null, null));

        MethodNode codec = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "write",
                "(Ltest/Feedback;Ljava/lang/Object;)V", null, null);
        codec.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        codec.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, feedback.name, "sequence", "I"));
        codec.instructions.add(new InsnNode(Opcodes.POP));
        codec.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        codec.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, feedback.name, "variables",
                "Lit/unimi/dsi/fastutil/objects/Object2FloatArrayMap;"));
        codec.instructions.add(new InsnNode(Opcodes.POP));
        codec.instructions.add(new InsnNode(Opcodes.RETURN));
        feedback.methods.add(codec);

        MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "handle",
                "(Ltest/CompleteFeedbackPacket;Ljava/lang/Object;)V", null, null);
        handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        handler.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, packet.name, "feedback",
                "Ltest/Feedback;"));
        handler.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, feedback.name, "target", "I"));
        if (entityLookup) {
            handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/server/level/ServerLevel", "getEntity",
                    "(I)Lnet/minecraft/world/entity/Entity;", false));
            handler.instructions.add(new InsnNode(Opcodes.POP));
        }
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        packet.methods.add(handler);

        return new Fixture(packet, Map.of(packet.name, packet, feedback.name, feedback));
    }

    private static ClassNode classNode(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    private static PlayerStateFixture playerStateFixture(boolean fullConversion) {
        ClassNode packet = classNode("test/PlayerState");
        packet.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "flags", "S", null, null));
        packet.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "decodedRoaming",
                "Lit/unimi/dsi/fastutil/ints/Int2FloatMap;", null, null));

        String self = "L" + packet.name + ";";
        MethodNode animation = new MethodNode(Opcodes.ACC_PUBLIC, "animation",
                "(Ljava/lang/String;)" + self, null, null);
        animation.instructions.add(new LdcInsnNode(2048));
        animation.instructions.add(new InsnNode(Opcodes.POP));
        animation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        animation.instructions.add(new InsnNode(Opcodes.ARETURN));
        packet.methods.add(animation);

        MethodNode roaming = new MethodNode(Opcodes.ACC_PUBLIC, "roaming",
                "(ILit/unimi/dsi/fastutil/objects/Object2FloatMap;)" + self, null, null);
        roaming.instructions.add(new LdcInsnNode(4096));
        roaming.instructions.add(new InsnNode(Opcodes.POP));
        roaming.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        roaming.instructions.add(new InsnNode(Opcodes.ARETURN));
        packet.methods.add(roaming);

        MethodNode writer = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "write",
                "(" + self + "Lnet/minecraft/network/RegistryFriendlyByteBuf;)V", null, null);
        writer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writer.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, packet.name, "flags", "S"));
        writer.instructions.add(new InsnNode(Opcodes.POP));
        writer.instructions.add(new LdcInsnNode(4096));
        writer.instructions.add(new InsnNode(Opcodes.POP));
        writer.instructions.add(new InsnNode(Opcodes.RETURN));
        packet.methods.add(writer);

        MethodNode decoder = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "decode",
                "(Lnet/minecraft/network/RegistryFriendlyByteBuf;)" + self, null, null);
        decoder.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        decoder.instructions.add(new InsnNode(Opcodes.ICONST_0));
        decoder.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, packet.name, "flags", "S"));
        decoder.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        decoder.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        decoder.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, packet.name,
                "decodedRoaming", "Lit/unimi/dsi/fastutil/ints/Int2FloatMap;"));
        decoder.instructions.add(new LdcInsnNode(4096));
        decoder.instructions.add(new InsnNode(Opcodes.POP));
        if (fullConversion) {
            decoder.instructions.add(new TypeInsnNode(Opcodes.NEW,
                    "it/unimi/dsi/fastutil/ints/Int2FloatOpenHashMap"));
            decoder.instructions.add(new InsnNode(Opcodes.POP));
        }
        decoder.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        decoder.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "test/Hashes", "hash",
                "(Ljava/lang/String;)I", false));
        decoder.instructions.add(new InsnNode(Opcodes.POP));
        decoder.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        decoder.instructions.add(new InsnNode(Opcodes.ARETURN));
        packet.methods.add(decoder);

        MethodNode fullFlag = new MethodNode(Opcodes.ACC_PUBLIC, "full", "()Z", null, null);
        fullFlag.instructions.add(new InsnNode(Opcodes.ICONST_1));
        fullFlag.instructions.add(new InsnNode(Opcodes.IRETURN));
        packet.methods.add(fullFlag);

        MethodNode apply = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "apply",
                "(" + self + "Ltest/Capability;)V", null, null);
        apply.instructions.add(new LdcInsnNode(4096));
        apply.instructions.add(new InsnNode(Opcodes.POP));
        apply.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        apply.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, packet.name, "full",
                "()Z", false));
        apply.instructions.add(new InsnNode(Opcodes.POP));
        apply.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        apply.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, packet.name,
                "decodedRoaming", "Lit/unimi/dsi/fastutil/ints/Int2FloatMap;"));
        apply.instructions.add(new InsnNode(Opcodes.POP));
        apply.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        apply.instructions.add(new InsnNode(Opcodes.ICONST_0));
        apply.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        apply.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "test/Capability",
                "initialize", "(ILit/unimi/dsi/fastutil/ints/Int2FloatOpenHashMap;)V", false));
        apply.instructions.add(new InsnNode(Opcodes.RETURN));
        packet.methods.add(apply);

        MethodNode handler = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "handle",
                "(" + self + "Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/network/Connection;)V", null, null);
        handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        handler.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        handler.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, packet.name, "apply",
                "(" + self + "Ltest/Capability;)V", false));
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        packet.methods.add(handler);

        return new PlayerStateFixture(packet, Map.of(packet.name, packet));
    }

    private record Fixture(ClassNode packet, Map<String, ClassNode> classes) {
    }

    private record PlayerStateFixture(ClassNode packet, Map<String, ClassNode> classes) {
    }
}
