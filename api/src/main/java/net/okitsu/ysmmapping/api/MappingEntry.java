package net.okitsu.ysmmapping.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MappingEntry(YsmSymbolKey<?> key, ResolutionStatus status, double confidence,
                           YsmResolvedSymbol resolved, List<MappingCandidate> candidates,
                           String diagnostic) {
    public MappingEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
        if (status.resolved() != (resolved != null)) {
            throw new IllegalArgumentException("Resolution status and symbol disagree for " + key.id());
        }
    }

    public Optional<YsmResolvedSymbol> resolvedSymbol() {
        return Optional.ofNullable(resolved);
    }
}
