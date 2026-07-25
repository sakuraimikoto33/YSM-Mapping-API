package net.okitsu.ysmmapping.api;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

public final class YsmMappingApi {
    private static volatile YsmMappingProvider provider;

    private YsmMappingApi() {
    }

    public static void install(YsmMappingProvider installedProvider) {
        Objects.requireNonNull(installedProvider, "installedProvider");
        synchronized (YsmMappingApi.class) {
            if (provider != null && provider != installedProvider) {
                throw new IllegalStateException("YSM Mapping API provider is already installed");
            }
            provider = installedProvider;
        }
    }

    public static MappingSnapshot resolve(String consumerModId,
                                          Collection<YsmSymbolKey<?>> keys) throws IOException {
        if (consumerModId == null || !consumerModId.matches("[a-z][a-z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("Invalid consumer mod ID: " + consumerModId);
        }
        return requireProvider().resolve(consumerModId, ListCopy.copy(keys));
    }

    public static MappingSnapshot current() throws IOException {
        return requireProvider().current();
    }

    private static YsmMappingProvider requireProvider() {
        YsmMappingProvider current = provider;
        if (current == null) {
            throw new IllegalStateException("YSM Mapping API has not initialized");
        }
        return current;
    }

    private static final class ListCopy {
        private ListCopy() {
        }

        private static <T> java.util.List<T> copy(Collection<T> source) {
            return java.util.List.copyOf(Objects.requireNonNull(source, "keys"));
        }
    }
}
