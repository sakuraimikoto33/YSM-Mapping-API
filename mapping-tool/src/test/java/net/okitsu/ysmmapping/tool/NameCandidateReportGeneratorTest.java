package net.okitsu.ysmmapping.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameCandidateReportGeneratorTest {
    @TempDir
    Path temporary;

    @Test
    void reviewSetUsesSemanticKeysWithoutLoaderSequenceNames() throws Exception {
        Path spec = temporary.resolve("candidates.json");
        Files.writeString(spec, """
                {
                  "formatVersion": 1,
                  "minecraftVersion": "test-mc",
                  "ysmVersion": "test-ysm",
                  "fixtures": {"alpha": "alpha.jar"},
                  "candidates": [
                    {
                      "semanticKey": "ysm.test.class",
                      "kind": "CLASS",
                      "open": {"owner": "example.Open"},
                      "port": {"owner": "example.Port"}
                    },
                    {
                      "semanticKey": "ysm.test.method",
                      "kind": "METHOD",
                      "open": {"owner": "example.Open", "name": "method"},
                      "port": {"owner": "example.Port", "name": "method"}
                    },
                    {
                      "semanticKey": "ysm.test.field",
                      "kind": "FIELD",
                      "open": {"owner": "example.Open", "name": "field"},
                      "port": {"owner": "example.Port", "name": "field"}
                    }
                  ]
                }
                """);
        List<String> keys = NameCandidateReportGenerator.canonicalCandidateKeys(spec);

        assertEquals(keys.size(), new HashSet<>(keys).size());
        assertEquals(3, keys.size());
        assertTrue(keys.stream().allMatch(value -> value.startsWith("ysm.")));
        assertTrue(keys.stream().anyMatch(value -> value.endsWith(".class")));
        assertTrue(keys.stream().anyMatch(value -> value.endsWith(".method")));
        assertTrue(keys.stream().anyMatch(value -> value.endsWith(".field")));
        assertFalse(keys.stream().anyMatch(value ->
                value.matches(".*class:(fabric|neoforge):\\d+.*")));
    }
}
