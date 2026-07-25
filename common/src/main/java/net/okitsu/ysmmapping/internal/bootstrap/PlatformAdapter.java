package net.okitsu.ysmmapping.internal.bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface PlatformAdapter {
    String loader();

    Path configDirectory();

    YsmInstallation ysmInstallation();

    List<RequestManifestSource> requestManifests();

    default Optional<String> ownerOfClass(String binaryClassName) {
        return Optional.empty();
    }

    void info(String message);

    void warn(String message, Throwable failure);
}
