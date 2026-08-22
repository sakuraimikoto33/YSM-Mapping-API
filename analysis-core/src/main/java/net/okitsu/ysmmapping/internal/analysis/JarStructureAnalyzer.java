package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmFieldSymbol;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class JarStructureAnalyzer {
    private static final String CLIENT_MODEL_CATALOG_DELTA_DESCRIPTOR =
            "([Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Z)V";
    private static final String CLIENT_MODEL_CATALOG_DELTA_HELPER_DESCRIPTOR =
            "([Ljava/lang/String;[Ljava/lang/String;[Z[Ljava/lang/String;)V";
    private static final String CLIENT_MODEL_CATALOG_DELTA_LAMBDA_DESCRIPTOR =
            "([Ljava/lang/String;[Ljava/lang/String;[Z[Ljava/lang/String;)Ljava/lang/Runnable;";
    private final AnalysisProfile profile;
    private final Set<Integer> targetIds;
    private final Set<String> entityTypes;
    private final Set<String> playerTypes;
    private final Set<String> connectionTypes;
    private final Set<String> componentTypes;

    public JarStructureAnalyzer(AnalysisProfile profile) {
        this.profile = java.util.Objects.requireNonNull(profile, "profile");
        targetIds = profile.packets().keySet();
        entityTypes = loaderTypes(AnalysisProfile.LoaderTypes::entityTypes);
        playerTypes = loaderTypes(AnalysisProfile.LoaderTypes::playerTypes);
        connectionTypes = loaderTypes(AnalysisProfile.LoaderTypes::connectionTypes);
        componentTypes = loaderTypes(AnalysisProfile.LoaderTypes::componentTypes);
    }

    private Set<String> loaderTypes(
            java.util.function.Function<AnalysisProfile.LoaderTypes, List<String>> selector) {
        return profile.loaders().values().stream().flatMap(value -> selector.apply(value).stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public YsmCompatibilityMap analyze(YsmArtifact artifact, Path jar) throws IOException {
        return analyze(artifact, YsmClassIndex.read(jar));
    }

    public YsmCompatibilityMap analyze(YsmArtifact artifact, YsmClassIndex index)
            throws IOException {
        validateTarget(artifact);
        List<ClassNode> classes = index.classes();
        try {
            List<RegistrationCandidate> registrationCandidates = classes.stream()
                    .flatMap(node -> node.methods.stream()
                            .map(method -> analyzeRegistration(node, method)))
                    .filter(candidate -> !candidate.symbols.isEmpty()).toList();
            int largestRegistration = registrationCandidates.stream()
                    .mapToInt(candidate -> candidate.symbols.size()).max()
                    .orElseThrow(() -> new IOException("No packet registration candidate found"));
            List<RegistrationCandidate> largestCandidates = registrationCandidates.stream()
                    .filter(candidate -> candidate.symbols.size() == largestRegistration)
                    .distinct().toList();
            if (largestCandidates.size() != 1) {
                throw new IOException("Packet registration surface is ambiguous; found "
                        + largestCandidates.size() + " equally complete candidates");
            }
            RegistrationCandidate registration = largestCandidates.get(0);
        if (registration.symbols.size() < targetIds.size()) {
            Set<Integer> missing = new HashSet<>(targetIds);
            missing.removeAll(registration.symbols.keySet());
            throw new IOException("Packet registration map is incomplete; missing IDs " + missing);
        }

        List<YsmCompatibilityMap.PacketSymbol> packets = profile.packets().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new YsmCompatibilityMap.PacketSymbol(entry.getKey(),
                        entry.getValue().name(), entry.getValue().direction(),
                        registration.symbols.get(entry.getKey())))
                .toList();
        String channel = findChannel(classes);
        Map<String, ClassNode> classesByName = new HashMap<>();
        classes.forEach(node -> classesByName.put(node.name, node));
        validateBridgeSurface(classesByName, registration.symbols);
        CompleteFeedbackSymbols feedback = findCompleteFeedbackSymbols(classesByName,
                registration.symbols);
        AnimationSymbols animation = findAnimationSymbols(classesByName, registration.symbols);
        PlayerStateSymbols playerState = findPlayerStateSymbols(classesByName,
                registration.symbols, artifact.loader().equals("forge"));
        MethodReference clientStart = findUniqueInvokedMethod(
                classesByName.get(registration.symbols.get(1)),
                reference -> hasArguments(reference.desc, "net/minecraft/", "java/nio/ByteBuffer")
                        && Type.getReturnType(reference.desc).equals(Type.VOID_TYPE),
                "client model start invocation");
        MethodReference serverReceive = findUniqueInvokedMethod(
                classesByName.get(registration.symbols.get(2)),
                reference -> reference.desc.equals("(Ljava/util/UUID;Ljava/nio/ByteBuffer;)V"),
                "server model receive invocation");
        MethodReference clientConnected = findUniqueInvokedMethod(
                classesByName.get(registration.symbols.get(51)),
                reference -> reference.desc.equals("()V") && clientStart != null
                        && reference.owner.equals(clientStart.owner),
                "client model connected invocation");
        ClassNode clientManager = clientStart == null ? null : classesByName.get(clientStart.owner);
        YsmCompatibilityMap.MethodSymbol clientReset = findClientReset(clientManager);
        YsmCompatibilityMap.MethodSymbol clientModelLookup =
                findClientModelLookup(clientManager);
        YsmCompatibilityMap.MethodSymbol clientModelMap = findClientModelMapGetter(
                clientManager, clientModelLookup);
        YsmCompatibilityMap.MethodSymbol clientCatalogDelta = findClientModelCatalogDeltaCallback(
                clientManager, clientModelMap);
        YsmCompatibilityMap.MethodSymbol clientPackMap = findClientPackMapGetter(clientManager,
                clientModelMap);
        YsmCompatibilityMap.MethodSymbol clientPendingCount = findUniqueDeclared(clientManager,
                method -> isStatic(method) && isPublic(method) && method.desc.equals("()I"),
                "client pending model count getter");
        YsmCompatibilityMap.MethodSymbol clientFlushPending = findClientFlushPending(clientManager,
                clientModelMap, clientPendingCount);
        YsmCompatibilityMap.MethodSymbol clientRawSender = findUniqueDeclared(clientManager,
                method -> isStatic(method) && !isNative(method)
                        && method.desc.equals("(Ljava/nio/ByteBuffer;)V"),
                "client raw model sender");
        YsmCompatibilityMap.MethodSymbol rouletteConfigurationExpression =
                findAnimationRouletteConfigurationExpression(classes, artifact.loader());
        ClassNode serverManager = serverReceive == null ? null : classesByName.get(serverReceive.owner);
        YsmCompatibilityMap.MethodSymbol reload = findDeclared(serverManager,
                method -> isStatic(method)
                        && method.desc.equals("(Ljava/util/function/Consumer;Ljava/util/function/Consumer;)Z"));
        YsmCompatibilityMap.MethodSymbol sync = findDeclared(serverManager,
                method -> isStatic(method) && method.desc.equals(
                        "([Ljava/util/UUID;[Ljava/lang/String;[Ljava/lang/String;Ljava/lang/Object;)V"));
        validateServerSyncInvocation(serverManager, sync);
        ServerSyncResultSymbols syncResult = findServerSyncResultSymbols(classes);
        YsmCompatibilityMap.MethodSymbol streamCallback = findDeclared(serverManager,
                method -> isStatic(method)
                        && method.desc.startsWith("(Ljava/util/UUID;Ljava/nio/ByteBuffer;L")
                        && method.desc.endsWith(")Z"));
        YsmCompatibilityMap.MethodSymbol serverModelMap = findUniqueDeclared(serverManager,
                method -> isStatic(method) && isPublic(method)
                        && method.desc.equals("()Ljava/util/Map;"),
                "server model map getter");
        YsmCompatibilityMap.FieldSymbol serverModelMapField = findServerModelMapField(
                serverManager, serverModelMap);
        YsmCompatibilityMap.MethodSymbol payloadFactory = findUniqueDeclared(serverManager,
                method -> isStatic(method) && method.desc.equals(
                        "(Ljava/nio/ByteBuffer;)Ljava/lang/Object;"),
                "server model payload factory");
        String payloadCallbackDescriptor = streamCallback == null ? null
                : streamCallback.descriptor().replace("Ljava/nio/ByteBuffer;", "Ljava/lang/Object;");
        YsmCompatibilityMap.MethodSymbol payloadCallback = findDeclared(serverManager,
                method -> isStatic(method) && streamCallback != null
                        && method.name.equals(streamCallback.name())
                        && method.desc.equals(payloadCallbackDescriptor));
        YsmCompatibilityMap.MethodSymbol clientSend = findDeclared(classesByName.get(registration.owner),
                JarStructureAnalyzer::isClientSendMethod);
        YsmCompatibilityMap.MethodSymbol channelSetter = findDeclared(classesByName.get(registration.owner),
                method -> isStatic(method)
                        && hasArguments(method.desc, "net/minecraft/", "java/lang/String")
                        && Type.getReturnType(method.desc).equals(Type.BOOLEAN_TYPE));
        YsmCompatibilityMap.FieldSymbol clientNotDisplayModels = findConfigField(classes,
                "ClientNotDisplayModels");
        requireStatic(classesByName, clientStart, "client model start");
        requireStatic(classesByName, clientConnected, "client model connect");
        requireStatic(classesByName, serverReceive, "server model receive");
        if (payloadCallback == null) {
            throw new IOException("Missing server model payload callback");
        }
            return new YsmCompatibilityMap(artifact.id(), artifact.minecraftVersion(), artifact.loader(),
                artifact.ysmVersion(), artifact.sha512(), channel, registration.owner,
                registration.method, packets, clientSend, channelSetter,
                symbol(clientStart), symbol(clientConnected), clientReset,
                clientModelMap, clientModelLookup, clientCatalogDelta, clientPackMap,
                clientPendingCount, clientFlushPending, clientRawSender,
                rouletteConfigurationExpression,
                symbol(serverReceive), reload, sync,
                syncResult.successGetter(), syncResult.errorGetter(),
                serverModelMap, serverModelMapField, streamCallback, payloadFactory, payloadCallback,
                clientNotDisplayModels, feedback.payloadField(), feedback.modelKeyField(),
                feedback.targetEntityIdField(), feedback.variablesField(),
                animation.indexField(), animation.packField(), animation.targetEntityIdField(),
                animation.containerDataGetter(), animation.dataPropertiesGetter(),
                animation.dataStorageKeyGetter(), animation.defaultAnimationsGetter(),
                animation.animationPacksGetter(), animation.orderedCountGetter(),
                animation.orderedNameGetter(), playerState.animationSetter(),
                playerState.roamingSetter(), playerState.codecWriter(),
                playerState.codecDecoder(), playerState.clientHandler(),
                playerState.flagsField(), playerState.decodedRoamingField(),
                playerState.capabilityClass(), playerState.activeAnimationGetter(),
                playerState.animationPlayingGetter(),
                playerState.animationStopPacketFactory(),
                playerState.animationStopSender(), playerState.roamingProviderGetter(),
                playerState.roamingValueGetter(), playerState.roamingValueSetter(),
                playerState.roamingNameHasher(),
                playerState.fullRoamingInitializer());
        } catch (IOException exception) {
            throw new StructuralAnalysisException(exception.getMessage(), exception);
        }
    }

    /**
     * Runtime fallback that preserves successful symbol groups when a future YSM changes one
     * surface. Known fixtures take the fully validated fast path above.
     */
    public PartialAnalysis analyzePartial(YsmArtifact artifact, YsmClassIndex index)
            throws IOException {
        validateTarget(artifact);
        List<ClassNode> classes = index.classes();
        Map<String, ClassNode> byName = new HashMap<>();
        classes.forEach(node -> byName.put(node.name, node));
        try {
            Map<YsmSymbolKey<?>, YsmResolvedSymbol> values = new LinkedHashMap<>(
                    SymbolMappings.from(analyze(artifact, index)));
            Map<YsmSymbolKey<?>, String> diagnostics = new LinkedHashMap<>();
            recoverClientTextureCache(byName, values, diagnostics);
            recoverClientAudioCache(byName, values, diagnostics);
            return new PartialAnalysis(Map.copyOf(values), Map.copyOf(diagnostics));
        } catch (StructuralAnalysisException ignored) {
            // Recover independent groups below; an individual failure is recorded per key.
        }

        Map<YsmSymbolKey<?>, YsmResolvedSymbol> values = new LinkedHashMap<>();
        Map<YsmSymbolKey<?>, String> diagnostics = new LinkedHashMap<>();

        RegistrationCandidate registration = null;
        try {
            registration = bestRegistration(classes);
            values.put(YsmSymbols.REGISTRATION_CLASS,
                    new YsmClassSymbol(registration.owner));
            int descriptorStart = registration.method.indexOf('(');
            if (descriptorStart <= 0) {
                throw new IOException("Invalid packet registration method");
            }
            values.put(YsmSymbols.REGISTRATION_METHOD, new YsmMethodSymbol(
                    registration.owner, registration.method.substring(0, descriptorStart),
                    registration.method.substring(descriptorStart)));
        } catch (IOException exception) {
            fail(diagnostics, exception, YsmSymbols.REGISTRATION_CLASS,
                    YsmSymbols.REGISTRATION_METHOD);
        }

        if (registration != null) {
            for (int packetId : targetIds) {
                YsmSymbolKey<?> key = YsmSymbols.packetClass(packetId);
                String runtimeName = registration.symbols.get(packetId);
                try {
                    validatePacketSurface(byName, registration.symbols, packetId);
                    values.put(key, new YsmClassSymbol(runtimeName));
                } catch (IOException | RuntimeException exception) {
                    fail(diagnostics, exception, key);
                }
            }
            putMethod(values, diagnostics, YsmSymbols.CLIENT_SEND_METHOD,
                    findDeclared(byName.get(registration.owner),
                            JarStructureAnalyzer::isClientSendMethod));
            putMethod(values, diagnostics, YsmSymbols.CHANNEL_VERSION_SETTER,
                    findDeclared(byName.get(registration.owner), method -> isStatic(method)
                            && hasArguments(method.desc, "net/minecraft/", "java/lang/String")
                            && Type.getReturnType(method.desc).equals(Type.BOOLEAN_TYPE)));
            recoverFeedback(byName, registration.symbols, values, diagnostics);
            recoverAnimation(byName, registration.symbols, values, diagnostics);
            recoverPlayerState(byName, registration.symbols, values, diagnostics,
                    artifact.loader().equals("forge"));
            recoverClientManager(byName, registration.symbols, values, diagnostics);
            recoverClientTextureCache(byName, values, diagnostics);
            recoverClientAudioCache(byName, values, diagnostics);
            recoverServerManager(byName, registration.symbols, values, diagnostics);
        }

        try {
            putField(values, diagnostics, YsmSymbols.CLIENT_NOT_DISPLAY_MODELS,
                    findConfigField(classes, "ClientNotDisplayModels"));
        } catch (IOException exception) {
            fail(diagnostics, exception, YsmSymbols.CLIENT_NOT_DISPLAY_MODELS);
        }
        recoverAnimationRoulette(classes, artifact.loader(), values, diagnostics);
        try {
            ServerSyncResultSymbols result = findServerSyncResultSymbols(classes);
            putMethod(values, diagnostics, YsmSymbols.SERVER_SYNC_RESULT_SUCCESS_GETTER,
                    result.successGetter());
            putMethod(values, diagnostics, YsmSymbols.SERVER_SYNC_RESULT_ERROR_GETTER,
                    result.errorGetter());
        } catch (IOException exception) {
            fail(diagnostics, exception, YsmSymbols.SERVER_SYNC_RESULT_SUCCESS_GETTER,
                    YsmSymbols.SERVER_SYNC_RESULT_ERROR_GETTER);
        }
        return new PartialAnalysis(Map.copyOf(values), Map.copyOf(diagnostics));
    }

    private void validateTarget(YsmArtifact artifact) {
        java.util.Objects.requireNonNull(artifact, "artifact");
        if (!profile.minecraftVersion().equals(artifact.minecraftVersion())) {
            throw new IllegalArgumentException("Artifact Minecraft version "
                    + artifact.minecraftVersion() + " does not match profile "
                    + profile.minecraftVersion());
        }
        profile.loader(artifact.loader());
    }

    private RegistrationCandidate bestRegistration(List<ClassNode> classes)
            throws IOException {
        List<RegistrationCandidate> candidates = classes.stream()
                .flatMap(node -> node.methods.stream().map(method ->
                        analyzeRegistration(node, method)))
                .filter(candidate -> !candidate.symbols.isEmpty()).toList();
        int largest = candidates.stream().mapToInt(candidate -> candidate.symbols.size()).max()
                .orElseThrow(() -> new IOException("No packet registration candidate found"));
        List<RegistrationCandidate> largestCandidates = candidates.stream()
                .filter(candidate -> candidate.symbols.size() == largest).distinct().toList();
        if (largestCandidates.size() != 1) {
            throw new IOException("Packet registration surface is ambiguous; found "
                    + largestCandidates.size() + " equally complete candidates");
        }
        return largestCandidates.get(0);
    }

    private void recoverFeedback(Map<String, ClassNode> classes,
            Map<Integer, String> packets, Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics) {
        try {
            CompleteFeedbackSymbols result = findCompleteFeedbackSymbols(classes, packets);
            putField(values, diagnostics, YsmSymbols.COMPLETE_FEEDBACK_PAYLOAD_FIELD,
                    result.payloadField());
            putField(values, diagnostics, YsmSymbols.FEEDBACK_MODEL_KEY_FIELD,
                    result.modelKeyField());
            putField(values, diagnostics, YsmSymbols.FEEDBACK_TARGET_ENTITY_ID_FIELD,
                    result.targetEntityIdField());
            putField(values, diagnostics, YsmSymbols.FEEDBACK_VARIABLES_FIELD,
                    result.variablesField());
        } catch (IOException exception) {
            fail(diagnostics, exception, YsmSymbols.COMPLETE_FEEDBACK_PAYLOAD_FIELD,
                    YsmSymbols.FEEDBACK_MODEL_KEY_FIELD,
                    YsmSymbols.FEEDBACK_TARGET_ENTITY_ID_FIELD, YsmSymbols.FEEDBACK_VARIABLES_FIELD);
        }
    }

    private void recoverAnimation(Map<String, ClassNode> classes,
            Map<Integer, String> packets, Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics) {
        try {
            AnimationSymbols result = findAnimationSymbols(classes, packets);
            putField(values, diagnostics, YsmSymbols.ANIMATION_INDEX_FIELD, result.indexField());
            putField(values, diagnostics, YsmSymbols.ANIMATION_PACK_FIELD, result.packField());
            putField(values, diagnostics, YsmSymbols.ANIMATION_TARGET_ENTITY_ID_FIELD,
                    result.targetEntityIdField());
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_CONTAINER_DATA_GETTER,
                    result.containerDataGetter());
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_DATA_PROPERTIES_GETTER,
                    result.dataPropertiesGetter());
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_DATA_STORAGE_KEY_GETTER,
                    result.dataStorageKeyGetter());
            putMethod(values, diagnostics, YsmSymbols.MODEL_PROPERTIES_DEFAULT_ANIMATIONS_GETTER,
                    result.defaultAnimationsGetter());
            putMethod(values, diagnostics, YsmSymbols.MODEL_PROPERTIES_ANIMATION_PACKS_GETTER,
                    result.animationPacksGetter());
            putMethod(values, diagnostics, YsmSymbols.ORDERED_ANIMATION_COUNT_GETTER,
                    result.orderedCountGetter());
            putMethod(values, diagnostics, YsmSymbols.ORDERED_ANIMATION_NAME_GETTER,
                    result.orderedNameGetter());
        } catch (IOException exception) {
            fail(diagnostics, exception, YsmSymbols.ANIMATION_INDEX_FIELD,
                    YsmSymbols.ANIMATION_PACK_FIELD, YsmSymbols.ANIMATION_TARGET_ENTITY_ID_FIELD,
                    YsmSymbols.SERVER_MODEL_CONTAINER_DATA_GETTER,
                    YsmSymbols.SERVER_MODEL_DATA_PROPERTIES_GETTER,
                    YsmSymbols.SERVER_MODEL_DATA_STORAGE_KEY_GETTER,
                    YsmSymbols.MODEL_PROPERTIES_DEFAULT_ANIMATIONS_GETTER,
                    YsmSymbols.MODEL_PROPERTIES_ANIMATION_PACKS_GETTER,
                    YsmSymbols.ORDERED_ANIMATION_COUNT_GETTER,
                    YsmSymbols.ORDERED_ANIMATION_NAME_GETTER);
        }
    }

    private void recoverPlayerState(Map<String, ClassNode> classes,
            Map<Integer, String> packets, Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics, boolean requirePlayerConstructor) {
        try {
            PlayerStateSymbols result = findPlayerStateSymbols(
                    classes, packets, requirePlayerConstructor);
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ANIMATION_SETTER,
                    result.animationSetter());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ROAMING_SETTER,
                    result.roamingSetter());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_CODEC_WRITER,
                    result.codecWriter());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_CODEC_DECODER,
                    result.codecDecoder());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_CLIENT_HANDLER,
                    result.clientHandler());
            putField(values, diagnostics, YsmSymbols.PLAYER_STATE_FLAGS_FIELD,
                    result.flagsField());
            putField(values, diagnostics, YsmSymbols.PLAYER_STATE_DECODED_ROAMING_FIELD,
                    result.decodedRoamingField());
            values.put(YsmSymbols.PLAYER_STATE_CAPABILITY_CLASS,
                    new YsmClassSymbol(result.capabilityClass()));
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ACTIVE_ANIMATION_GETTER,
                    result.activeAnimationGetter());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ANIMATION_PLAYING_GETTER,
                    result.animationPlayingGetter());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ANIMATION_STOP_PACKET_FACTORY,
                    result.animationStopPacketFactory());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ANIMATION_STOP_SENDER,
                    result.animationStopSender());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ROAMING_PROVIDER_GETTER,
                    result.roamingProviderGetter());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ROAMING_VALUE_GETTER,
                    result.roamingValueGetter());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ROAMING_VALUE_SETTER,
                    result.roamingValueSetter());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_ROAMING_NAME_HASHER,
                    result.roamingNameHasher());
            putMethod(values, diagnostics, YsmSymbols.PLAYER_STATE_FULL_ROAMING_INITIALIZER,
                    result.fullRoamingInitializer());
        } catch (IOException exception) {
            fail(diagnostics, exception, YsmSymbols.PLAYER_STATE_ANIMATION_SETTER,
                    YsmSymbols.PLAYER_STATE_ROAMING_SETTER,
                    YsmSymbols.PLAYER_STATE_CODEC_WRITER,
                    YsmSymbols.PLAYER_STATE_CODEC_DECODER,
                    YsmSymbols.PLAYER_STATE_CLIENT_HANDLER, YsmSymbols.PLAYER_STATE_FLAGS_FIELD,
                    YsmSymbols.PLAYER_STATE_DECODED_ROAMING_FIELD,
                    YsmSymbols.PLAYER_STATE_CAPABILITY_CLASS,
                    YsmSymbols.PLAYER_STATE_ACTIVE_ANIMATION_GETTER,
                    YsmSymbols.PLAYER_STATE_ANIMATION_PLAYING_GETTER,
                    YsmSymbols.PLAYER_STATE_ANIMATION_STOP_PACKET_FACTORY,
                    YsmSymbols.PLAYER_STATE_ANIMATION_STOP_SENDER,
                    YsmSymbols.PLAYER_STATE_ROAMING_PROVIDER_GETTER,
                    YsmSymbols.PLAYER_STATE_ROAMING_VALUE_GETTER,
                    YsmSymbols.PLAYER_STATE_ROAMING_VALUE_SETTER,
                    YsmSymbols.PLAYER_STATE_ROAMING_NAME_HASHER,
                    YsmSymbols.PLAYER_STATE_FULL_ROAMING_INITIALIZER);
        }
    }

    private static void recoverClientManager(Map<String, ClassNode> classes,
            Map<Integer, String> packets, Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics) {
        YsmSymbolKey<?>[] keys = {YsmSymbols.CLIENT_MODEL_START_SYNC,
                YsmSymbols.CLIENT_MODEL_CONNECTED, YsmSymbols.CLIENT_MODEL_RESET,
                YsmSymbols.CLIENT_MODEL_MAP_GETTER, YsmSymbols.CLIENT_MODEL_LOOKUP,
                YsmSymbols.CLIENT_MODEL_CATALOG_DELTA_CALLBACK,
                YsmSymbols.CLIENT_PACK_MAP_GETTER, YsmSymbols.CLIENT_PENDING_COUNT_GETTER,
                YsmSymbols.CLIENT_MODEL_FLUSH_PENDING, YsmSymbols.CLIENT_MODEL_RAW_SENDER};
        try {
            MethodReference start = findUniqueInvokedMethod(classes.get(packets.get(1)),
                    reference -> hasArguments(reference.desc, "net/minecraft/",
                            "java/nio/ByteBuffer")
                            && Type.getReturnType(reference.desc).equals(Type.VOID_TYPE),
                    "client model start invocation");
            requireStatic(classes, start, "client model start");
            MethodReference connected = findUniqueInvokedMethod(classes.get(packets.get(51)),
                    reference -> reference.desc.equals("()V")
                            && reference.owner.equals(start.owner),
                    "client model connected invocation");
            requireStatic(classes, connected, "client model connect");
            ClassNode manager = classes.get(start.owner);
            YsmCompatibilityMap.MethodSymbol reset = findClientReset(manager);
            YsmCompatibilityMap.MethodSymbol modelLookup = findClientModelLookup(manager);
            YsmCompatibilityMap.MethodSymbol modelMap = findClientModelMapGetter(
                    manager, modelLookup);
            YsmCompatibilityMap.MethodSymbol catalog = findClientModelCatalogDeltaCallback(
                    manager, modelMap);
            YsmCompatibilityMap.MethodSymbol packMap = findClientPackMapGetter(manager, modelMap);
            YsmCompatibilityMap.MethodSymbol pending = findUniqueDeclared(manager,
                    method -> isStatic(method) && isPublic(method) && method.desc.equals("()I"),
                    "client pending model count getter");
            YsmCompatibilityMap.MethodSymbol flush = findClientFlushPending(manager, modelMap,
                    pending);
            YsmCompatibilityMap.MethodSymbol raw = findUniqueDeclared(manager,
                    method -> isStatic(method) && !isNative(method)
                            && method.desc.equals("(Ljava/nio/ByteBuffer;)V"),
                    "client raw model sender");
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_START_SYNC, symbol(start));
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_CONNECTED, symbol(connected));
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_RESET, reset);
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_MAP_GETTER, modelMap);
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_LOOKUP, modelLookup);
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_CATALOG_DELTA_CALLBACK, catalog);
            putMethod(values, diagnostics, YsmSymbols.CLIENT_PACK_MAP_GETTER, packMap);
            putMethod(values, diagnostics, YsmSymbols.CLIENT_PENDING_COUNT_GETTER, pending);
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_FLUSH_PENDING, flush);
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_RAW_SENDER, raw);
        } catch (IOException | RuntimeException exception) {
            fail(diagnostics, exception, keys);
        }
    }

    /**
     * Recover the official client's in-memory texture cache path from the model
     * lookup method. The manager itself contains a small default-texture helper
     * whose bytecode forms a stable chain across supported releases:
     * model -&gt; model data -&gt; ordered texture map -&gt; AbstractTexture -&gt;
     * cache lease -&gt; Optional&lt;ResourceLocation&gt;.
     */
    private static void recoverClientTextureCache(Map<String, ClassNode> classes,
            Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics) {
        YsmSymbolKey<?>[] keys = {YsmSymbols.CLIENT_MODEL_DATA_GETTER,
                YsmSymbols.CLIENT_MODEL_TEXTURES_GETTER,
                YsmSymbols.CLIENT_TEXTURE_CACHE_ACQUIRE,
                YsmSymbols.CLIENT_TEXTURE_LOCATION_GETTER};
        try {
            YsmResolvedSymbol resolved = values.get(YsmSymbols.CLIENT_MODEL_LOOKUP);
            if (!(resolved instanceof YsmMethodSymbol lookup)) {
                throw new IOException("Client model lookup is unavailable");
            }
            ClientTextureCacheSymbols result = findClientTextureCacheSymbols(classes, lookup);
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_DATA_GETTER,
                    result.modelDataGetter());
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_TEXTURES_GETTER,
                    result.texturesGetter());
            putMethod(values, diagnostics, YsmSymbols.CLIENT_TEXTURE_CACHE_ACQUIRE,
                    result.cacheAcquire());
            putMethod(values, diagnostics, YsmSymbols.CLIENT_TEXTURE_LOCATION_GETTER,
                    result.locationGetter());
        } catch (IOException | RuntimeException exception) {
            fail(diagnostics, exception, keys);
        }
    }

    static ClientTextureCacheSymbols findClientTextureCacheSymbols(
            Map<String, ClassNode> classes, YsmMethodSymbol lookup) throws IOException {
        ClassNode manager = requireClass(classes, lookup.owner(), "client model manager");
        MethodNode lookupMethod = manager.methods.stream()
                .filter(method -> method.name.equals(lookup.name())
                        && method.desc.equals(lookup.descriptor()))
                .findFirst().orElseThrow(() -> new IOException("Missing client model lookup"));

        List<String> modelTypes = new ArrayList<>();
        for (AbstractInsnNode instruction : lookupMethod.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.CHECKCAST
                    && classes.containsKey(type.desc) && !modelTypes.contains(type.desc)) {
                modelTypes.add(type.desc);
            }
        }
        if (modelTypes.size() != 1) {
            throw new IOException("Client model lookup must cast exactly one model type");
        }
        String modelType = modelTypes.get(0);

        List<ClientTextureCacheSymbols> candidates = new ArrayList<>();
        for (MethodNode method : manager.methods) {
            List<AbstractInsnNode> code = realInstructions(method);
            for (int index = 0; index < code.size(); index++) {
                if (!(code.get(index) instanceof MethodInsnNode modelDataCall)
                        || !modelDataCall.owner.equals(modelType)
                        || Type.getArgumentTypes(modelDataCall.desc).length != 0
                        || Type.getReturnType(modelDataCall.desc).getSort() != Type.OBJECT) {
                    continue;
                }
                String dataType = Type.getReturnType(modelDataCall.desc).getInternalName();
                MethodInsnNode texturesCall = nextInvocation(code, index + 1, 4,
                        call -> call.owner.equals(dataType)
                                && Type.getArgumentTypes(call.desc).length == 0
                                && Type.getReturnType(call.desc).getSort() == Type.OBJECT);
                if (texturesCall == null) {
                    continue;
                }
                int texturesIndex = code.indexOf(texturesCall);
                String mapType = Type.getReturnType(texturesCall.desc).getInternalName();
                MethodInsnNode indexedTexture = nextInvocation(code, texturesIndex + 1, 5,
                        call -> call.owner.equals(mapType)
                                && call.desc.equals("(I)Ljava/lang/Object;"));
                if (indexedTexture == null) {
                    continue;
                }
                int indexedTextureIndex = code.indexOf(indexedTexture);
                int textureCastIndex = nextMinecraftTypeInstruction(
                        code, indexedTextureIndex + 1, 3);
                if (textureCastIndex < 0) {
                    continue;
                }
                String textureType = ((TypeInsnNode) code.get(textureCastIndex)).desc;
                MethodInsnNode acquire = nextInvocation(code, textureCastIndex + 1, 6,
                        call -> call.getOpcode() == Opcodes.INVOKESTATIC
                                && cacheAcquireDescriptor(call.desc, textureType));
                if (acquire == null) {
                    continue;
                }
                String leaseType = Type.getReturnType(acquire.desc).getInternalName();
                ClassNode lease = classes.get(leaseType);
                if (lease == null) {
                    continue;
                }
                List<MethodNode> locationGetters = lease.methods.stream()
                        .filter(candidate -> !isStatic(candidate) && isPublic(candidate)
                                && candidate.desc.equals("()Ljava/util/Optional;"))
                        .toList();
                if (locationGetters.size() != 1) {
                    continue;
                }
                MethodNode locationGetter = locationGetters.get(0);
                ClientTextureCacheSymbols candidate = new ClientTextureCacheSymbols(
                        new YsmCompatibilityMap.MethodSymbol(modelDataCall.owner,
                                modelDataCall.name, modelDataCall.desc),
                        new YsmCompatibilityMap.MethodSymbol(texturesCall.owner,
                                texturesCall.name, texturesCall.desc),
                        new YsmCompatibilityMap.MethodSymbol(acquire.owner,
                                acquire.name, acquire.desc),
                        new YsmCompatibilityMap.MethodSymbol(lease.name,
                                locationGetter.name, locationGetter.desc));
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        if (candidates.size() != 1) {
            throw new IOException("Expected one client texture cache chain, found "
                    + candidates.size());
        }
        return candidates.get(0);
    }

    private static boolean cacheAcquireDescriptor(String descriptor, String textureType) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        Type result = Type.getReturnType(descriptor);
        return arguments.length == 2
                && arguments[0].getSort() == Type.OBJECT
                && arguments[0].getInternalName().equals(textureType)
                && arguments[1].equals(Type.BOOLEAN_TYPE)
                && result.getSort() == Type.OBJECT;
    }

    private static void recoverClientAudioCache(Map<String, ClassNode> classes,
            Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics) {
        YsmSymbolKey<?>[] keys = {YsmSymbols.CLIENT_MODEL_RESOURCES_GETTER,
                YsmSymbols.CLIENT_MODEL_SOUNDS_GETTER,
                YsmSymbols.CLIENT_AUDIO_STREAM_CACHE_ACQUIRE,
                YsmSymbols.CLIENT_AUDIO_STREAM_OPEN};
        try {
            YsmResolvedSymbol resolved = values.get(YsmSymbols.CLIENT_MODEL_LOOKUP);
            if (!(resolved instanceof YsmMethodSymbol lookup)) {
                throw new IOException("Client model lookup is unavailable");
            }
            ClientAudioCacheSymbols result = findClientAudioCacheSymbols(classes, lookup);
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_RESOURCES_GETTER,
                    result.modelResourcesGetter());
            putMethod(values, diagnostics, YsmSymbols.CLIENT_MODEL_SOUNDS_GETTER,
                    result.soundsGetter());
            putMethod(values, diagnostics, YsmSymbols.CLIENT_AUDIO_STREAM_CACHE_ACQUIRE,
                    result.cacheAcquire());
            putMethod(values, diagnostics, YsmSymbols.CLIENT_AUDIO_STREAM_OPEN,
                    result.streamOpen());
        } catch (IOException | RuntimeException exception) {
            fail(diagnostics, exception, keys);
        }
    }

    static ClientAudioCacheSymbols findClientAudioCacheSymbols(
            Map<String, ClassNode> classes, YsmMethodSymbol lookup) throws IOException {
        String modelType = clientModelType(classes, lookup);
        List<ClientAudioCacheSymbols> candidates = new ArrayList<>();
        for (ClassNode owner : classes.values()) {
            for (MethodNode method : owner.methods) {
                List<AbstractInsnNode> code = realInstructions(method);
                for (int index = 0; index < code.size(); index++) {
                    if (!(code.get(index) instanceof MethodInsnNode resourcesCall)
                            || !resourcesCall.owner.equals(modelType)
                            || Type.getArgumentTypes(resourcesCall.desc).length != 0
                            || Type.getReturnType(resourcesCall.desc).getSort() != Type.OBJECT) {
                        continue;
                    }
                    String resourcesType = Type.getReturnType(resourcesCall.desc)
                            .getInternalName();
                    if (!classes.containsKey(resourcesType)) {
                        continue;
                    }
                    MethodInsnNode soundsCall = nextInvocation(code, index + 1, 4,
                            call -> call.owner.equals(resourcesType)
                                    && call.desc.equals("()Ljava/util/Map;"));
                    if (soundsCall == null) {
                        continue;
                    }
                    int soundsIndex = code.indexOf(soundsCall);
                    MethodInsnNode mapLookup = nextInvocation(code, soundsIndex + 1, 5,
                            call -> call.owner.equals("java/util/Map")
                                    && call.name.equals("get")
                                    && call.desc.equals(
                                    "(Ljava/lang/Object;)Ljava/lang/Object;"));
                    if (mapLookup == null) {
                        continue;
                    }
                    int trackCastIndex = nextYsmTypeInstruction(
                            code, code.indexOf(mapLookup) + 1, 4, classes);
                    if (trackCastIndex < 0) {
                        continue;
                    }
                    String trackType = ((TypeInsnNode) code.get(trackCastIndex)).desc;
                    ProviderSymbols provider = findAudioProvider(classes, modelType, trackType);
                    if (provider == null) {
                        continue;
                    }
                    ClientAudioCacheSymbols candidate = new ClientAudioCacheSymbols(
                            new YsmCompatibilityMap.MethodSymbol(resourcesCall.owner,
                                    resourcesCall.name, resourcesCall.desc),
                            new YsmCompatibilityMap.MethodSymbol(soundsCall.owner,
                                    soundsCall.name, soundsCall.desc),
                            provider.acquire(), provider.open());
                    if (!candidates.contains(candidate)) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        if (candidates.size() != 1) {
            throw new IOException("Expected one client audio cache chain, found "
                    + candidates.size());
        }
        return candidates.get(0);
    }

    private static ProviderSymbols findAudioProvider(Map<String, ClassNode> classes,
            String modelType, String trackType) throws IOException {
        List<ProviderSymbols> candidates = new ArrayList<>();
        for (ClassNode owner : classes.values()) {
            for (MethodNode acquire : owner.methods) {
                Type[] arguments = Type.getArgumentTypes(acquire.desc);
                Type result = Type.getReturnType(acquire.desc);
                if (!isStatic(acquire) || !isPublic(acquire) || arguments.length != 1
                        || arguments[0].getSort() != Type.OBJECT
                        || !arguments[0].getInternalName().equals(modelType)
                        || result.getSort() != Type.OBJECT) {
                    continue;
                }
                String providerType = result.getInternalName();
                ClassNode provider = classes.get(providerType);
                if (provider == null || (provider.access & Opcodes.ACC_INTERFACE) == 0) {
                    continue;
                }
                List<MethodNode> openMethods = provider.methods.stream()
                        .filter(method -> !isStatic(method) && isPublic(method)
                                && Type.getArgumentTypes(method.desc).length == 1
                                && Type.getArgumentTypes(method.desc)[0].getSort()
                                == Type.OBJECT
                                && Type.getArgumentTypes(method.desc)[0].getInternalName()
                                .equals(trackType)
                                && Type.getReturnType(method.desc).getSort() == Type.OBJECT
                                && isAudioStreamContract(classes,
                                Type.getReturnType(method.desc).getInternalName()))
                        .toList();
                if (openMethods.size() != 1) {
                    continue;
                }
                MethodNode open = openMethods.get(0);
                candidates.add(new ProviderSymbols(
                        new YsmCompatibilityMap.MethodSymbol(owner.name, acquire.name,
                                acquire.desc),
                        new YsmCompatibilityMap.MethodSymbol(provider.name, open.name,
                                open.desc)));
            }
        }
        List<ProviderSymbols> distinct = candidates.stream().distinct().toList();
        if (distinct.size() > 1) {
            throw new IOException("Expected one client audio stream provider, found "
                    + distinct.size());
        }
        return distinct.isEmpty() ? null : distinct.get(0);
    }

    private static boolean isAudioStreamContract(Map<String, ClassNode> classes,
            String streamType) {
        ClassNode stream = classes.get(streamType);
        return stream != null && (stream.access & Opcodes.ACC_INTERFACE) != 0
                && !stream.interfaces.isEmpty()
                && stream.methods.stream().anyMatch(method -> !isStatic(method)
                        && method.desc.equals("()Z"));
    }

    private static String clientModelType(Map<String, ClassNode> classes,
            YsmMethodSymbol lookup) throws IOException {
        ClassNode manager = requireClass(classes, lookup.owner(), "client model manager");
        MethodNode lookupMethod = manager.methods.stream()
                .filter(method -> method.name.equals(lookup.name())
                        && method.desc.equals(lookup.descriptor()))
                .findFirst().orElseThrow(() -> new IOException("Missing client model lookup"));
        List<String> modelTypes = new ArrayList<>();
        for (AbstractInsnNode instruction : lookupMethod.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.CHECKCAST
                    && classes.containsKey(type.desc) && !modelTypes.contains(type.desc)) {
                modelTypes.add(type.desc);
            }
        }
        if (modelTypes.size() != 1) {
            throw new IOException("Client model lookup must cast exactly one model type");
        }
        return modelTypes.get(0);
    }

    private static List<AbstractInsnNode> realInstructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) {
                result.add(instruction);
            }
        }
        return result;
    }

    private static MethodInsnNode nextInvocation(List<AbstractInsnNode> code, int start,
            int maximumDistance, Predicate<MethodInsnNode> predicate) {
        int end = Math.min(code.size(), start + maximumDistance);
        for (int index = start; index < end; index++) {
            if (code.get(index) instanceof MethodInsnNode call && predicate.test(call)) {
                return call;
            }
        }
        return null;
    }

    private static int nextMinecraftTypeInstruction(List<AbstractInsnNode> code, int start,
            int maximumDistance) {
        int end = Math.min(code.size(), start + maximumDistance);
        for (int index = start; index < end; index++) {
            if (code.get(index) instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.CHECKCAST
                    && type.desc.startsWith("net/minecraft/")) {
                return index;
            }
        }
        return -1;
    }

    private static int nextYsmTypeInstruction(List<AbstractInsnNode> code, int start,
            int maximumDistance, Map<String, ClassNode> classes) {
        int end = Math.min(code.size(), start + maximumDistance);
        for (int index = start; index < end; index++) {
            if (code.get(index) instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.CHECKCAST
                    && classes.containsKey(type.desc)) {
                return index;
            }
        }
        return -1;
    }

    private static void recoverServerManager(Map<String, ClassNode> classes,
            Map<Integer, String> packets,
            Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics) {
        YsmSymbolKey<?>[] keys = {YsmSymbols.SERVER_MODEL_RECEIVE,
                YsmSymbols.SERVER_MODEL_RELOAD, YsmSymbols.SERVER_MODEL_SYNC,
                YsmSymbols.SERVER_MODEL_MAP_GETTER, YsmSymbols.SERVER_MODEL_MAP_FIELD,
                YsmSymbols.SERVER_MODEL_STREAM_CALLBACK, YsmSymbols.SERVER_MODEL_PAYLOAD_FACTORY,
                YsmSymbols.SERVER_MODEL_PAYLOAD_CALLBACK};
        try {
            MethodReference receive = findUniqueInvokedMethod(classes.get(packets.get(2)),
                    reference -> reference.desc.equals(
                            "(Ljava/util/UUID;Ljava/nio/ByteBuffer;)V"),
                    "server model receive invocation");
            requireStatic(classes, receive, "server model receive");
            ClassNode manager = classes.get(receive.owner);
            YsmCompatibilityMap.MethodSymbol reload = findDeclared(manager,
                    method -> isStatic(method) && method.desc.equals(
                            "(Ljava/util/function/Consumer;Ljava/util/function/Consumer;)Z"));
            YsmCompatibilityMap.MethodSymbol sync = findDeclared(manager,
                    method -> isStatic(method) && method.desc.equals(
                            "([Ljava/util/UUID;[Ljava/lang/String;[Ljava/lang/String;"
                                    + "Ljava/lang/Object;)V"));
            validateServerSyncInvocation(manager, sync);
            YsmCompatibilityMap.MethodSymbol stream = findDeclared(manager,
                    method -> isStatic(method)
                            && method.desc.startsWith(
                                    "(Ljava/util/UUID;Ljava/nio/ByteBuffer;L")
                            && method.desc.endsWith(")Z"));
            YsmCompatibilityMap.MethodSymbol modelMap = findUniqueDeclared(manager,
                    method -> isStatic(method) && isPublic(method)
                            && method.desc.equals("()Ljava/util/Map;"),
                    "server model map getter");
            YsmCompatibilityMap.FieldSymbol mapField = findServerModelMapField(manager, modelMap);
            YsmCompatibilityMap.MethodSymbol factory = findUniqueDeclared(manager,
                    method -> isStatic(method) && method.desc.equals(
                            "(Ljava/nio/ByteBuffer;)Ljava/lang/Object;"),
                    "server model payload factory");
            String callbackDescriptor = stream == null ? null
                    : stream.descriptor().replace("Ljava/nio/ByteBuffer;", "Ljava/lang/Object;");
            YsmCompatibilityMap.MethodSymbol callback = findDeclared(manager,
                    method -> isStatic(method) && stream != null
                            && method.name.equals(stream.name())
                            && method.desc.equals(callbackDescriptor));
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_RECEIVE, symbol(receive));
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_RELOAD, reload);
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_SYNC, sync);
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_MAP_GETTER, modelMap);
            putField(values, diagnostics, YsmSymbols.SERVER_MODEL_MAP_FIELD, mapField);
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_STREAM_CALLBACK, stream);
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_PAYLOAD_FACTORY, factory);
            putMethod(values, diagnostics, YsmSymbols.SERVER_MODEL_PAYLOAD_CALLBACK, callback);
        } catch (IOException | RuntimeException exception) {
            fail(diagnostics, exception, keys);
        }
    }

    private void validatePacketSurface(Map<String, ClassNode> classes,
            Map<Integer, String> packets, int packetId) throws IOException {
        requireClass(classes, packets.get(packetId), "packet " + packetId);
        switch (packetId) {
            case 3 -> requireConstructor(classes, packets, 3,
                    descriptor -> descriptor.startsWith("([ILjava/lang/String;)"));
            case 4 -> requireConstructor(classes, packets, 4, descriptor -> {
                Type[] arguments = Type.getArgumentTypes(descriptor);
                return arguments.length == 5 && arguments[0].equals(Type.INT_TYPE)
                        && arguments[1].equals(Type.getType(String.class))
                        && arguments[2].equals(Type.getType(String.class))
                        && arguments[3].equals(Type.BOOLEAN_TYPE)
                        && arguments[4].getSort() == Type.OBJECT;
            });
            case 5, 17 -> requireFields(classes, packets, packetId,
                    packetId == 5 ? 2 : 1, Type.getDescriptor(String.class));
            case 7 -> requireFields(classes, packets, 7, 1,
                    Type.getDescriptor(String.class));
            case 18 -> requireFields(classes, packets, 18, 1,
                    "Lit/unimi/dsi/fastutil/floats/FloatArrayList;");
            case 23 -> requireFields(classes, packets, 23, 1, null);
            case 21 -> {
                ClassNode packet = requireClass(classes, packets.get(21), "packet 21");
                String descriptor = "L" + packet.name + ";";
                if (packet.methods.stream().noneMatch(method ->
                        method.desc.equals("(Ljava/lang/String;)" + descriptor))) {
                    throw new IOException("Packet 21 does not expose its animation state setter");
                }
                requirePacketCodecAndHandler(packet);
            }
            case 16, 22 -> validateEntityAppearancePacket(classes,
                    requireClass(classes, packets.get(packetId), "packet " + packetId));
            default -> { }
        }
    }

    private static void putMethod(Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics,
            YsmSymbolKey<YsmMethodSymbol> key, YsmCompatibilityMap.MethodSymbol symbol) {
        if (symbol == null) {
            diagnostics.putIfAbsent(key, "No structurally valid method candidate");
        } else {
            values.put(key, new YsmMethodSymbol(symbol.owner(), symbol.name(),
                    symbol.descriptor()));
        }
    }

    private static void putField(Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics,
            YsmSymbolKey<YsmFieldSymbol> key, YsmCompatibilityMap.FieldSymbol symbol) {
        if (symbol == null) {
            diagnostics.putIfAbsent(key, "No structurally valid field candidate");
        } else {
            values.put(key, new YsmFieldSymbol(symbol.owner(), symbol.name(),
                    symbol.descriptor()));
        }
    }

    private static void fail(Map<YsmSymbolKey<?>, String> diagnostics, Throwable failure,
            YsmSymbolKey<?>... keys) {
        String diagnostic = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        for (YsmSymbolKey<?> key : keys) diagnostics.putIfAbsent(key, diagnostic);
    }

    public record PartialAnalysis(Map<YsmSymbolKey<?>, YsmResolvedSymbol> symbols,
                                  Map<YsmSymbolKey<?>, String> diagnostics) {
    }

    CompleteFeedbackSymbols findCompleteFeedbackSymbols(
            Map<String, ClassNode> classes, Map<Integer, String> packets) throws IOException {
        ClassNode packet = requireClass(classes, packets.get(15), "packet 15");
        List<FieldNode> payloadFields = packet.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0
                        && Type.getType(field.desc).getSort() == Type.OBJECT)
                .toList();
        if (payloadFields.size() != 1) {
            throw new IOException("Expected packet 15 to contain one feedback payload field, found "
                    + payloadFields.size());
        }
        FieldNode payloadField = payloadFields.get(0);
        String feedbackName = Type.getType(payloadField.desc).getInternalName();
        ClassNode feedback = requireClass(classes, feedbackName, "packet 15 feedback payload");

        List<FieldNode> variableFields = feedback.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0
                        && field.desc.equals("Lit/unimi/dsi/fastutil/objects/Object2FloatArrayMap;"))
                .toList();
        if (variableFields.size() != 1) {
            throw new IOException("Expected feedback payload to contain one String-float variable Map, found "
                    + variableFields.size());
        }
        FieldNode variablesField = variableFields.get(0);
        boolean codecReadsVariables = feedback.methods.stream().anyMatch(method ->
                Type.getArgumentTypes(method.desc).length >= 2
                        && Type.getArgumentTypes(method.desc)[0].getDescriptor()
                        .equals("L" + feedback.name + ";")
                        && readsInstanceField(method, feedback.name, variablesField.name,
                        variablesField.desc));
        if (!codecReadsVariables) {
            throw new IOException("Feedback variable Map is not read by its packet codec");
        }

        List<FieldReference> targetReads = new ArrayList<>();
        for (MethodNode method : packet.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode invocation)
                        || !isEntityLookup(invocation.desc)) {
                    continue;
                }
                AbstractInsnNode cursor = previousMeaningful(instruction);
                for (int distance = 0; distance < 8 && cursor != null; distance++) {
                    if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.equals(feedback.name) && field.desc.equals("I")) {
                        targetReads.add(new FieldReference(field.name, field.desc));
                        break;
                    }
                    cursor = previousMeaningful(cursor);
                }
            }
        }
        List<FieldReference> uniqueTargetReads = targetReads.stream().distinct().toList();
        if (uniqueTargetReads.size() != 1) {
            throw new IOException("Expected feedback target entity ID to feed one entity lookup, found "
                    + uniqueTargetReads.size());
        }
        FieldReference target = uniqueTargetReads.get(0);
        boolean targetDeclared = feedback.fields.stream().anyMatch(field ->
                (field.access & Opcodes.ACC_STATIC) == 0 && field.name.equals(target.name())
                        && field.desc.equals(target.desc()));
        if (!targetDeclared) {
            throw new IOException("Feedback target entity ID field is not declared by " + feedback.name);
        }

        List<FieldNode> modelKeyFields = feedback.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0
                        && field.desc.equals("I") && !field.name.equals(target.name()))
                .toList();
        if (modelKeyFields.size() != 1) {
            throw new IOException("Expected one feedback model/storage key field, found "
                    + modelKeyFields.size());
        }
        FieldNode modelKey = modelKeyFields.get(0);
        boolean codecReadsModelKey = feedback.methods.stream().anyMatch(method ->
                Type.getArgumentTypes(method.desc).length >= 2
                        && Type.getArgumentTypes(method.desc)[0].getDescriptor()
                        .equals("L" + feedback.name + ";")
                        && readsInstanceField(method, feedback.name, modelKey.name, modelKey.desc));
        if (!codecReadsModelKey) {
            throw new IOException("Feedback model/storage key is not read by its packet codec");
        }

        return new CompleteFeedbackSymbols(
                new YsmCompatibilityMap.FieldSymbol(packet.name, payloadField.name, payloadField.desc),
                new YsmCompatibilityMap.FieldSymbol(feedback.name, modelKey.name, modelKey.desc),
                new YsmCompatibilityMap.FieldSymbol(feedback.name, target.name(), target.desc()),
                new YsmCompatibilityMap.FieldSymbol(feedback.name, variablesField.name,
                        variablesField.desc));
    }

    AnimationSymbols findAnimationSymbols(Map<String, ClassNode> classes,
                                                  Map<Integer, String> packets)
            throws IOException {
        ClassNode packet = requireClass(classes, packets.get(7), "packet 7");
        List<FieldNode> integers = packet.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0 && field.desc.equals("I"))
                .toList();
        List<FieldNode> strings = packet.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0
                        && field.desc.equals("Ljava/lang/String;"))
                .toList();
        if (integers.size() != 2 || strings.size() != 1) {
            throw new IOException("Packet 7 must contain two int fields and one String field");
        }
        FieldReference target = findEntityTargetField(packet, packet.name, "animation");
        FieldNode index = integers.stream().filter(field -> !field.name.equals(target.name()))
                .findFirst().orElseThrow(() -> new IOException("Missing animation index field"));
        FieldNode pack = strings.get(0);
        String self = "L" + packet.name + ";";
        boolean writerReadsFields = packet.methods.stream().anyMatch(method ->
                isStatic(method) && Type.getArgumentTypes(method.desc).length == 2
                        && Type.getArgumentTypes(method.desc)[0].getDescriptor().equals(self)
                        && Type.getReturnType(method.desc).equals(Type.VOID_TYPE)
                        && readsInstanceField(method, packet.name, index.name, index.desc)
                        && readsInstanceField(method, packet.name, pack.name, pack.desc)
                        && readsInstanceField(method, packet.name, target.name(), target.desc()));
        if (!writerReadsFields) {
            throw new IOException("Packet 7 codec does not serialize index, pack and target");
        }

        List<MethodInsnNode> invocations = packet.methods.stream()
                .flatMap(method -> invocationNodes(method).stream()).toList();
        List<MethodInsnNode> orderedNameCandidates = invocations.stream()
                .filter(invocation -> invocation.desc.equals("(I)Ljava/lang/Object;")
                        && classes.containsKey(invocation.owner))
                .filter(invocation -> invocations.stream().anyMatch(other ->
                        other.owner.equals(invocation.owner) && other.desc.equals("()I")))
                .distinct().toList();
        if (orderedNameCandidates.size() != 1) {
            throw new IOException("Expected one ordered animation name accessor, found "
                    + orderedNameCandidates.size());
        }
        MethodInsnNode orderedName = orderedNameCandidates.get(0);
        MethodInsnNode orderedCount = uniqueInvocation(invocations,
                invocation -> invocation.owner.equals(orderedName.owner)
                        && invocation.desc.equals("()I"), "ordered animation count getter");
        MethodInsnNode defaultAnimations = uniqueInvocation(invocations,
                invocation -> Type.getReturnType(invocation.desc).getSort() == Type.OBJECT
                        && Type.getReturnType(invocation.desc).getInternalName()
                        .equals(orderedName.owner), "default animation getter");
        MethodInsnNode animationPacks = uniqueInvocation(invocations,
                invocation -> invocation.owner.equals(defaultAnimations.owner)
                        && invocation.desc.equals("()Ljava/util/Map;"),
                "animation pack map getter");
        MethodInsnNode dataProperties = uniqueInvocation(invocations,
                invocation -> Type.getReturnType(invocation.desc).getSort() == Type.OBJECT
                        && Type.getReturnType(invocation.desc).getInternalName()
                        .equals(defaultAnimations.owner), "server model properties getter");
        MethodInsnNode containerData = uniqueInvocation(invocations,
                invocation -> Type.getReturnType(invocation.desc).getSort() == Type.OBJECT
                        && Type.getReturnType(invocation.desc).getInternalName()
                        .equals(dataProperties.owner), "server model data getter");
        MethodInsnNode storageKey = findStorageKeyGetter(classes, dataProperties.owner);

        return new AnimationSymbols(
                fieldSymbol(packet, index), fieldSymbol(packet, pack),
                new YsmCompatibilityMap.FieldSymbol(packet.name, target.name(), target.desc()),
                methodSymbol(containerData), methodSymbol(dataProperties), methodSymbol(storageKey),
                methodSymbol(defaultAnimations), methodSymbol(animationPacks),
                methodSymbol(orderedCount), methodSymbol(orderedName));
    }

    PlayerStateSymbols findPlayerStateSymbols(Map<String, ClassNode> classes,
                                               Map<Integer, String> packets)
            throws IOException {
        return findPlayerStateSymbols(classes, packets, true);
    }

    private PlayerStateSymbols findPlayerStateSymbols(Map<String, ClassNode> classes,
                                                       Map<Integer, String> packets,
                                                       boolean requirePlayerConstructor)
            throws IOException {
        ClassNode packet = requireClass(classes, packets.get(21), "packet 21");
        String self = "L" + packet.name + ";";
        YsmCompatibilityMap.MethodSymbol animation = findUniqueDeclared(packet,
                method -> !isStatic(method)
                        && method.desc.equals("(Ljava/lang/String;)" + self)
                        && containsInteger(method, 2048), "packet 21 animation setter");
        YsmCompatibilityMap.MethodSymbol roaming = findUniqueDeclared(packet,
                method -> !isStatic(method)
                        && method.desc.startsWith("(ILit/unimi/dsi/fastutil/objects/Object2Float")
                        && method.desc.endsWith(")" + self)
                        && containsInteger(method, 4096), "packet 21 roaming setter");

        FieldNode flags = uniqueField(packet, field -> (field.access & Opcodes.ACC_STATIC) == 0
                && field.desc.equals("S"), "packet 21 flags field");
        MethodNode writer = uniqueMethod(packet, method -> isStatic(method)
                        && method.desc.startsWith("(" + self + "Lnet/minecraft/")
                        && Type.getArgumentTypes(method.desc).length == 2
                        && accessesField(method, Opcodes.GETFIELD, packet.name, flags)
                        && Type.getReturnType(method.desc).equals(Type.VOID_TYPE),
                "packet 21 codec writer");
        MethodNode decoder = uniqueMethod(packet, method -> isStatic(method)
                        && method.desc.startsWith("(Lnet/minecraft/")
                        && Type.getArgumentTypes(method.desc).length == 1
                        && Type.getReturnType(method.desc).getDescriptor().equals(self),
                "packet 21 codec decoder");
        MethodNode clientHandler = uniqueMethod(packet,
                method -> isClientHandler(method, self), "packet 21 client handler");
        FieldNode decodedRoaming = uniqueField(packet,
                field -> (field.access & Opcodes.ACC_STATIC) == 0
                        && field.desc.equals("Lit/unimi/dsi/fastutil/ints/Int2FloatMap;"),
                "packet 21 decoded roaming field");

        MethodInsnNode roamingNameHasher = uniqueInvocation(invocationNodes(decoder),
                invocation -> invocation.getOpcode() == Opcodes.INVOKESTATIC
                        && invocation.desc.equals("(Ljava/lang/String;)I"),
                "packet 21 roaming name hasher");
        if (!accessesField(writer, Opcodes.GETFIELD, packet.name, flags)
                || !accessesField(decoder, Opcodes.PUTFIELD, packet.name, flags)
                || !accessesField(decoder, Opcodes.PUTFIELD, packet.name, decodedRoaming)
                || !containsInteger(writer, 4096) || !containsInteger(decoder, 4096)
                || !containsType(decoder,
                        "it/unimi/dsi/fastutil/ints/Int2FloatOpenHashMap")) {
            throw new IOException("Packet 21 full-state codec contract is unsupported");
        }

        MethodInsnNode fullInitializer = uniqueInvocation(packet.methods.stream()
                        .flatMap(method -> invocationNodes(method).stream()).toList(),
                invocation -> invocation.desc.equals(
                        "(ILit/unimi/dsi/fastutil/ints/Int2FloatOpenHashMap;)V"),
                "packet 21 full roaming initializer");
        ClassNode capability = requireClass(classes, fullInitializer.owner,
                "player state capability");
        if (requirePlayerConstructor && capability.methods.stream().noneMatch(method ->
                method.name.equals("<init>") && method.desc.equals(
                        "(Lnet/minecraft/world/entity/player/Player;)V"))) {
            throw new IOException("Player state capability has no Player constructor");
        }
        RoamingProviderSymbols provider = findRoamingProviderSymbols(
                classes, capability, fullInitializer);
        MethodNode initializerCaller = packet.methods.stream()
                .filter(method -> hasInvocation(method, invocation -> invocation.owner.equals(
                        fullInitializer.owner) && invocation.name.equals(fullInitializer.name)
                        && invocation.desc.equals(fullInitializer.desc)))
                .findFirst().orElseThrow(() -> new IOException(
                        "Packet 21 full roaming initializer is not called"));
        boolean fullFlagCheck = invocationNodes(initializerCaller).stream()
                .filter(invocation -> invocation.owner.equals(packet.name)
                        && invocation.desc.equals("()Z"))
                .map(invocation -> declaredMethod(packet, invocation.name, invocation.desc))
                .filter(java.util.Objects::nonNull)
                .anyMatch(method -> containsInteger(method, 1));
        if (!containsInteger(initializerCaller, 4096)
                || !accessesField(initializerCaller, Opcodes.GETFIELD, packet.name, decodedRoaming)
                || !fullFlagCheck || !reachable(packet, clientHandler, initializerCaller)) {
            throw new IOException("Packet 21 client handler does not initialize full roaming state");
        }
        WheelStateSymbols wheel = findWheelStateSymbols(
                classes, capability, initializerCaller);

        return new PlayerStateSymbols(animation, roaming, methodSymbol(packet, writer),
                methodSymbol(packet, decoder), methodSymbol(packet, clientHandler),
                fieldSymbol(packet, flags), fieldSymbol(packet, decodedRoaming),
                capability.name, wheel.activeAnimationGetter(),
                wheel.animationPlayingGetter(), wheel.animationStopPacketFactory(),
                wheel.animationStopSender(), provider.providerGetter(), provider.valueGetter(),
                provider.valueSetter(), methodSymbol(roamingNameHasher),
                methodSymbol(fullInitializer));
    }

    private static WheelStateSymbols findWheelStateSymbols(
            Map<String, ClassNode> classes, ClassNode capability,
            MethodNode stateApplicator) throws IOException {
        Set<String> hierarchy = new LinkedHashSet<>();
        ClassNode cursor = capability;
        while (cursor != null && hierarchy.add(cursor.name)) {
            cursor = classes.get(cursor.superName);
        }

        MethodInsnNode requestInvocation = uniqueInvocation(invocationNodes(stateApplicator),
                invocation -> hierarchy.contains(invocation.owner)
                        && invocation.desc.equals("(Ljava/lang/String;)V"),
                "player roulette animation request");
        OwnedMethod request = resolveHierarchyMethod(classes, requestInvocation.owner,
                requestInvocation.name, requestInvocation.desc);
        if (request == null) {
            throw new IOException("Player roulette animation request is not declared");
        }

        List<YsmCompatibilityMap.FieldSymbol> animationFields = instanceFieldWrites(request.method())
                .stream().filter(field -> field.descriptor().equals("Ljava/lang/String;"))
                .distinct().toList();
        if (animationFields.size() != 1) {
            throw new IOException("Expected one roulette animation name field, found "
                    + animationFields.size());
        }
        YsmCompatibilityMap.FieldSymbol animationField = animationFields.get(0);
        List<YsmCompatibilityMap.FieldSymbol> requestFlags = instanceFieldWrites(request.method())
                .stream().filter(field -> field.descriptor().equals("Z"))
                .distinct().toList();

        List<OwnedMethod> clearMethods = invocationNodes(stateApplicator).stream()
                .filter(invocation -> hierarchy.contains(invocation.owner)
                        && invocation.desc.equals("()V"))
                .map(invocation -> resolveHierarchyMethod(classes, invocation.owner,
                        invocation.name, invocation.desc))
                .filter(java.util.Objects::nonNull)
                .filter(method -> instanceFieldWrites(method.method()).stream()
                        .anyMatch(field -> field.descriptor().equals("Z")
                                && requestFlags.contains(field)))
                .distinct().toList();
        if (clearMethods.size() != 1) {
            throw new IOException("Expected one roulette playing flag cleared by packet state, found "
                    + clearMethods.size());
        }
        OwnedMethod clear = clearMethods.get(0);
        List<YsmCompatibilityMap.FieldSymbol> clearedFlags = instanceFieldWrites(clear.method())
                .stream().filter(field -> field.descriptor().equals("Z")
                        && requestFlags.contains(field)).distinct().toList();
        if (clearedFlags.size() != 1) {
            throw new IOException("Expected one roulette playing flag in the clear method, found "
                    + clearedFlags.size());
        }
        YsmCompatibilityMap.FieldSymbol playingField = clearedFlags.get(0);

        ClassNode animationOwner = requireClass(classes, animationField.owner(),
                "roulette animation name owner");
        MethodNode animationGetter = uniqueMethod(animationOwner,
                method -> !isStatic(method) && isPublic(method)
                        && method.desc.equals("()Ljava/lang/String;")
                        && accessesInstanceField(method, Opcodes.GETFIELD, animationField),
                "roulette animation name getter");
        ClassNode playingOwner = requireClass(classes, playingField.owner(),
                "roulette playing flag owner");
        MethodNode playingGetter = uniqueMethod(playingOwner,
                method -> !isStatic(method) && isPublic(method)
                        && method.desc.equals("()Z")
                        && accessesInstanceField(method, Opcodes.GETFIELD, playingField),
                "roulette animation playing getter");
        List<OwnedMethod> completionMethods = hierarchy.stream()
                .map(classes::get).filter(java.util.Objects::nonNull)
                .flatMap(owner -> owner.methods.stream().map(method ->
                        new OwnedMethod(owner, method)))
                .filter(method -> !isStatic(method.method())
                        && method.method().desc.equals("(FZ)V")
                        && hasInvocation(method.method(), invocation ->
                        invocation.owner.equals(clear.owner().name)
                                && invocation.name.equals(clear.method().name)
                                && invocation.desc.equals(clear.method().desc)))
                .toList();
        if (completionMethods.size() != 1) {
            throw new IOException("Expected one roulette completion method, found "
                    + completionMethods.size());
        }
        List<MethodInsnNode> completionCalls = invocationNodes(
                completionMethods.get(0).method());
        MethodInsnNode stopFactory = uniqueInvocation(completionCalls,
                invocation -> invocation.getOpcode() == Opcodes.INVOKESTATIC
                        && Type.getArgumentTypes(invocation.desc).length == 0
                        && Type.getReturnType(invocation.desc).getSort() == Type.OBJECT,
                "roulette stop packet factory");
        MethodInsnNode stopSender = uniqueInvocation(completionCalls,
                invocation -> invocation.getOpcode() == Opcodes.INVOKESTATIC
                        && invocation.desc.equals("(Ljava/lang/Object;)V"),
                "roulette stop packet sender");
        return new WheelStateSymbols(methodSymbol(animationOwner, animationGetter),
                methodSymbol(playingOwner, playingGetter), methodSymbol(stopFactory),
                methodSymbol(stopSender));
    }

    private static OwnedMethod resolveHierarchyMethod(Map<String, ClassNode> classes,
            String ownerName, String name, String descriptor) {
        ClassNode owner = classes.get(ownerName);
        Set<String> visited = new HashSet<>();
        while (owner != null && visited.add(owner.name)) {
            MethodNode method = declaredMethod(owner, name, descriptor);
            if (method != null) {
                return new OwnedMethod(owner, method);
            }
            owner = classes.get(owner.superName);
        }
        return null;
    }

    private static List<YsmCompatibilityMap.FieldSymbol> instanceFieldWrites(MethodNode method) {
        List<YsmCompatibilityMap.FieldSymbol> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD) {
                result.add(new YsmCompatibilityMap.FieldSymbol(
                        field.owner, field.name, field.desc));
            }
        }
        return result;
    }

    private static boolean accessesInstanceField(MethodNode method, int opcode,
            YsmCompatibilityMap.FieldSymbol field) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode access && access.getOpcode() == opcode
                    && access.owner.equals(field.owner()) && access.name.equals(field.name())
                    && access.desc.equals(field.descriptor())) {
                return true;
            }
        }
        return false;
    }

    private static RoamingProviderSymbols findRoamingProviderSymbols(
            Map<String, ClassNode> classes, ClassNode capability,
            MethodInsnNode fullInitializer) throws IOException {
        MethodNode initializer = declaredMethod(capability, fullInitializer.name,
                fullInitializer.desc);
        if (initializer == null) {
            throw new IOException("Player state full roaming initializer is not declared");
        }
        List<FieldReference> providerWrites = new ArrayList<>();
        for (AbstractInsnNode instruction : initializer.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(capability.name)
                    && Type.getType(field.desc).getSort() == Type.OBJECT
                    && classes.containsKey(Type.getType(field.desc).getInternalName())) {
                providerWrites.add(new FieldReference(field.name, field.desc));
            }
        }
        List<FieldReference> uniqueWrites = providerWrites.stream().distinct().toList();
        if (uniqueWrites.size() != 1) {
            throw new IOException("Expected one current roaming provider field, found "
                    + uniqueWrites.size());
        }
        FieldReference providerField = uniqueWrites.get(0);
        FieldNode declaredProvider = capability.fields.stream()
                .filter(field -> field.name.equals(providerField.name())
                        && field.desc.equals(providerField.desc()))
                .findFirst().orElseThrow(() -> new IOException(
                        "Current roaming provider field is not declared"));
        MethodNode providerGetter = uniqueMethod(capability,
                method -> !isStatic(method) && isPublic(method)
                        && Type.getArgumentTypes(method.desc).length == 0
                        && Type.getReturnType(method.desc).getDescriptor()
                        .equals(providerField.desc())
                        && accessesField(method, Opcodes.GETFIELD, capability.name,
                        declaredProvider), "current roaming provider getter");
        ClassNode provider = requireClass(classes,
                Type.getReturnType(providerGetter.desc).getInternalName(),
                "roaming variable provider");
        MethodNode valueGetter = uniqueMethod(provider,
                method -> !isStatic(method) && isPublic(method)
                        && method.desc.equals("(I)Ljava/lang/Object;"),
                "roaming variable value getter");
        MethodNode valueSetter = uniqueMethod(provider,
                method -> !isStatic(method) && isPublic(method)
                        && method.desc.equals("(ILjava/lang/Object;)V"),
                "roaming variable value setter");
        return new RoamingProviderSymbols(methodSymbol(capability, providerGetter),
                methodSymbol(provider, valueGetter), methodSymbol(provider, valueSetter));
    }

    YsmCompatibilityMap.MethodSymbol findAnimationRouletteConfigurationExpression(
            List<ClassNode> classes, String loader) throws IOException {
        String screen = profile.loader(loader).screen();
        Map<String, ClassNode> classesByName = new HashMap<>();
        classes.forEach(node -> classesByName.put(node.name, node));
        List<OwnedMethod> candidates = new ArrayList<>();
        for (ClassNode owner : classes) {
            if (!screen.equals(owner.superName)) {
                continue;
            }
            for (MethodNode method : owner.methods) {
                if (isStatic(method) || (method.access & Opcodes.ACC_PRIVATE) == 0
                        || !method.desc.equals(
                        "(Ljava/lang/String;Ljava/util/function/Consumer;)V")) {
                    continue;
                }
                if (isConfigurationExpressionExecutor(owner, method, classesByName)) {
                    candidates.add(new OwnedMethod(owner, method));
                }
            }
        }
        List<OwnedMethod> distinct = candidates.stream().distinct().toList();
        if (distinct.size() != 1) {
            throw new IOException("Expected one animation roulette configuration expression "
                    + "executor, found " + distinct.size());
        }
        OwnedMethod result = distinct.get(0);
        return methodSymbol(result.owner(), result.method());
    }

    private static boolean isConfigurationExpressionExecutor(ClassNode owner, MethodNode method,
            Map<String, ClassNode> classes) {
        for (MethodInsnNode parser : invocationNodes(method)) {
            Type expressionType = Type.getReturnType(parser.desc);
            if (parser.getOpcode() != Opcodes.INVOKESTATIC
                    || !parser.desc.startsWith("(Ljava/lang/String;)")
                    || Type.getArgumentTypes(parser.desc).length != 1
                    || expressionType.getSort() != Type.OBJECT
                    || !classes.containsKey(expressionType.getInternalName())) {
                continue;
            }
            String executorDescriptor = '(' + expressionType.getDescriptor()
                    + "ZZLjava/util/function/Consumer;)V";
            for (MethodInsnNode executor : invocationNodes(method)) {
                if (executor.getOpcode() == Opcodes.INVOKESTATIC
                        || !executor.desc.equals(executorDescriptor)) {
                    continue;
                }
                String ownerDescriptor = 'L' + executor.owner + ';';
                boolean readsExecutorOwner = false;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.equals(owner.name)
                            && field.desc.equals(ownerDescriptor)) {
                        readsExecutorOwner = true;
                        break;
                    }
                }
                if (readsExecutorOwner) {
                    return true;
                }
            }
        }
        return false;
    }

    private void recoverAnimationRoulette(List<ClassNode> classes, String loader,
            Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
            Map<YsmSymbolKey<?>, String> diagnostics) {
        try {
            putMethod(values, diagnostics,
                    YsmSymbols.ANIMATION_ROULETTE_CONFIGURATION_EXPRESSION,
                    findAnimationRouletteConfigurationExpression(classes, loader));
        } catch (IOException exception) {
            fail(diagnostics, exception,
                    YsmSymbols.ANIMATION_ROULETTE_CONFIGURATION_EXPRESSION);
        }
    }

    private static MethodNode uniqueMethod(ClassNode owner, Predicate<MethodNode> filter,
                                           String label) throws IOException {
        List<MethodNode> matches = owner.methods.stream().filter(filter).toList();
        if (matches.size() != 1) {
            throw new IOException("Expected one " + label + " in " + owner.name
                    + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static FieldNode uniqueField(ClassNode owner, Predicate<FieldNode> filter,
                                         String label) throws IOException {
        List<FieldNode> matches = owner.fields.stream().filter(filter).toList();
        if (matches.size() != 1) {
            throw new IOException("Expected one " + label + " in " + owner.name
                    + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static YsmCompatibilityMap.MethodSymbol methodSymbol(ClassNode owner,
                                                                  MethodNode method) {
        return new YsmCompatibilityMap.MethodSymbol(owner.name, method.name, method.desc);
    }

    private static boolean accessesField(MethodNode method, int opcode, String owner,
                                         FieldNode field) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode access && access.getOpcode() == opcode
                    && access.owner.equals(owner) && access.name.equals(field.name)
                    && access.desc.equals(field.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsType(MethodNode method, String internalName) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type && type.desc.equals(internalName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInvocation(MethodNode method, Predicate<MethodInsnNode> filter) {
        return invocationNodes(method).stream().anyMatch(filter);
    }

    private static MethodNode declaredMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(method -> method.name.equals(name)
                && method.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static boolean reachable(ClassNode owner, MethodNode start, MethodNode target) {
        ArrayDeque<MethodNode> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            MethodNode method = pending.removeFirst();
            String key = method.name + method.desc;
            if (!visited.add(key)) {
                continue;
            }
            if (method == target) {
                return true;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode invocation
                        && invocation.owner.equals(owner.name)) {
                    MethodNode next = declaredMethod(owner, invocation.name, invocation.desc);
                    if (next != null) {
                        pending.add(next);
                    }
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle
                                && handle.getOwner().equals(owner.name)) {
                            MethodNode next = declaredMethod(owner, handle.getName(), handle.getDesc());
                            if (next != null) {
                                pending.add(next);
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private FieldReference findEntityTargetField(ClassNode packet, String fieldOwner,
                                                        String label) throws IOException {
        List<FieldReference> targetReads = new ArrayList<>();
        for (MethodNode method : packet.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode invocation)
                        || !isEntityLookup(invocation.desc)) {
                    continue;
                }
                AbstractInsnNode cursor = previousMeaningful(instruction);
                for (int distance = 0; distance < 12 && cursor != null; distance++) {
                    if (cursor instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.equals(fieldOwner) && field.desc.equals("I")) {
                        targetReads.add(new FieldReference(field.name, field.desc));
                        break;
                    }
                    cursor = previousMeaningful(cursor);
                }
            }
        }
        List<FieldReference> unique = targetReads.stream().distinct().toList();
        if (unique.size() != 1) {
            throw new IOException("Expected one " + label + " target entity field, found "
                    + unique.size());
        }
        return unique.get(0);
    }

    private static MethodInsnNode findStorageKeyGetter(Map<String, ClassNode> classes,
                                                        String modelDataOwner) throws IOException {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (ClassNode owner : classes.values()) {
            for (MethodNode method : owner.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode compute)
                            || !compute.name.equals("computeIfAbsent")
                            || !compute.owner.contains("Int2Reference")) {
                        continue;
                    }
                    AbstractInsnNode cursor = previousMeaningful(instruction);
                    for (int distance = 0; distance < 24 && cursor != null; distance++) {
                        if (cursor instanceof MethodInsnNode invocation
                                && invocation.owner.equals(modelDataOwner)
                                && invocation.desc.equals("()I")) {
                            matches.add(invocation);
                            break;
                        }
                        cursor = previousMeaningful(cursor);
                    }
                }
            }
        }
        List<MethodInsnNode> unique = matches.stream()
                .collect(java.util.stream.Collectors.toMap(
                        invocation -> invocation.owner + invocation.name + invocation.desc,
                        invocation -> invocation, (left, right) -> left,
                        LinkedHashMap::new)).values().stream().toList();
        if (unique.size() != 1) {
            throw new IOException("Expected one server model storage key getter, found "
                    + unique.size());
        }
        return unique.get(0);
    }

    private static List<MethodInsnNode> invocationNodes(MethodNode method) {
        List<MethodInsnNode> invocations = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invocation) {
                invocations.add(invocation);
            }
        }
        return invocations;
    }

    private static MethodInsnNode uniqueInvocation(List<MethodInsnNode> invocations,
                                                    Predicate<MethodInsnNode> filter,
                                                    String label) throws IOException {
        List<MethodInsnNode> unique = invocations.stream().filter(filter)
                .collect(java.util.stream.Collectors.toMap(
                        invocation -> invocation.owner + invocation.name + invocation.desc,
                        invocation -> invocation, (left, right) -> left,
                        LinkedHashMap::new)).values().stream().toList();
        if (unique.size() != 1) {
            throw new IOException("Expected one " + label + ", found " + unique.size());
        }
        return unique.get(0);
    }

    private static boolean containsInteger(MethodNode method, int value) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (java.util.Objects.equals(integerValue(instruction), value)) {
                return true;
            }
        }
        return false;
    }

    private static YsmCompatibilityMap.FieldSymbol fieldSymbol(ClassNode owner, FieldNode field) {
        return new YsmCompatibilityMap.FieldSymbol(owner.name, field.name, field.desc);
    }

    private static YsmCompatibilityMap.MethodSymbol methodSymbol(MethodInsnNode method) {
        return new YsmCompatibilityMap.MethodSymbol(method.owner, method.name, method.desc);
    }

    private boolean isEntityLookup(String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (arguments.length != 1 || !arguments[0].equals(Type.INT_TYPE)) {
            return false;
        }
        Type result = Type.getReturnType(descriptor);
        return result.getSort() == Type.OBJECT
                && entityTypes.contains(result.getInternalName());
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static boolean readsInstanceField(MethodNode method, String owner, String name,
                                              String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(owner)
                    && field.name.equals(name) && field.desc.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private ServerSyncResultSymbols findServerSyncResultSymbols(List<ClassNode> classes)
            throws IOException {
        List<ClassNode> candidates = classes.stream().filter(owner -> owner.methods.stream()
                .anyMatch(method -> method.name.equals("<init>") && method.desc.equals(
                        "(ZLjava/lang/Object;[Ljava/util/UUID;Ljava/util/Map;)V"))).toList();
        if (candidates.size() != 1) {
            throw new IOException("Expected one server sync result class, found "
                    + candidates.size());
        }
        ClassNode result = candidates.get(0);
        YsmCompatibilityMap.MethodSymbol success = findUniqueDeclared(result,
                method -> !isStatic(method) && isPublic(method) && method.desc.equals("()Z"),
                "server sync result success getter");
        YsmCompatibilityMap.MethodSymbol error = findUniqueDeclared(result,
                method -> !isStatic(method) && isPublic(method)
                        && Type.getArgumentTypes(method.desc).length == 0
                        && Type.getReturnType(method.desc).getSort() == Type.OBJECT
                        && componentTypes.contains(
                        Type.getReturnType(method.desc).getInternalName()),
                "server sync result error getter");
        return new ServerSyncResultSymbols(success, error);
    }

    private static YsmCompatibilityMap.FieldSymbol findServerModelMapField(
            ClassNode serverManager, YsmCompatibilityMap.MethodSymbol getter) throws IOException {
        MethodNode method = findMethod(serverManager, getter);
        List<FieldInsnNode> reads = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(serverManager.name)
                    && field.desc.equals("Ljava/util/Map;")) {
                reads.add(field);
            }
        }
        if (reads.size() != 1) {
            throw new IOException("Expected server model map getter to read one static Map field, found "
                    + reads.size());
        }
        FieldInsnNode read = reads.get(0);
        var declared = serverManager.fields.stream()
                .filter(field -> field.name.equals(read.name) && field.desc.equals(read.desc))
                .findFirst().orElseThrow(() -> new IOException(
                        "Server model map field is not declared by " + serverManager.name));
        if ((declared.access & Opcodes.ACC_STATIC) == 0
                || (declared.access & Opcodes.ACC_FINAL) != 0) {
            throw new IOException("Server model map field must be replaceable and static");
        }
        return new YsmCompatibilityMap.FieldSymbol(serverManager.name, read.name, read.desc);
    }

    private static YsmCompatibilityMap.FieldSymbol findConfigField(
            List<ClassNode> classes, String literal) throws IOException {
        List<YsmCompatibilityMap.FieldSymbol> matches = new ArrayList<>();
        for (ClassNode owner : classes) {
            for (MethodNode method : owner.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof LdcInsnNode ldc) || !literal.equals(ldc.cst)) {
                        continue;
                    }
                    AbstractInsnNode cursor = instruction;
                    for (int distance = 0; distance < 40 && cursor != null; distance++) {
                        if (cursor instanceof FieldInsnNode field
                                && field.getOpcode() == Opcodes.PUTSTATIC
                                && field.owner.equals(owner.name)) {
                            matches.add(new YsmCompatibilityMap.FieldSymbol(field.owner,
                                    field.name, field.desc));
                            break;
                        }
                        cursor = cursor.getNext();
                    }
                }
            }
        }
        List<YsmCompatibilityMap.FieldSymbol> unique = matches.stream().distinct().toList();
        if (unique.size() != 1) {
            throw new IOException("Expected one " + literal + " config field, found "
                    + unique.size());
        }
        return unique.get(0);
    }

    private static YsmCompatibilityMap.MethodSymbol findClientReset(ClassNode clientManager)
            throws IOException {
        YsmCompatibilityMap.MethodSymbol nativeInput = findUniqueDeclared(clientManager,
                method -> isStatic(method) && isNative(method)
                        && method.desc.equals("(Ljava/nio/ByteBuffer;)V"),
                "client native model input");
        return findUniqueDeclared(clientManager,
                method -> isStatic(method) && isPublic(method) && !isNative(method)
                        && method.desc.equals("()V")
                        && invokesWithImmediateNull(method, clientManager.name,
                        nativeInput.name(), nativeInput.descriptor()),
                "client native model reset");
    }

    private static boolean invokesWithImmediateNull(MethodNode method, String owner, String name,
                                                    String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode invocation)
                    || !invocation.owner.equals(owner) || !invocation.name.equals(name)
                    || !invocation.desc.equals(descriptor)) {
                continue;
            }
            AbstractInsnNode previous = instruction.getPrevious();
            while (previous != null && previous.getOpcode() < 0) {
                previous = previous.getPrevious();
            }
            if (previous != null && previous.getOpcode() == Opcodes.ACONST_NULL) {
                return true;
            }
        }
        return false;
    }

    private static YsmCompatibilityMap.MethodSymbol findClientFlushPending(
            ClassNode clientManager, YsmCompatibilityMap.MethodSymbol modelGetter,
            YsmCompatibilityMap.MethodSymbol pendingGetter) throws IOException {
        MethodNode modelMethod = findMethod(clientManager, modelGetter);
        MethodNode pendingMethod = findMethod(clientManager, pendingGetter);
        String modelField = fieldReads(modelMethod, clientManager.name).stream()
                .filter(field -> field.desc.equals("Ljava/util/Map;"))
                .map(FieldReference::name).findFirst()
                .orElseThrow(() -> new IOException("Missing client model map field"));
        String queueField = fieldReads(pendingMethod, clientManager.name).stream()
                .filter(field -> field.desc.equals("Ljava/util/concurrent/ConcurrentLinkedQueue;"))
                .map(FieldReference::name).findFirst()
                .orElseThrow(() -> new IOException("Missing client pending model queue field"));
        return findUniqueDeclared(clientManager,
                method -> isStatic(method) && isPublic(method) && !isNative(method)
                        && method.desc.equals("()V")
                        && fieldReads(method, clientManager.name).stream()
                        .anyMatch(field -> field.name.equals(queueField))
                        && fieldWrites(method, clientManager.name).stream()
                        .anyMatch(field -> field.name.equals(modelField))
                        && invokes(method, "java/util/concurrent/ConcurrentLinkedQueue",
                        "poll", "()Ljava/lang/Object;"),
                "client pending model flush");
    }

    private static void validateServerSyncInvocation(
            ClassNode serverManager, YsmCompatibilityMap.MethodSymbol sync) throws IOException {
        if (serverManager == null || sync == null) {
            throw new IOException("Missing server model sync method");
        }
        boolean valid = serverManager.methods.stream().anyMatch(method ->
                invokes(method, serverManager.name, sync.name(), sync.descriptor())
                        && createsOneElementArray(method, "java/util/UUID")
                        && createsOneElementArray(method, "java/lang/String")
                        && invokes(method, "com/mojang/authlib/GameProfile", "getName",
                        "()Ljava/lang/String;"));
        if (!valid) {
            throw new IOException("Server model sync wrapper does not expose UUID/name arrays "
                    + "backed by GameProfile#getName");
        }
    }

    private static boolean createsOneElementArray(MethodNode method, String type) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof TypeInsnNode array)
                    || array.getOpcode() != Opcodes.ANEWARRAY || !array.desc.equals(type)) {
                continue;
            }
            AbstractInsnNode previous = instruction.getPrevious();
            while (previous != null && previous.getOpcode() < 0) {
                previous = previous.getPrevious();
            }
            if (previous != null && Integer.valueOf(1).equals(integerValue(previous))) {
                return true;
            }
        }
        return false;
    }

    static YsmCompatibilityMap.MethodSymbol findClientModelLookup(ClassNode clientManager)
            throws IOException {
        if (clientManager == null) {
            throw new IOException("Missing client model manager");
        }
        return findUniqueDeclared(clientManager,
                method -> isStatic(method) && isPublic(method) && !isNative(method)
                        && method.desc.equals(
                        "(Ljava/lang/String;)Ljava/util/Optional;")
                        && fieldReads(method, clientManager.name).stream()
                        .anyMatch(field -> field.desc.equals("Ljava/util/Map;")),
                "client model lookup");
    }

    private static YsmCompatibilityMap.MethodSymbol findClientModelMapGetter(
            ClassNode clientManager, YsmCompatibilityMap.MethodSymbol modelLookup)
            throws IOException {
        MethodNode lookup = findMethod(clientManager, modelLookup);
        List<FieldReference> modelFields = fieldReads(lookup, clientManager.name).stream()
                .filter(field -> field.desc.equals("Ljava/util/Map;"))
                .distinct().toList();
        if (modelFields.size() != 1) {
            throw new IOException("Client model lookup must read exactly one Map field");
        }
        String modelField = modelFields.get(0).name();
        return findUniqueDeclared(clientManager,
                method -> isStatic(method) && isPublic(method)
                        && method.desc.equals("()Ljava/util/Map;")
                        && fieldReads(method, clientManager.name).stream()
                        .anyMatch(field -> field.name.equals(modelField)),
                "client model map getter");
    }

    private static YsmCompatibilityMap.MethodSymbol findClientModelCatalogDeltaCallback(
            ClassNode clientManager, YsmCompatibilityMap.MethodSymbol modelGetter)
            throws IOException {
        YsmCompatibilityMap.MethodSymbol callback = findUniqueDeclared(clientManager,
                method -> isStatic(method) && !isNative(method)
                        && method.desc.equals(CLIENT_MODEL_CATALOG_DELTA_DESCRIPTOR),
                "client model catalog delta callback");
        MethodNode callbackMethod = findMethod(clientManager, callback);

        List<InvokeDynamicInsnNode> lambdas = new ArrayList<>();
        for (AbstractInsnNode instruction : callbackMethod.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode lambda
                    && lambda.desc.equals(CLIENT_MODEL_CATALOG_DELTA_LAMBDA_DESCRIPTOR)
                    && implementationHandle(lambda, clientManager.name) != null) {
                lambdas.add(lambda);
            }
        }
        if (lambdas.size() != 1) {
            throw new IOException("Expected client model catalog delta callback to schedule one "
                    + "catalog helper, found " + lambdas.size());
        }
        InvokeDynamicInsnNode lambda = lambdas.get(0);
        requireCapturedArguments(lambda, 0, 1, 3, 2);
        Handle implementation = implementationHandle(lambda, clientManager.name);
        MethodNode helper = clientManager.methods.stream()
                .filter(method -> method.name.equals(implementation.getName())
                        && method.desc.equals(CLIENT_MODEL_CATALOG_DELTA_HELPER_DESCRIPTOR)
                        && isStatic(method) && !isNative(method))
                .findFirst().orElseThrow(() -> new IOException(
                        "Missing client model catalog delta helper"));
        validateClientModelCatalogDeltaHelper(clientManager, modelGetter, helper);
        return callback;
    }

    private static Handle implementationHandle(InvokeDynamicInsnNode lambda, String owner) {
        for (Object argument : lambda.bsmArgs) {
            if (argument instanceof Handle handle && handle.getOwner().equals(owner)
                    && handle.getDesc().equals(CLIENT_MODEL_CATALOG_DELTA_HELPER_DESCRIPTOR)) {
                return handle;
            }
        }
        return null;
    }

    private static void requireCapturedArguments(InvokeDynamicInsnNode lambda, int... variables)
            throws IOException {
        AbstractInsnNode cursor = previousInstruction(lambda);
        for (int index = variables.length - 1; index >= 0; index--) {
            if (!(cursor instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                    || load.var != variables[index]) {
                throw new IOException("Client model catalog delta callback has an unsupported "
                        + "removals/old-keys/new-keys/flags argument order");
            }
            cursor = previousInstruction(cursor);
        }
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static void validateClientModelCatalogDeltaHelper(
            ClassNode clientManager, YsmCompatibilityMap.MethodSymbol modelGetter,
            MethodNode helper) throws IOException {
        MethodNode getter = findMethod(clientManager, modelGetter);
        List<FieldReference> getterMaps = fieldReads(getter, clientManager.name).stream()
                .filter(field -> field.desc.equals("Ljava/util/Map;"))
                .distinct().toList();
        if (getterMaps.size() != 1) {
            throw new IOException("Client model map getter must read exactly one static Map field");
        }
        FieldReference modelMap = getterMaps.get(0);
        boolean readsModelMap = fieldReads(helper, clientManager.name).stream()
                .anyMatch(modelMap::equals);
        boolean replacesModelMap = fieldWrites(helper, clientManager.name).stream()
                .anyMatch(modelMap::equals);
        if (!readsModelMap || !replacesModelMap) {
            throw new IOException("Client model catalog delta helper does not replace the Map "
                    + "returned by the model getter");
        }
        boolean removesOldKeys = invokesWithArrayKey(helper, "remove",
                "(Ljava/lang/Object;)Ljava/lang/Object;", 1, 1);
        boolean putsNewKeys = invokesWithArrayKey(helper, "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 2);
        if (!removesOldKeys || !putsNewKeys) {
            throw new IOException("Client model catalog delta helper does not remove old keys "
                    + "and put their models under new keys");
        }
    }

    private static boolean invokesWithArrayKey(MethodNode method, String name, String descriptor,
                                               int arrayVariable, int argumentCount) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode invocation)
                    || !invocation.name.equals(name) || !invocation.desc.equals(descriptor)) {
                continue;
            }
            AbstractInsnNode cursor = previousInstruction(invocation);
            int remainingArguments = argumentCount;
            while (cursor != null && remainingArguments > 0) {
                if (cursor.getOpcode() == Opcodes.AALOAD) {
                    AbstractInsnNode index = previousInstruction(cursor);
                    AbstractInsnNode array = index == null ? null : previousInstruction(index);
                    if (array instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                            && load.var == arrayVariable) {
                        return true;
                    }
                }
                cursor = previousInstruction(cursor);
                remainingArguments--;
            }
        }
        return false;
    }

    private static YsmCompatibilityMap.MethodSymbol findClientPackMapGetter(
            ClassNode clientManager, YsmCompatibilityMap.MethodSymbol modelGetter) throws IOException {
        return findUniqueDeclared(clientManager,
                method -> isStatic(method) && isPublic(method)
                        && method.desc.equals("()Ljava/util/Map;")
                        && !method.name.equals(modelGetter.name()),
                "client pack map getter");
    }

    private static List<FieldReference> fieldReads(MethodNode method, String owner) {
        List<FieldReference> fields = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(owner)) {
                fields.add(new FieldReference(field.name, field.desc));
            }
        }
        return fields;
    }

    private static List<FieldReference> fieldWrites(MethodNode method, String owner) {
        List<FieldReference> fields = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals(owner)) {
                fields.add(new FieldReference(field.name, field.desc));
            }
        }
        return fields;
    }

    private static boolean invokes(MethodNode method, String owner, String name, String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals(owner) && invocation.name.equals(name)
                    && invocation.desc.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode findMethod(ClassNode owner,
                                         YsmCompatibilityMap.MethodSymbol symbol) throws IOException {
        if (owner == null || symbol == null) {
            throw new IOException("Missing method owner or symbol");
        }
        return owner.methods.stream().filter(method -> method.name.equals(symbol.name())
                && method.desc.equals(symbol.descriptor())).findFirst()
                .orElseThrow(() -> new IOException("Missing method " + symbol.name()
                        + symbol.descriptor()));
    }

    private void validateBridgeSurface(Map<String, ClassNode> classes,
                                              Map<Integer, String> packets) throws IOException {
        requireConstructor(classes, packets, 3, descriptor -> descriptor.startsWith("([ILjava/lang/String;)"));
        requireConstructor(classes, packets, 4, descriptor -> {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            return arguments.length == 5 && arguments[0].equals(Type.INT_TYPE)
                    && arguments[1].equals(Type.getType(String.class))
                    && arguments[2].equals(Type.getType(String.class))
                    && arguments[3].equals(Type.BOOLEAN_TYPE)
                    && arguments[4].getSort() == Type.OBJECT;
        });
        requireFields(classes, packets, 5, 2, Type.getDescriptor(String.class));
        requireFields(classes, packets, 7, 1, Type.getDescriptor(String.class));
        requireFields(classes, packets, 17, 1, Type.getDescriptor(String.class));
        requireFields(classes, packets, 18, 1, "Lit/unimi/dsi/fastutil/floats/FloatArrayList;");
        requireFields(classes, packets, 23, 1, null);

        ClassNode playerState = requireClass(classes, packets.get(21), "packet 21");
        String playerStateDescriptor = "L" + playerState.name + ";";
        boolean animationSetter = playerState.methods.stream().anyMatch(method ->
                method.desc.equals("(Ljava/lang/String;)" + playerStateDescriptor));
        if (!animationSetter) {
            throw new IOException("Packet 21 does not expose its animation state setter");
        }
        requirePacketCodecAndHandler(playerState);
        validateEntityAppearancePacket(classes, requireClass(classes, packets.get(16), "packet 16"));
        validateEntityAppearancePacket(classes, requireClass(classes, packets.get(22), "packet 22"));
    }

    private void validateEntityAppearancePacket(Map<String, ClassNode> classes, ClassNode packet)
            throws IOException {
        Type capabilityType = packet.methods.stream()
                .filter(method -> method.name.equals("<init>"))
                .map(method -> Type.getArgumentTypes(method.desc))
                .filter(arguments -> arguments.length == 2 && arguments[0].equals(Type.INT_TYPE)
                        && arguments[1].getSort() == Type.OBJECT)
                .map(arguments -> arguments[1]).findFirst()
                .orElseThrow(() -> new IOException("Entity appearance packet lacks (int, capability)"));
        ClassNode capability = requireClass(classes, capabilityType.getInternalName(), "entity capability");
        if (capability.methods.stream().noneMatch(method -> method.name.equals("<init>")
                && method.desc.equals("()V"))) {
            throw new IOException("Entity appearance capability lacks a default constructor");
        }
        String initializer = "(Ljava/lang/String;Lit/unimi/dsi/fastutil/objects/"
                + "Object2FloatOpenHashMap;)V";
        if (capability.methods.stream().noneMatch(method -> method.desc.equals(initializer))) {
            throw new IOException("Entity appearance capability lacks its model initializer");
        }
        requirePacketCodecAndHandler(packet);
    }

    private void requirePacketCodecAndHandler(ClassNode packet) throws IOException {
        String self = "L" + packet.name + ";";
        boolean writer = packet.methods.stream().anyMatch(method ->
                (method.access & Opcodes.ACC_STATIC) != 0 && method.desc.startsWith("(" + self + "L")
                        && Type.getReturnType(method.desc).equals(Type.VOID_TYPE));
        boolean decoder = packet.methods.stream().anyMatch(method ->
                (method.access & Opcodes.ACC_STATIC) != 0 && method.desc.startsWith("(L")
                        && Type.getReturnType(method.desc).getDescriptor().equals(self));
        boolean handler = packet.methods.stream().anyMatch(method ->
                isClientHandler(method, self));
        if (!writer || !decoder || !handler) {
            throw new IOException("Incomplete packet codec/handler surface for " + packet.name);
        }
    }

    private boolean isClientHandler(MethodNode method, String self) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        if ((method.access & Opcodes.ACC_STATIC) == 0
                || !Type.getReturnType(method.desc).equals(Type.VOID_TYPE)
                || arguments.length == 0 || !arguments[0].getDescriptor().equals(self)) {
            return false;
        }
        if (isForgeSupplierClientHandler(arguments)) {
            return true;
        }
        if (arguments.length != 3) {
            return false;
        }
        String player = arguments[1].getInternalName();
        String connection = arguments[2].getInternalName();
        return playerTypes.contains(player) && connectionTypes.contains(connection);
    }

    static boolean isForgeSupplierClientHandler(Type[] arguments) {
        return arguments.length == 2
                && arguments[1].equals(Type.getType(java.util.function.Supplier.class));
    }

    private static void requireConstructor(Map<String, ClassNode> classes, Map<Integer, String> packets,
                                           int packetId, Predicate<String> descriptor) throws IOException {
        ClassNode packet = requireClass(classes, packets.get(packetId), "packet " + packetId);
        if (packet.methods.stream().noneMatch(method -> method.name.equals("<init>")
                && descriptor.test(method.desc))) {
            throw new IOException("Packet " + packetId + " constructor layout is unsupported");
        }
    }

    private static void requireFields(Map<String, ClassNode> classes, Map<Integer, String> packets,
                                      int packetId, int minimum, String descriptor) throws IOException {
        ClassNode packet = requireClass(classes, packets.get(packetId), "packet " + packetId);
        long count = packet.fields.stream().filter(field -> descriptor == null
                ? field.desc.startsWith("L") : field.desc.equals(descriptor)).count();
        if (count < minimum) {
            throw new IOException("Packet " + packetId + " field layout is unsupported");
        }
    }

    private static ClassNode requireClass(Map<String, ClassNode> classes, String name, String label)
            throws IOException {
        ClassNode node = classes.get(name);
        if (node == null) {
            throw new IOException("Missing " + label + ": " + name);
        }
        return node;
    }

    private RegistrationCandidate analyzeRegistration(ClassNode owner, MethodNode method) {
        Map<Integer, String> symbols = new HashMap<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            Integer packetId = integerValue(instruction);
            if (packetId == null || !targetIds.contains(packetId)) {
                continue;
            }
            String type = nextType(instruction, 12);
            if (type != null) {
                symbols.putIfAbsent(packetId, type);
            }
        }
        return new RegistrationCandidate(owner.name, method.name + method.desc, symbols);
    }

    private static Integer integerValue(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (instruction instanceof IntInsnNode integer) {
            return integer.operand;
        }
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer integer) {
            return integer;
        }
        return null;
    }

    private static String nextType(AbstractInsnNode start, int distance) {
        AbstractInsnNode current = start;
        for (int i = 0; i < distance && current != null; i++, current = current.getNext()) {
            if (current instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
                    && type.getSort() == Type.OBJECT) {
                return type.getInternalName();
            }
        }
        return null;
    }

    private String findChannel(List<ClassNode> classes) {
        for (ClassNode node : classes) {
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                            && profile.channelIdentifiers().contains(text)) {
                        return profile.channelIdentifiers().get(0);
                    }
                }
            }
        }
        return profile.channelIdentifiers().get(0);
    }

    private static MethodReference findUniqueInvokedMethod(ClassNode node,
            Predicate<MethodReference> filter, String label) throws IOException {
        if (node == null) {
            throw new IOException("Missing " + label + " owner");
        }
        List<MethodReference> matches = node.methods.stream()
                .flatMap(method -> invocationNodes(method).stream())
                .map(invocation -> new MethodReference(invocation.owner, invocation.name,
                        invocation.desc))
                .filter(filter).distinct().toList();
        if (matches.size() != 1) {
            throw new IOException("Expected one " + label + " in " + node.name
                    + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static YsmCompatibilityMap.MethodSymbol findDeclared(ClassNode owner,
                                                                   Predicate<MethodNode> filter) {
        if (owner == null) {
            return null;
        }
        return owner.methods.stream().filter(filter).findFirst()
                .map(method -> new YsmCompatibilityMap.MethodSymbol(owner.name, method.name, method.desc))
                .orElse(null);
    }

    private static YsmCompatibilityMap.MethodSymbol findUniqueDeclared(ClassNode owner,
                                                                         Predicate<MethodNode> filter,
                                                                         String label)
            throws IOException {
        if (owner == null) {
            throw new IOException("Missing " + label + " owner");
        }
        List<MethodNode> matches = owner.methods.stream().filter(filter).toList();
        if (matches.size() != 1) {
            throw new IOException("Expected one " + label + " in " + owner.name
                    + ", found " + matches.size());
        }
        MethodNode method = matches.get(0);
        return new YsmCompatibilityMap.MethodSymbol(owner.name, method.name, method.desc);
    }

    private static boolean isClientSendMethod(MethodNode method) {
        if (!method.desc.equals("(Ljava/lang/Object;)V")
                || (method.access & Opcodes.ACC_STATIC) == 0) {
            return false;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invocation && invocation.desc.equals("()Z")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStatic(MethodNode method) {
        return (method.access & Opcodes.ACC_STATIC) != 0;
    }

    private static boolean isPublic(MethodNode method) {
        return (method.access & Opcodes.ACC_PUBLIC) != 0;
    }

    private static boolean isNative(MethodNode method) {
        return (method.access & Opcodes.ACC_NATIVE) != 0;
    }

    private static void requireStatic(Map<String, ClassNode> classes, MethodReference reference,
                                      String label) throws IOException {
        if (reference == null) {
            throw new IOException("Missing " + label + " method");
        }
        ClassNode owner = requireClass(classes, reference.owner, label + " owner");
        boolean found = owner.methods.stream().anyMatch(method -> method.name.equals(reference.name)
                && method.desc.equals(reference.desc) && isStatic(method));
        if (!found) {
            throw new IOException(label + " method is not static");
        }
    }

    private static YsmCompatibilityMap.MethodSymbol symbol(MethodReference reference) {
        return reference == null ? null : new YsmCompatibilityMap.MethodSymbol(reference.owner,
                reference.name, reference.desc);
    }

    private static boolean hasArguments(String descriptor, String firstPrefix, String secondName) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        return arguments.length == 2 && arguments[0].getSort() == Type.OBJECT
                && arguments[0].getInternalName().startsWith(firstPrefix)
                && arguments[1].getSort() == Type.OBJECT
                && arguments[1].getInternalName().equals(secondName);
    }

    private record RegistrationCandidate(String owner, String method, Map<Integer, String> symbols) {
    }

    private record MethodReference(String owner, String name, String desc) {
    }

    private record FieldReference(String name, String desc) {
    }

    private record ServerSyncResultSymbols(YsmCompatibilityMap.MethodSymbol successGetter,
                                           YsmCompatibilityMap.MethodSymbol errorGetter) {
    }

    record ClientTextureCacheSymbols(YsmCompatibilityMap.MethodSymbol modelDataGetter,
                                     YsmCompatibilityMap.MethodSymbol texturesGetter,
                                     YsmCompatibilityMap.MethodSymbol cacheAcquire,
                                     YsmCompatibilityMap.MethodSymbol locationGetter) {
    }

    record ClientAudioCacheSymbols(YsmCompatibilityMap.MethodSymbol modelResourcesGetter,
                                   YsmCompatibilityMap.MethodSymbol soundsGetter,
                                   YsmCompatibilityMap.MethodSymbol cacheAcquire,
                                   YsmCompatibilityMap.MethodSymbol streamOpen) {
    }

    private record ProviderSymbols(YsmCompatibilityMap.MethodSymbol acquire,
                                   YsmCompatibilityMap.MethodSymbol open) {
    }

    record CompleteFeedbackSymbols(YsmCompatibilityMap.FieldSymbol payloadField,
                                   YsmCompatibilityMap.FieldSymbol modelKeyField,
                                   YsmCompatibilityMap.FieldSymbol targetEntityIdField,
                                   YsmCompatibilityMap.FieldSymbol variablesField) {
    }

    record AnimationSymbols(YsmCompatibilityMap.FieldSymbol indexField,
                            YsmCompatibilityMap.FieldSymbol packField,
                            YsmCompatibilityMap.FieldSymbol targetEntityIdField,
                            YsmCompatibilityMap.MethodSymbol containerDataGetter,
                            YsmCompatibilityMap.MethodSymbol dataPropertiesGetter,
                            YsmCompatibilityMap.MethodSymbol dataStorageKeyGetter,
                            YsmCompatibilityMap.MethodSymbol defaultAnimationsGetter,
                            YsmCompatibilityMap.MethodSymbol animationPacksGetter,
                            YsmCompatibilityMap.MethodSymbol orderedCountGetter,
                            YsmCompatibilityMap.MethodSymbol orderedNameGetter) {
    }

    record PlayerStateSymbols(YsmCompatibilityMap.MethodSymbol animationSetter,
                              YsmCompatibilityMap.MethodSymbol roamingSetter,
                              YsmCompatibilityMap.MethodSymbol codecWriter,
                              YsmCompatibilityMap.MethodSymbol codecDecoder,
                              YsmCompatibilityMap.MethodSymbol clientHandler,
                              YsmCompatibilityMap.FieldSymbol flagsField,
                              YsmCompatibilityMap.FieldSymbol decodedRoamingField,
                              String capabilityClass,
                              YsmCompatibilityMap.MethodSymbol activeAnimationGetter,
                              YsmCompatibilityMap.MethodSymbol animationPlayingGetter,
                              YsmCompatibilityMap.MethodSymbol animationStopPacketFactory,
                              YsmCompatibilityMap.MethodSymbol animationStopSender,
                              YsmCompatibilityMap.MethodSymbol roamingProviderGetter,
                              YsmCompatibilityMap.MethodSymbol roamingValueGetter,
                              YsmCompatibilityMap.MethodSymbol roamingValueSetter,
                              YsmCompatibilityMap.MethodSymbol roamingNameHasher,
                              YsmCompatibilityMap.MethodSymbol fullRoamingInitializer) {
    }

    private record WheelStateSymbols(
            YsmCompatibilityMap.MethodSymbol activeAnimationGetter,
            YsmCompatibilityMap.MethodSymbol animationPlayingGetter,
            YsmCompatibilityMap.MethodSymbol animationStopPacketFactory,
            YsmCompatibilityMap.MethodSymbol animationStopSender) {
    }

    private record RoamingProviderSymbols(
            YsmCompatibilityMap.MethodSymbol providerGetter,
            YsmCompatibilityMap.MethodSymbol valueGetter,
            YsmCompatibilityMap.MethodSymbol valueSetter) {
    }

    private record OwnedMethod(ClassNode owner, MethodNode method) {
    }

}
