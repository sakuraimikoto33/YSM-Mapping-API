package net.okitsu.ysmmapping.internal.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PacketSemantic {
    private PacketSemantic() {
    }

    public static Map<Integer, Definition> definitions() {
        Map<Integer, Definition> values = new LinkedHashMap<>();
        values.put(1, new Definition("MODEL_SYNC_TO_CLIENT", "S2C"));
        values.put(2, new Definition("MODEL_SYNC_TO_SERVER", "C2S"));
        values.put(3, new Definition("EXECUTE_MOLANG", "S2C"));
        values.put(4, new Definition("SET_MODEL_AND_TEXTURE", "S2C"));
        values.put(5, new Definition("REQUEST_SWITCH_MODEL", "C2S"));
        values.put(7, new Definition("PLAY_ANIMATION", "C2S"));
        values.put(15, new Definition("COMPLETE_FEEDBACK", "C2S"));
        values.put(16, new Definition("SYNC_PROJECTILE_MODEL", "S2C"));
        values.put(17, new Definition("REQUEST_EXECUTE_MOLANG", "C2S"));
        values.put(18, new Definition("SYNC_ANIMATION_EXPRESSION", "C2S"));
        values.put(19, new Definition("SYNC_ANIMATION_EXPRESSION", "S2C"));
        values.put(21, new Definition("SYNC_PLAYER_STATE", "S2C"));
        values.put(22, new Definition("SYNC_VEHICLE_MODEL", "S2C"));
        values.put(23, new Definition("SWING_ARM", "C2S"));
        values.put(51, new Definition("VERSION_CHECK", "S2C"));
        values.put(52, new Definition("VERSION_CHECK", "C2S"));
        return Map.copyOf(values);
    }

    public record Definition(String name, String direction) {
    }
}
