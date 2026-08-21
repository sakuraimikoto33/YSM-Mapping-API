package net.okitsu.ysmmapping.internal.analysis;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JarStructureAnalyzerTest {
    @Test
    void discoversTheAnimationRouletteConfigurationExpressionExecutor() throws Exception {
        List<ClassNode> classes = rouletteFixture("test/Roulette");

        YsmCompatibilityMap.MethodSymbol symbol = analyzer()
                .findAnimationRouletteConfigurationExpression(classes, "forge");

        assertEquals("test/Roulette", symbol.owner());
        assertEquals("executeConfig", symbol.name());
    }

    @Test
    void rejectsAmbiguousAnimationRouletteConfigurationExpressionExecutors() {
        List<ClassNode> first = rouletteFixture("test/FirstRoulette");
        List<ClassNode> second = rouletteFixture("test/SecondRoulette");
        java.util.ArrayList<ClassNode> classes = new java.util.ArrayList<>(first);
        second.stream().filter(node -> classes.stream().noneMatch(existing ->
                existing.name.equals(node.name))).forEach(classes::add);

        assertThrows(IOException.class, () -> analyzer()
                .findAnimationRouletteConfigurationExpression(classes, "forge"));
    }

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
        assertEquals("test/Capability", symbols.capabilityClass());
        assertEquals("activeAnimation", symbols.activeAnimationGetter().name());
        assertEquals("animationPlaying", symbols.animationPlayingGetter().name());
        assertEquals("test/StopPacket", symbols.animationStopPacketFactory().owner());
        assertEquals("create", symbols.animationStopPacketFactory().name());
        assertEquals("test/Network", symbols.animationStopSender().owner());
        assertEquals("send", symbols.animationStopSender().name());
        assertEquals("provider", symbols.roamingProviderGetter().name());
        assertEquals("get", symbols.roamingValueGetter().name());
        assertEquals("put", symbols.roamingValueSetter().name());
        assertEquals("hash", symbols.roamingNameHasher().name());
        assertEquals("initialize", symbols.fullRoamingInitializer().name());
    }

    @Test
    void rejectsPlayerStateDecoderWithoutHashedFullMapConversion() {
        PlayerStateFixture fixture = playerStateFixture(false);

        assertThrows(IOException.class, () -> analyzer().findPlayerStateSymbols(
                fixture.classes(), Map.of(21, fixture.packet().name)));
    }

    @Test
    void rejectsPlayerStateWithoutAStaticRoamingNameHasher() {
        PlayerStateFixture fixture = playerStateFixture(true);
        MethodNode decoder = fixture.packet().methods.stream()
                .filter(method -> method.name.equals("decode")).findFirst().orElseThrow();
        AbstractInsnNode hasher = decoder.instructions.getFirst();
        while (!(hasher instanceof MethodInsnNode method)
                || !method.desc.equals("(Ljava/lang/String;)I")) {
            hasher = hasher.getNext();
        }
        ((MethodInsnNode) hasher).setOpcode(Opcodes.INVOKEVIRTUAL);

        assertThrows(IOException.class, () -> analyzer().findPlayerStateSymbols(
                fixture.classes(), Map.of(21, fixture.packet().name)));
    }

    @Test
    void rejectsPlayerStateWithoutACurrentRoamingProviderGetter() {
        PlayerStateFixture fixture = playerStateFixture(true);
        ClassNode capability = fixture.classes().get("test/Capability");
        capability.methods.removeIf(method -> method.name.equals("provider"));

        assertThrows(IOException.class, () -> analyzer().findPlayerStateSymbols(
                fixture.classes(), Map.of(21, fixture.packet().name)));
    }

    @Test
    void rejectsForgePlayerStateWithoutAPlayerConstructor() {
        PlayerStateFixture fixture = playerStateFixture(true);
        ClassNode capability = fixture.classes().get("test/Capability");
        capability.methods.removeIf(method -> method.name.equals("<init>"));

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

    private static List<ClassNode> rouletteFixture(String ownerName) {
        ClassNode owner = classNode(ownerName);
        owner.superName = "net/minecraft/client/gui/screens/Screen";
        owner.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "state", "Ltest/State;",
                null, null));
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "executeConfig",
                "(Ljava/lang/String;Ljava/util/function/Consumer;)V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "test/Parser", "parse",
                "(Ljava/lang/String;)Ltest/Expression;", false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, "state",
                "Ltest/State;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "test/State", "queue",
                "(Ltest/Expression;ZZLjava/util/function/Consumer;)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        owner.methods.add(method);

        return List.of(owner, classNode("test/Parser"), classNode("test/Expression"),
                classNode("test/State"));
    }

    private static PlayerStateFixture playerStateFixture(boolean fullConversion) {
        ClassNode packet = classNode("test/PlayerState");
        packet.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "flags", "S", null, null));
        packet.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "decodedRoaming",
                "Lit/unimi/dsi/fastutil/ints/Int2FloatMap;", null, null));

        ClassNode playerState = classNode("test/CustomPlayer");
        playerState.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "animationName",
                "Ljava/lang/String;", null, null));
        playerState.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "playing",
                "Z", null, null));
        playerState.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "disabled",
                "Z", null, null));
        MethodNode requestAnimation = new MethodNode(Opcodes.ACC_PUBLIC,
                "requestAnimation", "(Ljava/lang/String;)V", null, null);
        requestAnimation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requestAnimation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requestAnimation.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                playerState.name, "animationName", "Ljava/lang/String;"));
        requestAnimation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requestAnimation.instructions.add(new InsnNode(Opcodes.ICONST_1));
        requestAnimation.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                playerState.name, "playing", "Z"));
        requestAnimation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requestAnimation.instructions.add(new InsnNode(Opcodes.ICONST_1));
        requestAnimation.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                playerState.name, "disabled", "Z"));
        requestAnimation.instructions.add(new InsnNode(Opcodes.RETURN));
        playerState.methods.add(requestAnimation);
        MethodNode clearAnimation = new MethodNode(Opcodes.ACC_PUBLIC,
                "clearAnimation", "()V", null, null);
        clearAnimation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clearAnimation.instructions.add(new InsnNode(Opcodes.ICONST_0));
        clearAnimation.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                playerState.name, "playing", "Z"));
        clearAnimation.instructions.add(new InsnNode(Opcodes.RETURN));
        playerState.methods.add(clearAnimation);
        MethodNode activeAnimation = new MethodNode(Opcodes.ACC_PUBLIC,
                "activeAnimation", "()Ljava/lang/String;", null, null);
        activeAnimation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        activeAnimation.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                playerState.name, "animationName", "Ljava/lang/String;"));
        activeAnimation.instructions.add(new InsnNode(Opcodes.ARETURN));
        playerState.methods.add(activeAnimation);
        MethodNode animationPlaying = new MethodNode(Opcodes.ACC_PUBLIC,
                "animationPlaying", "()Z", null, null);
        animationPlaying.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        animationPlaying.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                playerState.name, "playing", "Z"));
        animationPlaying.instructions.add(new InsnNode(Opcodes.IRETURN));
        playerState.methods.add(animationPlaying);
        MethodNode finishAnimation = new MethodNode(Opcodes.ACC_PUBLIC,
                "finishAnimation", "(FZ)V", null, null);
        finishAnimation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finishAnimation.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                playerState.name, "clearAnimation", "()V", false));
        finishAnimation.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "test/StopPacket", "create", "()Ltest/StopPacket;", false));
        finishAnimation.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "test/Network", "send", "(Ljava/lang/Object;)V", false));
        finishAnimation.instructions.add(new InsnNode(Opcodes.RETURN));
        playerState.methods.add(finishAnimation);

        ClassNode capability = classNode("test/Capability");
        capability.superName = playerState.name;
        capability.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "current",
                "Ltest/Provider;", null, null));
        capability.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/world/entity/player/Player;)V", null, null));
        MethodNode initializer = new MethodNode(Opcodes.ACC_PUBLIC, "initialize",
                "(ILit/unimi/dsi/fastutil/ints/Int2FloatOpenHashMap;)V", null, null);
        initializer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializer.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        initializer.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, capability.name,
                "current", "Ltest/Provider;"));
        initializer.instructions.add(new InsnNode(Opcodes.RETURN));
        capability.methods.add(initializer);
        MethodNode providerGetter = new MethodNode(Opcodes.ACC_PUBLIC, "provider",
                "()Ltest/Provider;", null, null);
        providerGetter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        providerGetter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, capability.name,
                "current", "Ltest/Provider;"));
        providerGetter.instructions.add(new InsnNode(Opcodes.ARETURN));
        capability.methods.add(providerGetter);

        ClassNode provider = classNode("test/Provider");
        provider.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        provider.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "get", "(I)Ljava/lang/Object;", null, null));
        provider.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "put", "(ILjava/lang/Object;)V", null, null));

        ClassNode stopPacket = classNode("test/StopPacket");
        stopPacket.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "create", "()Ltest/StopPacket;", null, null));
        ClassNode network = classNode("test/Network");
        network.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "send", "(Ljava/lang/Object;)V", null, null));

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
        apply.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        apply.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, playerState.name,
                "requestAnimation", "(Ljava/lang/String;)V", false));
        apply.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        apply.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, playerState.name,
                "clearAnimation", "()V", false));
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

        return new PlayerStateFixture(packet, Map.of(packet.name, packet,
                capability.name, capability, playerState.name, playerState,
                provider.name, provider, stopPacket.name, stopPacket,
                network.name, network));
    }

    private record Fixture(ClassNode packet, Map<String, ClassNode> classes) {
    }

    private record PlayerStateFixture(ClassNode packet, Map<String, ClassNode> classes) {
    }
}
