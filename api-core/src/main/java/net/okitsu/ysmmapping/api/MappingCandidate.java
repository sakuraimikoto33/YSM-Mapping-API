package net.okitsu.ysmmapping.api;

import java.util.Objects;

public record MappingCandidate(YsmResolvedSymbol symbol, double confidence) {
    public MappingCandidate {
        Objects.requireNonNull(symbol, "symbol");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
    }
}
