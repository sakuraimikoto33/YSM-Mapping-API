package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmFieldSymbol;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class YsmSymbols {
    private static final YsmSymbolRegistry REGISTRY = new YsmSymbolRegistry();

    public static final YsmSymbolKey<YsmClassSymbol> REGISTRATION_CLASS =
            classKey("ysm.network.registration.class");
    public static final YsmSymbolKey<YsmMethodSymbol> REGISTRATION_METHOD =
            methodKey("ysm.network.registration.method");

    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_SEND_METHOD =
            methodKey("ysm.client.send.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CHANNEL_VERSION_SETTER =
            methodKey("ysm.network.channel_version_setter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_MODEL_START_SYNC =
            methodKey("ysm.client.model_manager.start_sync.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_MODEL_CONNECTED =
            methodKey("ysm.client.model_manager.connected.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_MODEL_RESET =
            methodKey("ysm.client.model_manager.reset.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_MODEL_MAP_GETTER =
            methodKey("ysm.client.model_manager.model_map_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_MODEL_CATALOG_DELTA_CALLBACK =
            methodKey("ysm.client.model_manager.catalog_delta_callback.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_PACK_MAP_GETTER =
            methodKey("ysm.client.model_manager.pack_map_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_PENDING_COUNT_GETTER =
            methodKey("ysm.client.model_manager.pending_count_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_MODEL_FLUSH_PENDING =
            methodKey("ysm.client.model_manager.flush_pending.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CLIENT_MODEL_RAW_SENDER =
            methodKey("ysm.client.model_manager.raw_sender.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_RECEIVE =
            methodKey("ysm.server.model_manager.receive.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_RELOAD =
            methodKey("ysm.server.model_manager.reload.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_SYNC =
            methodKey("ysm.server.model_manager.sync.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_SYNC_RESULT_SUCCESS_GETTER =
            methodKey("ysm.server.sync_result.success_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_SYNC_RESULT_ERROR_GETTER =
            methodKey("ysm.server.sync_result.error_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_MAP_GETTER =
            methodKey("ysm.server.model_manager.model_map_getter.method");
    public static final YsmSymbolKey<YsmFieldSymbol> SERVER_MODEL_MAP_FIELD =
            fieldKey("ysm.server.model_manager.model_map.field");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_STREAM_CALLBACK =
            methodKey("ysm.server.model_manager.stream_callback.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_PAYLOAD_FACTORY =
            methodKey("ysm.server.model_manager.payload_factory.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_PAYLOAD_CALLBACK =
            methodKey("ysm.server.model_manager.payload_callback.method");
    public static final YsmSymbolKey<YsmFieldSymbol> CLIENT_NOT_DISPLAY_MODELS =
            fieldKey("ysm.client.config.not_display_models.field");
    public static final YsmSymbolKey<YsmFieldSymbol> COMPLETE_FEEDBACK_PAYLOAD_FIELD =
            fieldKey("ysm.feedback.packet.payload.field");
    public static final YsmSymbolKey<YsmFieldSymbol> FEEDBACK_MODEL_KEY_FIELD =
            fieldKey("ysm.feedback.model_key.field");
    public static final YsmSymbolKey<YsmFieldSymbol> FEEDBACK_TARGET_ENTITY_ID_FIELD =
            fieldKey("ysm.feedback.target_entity_id.field");
    public static final YsmSymbolKey<YsmFieldSymbol> FEEDBACK_VARIABLES_FIELD =
            fieldKey("ysm.feedback.variables.field");
    public static final YsmSymbolKey<YsmFieldSymbol> ANIMATION_INDEX_FIELD =
            fieldKey("ysm.animation.packet.index.field");
    public static final YsmSymbolKey<YsmFieldSymbol> ANIMATION_PACK_FIELD =
            fieldKey("ysm.animation.packet.pack.field");
    public static final YsmSymbolKey<YsmFieldSymbol> ANIMATION_TARGET_ENTITY_ID_FIELD =
            fieldKey("ysm.animation.packet.target_entity_id.field");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_CONTAINER_DATA_GETTER =
            methodKey("ysm.server.model_container.data_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_DATA_PROPERTIES_GETTER =
            methodKey("ysm.server.model_data.properties_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> SERVER_MODEL_DATA_STORAGE_KEY_GETTER =
            methodKey("ysm.server.model_data.storage_key_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> MODEL_PROPERTIES_DEFAULT_ANIMATIONS_GETTER =
            methodKey("ysm.model_properties.default_animations_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> MODEL_PROPERTIES_ANIMATION_PACKS_GETTER =
            methodKey("ysm.model_properties.animation_packs_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ORDERED_ANIMATION_COUNT_GETTER =
            methodKey("ysm.ordered_animation.count_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ORDERED_ANIMATION_NAME_GETTER =
            methodKey("ysm.ordered_animation.name_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> PLAYER_STATE_ANIMATION_SETTER =
            methodKey("ysm.player_state.animation_setter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> PLAYER_STATE_ROAMING_SETTER =
            methodKey("ysm.player_state.roaming_setter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> PLAYER_STATE_CODEC_WRITER =
            methodKey("ysm.player_state.codec_writer.method");
    public static final YsmSymbolKey<YsmMethodSymbol> PLAYER_STATE_CODEC_DECODER =
            methodKey("ysm.player_state.codec_decoder.method");
    public static final YsmSymbolKey<YsmMethodSymbol> PLAYER_STATE_CLIENT_HANDLER =
            methodKey("ysm.player_state.client_handler.method");
    public static final YsmSymbolKey<YsmFieldSymbol> PLAYER_STATE_FLAGS_FIELD =
            fieldKey("ysm.player_state.flags.field");
    public static final YsmSymbolKey<YsmFieldSymbol> PLAYER_STATE_DECODED_ROAMING_FIELD =
            fieldKey("ysm.player_state.decoded_roaming.field");
    public static final YsmSymbolKey<YsmMethodSymbol> PLAYER_STATE_FULL_ROAMING_INITIALIZER =
            methodKey("ysm.player_state.full_roaming_initializer.method");

    public static final YsmSymbolKey<YsmMethodSymbol> EQUIPMENT_ELYTRA_ITEM_GETTER =
            methodKey("ysm.client.equipment.elytra_item_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDERER_ELYTRA_LAYER_RENDER =
            methodKey("ysm.client.renderer.elytra_layer.render.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CUSTOM_PLAYER_ENTITY_GETTER =
            methodKey("ysm.client.custom_player.entity_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> CUSTOM_PLAYER_CURRENT_MODEL_GETTER =
            methodKey("ysm.client.custom_player.current_model_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_RIGHT_WAIST_BONES_GETTER =
            methodKey("ysm.client.animated_model.right_waist_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDER_UTILS_PREP_MATRIX_FOR_LOCATOR =
            methodKey("ysm.client.render_utils.prep_matrix_for_locator.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_HEAD_BONES_GETTER =
            methodKey("ysm.client.animated_model.head_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_ALL_HEAD_BONE_GETTER =
            methodKey("ysm.client.animated_model.all_head_bone_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDER_UTILS_PREP_MATRIX_FOR_BONE =
            methodKey("ysm.client.render_utils.prep_matrix_for_bone.method");

    public static final YsmSymbolKey<YsmMethodSymbol> EQUIPMENT_ARMOR_ITEM_GETTER =
            methodKey("ysm.client.equipment.armor_item_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDERER_ELYTRA_LAYER_RENDER_ELYTRA =
            methodKey("ysm.client.renderer.elytra_layer.render_elytra.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDERER_ARMOR_LAYER_RENDER =
            methodKey("ysm.client.renderer.armor_layer.render.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDERER_ITEM_IN_HAND_LAYER_RENDER =
            methodKey("ysm.client.renderer.item_in_hand_layer.render.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDERER_PARROT_LAYER_RENDER =
            methodKey("ysm.client.renderer.parrot_layer.render.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDERER_BACKPACK_LAYER_RENDER =
            methodKey("ysm.client.renderer.backpack_layer.render.method");

    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_LEFT_HAND_BONES_GETTER =
            methodKey("ysm.client.animated_model.left_hand_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_RIGHT_HAND_BONES_GETTER =
            methodKey("ysm.client.animated_model.right_hand_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_ELYTRA_BONES_GETTER =
            methodKey("ysm.client.animated_model.elytra_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_BACKPACK_BONES_GETTER =
            methodKey("ysm.client.animated_model.backpack_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_TAC_PISTOL_BONES_GETTER =
            methodKey("ysm.client.animated_model.tac_pistol_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_TAC_RIFLE_BONES_GETTER =
            methodKey("ysm.client.animated_model.tac_rifle_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_LEFT_WAIST_BONES_GETTER =
            methodKey("ysm.client.animated_model.left_waist_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_LEFT_SHOULDER_BONES_GETTER =
            methodKey("ysm.client.animated_model.left_shoulder_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_RIGHT_SHOULDER_BONES_GETTER =
            methodKey("ysm.client.animated_model.right_shoulder_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_BLADE_BONES_GETTER =
            methodKey("ysm.client.animated_model.blade_bones_getter.method");
    public static final YsmSymbolKey<YsmMethodSymbol> ANIMATED_MODEL_SHEATH_BONES_GETTER =
            methodKey("ysm.client.animated_model.sheath_bones_getter.method");

    public static final YsmSymbolKey<YsmMethodSymbol> RENDER_UTILS_TRANSLATE_MATRIX_TO_BONE =
            methodKey("ysm.client.render_utils.translate_matrix_to_bone.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDER_UTILS_ROTATE_MATRIX_AROUND_BONE =
            methodKey("ysm.client.render_utils.rotate_matrix_around_bone.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDER_UTILS_SCALE_MATRIX_FOR_BONE =
            methodKey("ysm.client.render_utils.scale_matrix_for_bone.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDER_UTILS_TRANSLATE_TO_PIVOT_POINT =
            methodKey("ysm.client.render_utils.translate_to_pivot_point.method");
    public static final YsmSymbolKey<YsmMethodSymbol> RENDER_UTILS_TRANSLATE_AWAY_FROM_PIVOT_POINT =
            methodKey("ysm.client.render_utils.translate_away_from_pivot_point.method");
    public static final YsmSymbolKey<YsmMethodSymbol>
            RENDER_UTILS_TRANSLATE_AND_ROTATE_MATRIX_FOR_BONE =
            methodKey("ysm.client.render_utils.translate_and_rotate_matrix_for_bone.method");

    private static final List<Integer> PACKET_IDS = List.of(
            1, 2, 3, 4, 5, 7, 15, 16, 17, 18, 19, 21, 22, 23, 51, 52);

    static {
        for (int packetId : PACKET_IDS) {
            classKey(packetId(packetId));
        }
    }

    private YsmSymbols() {
    }

    public static YsmSymbolKey<YsmClassSymbol> packetClass(int packetId) {
        YsmSymbolKey<?> key = REGISTRY.byId(packetId(packetId)).orElse(null);
        if (key == null) {
            throw new IllegalArgumentException("Unsupported YSM packet ID: " + packetId);
        }
        @SuppressWarnings("unchecked")
        YsmSymbolKey<YsmClassSymbol> typed = (YsmSymbolKey<YsmClassSymbol>) key;
        return typed;
    }

    public static Optional<YsmSymbolKey<?>> byId(String id) {
        return REGISTRY.byId(id);
    }

    public static Collection<YsmSymbolKey<?>> all() {
        return REGISTRY.all();
    }

    public static YsmSymbolRegistry registry() {
        return REGISTRY;
    }

    private static String packetId(int id) {
        return "ysm.network.packet." + id + ".class";
    }

    private static YsmSymbolKey<YsmClassSymbol> classKey(String id) {
        return register(YsmSymbolKey.curated(id, SymbolKind.CLASS, YsmClassSymbol.class));
    }

    private static YsmSymbolKey<YsmMethodSymbol> methodKey(String id) {
        return register(YsmSymbolKey.curated(id, SymbolKind.METHOD, YsmMethodSymbol.class));
    }

    private static YsmSymbolKey<YsmFieldSymbol> fieldKey(String id) {
        return register(YsmSymbolKey.curated(id, SymbolKind.FIELD, YsmFieldSymbol.class));
    }

    private static <T extends YsmResolvedSymbol> YsmSymbolKey<T> register(YsmSymbolKey<T> key) {
        return REGISTRY.register(key);
    }
}
