package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmResolvedSymbol;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable-to-consumers registry of semantic YSM symbols known by this API version. */
public final class YsmSymbolRegistry {
    private final Map<String, YsmSymbolKey<?>> values = new TreeMap<>();

    YsmSymbolRegistry() {
    }

    synchronized <T extends YsmResolvedSymbol> YsmSymbolKey<T> register(
            YsmSymbolKey<T> key) {
        if (values.putIfAbsent(key.id(), key) != null) {
            throw new IllegalStateException("Duplicate YSM symbol key: " + key.id());
        }
        return key;
    }

    public synchronized Optional<YsmSymbolKey<?>> byId(String id) {
        return Optional.ofNullable(values.get(id));
    }

    public synchronized Collection<YsmSymbolKey<?>> all() {
        return java.util.List.copyOf(values.values());
    }
}
