package net.okitsu.ysmmapping.internal.mixin;

import net.okitsu.ysmmapping.api.MappingEntry;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.MappingTarget;
import net.okitsu.ysmmapping.api.ResolutionPolicy;
import net.okitsu.ysmmapping.api.ResolutionStatus;
import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmSourceAlias;
import net.okitsu.ysmmapping.api.YsmSymbols;
import net.okitsu.ysmmapping.internal.bootstrap.RequestManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YsmRuntimeRemapperTest {
    @Test
    void remapsConsumerOwnedOwnersMethodsAndVirtualDescriptorTypes() throws Exception {
        String aliasOwner = "net.okitsu.serverlessysm.ysmref.ServerModelManager";
        String aliasDescriptor = "(Lnet/okitsu/serverlessysm/ysmref/YsmConnection;"
                + "Lnet/okitsu/serverlessysm/ysmref/ServerModelTransfer;)V";
        String targetOwner = "com/elfmcys/yesstevemodel/RenamedManager";
        String targetDescriptor = "(Lnet/minecraft/class_2535;"
                + "Lcom/elfmcys/yesstevemodel/RenamedManager$Transfer;)V";
        YsmSourceAlias classAlias = YsmSourceAlias.classAlias(
                "net.okitsu.serverlessysm.ysmref.NetworkRegistration");
        YsmSourceAlias methodAlias = YsmSourceAlias.methodAlias(aliasOwner,
                "streamCallback", aliasDescriptor);

        Map<String, MappingEntry> entries = new TreeMap<>();
        entries.put(YsmSymbols.REGISTRATION_CLASS.id(), new MappingEntry(
                YsmSymbols.REGISTRATION_CLASS, ResolutionStatus.STRUCTURAL, 1.0,
                new YsmClassSymbol("com/elfmcys/yesstevemodel/RenamedRegistration"),
                List.of(), null));
        entries.put(YsmSymbols.SERVER_MODEL_STREAM_CALLBACK.id(), new MappingEntry(
                YsmSymbols.SERVER_MODEL_STREAM_CALLBACK, ResolutionStatus.STRUCTURAL, 1.0,
                new YsmMethodSymbol(targetOwner, "renamedCallback", targetDescriptor),
                List.of(), null));
        MappingSnapshot snapshot = new MappingSnapshot(new MappingTarget("1.21.1", "fabric",
                "future", "b".repeat(128)), entries);
        RequestManifest manifest = new RequestManifest(Map.of(
                YsmSymbols.REGISTRATION_CLASS, true,
                YsmSymbols.SERVER_MODEL_STREAM_CALLBACK, true), Map.of(
                YsmSymbols.REGISTRATION_CLASS.id(), classAlias,
                YsmSymbols.SERVER_MODEL_STREAM_CALLBACK.id(), methodAlias), Map.of(), Map.of());
        YsmRuntimeRemapper remapper = new YsmRuntimeRemapper(snapshot, "fabric",
                ResolutionPolicy.SAFE_ONLY, Map.of("serverless_ysm", manifest));

        assertEquals("com/elfmcys/yesstevemodel/RenamedRegistration",
                remapper.map("net/okitsu/serverlessysm/ysmref/NetworkRegistration"));
        assertEquals(targetOwner, remapper.map(aliasOwner.replace('.', '/')));
        assertEquals("renamedCallback", remapper.mapMethodName(aliasOwner.replace('.', '/'),
                "streamCallback", aliasDescriptor));
        assertEquals(targetDescriptor, remapper.mapDesc(aliasDescriptor));
        assertEquals("renamedCallback" + targetDescriptor,
                remapper.mapReference("streamCallback" + aliasDescriptor));
    }
}
