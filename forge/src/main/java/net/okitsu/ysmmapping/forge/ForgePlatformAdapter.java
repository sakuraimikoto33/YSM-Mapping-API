package net.okitsu.ysmmapping.forge;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;
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

public final class ForgePlatformAdapter implements PlatformAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_mapping_api");
    private static final String[] REQUEST = {"META-INF", "ysm-mapping-api", "requests-v1.json"};

    @Override
    public String loader() {
        return "forge";
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public YsmInstallation ysmInstallation() {
        var ysm = loadingMods().getModFileById("yes_steve_model");
        if (ysm == null) {
            throw new IllegalStateException("Yes Steve Model is not loaded");
        }
        String version = ysm.getMods().stream().filter(mod ->
                        mod.getModId().equals("yes_steve_model"))
                .findFirst().orElseThrow().getVersion().toString();
        return new YsmInstallation(FMLLoader.versionInfo().mcVersion(), loader(), version,
                ysm.getFile().getFilePath());
    }

    @Override
    public List<RequestManifestSource> requestManifests() {
        List<RequestManifestSource> result = new ArrayList<>();
        for (var mod : loadingMods().getMods()) {
            Path path = mod.getOwningFile().getFile().findResource(REQUEST);
            if (Files.isRegularFile(path)) {
                result.add(new RequestManifestSource(mod.getModId(), path));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<String> ownerOfClass(String binaryClassName) {
        String[] resource = binaryClassName.replace('.', '/').split("/");
        String[] classResource = java.util.Arrays.copyOf(resource, resource.length);
        classResource[classResource.length - 1] += ".class";
        return loadingMods().getMods().stream().filter(mod -> Files.isRegularFile(
                        mod.getOwningFile().getFile().findResource(classResource)))
                .map(mod -> mod.getModId()).findFirst();
    }

    private static LoadingModList loadingMods() {
        LoadingModList mods = FMLLoader.getLoadingModList();
        if (mods == null) {
            throw new IllegalStateException("Forge mod discovery has not completed");
        }
        return mods;
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
