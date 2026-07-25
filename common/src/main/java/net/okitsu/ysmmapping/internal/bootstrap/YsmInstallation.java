package net.okitsu.ysmmapping.internal.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

public record YsmInstallation(String minecraftVersion, String loader, String ysmVersion,
                              Path source) {
    public YsmInstallation {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(ysmVersion, "ysmVersion");
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
    }
}
