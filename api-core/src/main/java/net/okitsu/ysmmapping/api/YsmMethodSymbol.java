package net.okitsu.ysmmapping.api;

import java.util.Objects;

public record YsmMethodSymbol(String owner, String name, String descriptor)
        implements YsmResolvedSymbol {
    public YsmMethodSymbol {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        if (owner.isBlank() || name.isBlank() || !descriptor.startsWith("(")) {
            throw new IllegalArgumentException("Invalid method symbol");
        }
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.METHOD;
    }

    public String binaryOwner() {
        return owner.replace('/', '.');
    }
}
