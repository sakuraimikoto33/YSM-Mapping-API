package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmFieldSymbol;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SymbolMappings {
    private SymbolMappings() {
    }

    public static Map<YsmSymbolKey<?>, YsmResolvedSymbol> from(YsmCompatibilityMap build)
            throws IOException {
        Map<YsmSymbolKey<?>, YsmResolvedSymbol> values = new LinkedHashMap<>();
        values.put(YsmSymbols.REGISTRATION_CLASS,
                new YsmClassSymbol(build.registrationClass()));
        int descriptorStart = build.registrationMethod().indexOf('(');
        if (descriptorStart <= 0) {
            throw new IOException("Invalid YSM registration method: " + build.registrationMethod());
        }
        values.put(YsmSymbols.REGISTRATION_METHOD, new YsmMethodSymbol(
                build.registrationClass(), build.registrationMethod().substring(0, descriptorStart),
                build.registrationMethod().substring(descriptorStart)));
        for (YsmCompatibilityMap.PacketSymbol packet : build.packets()) {
            values.put(YsmSymbols.packetClass(packet.id()),
                    new YsmClassSymbol(packet.messageClass()));
        }

        method(values, YsmSymbols.CLIENT_SEND_METHOD, build.clientSendMethod());
        method(values, YsmSymbols.CHANNEL_VERSION_SETTER, build.channelVersionSetter());
        method(values, YsmSymbols.CLIENT_MODEL_START_SYNC, build.clientModelStartSync());
        method(values, YsmSymbols.CLIENT_MODEL_CONNECTED, build.clientModelConnected());
        method(values, YsmSymbols.CLIENT_MODEL_RESET, build.clientModelReset());
        method(values, YsmSymbols.CLIENT_MODEL_MAP_GETTER, build.clientModelMapGetter());
        method(values, YsmSymbols.CLIENT_MODEL_LOOKUP, build.clientModelLookup());
        method(values, YsmSymbols.CLIENT_MODEL_CATALOG_DELTA_CALLBACK,
                build.clientModelCatalogDeltaCallback());
        method(values, YsmSymbols.CLIENT_PACK_MAP_GETTER, build.clientPackMapGetter());
        method(values, YsmSymbols.CLIENT_PENDING_COUNT_GETTER,
                build.clientPendingCountGetter());
        method(values, YsmSymbols.CLIENT_MODEL_FLUSH_PENDING, build.clientModelFlushPending());
        method(values, YsmSymbols.CLIENT_MODEL_RAW_SENDER, build.clientModelRawSender());
        method(values, YsmSymbols.SERVER_MODEL_RECEIVE, build.serverModelReceive());
        method(values, YsmSymbols.SERVER_MODEL_RELOAD, build.serverModelReload());
        method(values, YsmSymbols.SERVER_MODEL_SYNC, build.serverModelSync());
        method(values, YsmSymbols.SERVER_SYNC_RESULT_SUCCESS_GETTER,
                build.serverSyncResultSuccessGetter());
        method(values, YsmSymbols.SERVER_SYNC_RESULT_ERROR_GETTER,
                build.serverSyncResultErrorGetter());
        method(values, YsmSymbols.SERVER_MODEL_MAP_GETTER, build.serverModelMapGetter());
        field(values, YsmSymbols.SERVER_MODEL_MAP_FIELD, build.serverModelMapField());
        method(values, YsmSymbols.SERVER_MODEL_STREAM_CALLBACK,
                build.serverModelStreamCallback());
        method(values, YsmSymbols.SERVER_MODEL_PAYLOAD_FACTORY,
                build.serverModelPayloadFactory());
        method(values, YsmSymbols.SERVER_MODEL_PAYLOAD_CALLBACK,
                build.serverModelPayloadCallback());
        field(values, YsmSymbols.CLIENT_NOT_DISPLAY_MODELS, build.clientNotDisplayModels());
        field(values, YsmSymbols.COMPLETE_FEEDBACK_PAYLOAD_FIELD,
                build.completeFeedbackPayloadField());
        field(values, YsmSymbols.FEEDBACK_MODEL_KEY_FIELD, build.feedbackModelKeyField());
        field(values, YsmSymbols.FEEDBACK_TARGET_ENTITY_ID_FIELD,
                build.feedbackTargetEntityIdField());
        field(values, YsmSymbols.FEEDBACK_VARIABLES_FIELD, build.feedbackVariablesField());
        field(values, YsmSymbols.ANIMATION_INDEX_FIELD, build.animationIndexField());
        field(values, YsmSymbols.ANIMATION_PACK_FIELD, build.animationPackField());
        field(values, YsmSymbols.ANIMATION_TARGET_ENTITY_ID_FIELD,
                build.animationTargetEntityIdField());
        method(values, YsmSymbols.SERVER_MODEL_CONTAINER_DATA_GETTER,
                build.serverModelContainerDataGetter());
        method(values, YsmSymbols.SERVER_MODEL_DATA_PROPERTIES_GETTER,
                build.serverModelDataPropertiesGetter());
        method(values, YsmSymbols.SERVER_MODEL_DATA_STORAGE_KEY_GETTER,
                build.serverModelDataStorageKeyGetter());
        method(values, YsmSymbols.MODEL_PROPERTIES_DEFAULT_ANIMATIONS_GETTER,
                build.modelPropertiesDefaultAnimationsGetter());
        method(values, YsmSymbols.MODEL_PROPERTIES_ANIMATION_PACKS_GETTER,
                build.modelPropertiesAnimationPacksGetter());
        method(values, YsmSymbols.ORDERED_ANIMATION_COUNT_GETTER,
                build.orderedAnimationCountGetter());
        method(values, YsmSymbols.ORDERED_ANIMATION_NAME_GETTER,
                build.orderedAnimationNameGetter());
        method(values, YsmSymbols.PLAYER_STATE_ANIMATION_SETTER,
                build.playerStateAnimationSetter());
        method(values, YsmSymbols.PLAYER_STATE_ROAMING_SETTER,
                build.playerStateRoamingSetter());
        method(values, YsmSymbols.PLAYER_STATE_CODEC_WRITER,
                build.playerStateCodecWriter());
        method(values, YsmSymbols.PLAYER_STATE_CODEC_DECODER,
                build.playerStateCodecDecoder());
        method(values, YsmSymbols.PLAYER_STATE_CLIENT_HANDLER,
                build.playerStateClientHandler());
        field(values, YsmSymbols.PLAYER_STATE_FLAGS_FIELD, build.playerStateFlagsField());
        field(values, YsmSymbols.PLAYER_STATE_DECODED_ROAMING_FIELD,
                build.playerStateDecodedRoamingField());
        method(values, YsmSymbols.PLAYER_STATE_FULL_ROAMING_INITIALIZER,
                build.playerStateFullRoamingInitializer());

        if (values.size() != 63) {
            throw new IOException("YSM mapping does not cover the 63-symbol legacy surface");
        }
        return Map.copyOf(values);
    }

    private static void method(Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
                               YsmSymbolKey<YsmMethodSymbol> key,
                               YsmCompatibilityMap.MethodSymbol symbol) throws IOException {
        if (symbol == null) {
            throw new IOException("Missing method mapping: " + key.id());
        }
        values.put(key, new YsmMethodSymbol(symbol.owner(), symbol.name(), symbol.descriptor()));
    }

    private static void field(Map<YsmSymbolKey<?>, YsmResolvedSymbol> values,
                              YsmSymbolKey<YsmFieldSymbol> key,
                              YsmCompatibilityMap.FieldSymbol symbol) throws IOException {
        if (symbol == null) {
            throw new IOException("Missing field mapping: " + key.id());
        }
        values.put(key, new YsmFieldSymbol(symbol.owner(), symbol.name(), symbol.descriptor()));
    }
}
