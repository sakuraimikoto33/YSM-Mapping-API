package net.okitsu.ysmmapping.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MappingSnapshot {
    private final MappingTarget target;
    private final Map<String, MappingEntry> entries;
    private final String consumerModId;

    public MappingSnapshot(MappingTarget target, Map<String, MappingEntry> entries) {
        this(target, entries, null);
    }

    private MappingSnapshot(MappingTarget target, Map<String, MappingEntry> entries,
                            String consumerModId) {
        this.target = Objects.requireNonNull(target, "target");
        this.entries = Map.copyOf(entries);
        this.consumerModId = consumerModId;
    }

    public MappingTarget target() {
        return target;
    }

    public Map<String, MappingEntry> entries() {
        return entries;
    }

    public Optional<MappingEntry> entry(YsmSymbolKey<?> key) {
        return Optional.ofNullable(entries.get(cacheId(key)));
    }

    public <T extends YsmResolvedSymbol> T require(YsmSymbolKey<T> key) {
        MappingEntry entry = entries.get(cacheId(key));
        if (entry == null || !entry.status().resolved() || entry.resolved() == null) {
            throw new IllegalStateException("YSM symbol is unresolved: " + key.id());
        }
        return key.symbolType().cast(entry.resolved());
    }

    public boolean allResolved() {
        return entries.values().stream().allMatch(entry -> entry.status().resolved());
    }

    public MappingSnapshot forConsumer(String consumerModId) {
        return new MappingSnapshot(target, entries,
                Objects.requireNonNull(consumerModId, "consumerModId"));
    }

    private String cacheId(YsmSymbolKey<?> key) {
        if (key.origin() == SymbolOrigin.CURATED) {
            return key.id();
        }
        if (consumerModId == null) {
            throw new IllegalStateException("A consumer-scoped snapshot is required for "
                    + key.id());
        }
        return key.scopedId(consumerModId);
    }
}
