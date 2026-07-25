package net.okitsu.ysmmapping.api;

import java.util.Objects;

/** Typed key for a curated semantic symbol or a consumer-owned structural definition. */
public final class YsmSymbolKey<T extends YsmResolvedSymbol> {
    private static final String LOCAL_ID_PATTERN = "[a-z][a-z0-9_.-]{1,127}";

    private final String id;
    private final SymbolKind kind;
    private final int definitionRevision;
    private final Class<T> symbolType;
    private final SymbolOrigin origin;
    private final String definitionSha256;
    private final YsmSourceAlias sourceAlias;
    private final YsmStructurePattern structurePattern;

    private YsmSymbolKey(String id, SymbolKind kind, int definitionRevision,
                         Class<T> symbolType, SymbolOrigin origin, String definitionSha256,
                         YsmSourceAlias sourceAlias, YsmStructurePattern structurePattern) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.symbolType = Objects.requireNonNull(symbolType, "symbolType");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.definitionSha256 = Objects.requireNonNull(definitionSha256, "definitionSha256");
        this.sourceAlias = sourceAlias;
        this.structurePattern = structurePattern;
        if (definitionRevision != 1) {
            throw new IllegalArgumentException("YSM mapping definitions remain at revision 1");
        }
        this.definitionRevision = definitionRevision;
    }

    public static YsmSymbolKey<YsmClassSymbol> consumerClass(String localId,
            YsmSourceAlias sourceAlias, YsmStructurePattern pattern) {
        return consumer(localId, SymbolKind.CLASS, YsmClassSymbol.class, sourceAlias, pattern);
    }

    public static YsmSymbolKey<YsmMethodSymbol> consumerMethod(String localId,
            YsmSourceAlias sourceAlias, YsmStructurePattern pattern) {
        return consumer(localId, SymbolKind.METHOD, YsmMethodSymbol.class, sourceAlias, pattern);
    }

    public static YsmSymbolKey<YsmFieldSymbol> consumerField(String localId,
            YsmSourceAlias sourceAlias, YsmStructurePattern pattern) {
        return consumer(localId, SymbolKind.FIELD, YsmFieldSymbol.class, sourceAlias, pattern);
    }

    static <T extends YsmResolvedSymbol> YsmSymbolKey<T> curated(String id,
            SymbolKind kind, Class<T> symbolType) {
        Objects.requireNonNull(id, "id");
        if (!id.matches("ysm\\.[a-z0-9_.-]+\\.(class|method|field)")) {
            throw new IllegalArgumentException("Invalid curated YSM symbol key: " + id);
        }
        String expectedSuffix = '.' + kind.name().toLowerCase(java.util.Locale.ROOT);
        if (!id.endsWith(expectedSuffix)) {
            throw new IllegalArgumentException("Curated YSM symbol kind does not match ID: " + id);
        }
        // The structural definition and its digest are owned by common's definition registry.
        return new YsmSymbolKey<>(id, kind, 1, symbolType, SymbolOrigin.CURATED,
                "", null, null);
    }

    private static <T extends YsmResolvedSymbol> YsmSymbolKey<T> consumer(String localId,
            SymbolKind kind, Class<T> symbolType, YsmSourceAlias sourceAlias,
            YsmStructurePattern pattern) {
        Objects.requireNonNull(localId, "localId");
        Objects.requireNonNull(sourceAlias, "sourceAlias");
        Objects.requireNonNull(pattern, "pattern");
        if (!localId.matches(LOCAL_ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid consumer-local symbol ID: " + localId);
        }
        if (sourceAlias.kind() != kind) {
            throw new IllegalArgumentException("Source alias kind does not match " + kind);
        }
        // A source alias identifies consumer bytecode. It is deliberately excluded from the
        // structural definition digest so changing a Mixin alias never re-analyzes the YSM JAR.
        String definition = "v1|consumer|" + kind + '|' + localId + '|'
                + pattern.canonicalForm();
        return new YsmSymbolKey<>(localId, kind, 1, symbolType, SymbolOrigin.CONSUMER_DEFINED,
                YsmSymbolSignatures.sha256(definition), sourceAlias, pattern);
    }

    public String id() {
        return id;
    }

    public SymbolKind kind() {
        return kind;
    }

    public int definitionRevision() {
        return definitionRevision;
    }

    public Class<T> symbolType() {
        return symbolType;
    }

    public SymbolOrigin origin() {
        return origin;
    }

    public String definitionSha256() {
        return definitionSha256;
    }

    public YsmSourceAlias sourceAlias() {
        return sourceAlias;
    }

    public YsmStructurePattern structurePattern() {
        return structurePattern;
    }

    public String scopedId(String consumerModId) {
        if (origin == SymbolOrigin.CURATED) {
            return id;
        }
        Objects.requireNonNull(consumerModId, "consumerModId");
        if (!consumerModId.matches("[a-z][a-z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("Invalid consumer mod ID: " + consumerModId);
        }
        return "@consumer/" + consumerModId + '/' + id;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof YsmSymbolKey<?> key
                && id.equals(key.id) && kind == key.kind && origin == key.origin
                && definitionSha256.equals(key.definitionSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, origin, definitionSha256);
    }

    @Override
    public String toString() {
        return id;
    }
}
