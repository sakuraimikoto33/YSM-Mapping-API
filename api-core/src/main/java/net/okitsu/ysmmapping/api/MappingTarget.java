package net.okitsu.ysmmapping.api;

import java.util.Objects;

public record MappingTarget(String minecraftVersion, String loader, String ysmVersion,
                            String contentSha512) {
    public MappingTarget {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(ysmVersion, "ysmVersion");
        Objects.requireNonNull(contentSha512, "contentSha512");
        if (!contentSha512.matches("[0-9a-f]{128}")) {
            throw new IllegalArgumentException("Invalid content SHA-512");
        }
    }
}
