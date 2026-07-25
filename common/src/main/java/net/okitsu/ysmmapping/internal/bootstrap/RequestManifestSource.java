package net.okitsu.ysmmapping.internal.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

public record RequestManifestSource(String consumerModId, Path path) {
    public RequestManifestSource {
        Objects.requireNonNull(consumerModId, "consumerModId");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
