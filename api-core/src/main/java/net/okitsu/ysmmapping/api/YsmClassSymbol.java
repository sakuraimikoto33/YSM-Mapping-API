package net.okitsu.ysmmapping.api;

import java.util.Objects;

public record YsmClassSymbol(String internalName) implements YsmResolvedSymbol {
    public YsmClassSymbol {
        Objects.requireNonNull(internalName, "internalName");
        if (internalName.isBlank() || internalName.indexOf('.') >= 0) {
            throw new IllegalArgumentException("Expected a non-empty JVM internal class name");
        }
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.CLASS;
    }

    public String binaryName() {
        return internalName.replace('/', '.');
    }
}
