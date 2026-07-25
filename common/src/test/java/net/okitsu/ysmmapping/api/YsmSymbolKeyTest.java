package net.okitsu.ysmmapping.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YsmSymbolKeyTest {
    @Test
    void exposesOnlyTheApprovedSemanticRegistry() {
        var symbols = YsmSymbols.all();
        assertEquals(94, symbols.size());
        assertEquals(17, symbols.stream().filter(key -> key.kind() == SymbolKind.CLASS).count());
        assertEquals(66, symbols.stream().filter(key -> key.kind() == SymbolKind.METHOD).count());
        assertEquals(11, symbols.stream().filter(key -> key.kind() == SymbolKind.FIELD).count());
        assertTrue(symbols.stream().allMatch(key -> key.id().startsWith("ysm.")
                && key.id().endsWith("." + key.kind().name().toLowerCase())));
        assertTrue(symbols.stream().allMatch(key -> key.origin() == SymbolOrigin.CURATED));
        assertTrue(symbols.stream().noneMatch(key -> key.id().contains("fabric")
                || key.id().contains("neoforge") || key.id().matches(".*\\d{6}.*")
                || key.id().contains("com.elfmcys")));
    }

    @Test
    void scopesConsumerDefinitionsWithoutLoaderOrdinals() {
        var constraints = YsmStructureConstraints.builder()
                .requiredAccess(0x0008)
                .descriptorShape("(Ljava/nio/ByteBuffer;)V")
                .opcodeDigest("a".repeat(64))
                .externalReference("java/nio/ByteBuffer")
                .call("@ysm#*(Ljava/nio/ByteBuffer;)V")
                .build();
        var alias = YsmSourceAlias.methodAlias(
                "net.okitsu.serverlessysm.ysmref.ClientFrameSender", "send",
                "(Ljava/nio/ByteBuffer;)V");
        var first = YsmSymbolKey.consumerMethod("client_frame_sender", alias,
                YsmStructurePattern.common(constraints));
        var reordered = YsmSymbolKey.consumerMethod("client_frame_sender", alias,
                YsmStructurePattern.common(YsmStructureConstraints.builder()
                        .call("@ysm#*(Ljava/nio/ByteBuffer;)V")
                        .externalReference("java/nio/ByteBuffer")
                        .opcodeDigest("a".repeat(64))
                        .descriptorShape("(Ljava/nio/ByteBuffer;)V")
                        .requiredAccess(0x0008).build()));

        assertEquals(first, reordered);
        assertEquals("@consumer/serverless_ysm/client_frame_sender",
                first.scopedId("serverless_ysm"));
        assertEquals(SymbolOrigin.CONSUMER_DEFINED, first.origin());
        assertNotEquals(first.definitionSha256(), YsmSymbolKey.consumerMethod(
                "client_frame_sender", alias,
                YsmStructurePattern.common(YsmStructureConstraints.builder()
                        .requiredAccess(0x0001).build())).definitionSha256());
        var alternateAlias = YsmSourceAlias.methodAlias(
                "net.okitsu.serverlessysm.ysmref.AlternateSender", "rawSend",
                "(Ljava/nio/ByteBuffer;)V");
        assertEquals(first.definitionSha256(), YsmSymbolKey.consumerMethod(
                "client_frame_sender", alternateAlias,
                YsmStructurePattern.common(constraints)).definitionSha256());
    }

    @Test
    void rejectsAliasesThatPretendToBeRealYsmNames() {
        assertThrows(IllegalArgumentException.class, () -> YsmSourceAlias.classAlias(
                "com.elfmcys.yesstevemodel.fake.ConsumerAlias"));
        assertThrows(IllegalArgumentException.class, () -> new YsmStructureConstraints(
                1, 1, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()));
    }
}
