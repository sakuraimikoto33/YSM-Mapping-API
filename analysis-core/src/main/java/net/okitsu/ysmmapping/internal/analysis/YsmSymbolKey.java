package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;

import java.util.Locale;
import java.util.Objects;

/** Internal stable role key used by the profile-driven analyzers. */
public final class YsmSymbolKey<T extends YsmResolvedSymbol> {
    private final String id;
    private final SymbolKind kind;
    private final Class<T> symbolType;

    private YsmSymbolKey(String id, SymbolKind kind, Class<T> symbolType) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.symbolType = Objects.requireNonNull(symbolType, "symbolType");
    }

    static <T extends YsmResolvedSymbol> YsmSymbolKey<T> curated(
            String id, SymbolKind kind, Class<T> symbolType) {
        String suffix = "." + kind.name().toLowerCase(Locale.ROOT);
        if (!id.matches("ysm\\.[a-z0-9_.-]+\\.(class|method|field)")
                || !id.endsWith(suffix)) {
            throw new IllegalArgumentException("Invalid analysis symbol key: " + id);
        }
        return new YsmSymbolKey<>(id, kind, symbolType);
    }

    public String id() {
        return id;
    }

    public SymbolKind kind() {
        return kind;
    }

    public Class<T> symbolType() {
        return symbolType;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof YsmSymbolKey<?> key
                && id.equals(key.id) && kind == key.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind);
    }

    @Override
    public String toString() {
        return id;
    }
}
