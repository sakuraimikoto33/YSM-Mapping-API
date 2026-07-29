package net.okitsu.ysmmapping.internal.bootstrap;

import net.okitsu.ysmmapping.api.YsmSymbols;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestManifestTest {
    @TempDir
    Path temporary;

    @Test
    void derivesConsumerFromTheOwningContainerAndValidatesMixinKeys() throws Exception {
        Path path = write("""
                {
                  "schemaVersion": 1,
                  "symbols": [
                    {
                      "key":"ysm.network.registration.class","kind":"CLASS","required":true,
                      "sourceAlias":{"common":{"owner":"net/okitsu/example/ysmref/NetworkRegistration"}}
                    }
                  ],
                  "mixinRequirements": {
                    "example.ValidMixin": ["ysm.network.registration.class"]
                  }
                }
                """);

        RequestManifest manifest = RequestManifest.read(
                new RequestManifestSource("owner_mod", path));

        assertEquals(Boolean.TRUE, manifest.symbols().get(YsmSymbols.REGISTRATION_CLASS));
        assertEquals(java.util.List.of(YsmSymbols.REGISTRATION_CLASS.id()),
                manifest.mixinRequirements().get("example.ValidMixin"));
        assertEquals("net.okitsu.example.ysmref.NetworkRegistration",
                manifest.sourceAliases().get(YsmSymbols.REGISTRATION_CLASS.id()).owner());
    }

    @Test
    void rejectsMixinKeysThatWereNotRequestedByTheConsumer() throws Exception {
        Path path = write("""
                {
                  "schemaVersion": 1,
                  "symbols": [
                    {"key":"ysm.network.registration.class","kind":"CLASS","required":true}
                  ],
                  "mixinRequirements": {
                    "example.InvalidMixin": ["ysm.client.send.method"]
                  }
                }
                """);

        IOException failure = assertThrows(IOException.class, () -> RequestManifest.read(
                new RequestManifestSource("owner_mod", path)));
        assertTrue(failure.getMessage().contains("is not declared in symbols"));
    }

    @Test
    void parsesAndScopesConsumerOwnedDefinitions() throws Exception {
        Path path = write("""
                {
                  "schemaVersion": 1,
                  "symbols": [{
                    "key":"client_frame_sender",
                    "kind":"METHOD",
                    "required":true,
                    "sourceAlias": {
                      "common": {
                        "owner":"net/okitsu/example/ClientFrameSender",
                        "name":"send",
                        "descriptor":"(Ljava/nio/ByteBuffer;)V"
                      },
                      "forge": {"name":"sendForge"}
                    },
                    "definition": {
                      "common": {"descriptorShapes":["(Ljava/nio/ByteBuffer;)V"]},
                      "fabric": {},
                      "forge": {}
                    }
                  }],
                  "mixinRequirements": {"example.Mixin":["client_frame_sender"]}
                }
                """);
        RequestManifest manifest = RequestManifest.read(
                new RequestManifestSource("owner_mod", path), "forge");
        var key = manifest.symbols().keySet().iterator().next();
        assertEquals("@consumer/owner_mod/client_frame_sender",
                key.scopedId("owner_mod"));
        assertTrue(key.definitionSha256().matches("[0-9a-f]{64}"));
        assertEquals("sendForge", manifest.sourceAliases().get("client_frame_sender").name());
    }

    @Test
    void missingCuratedAliasDisablesOnlyTheReferencingMixin() throws Exception {
        Path path = write("""
                {
                  "schemaVersion": 1,
                  "symbols": [
                    {"key":"ysm.network.registration.class","kind":"CLASS","required":true}
                  ],
                  "mixinRequirements": {"example.Mixin":["ysm.network.registration.class"]}
                }
                """);
        RequestManifest manifest = RequestManifest.read(
                new RequestManifestSource("owner_mod", path), "fabric");

        assertEquals("ysm.network.registration.class: Mixin source alias is required",
                manifest.mixinAliasProblem("example.Mixin"));
        assertTrue(manifest.symbols().containsKey(YsmSymbols.REGISTRATION_CLASS));
    }

    private Path write(String json) throws IOException {
        Path path = temporary.resolve("requests-v1.json");
        Files.writeString(path, json);
        return path;
    }
}
