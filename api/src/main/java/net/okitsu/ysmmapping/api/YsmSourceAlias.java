package net.okitsu.ysmmapping.api;

import java.util.Objects;

/** Consumer-owned bytecode name that the early Mixin remapper replaces at runtime. */
public record YsmSourceAlias(SymbolKind kind, String owner, String name, String descriptor) {
    public YsmSourceAlias {
        Objects.requireNonNull(kind, "kind");
        YsmSymbolSignatures.requireBinaryName(owner, "source alias owner");
        if (owner.startsWith("com.elfmcys.yesstevemodel.")) {
            throw new IllegalArgumentException("Consumer aliases cannot claim the YSM namespace");
        }
        switch (kind) {
            case CLASS -> {
                if (name != null || descriptor != null) {
                    throw new IllegalArgumentException("Class aliases contain only an owner");
                }
            }
            case METHOD -> {
                YsmSymbolSignatures.requireMethodName(name);
                YsmSymbolSignatures.requireMethodDescriptor(descriptor);
            }
            case FIELD -> {
                YsmSymbolSignatures.requireJavaIdentifier(name, "source alias field name");
                YsmSymbolSignatures.requireFieldDescriptor(descriptor);
            }
        }
    }

    public static YsmSourceAlias classAlias(String binaryName) {
        return new YsmSourceAlias(SymbolKind.CLASS, binaryName, null, null);
    }

    public static YsmSourceAlias methodAlias(String owner, String name, String descriptor) {
        return new YsmSourceAlias(SymbolKind.METHOD, owner, name, descriptor);
    }

    public static YsmSourceAlias fieldAlias(String owner, String name, String descriptor) {
        return new YsmSourceAlias(SymbolKind.FIELD, owner, name, descriptor);
    }

    String canonicalForm() {
        return kind + "|" + owner + "|" + Objects.toString(name, "") + "|"
                + Objects.toString(descriptor, "");
    }
}
