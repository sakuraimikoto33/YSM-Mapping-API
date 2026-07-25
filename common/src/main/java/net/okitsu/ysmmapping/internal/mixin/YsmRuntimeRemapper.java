package net.okitsu.ysmmapping.internal.mixin;

import net.okitsu.ysmmapping.api.MappingEntry;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.ResolutionPolicy;
import net.okitsu.ysmmapping.api.ResolutionStatus;
import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmFieldSymbol;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSourceAlias;
import net.okitsu.ysmmapping.api.mixin.YsmMappingReferenceMapper;
import net.okitsu.ysmmapping.internal.bootstrap.RequestManifest;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IRemapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Remaps consumer-owned stable aliases to symbols resolved from the loaded YSM. */
public final class YsmRuntimeRemapper implements IRemapper {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private final Map<String, String> classes = new HashMap<>();
    private final Map<Member, String> methods = new HashMap<>();
    private final Map<Member, String> fields = new HashMap<>();
    private final Map<String, String> references = new HashMap<>();
    private final java.util.Set<String> ambiguousReferences = new java.util.HashSet<>();

    YsmRuntimeRemapper(MappingSnapshot snapshot, String loader, ResolutionPolicy policy,
                       Map<String, RequestManifest> manifests)
            throws IOException {
        for (Map.Entry<String, RequestManifest> consumer : manifests.entrySet()) {
            for (Map.Entry<String, YsmSourceAlias> alias
                    : consumer.getValue().sourceAliases().entrySet()) {
                YsmSymbolKey<?> key = consumer.getValue().symbols().keySet().stream()
                        .filter(value -> value.id().equals(alias.getKey()))
                        .findFirst().orElse(null);
                if (key == null) continue;
                MappingEntry targetEntry = snapshot.entries().get(key.scopedId(consumer.getKey()));
                if (targetEntry == null || !allowed(targetEntry, policy)) continue;
                addAlias(alias.getValue(), targetEntry.resolved());
            }
        }
    }

    YsmRuntimeRemapper(MappingSnapshot snapshot, String loader, ResolutionPolicy policy)
            throws IOException {
        this(snapshot, loader, policy, Map.of());
    }

    public static void install(MappingSnapshot snapshot, String loader, ResolutionPolicy policy)
            throws IOException {
        install(snapshot, loader, policy, Map.of());
    }

    public static void install(MappingSnapshot snapshot, String loader, ResolutionPolicy policy,
                               Map<String, RequestManifest> manifests) throws IOException {
        YsmRuntimeRemapper remapper = new YsmRuntimeRemapper(snapshot, loader, policy, manifests);
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        YsmMappingReferenceMapper.installRuntimeMappings(remapper::map, remapper::mapReference);
        MixinEnvironment defaults = MixinEnvironment.getDefaultEnvironment();
        defaults.getRemappers().add(remapper);
        MixinEnvironment current = MixinEnvironment.getCurrentEnvironment();
        if (current != defaults) {
            current.getRemappers().add(remapper);
        }
    }

    private void addAlias(YsmSourceAlias alias, YsmResolvedSymbol target) {
        String owner = alias.owner().replace('.', '/');
        if (target instanceof YsmClassSymbol to) {
            classes.put(owner, to.internalName());
        } else if (target instanceof YsmMethodSymbol to) {
            classes.putIfAbsent(owner, to.owner());
            mapDescriptorTypes(owner, alias.descriptor(), to.descriptor());
            methods.put(new Member(owner, alias.name(), alias.descriptor()), to.name());
            putReference(alias.name() + alias.descriptor(), to.name() + to.descriptor());
        } else if (target instanceof YsmFieldSymbol to) {
            classes.putIfAbsent(owner, to.owner());
            mapDescriptorTypes(owner, alias.descriptor(), to.descriptor());
            fields.put(new Member(owner, alias.name(), alias.descriptor()), to.name());
            putReference(alias.name() + ":" + alias.descriptor(),
                    to.name() + ":" + to.descriptor());
        }
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        return methods.getOrDefault(new Member(owner, name, descriptor), name);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return fields.getOrDefault(new Member(owner, name, descriptor), name);
    }

    @Override
    public String map(String typeName) {
        boolean binaryName = typeName.indexOf('.') >= 0;
        String internalName = binaryName ? typeName.replace('.', '/') : typeName;
        String mapped = classes.getOrDefault(internalName, internalName);
        return binaryName ? mapped.replace('/', '.') : mapped;
    }

    @Override
    public String unmap(String typeName) {
        return classes.entrySet().stream().filter(entry -> entry.getValue().equals(typeName))
                .map(Map.Entry::getKey).findFirst().orElse(typeName);
    }

    @Override
    public String mapDesc(String descriptor) {
        return transformDescriptor(descriptor, false);
    }

    @Override
    public String unmapDesc(String descriptor) {
        return transformDescriptor(descriptor, true);
    }

    private String transformDescriptor(String descriptor, boolean reverse) {
        StringBuilder mapped = new StringBuilder(descriptor.length());
        int cursor = 0;
        while (cursor < descriptor.length()) {
            int marker = descriptor.indexOf('L', cursor);
            if (marker < 0) {
                mapped.append(descriptor, cursor, descriptor.length());
                break;
            }
            int end = descriptor.indexOf(';', marker);
            if (end < 0) {
                return descriptor;
            }
            mapped.append(descriptor, cursor, marker + 1);
            String type = descriptor.substring(marker + 1, end);
            mapped.append(reverse ? unmap(type) : map(type)).append(';');
            cursor = end + 1;
        }
        return mapped.toString();
    }

    private void mapDescriptorTypes(String aliasOwner, String source, String target) {
        java.util.List<String> sourceTypes = descriptorTypes(source);
        java.util.List<String> targetTypes = descriptorTypes(target);
        if (sourceTypes.size() != targetTypes.size()) {
            return;
        }
        int separator = aliasOwner.lastIndexOf('/');
        String aliasPackage = separator < 0 ? "" : aliasOwner.substring(0, separator + 1);
        for (int index = 0; index < sourceTypes.size(); index++) {
            String from = sourceTypes.get(index);
            String to = targetTypes.get(index);
            if (!from.equals(to) && !aliasPackage.isEmpty() && from.startsWith(aliasPackage)) {
                classes.putIfAbsent(from, to);
            }
        }
    }

    private void putReference(String source, String target) {
        if (ambiguousReferences.contains(source)) {
            return;
        }
        String previous = references.putIfAbsent(source, target);
        if (previous != null && !previous.equals(target)) {
            references.remove(source);
            ambiguousReferences.add(source);
        }
    }

    String mapReference(String reference) {
        return references.getOrDefault(reference, reference);
    }

    private static java.util.List<String> descriptorTypes(String descriptor) {
        java.util.List<String> result = new java.util.ArrayList<>();
        int cursor = 0;
        while ((cursor = descriptor.indexOf('L', cursor)) >= 0) {
            int end = descriptor.indexOf(';', cursor);
            if (end < 0) {
                break;
            }
            result.add(descriptor.substring(cursor + 1, end));
            cursor = end + 1;
        }
        return result;
    }

    private static boolean allowed(MappingEntry entry, ResolutionPolicy policy) {
        return entry.status().resolved()
                && (entry.status() != ResolutionStatus.BEST_EFFORT
                || policy == ResolutionPolicy.BEST_EFFORT);
    }

    private record Member(String owner, String name, String descriptor) {
    }
}
