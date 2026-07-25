package net.okitsu.ysmmapping.api;

public sealed interface YsmResolvedSymbol permits YsmClassSymbol, YsmMethodSymbol, YsmFieldSymbol {
    SymbolKind kind();
}
