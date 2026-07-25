package net.okitsu.ysmmapping.internal.bootstrap;

import net.okitsu.ysmmapping.api.MappingEntry;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.ResolutionPolicy;
import net.okitsu.ysmmapping.api.YsmMappingApi;
import net.okitsu.ysmmapping.internal.cache.MappingEngine;
import net.okitsu.ysmmapping.internal.mixin.YsmRuntimeRemapper;
import net.okitsu.ysmmapping.internal.mixin.MixinAliasValidator;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.ServiceLoader;

public final class MappingBootstrap {
    private static volatile State state;

    private MappingBootstrap() {
    }

    public static State initialize() {
        State current = state;
        if (current != null) {
            return current;
        }
        synchronized (MappingBootstrap.class) {
            current = state;
            if (current != null) {
                return current;
            }
            PlatformAdapter platform = ServiceLoader.load(PlatformAdapter.class,
                            MappingBootstrap.class.getClassLoader()).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No YSM Mapping API platform adapter is available"));
            Map<String, RequestManifest> manifests = Map.of();
            try {
                ManifestCollection collected = readManifests(platform);
                manifests = collected.manifests();
                MappingEngine engine = new MappingEngine(platform, manifests);
                MappingSnapshot snapshot = engine.initialize();
                YsmRuntimeRemapper.install(snapshot, platform.loader(), engine.policy(),
                        manifests);
                YsmMappingApi.install(engine);
                current = new State(platform, engine.policy(), snapshot, manifests,
                        collected.invalidConsumers(), null);
            } catch (IOException | RuntimeException exception) {
                platform.warn("YSM Mapping API initialization failed; YSM integrations are disabled",
                        exception);
                current = new State(platform, ResolutionPolicy.SAFE_ONLY, null, manifests,
                        Set.of(), exception);
            }
            state = current;
            return current;
        }
    }

    public static boolean shouldApplyMixin(String mixinClassName) {
        State current = initialize();
        if (current.snapshot() == null) {
            current.platform().warn("Skipping mixin " + mixinClassName
                    + " because YSM mappings did not initialize", current.failure());
            return false;
        }
        String owner = current.platform().ownerOfClass(mixinClassName).orElse(null);
        if (owner != null && current.invalidConsumers().contains(owner)) {
            current.platform().warn("Skipping mixin " + mixinClassName
                    + " because its consumer manifest is invalid", null);
            return false;
        }
        for (Map.Entry<String, RequestManifest> consumer : current.manifests().entrySet()) {
            RequestManifest manifest = consumer.getValue();
            List<String> requirements = manifest.mixinRequirements().get(mixinClassName);
            if (requirements == null) {
                continue;
            }
            String aliasProblem = manifest.mixinAliasProblem(mixinClassName);
            if (aliasProblem != null) {
                current.platform().warn("Skipping mixin " + mixinClassName
                        + " because its YSM source alias is unavailable: " + aliasProblem, null);
                return false;
            }
            String compiledAliasProblem = MixinAliasValidator.validate(
                    MappingBootstrap.class.getClassLoader(), mixinClassName, manifest);
            if (compiledAliasProblem != null) {
                current.platform().warn("Skipping mixin " + mixinClassName
                        + " because its compiled aliases do not match the manifest: "
                        + compiledAliasProblem, null);
                return false;
            }
            for (String id : requirements) {
                String cacheId = manifest.cacheId(consumer.getKey(), id);
                MappingEntry entry = current.snapshot().entries().get(cacheId);
                if (entry == null || !allowed(entry, current.policy())) {
                    current.platform().warn("Skipping mixin " + mixinClassName
                            + " because YSM symbol is unavailable: " + id, null);
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean allowed(MappingEntry entry, ResolutionPolicy policy) {
        if (!entry.status().resolved()) {
            return false;
        }
        return entry.status() != net.okitsu.ysmmapping.api.ResolutionStatus.BEST_EFFORT
                || policy == ResolutionPolicy.BEST_EFFORT;
    }

    private static ManifestCollection readManifests(PlatformAdapter platform) {
        Map<String, RequestManifest> manifests = new LinkedHashMap<>();
        Set<String> invalid = new TreeSet<>();
        for (RequestManifestSource source : platform.requestManifests()) {
            if (invalid.contains(source.consumerModId())) continue;
            try {
                RequestManifest manifest = RequestManifest.read(source, platform.loader());
                manifests.merge(source.consumerModId(), manifest, RequestManifest::merge);
            } catch (IOException | RuntimeException exception) {
                manifests.remove(source.consumerModId());
                invalid.add(source.consumerModId());
                platform.warn("Ignoring invalid YSM mapping consumer "
                        + source.consumerModId(), exception);
            }
        }
        return new ManifestCollection(Map.copyOf(manifests), Set.copyOf(invalid));
    }

    public record State(PlatformAdapter platform, ResolutionPolicy policy,
                        MappingSnapshot snapshot, Map<String, RequestManifest> manifests,
                        Set<String> invalidConsumers,
                        Throwable failure) {
        public boolean hasDeclaredRequirements(String mixinClassName) {
            return manifests.values().stream().anyMatch(manifest ->
                    manifest.mixinRequirements().containsKey(mixinClassName));
        }
    }

    private record ManifestCollection(Map<String, RequestManifest> manifests,
                                      Set<String> invalidConsumers) {
    }
}
