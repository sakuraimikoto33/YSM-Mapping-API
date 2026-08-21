package net.okitsu.ysmmapping.internal.analysis;

import java.util.List;

public record YsmCompatibilityMap(String id, String minecraftVersion, String loader,
                                  String ysmVersion, String sha512, String channel,
                                  String registrationClass, String registrationMethod,
                                  List<PacketSymbol> packets,
                                  MethodSymbol clientSendMethod,
                                  MethodSymbol channelVersionSetter,
                                  MethodSymbol clientModelStartSync,
                                  MethodSymbol clientModelConnected,
                                  MethodSymbol clientModelReset,
                                  MethodSymbol clientModelMapGetter,
                                  MethodSymbol clientModelLookup,
                                  MethodSymbol clientModelCatalogDeltaCallback,
                                  MethodSymbol clientPackMapGetter,
                                  MethodSymbol clientPendingCountGetter,
                                  MethodSymbol clientModelFlushPending,
                                  MethodSymbol clientModelRawSender,
                                  MethodSymbol animationRouletteConfigurationExpression,
                                  MethodSymbol serverModelReceive,
                                  MethodSymbol serverModelReload,
                                  MethodSymbol serverModelSync,
                                  MethodSymbol serverSyncResultSuccessGetter,
                                  MethodSymbol serverSyncResultErrorGetter,
                                  MethodSymbol serverModelMapGetter,
                                  FieldSymbol serverModelMapField,
                                  MethodSymbol serverModelStreamCallback,
                                  MethodSymbol serverModelPayloadFactory,
                                  MethodSymbol serverModelPayloadCallback,
                                  FieldSymbol clientNotDisplayModels,
                                  FieldSymbol completeFeedbackPayloadField,
                                  FieldSymbol feedbackModelKeyField,
                                  FieldSymbol feedbackTargetEntityIdField,
                                  FieldSymbol feedbackVariablesField,
                                  FieldSymbol animationIndexField,
                                  FieldSymbol animationPackField,
                                  FieldSymbol animationTargetEntityIdField,
                                  MethodSymbol serverModelContainerDataGetter,
                                  MethodSymbol serverModelDataPropertiesGetter,
                                  MethodSymbol serverModelDataStorageKeyGetter,
                                  MethodSymbol modelPropertiesDefaultAnimationsGetter,
                                  MethodSymbol modelPropertiesAnimationPacksGetter,
                                  MethodSymbol orderedAnimationCountGetter,
                                  MethodSymbol orderedAnimationNameGetter,
                                  MethodSymbol playerStateAnimationSetter,
                                  MethodSymbol playerStateRoamingSetter,
                                  MethodSymbol playerStateCodecWriter,
                                  MethodSymbol playerStateCodecDecoder,
                                  MethodSymbol playerStateClientHandler,
                                  FieldSymbol playerStateFlagsField,
                                  FieldSymbol playerStateDecodedRoamingField,
                                  String playerStateCapabilityClass,
                                  MethodSymbol playerStateRoamingProviderGetter,
                                  MethodSymbol playerStateRoamingValueGetter,
                                  MethodSymbol playerStateRoamingNameHasher,
                                  MethodSymbol playerStateFullRoamingInitializer) {
    public record PacketSymbol(int id, String semantic, String direction, String messageClass) {
    }

    public record MethodSymbol(String owner, String name, String descriptor) {
    }

    public record FieldSymbol(String owner, String name, String descriptor) {
    }
}
