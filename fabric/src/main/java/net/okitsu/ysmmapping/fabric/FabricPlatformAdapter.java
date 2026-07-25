package net.okitsu.ysmmapping.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.okitsu.ysmmapping.internal.bootstrap.PlatformAdapter;
import net.okitsu.ysmmapping.internal.bootstrap.RequestManifestSource;
import net.okitsu.ysmmapping.internal.bootstrap.YsmInstallation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FabricPlatformAdapter implements PlatformAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_mapping_api");
    private static final String REQUEST = "META-INF/ysm-mapping-api/requests-v1.json";

    @Override
    public String loader() {
        return "fabric";
    }

    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public YsmInstallation ysmInstallation() {
        ModContainer ysm = FabricLoader.getInstance().getModContainer("yes_steve_model")
                .orElseThrow(() -> new IllegalStateException("Yes Steve Model is not loaded"));
        Path source = ysm.getOrigin().getPaths().stream()
                .filter(path -> Files.isDirectory(path) || path.toString().endsWith(".jar"))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Unable to locate the loaded Yes Steve Model source"));
        return new YsmInstallation(
                FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                        .getMetadata().getVersion().getFriendlyString(),
                loader(), ysm.getMetadata().getVersion().getFriendlyString(), source);
    }

    @Override
    public List<RequestManifestSource> requestManifests() {
        List<RequestManifestSource> result = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            mod.findPath(REQUEST).ifPresent(path -> result.add(
                    new RequestManifestSource(mod.getMetadata().getId(), path)));
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<String> ownerOfClass(String binaryClassName) {
        String resource = binaryClassName.replace('.', '/') + ".class";
        return FabricLoader.getInstance().getAllMods().stream()
                .filter(mod -> mod.findPath(resource).isPresent())
                .map(mod -> mod.getMetadata().getId()).findFirst();
    }

    @Override
    public void info(String message) {
        LOGGER.info(message);
    }

    @Override
    public void warn(String message, Throwable failure) {
        if (failure == null) {
            LOGGER.warn(message);
        } else {
            LOGGER.warn(message, failure);
        }
    }
}
