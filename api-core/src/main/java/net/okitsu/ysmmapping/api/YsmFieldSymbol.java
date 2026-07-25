package net.okitsu.ysmmapping.api;

import java.util.Objects;

public record YsmFieldSymbol(String owner, String name, String descriptor)
        implements YsmResolvedSymbol {
    public YsmFieldSymbol {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        if (owner.isBlank() || name.isBlank() || descriptor.isBlank()
                || descriptor.startsWith("(")) {
            throw new IllegalArgumentException("Invalid field symbol");
        }
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.FIELD;
    }

    public String binaryOwner() {
        return owner.replace('/', '.');
    }
}
