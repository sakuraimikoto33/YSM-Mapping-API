package net.okitsu.ysmmapping.internal.analysis;

import java.util.Locale;

public record YsmArtifact(String id, String minecraftVersion, String loader,
                          String ysmVersion, String sha512) {
    public YsmArtifact {
        id = requireText(id, "id");
        minecraftVersion = requireText(minecraftVersion, "minecraftVersion");
        loader = requireText(loader, "loader").toLowerCase(Locale.ROOT);
        ysmVersion = requireText(ysmVersion, "ysmVersion");
        sha512 = requireText(sha512, "sha512").toLowerCase(Locale.ROOT);
        if (!loader.equals("fabric") && !loader.equals("neoforge")) {
            throw new IllegalArgumentException("Unsupported loader: " + loader);
        }
        if (!sha512.matches("[0-9a-f]{128}")) {
            throw new IllegalArgumentException("Invalid SHA-512 for " + id);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
