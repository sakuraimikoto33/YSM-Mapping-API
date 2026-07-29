package net.okitsu.ysmmapping.internal.cache;

import net.okitsu.ysmmapping.api.MappingCandidate;
import net.okitsu.ysmmapping.api.MappingEntry;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.MappingTarget;
import net.okitsu.ysmmapping.api.ResolutionPolicy;
import net.okitsu.ysmmapping.api.ResolutionStatus;
import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.SymbolOrigin;
import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmFieldSymbol;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import net.okitsu.ysmmapping.api.YsmSymbolKey;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class MappingsDocument {
    static final int SCHEMA_VERSION = 1;
    static final int FINGERPRINT_ALGORITHM = 1;

    int schemaVersion = SCHEMA_VERSION;
    int fingerprintAlgorithm = FINGERPRINT_ALGORITHM;
    String registryDefinitionSha256;
    String fingerprintDefinitionSha256;
    String resolutionPolicy;
    TargetJson target;
    Map<String, EntryJson> entries = new TreeMap<>();
    Map<String, ConsumerJson> consumers = new TreeMap<>();

    static MappingsDocument fresh(MappingTarget target, ResolutionPolicy policy,
                                  String registryDefinitionSha256,
                                  String fingerprintDefinitionSha256) {
        MappingsDocument document = new MappingsDocument();
        document.resolutionPolicy = policy.name();
        document.registryDefinitionSha256 = registryDefinitionSha256;
        document.fingerprintDefinitionSha256 = fingerprintDefinitionSha256;
        document.target = TargetJson.from(target);
        return document;
    }

    boolean matches(MappingTarget expected, ResolutionPolicy policy,
                    String expectedRegistry, String expectedFingerprint) {
        return schemaVersion == SCHEMA_VERSION
                && fingerprintAlgorithm == FINGERPRINT_ALGORITHM
                && policy.name().equals(resolutionPolicy)
                && expectedRegistry.equals(registryDefinitionSha256)
                && expectedFingerprint.equals(fingerprintDefinitionSha256)
                && target != null && target.matches(expected);
    }

    boolean valid() {
        return schemaVersion == SCHEMA_VERSION && fingerprintAlgorithm == FINGERPRINT_ALGORITHM
                && resolutionPolicy != null && target != null
                && registryDefinitionSha256 != null && fingerprintDefinitionSha256 != null
                && entries != null && consumers != null;
    }

    MappingSnapshot snapshot(Map<String, YsmSymbolKey<?>> keys) throws IOException {
        Map<String, MappingEntry> result = new TreeMap<>();
        for (Map.Entry<String, EntryJson> entry : entries.entrySet()) {
            YsmSymbolKey<?> key = keys.get(entry.getKey());
            if (key == null) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue().toApi(key));
        }
        return new MappingSnapshot(target.toApi(), result);
    }

    static final class TargetJson {
        String minecraftVersion;
        String loader;
        String ysmVersion;
        String contentSha512;

        static TargetJson from(MappingTarget source) {
            TargetJson json = new TargetJson();
            json.minecraftVersion = source.minecraftVersion();
            json.loader = source.loader();
            json.ysmVersion = source.ysmVersion();
            json.contentSha512 = source.contentSha512();
            return json;
        }

        boolean matches(MappingTarget source) {
            return source.minecraftVersion().equals(minecraftVersion)
                    && source.loader().equals(loader)
                    && source.ysmVersion().equals(ysmVersion)
                    && source.contentSha512().equals(contentSha512);
        }

        MappingTarget toApi() {
            return new MappingTarget(minecraftVersion, loader, ysmVersion, contentSha512);
        }
    }

    static final class EntryJson {
        int definitionRevision;
        String origin;
        String definitionSha256;
        String kind;
        String status;
        double confidence;
        ResolvedJson resolved;
        List<CandidateJson> candidates = new ArrayList<>();
        String diagnostic;

        static EntryJson resolved(YsmSymbolKey<?> key, String definitionSha256,
                                  ResolutionStatus status, YsmResolvedSymbol symbol) {
            EntryJson json = new EntryJson();
            json.definitionRevision = key.definitionRevision();
            json.origin = key.origin().name();
            json.definitionSha256 = definitionSha256;
            json.kind = key.kind().name();
            json.status = status.name();
            json.confidence = 1.0;
            json.resolved = ResolvedJson.from(symbol);
            return json;
        }

        static EntryJson incompatible(YsmSymbolKey<?> key, String definitionSha256,
                                      String diagnostic) {
            EntryJson json = new EntryJson();
            json.definitionRevision = key.definitionRevision();
            json.origin = key.origin().name();
            json.definitionSha256 = definitionSha256;
            json.kind = key.kind().name();
            json.status = ResolutionStatus.INCOMPATIBLE.name();
            json.confidence = 0.0;
            json.diagnostic = diagnostic;
            return json;
        }

        static EntryJson candidates(YsmSymbolKey<?> key, String definitionSha256,
                                    List<MappingCandidate> sourceCandidates,
                                    ResolutionPolicy policy, String diagnostic) {
            if (sourceCandidates == null || sourceCandidates.isEmpty()) {
                EntryJson json = new EntryJson();
                json.definitionRevision = key.definitionRevision();
                json.origin = key.origin().name();
                json.definitionSha256 = definitionSha256;
                json.kind = key.kind().name();
                json.status = ResolutionStatus.NOT_FOUND.name();
                json.confidence = 0.0;
                json.diagnostic = diagnostic;
                return json;
            }
            List<MappingCandidate> ordered = sourceCandidates.stream()
                    .sorted(java.util.Comparator.comparingDouble(MappingCandidate::confidence)
                            .reversed())
                    .toList();
            EntryJson json = new EntryJson();
            json.definitionRevision = key.definitionRevision();
            json.origin = key.origin().name();
            json.definitionSha256 = definitionSha256;
            json.kind = key.kind().name();
            json.confidence = ordered.get(0).confidence();
            json.candidates = ordered.stream().map(CandidateJson::from).toList();
            json.diagnostic = diagnostic;
            if (policy == ResolutionPolicy.BEST_EFFORT) {
                json.status = ResolutionStatus.BEST_EFFORT.name();
                json.resolved = ResolvedJson.from(ordered.get(0).symbol());
            } else {
                json.status = ResolutionStatus.AMBIGUOUS.name();
            }
            return json;
        }

        MappingEntry toApi(YsmSymbolKey<?> key) throws IOException {
            try {
                ResolutionStatus parsedStatus = ResolutionStatus.valueOf(status);
                SymbolKind parsedKind = SymbolKind.valueOf(kind);
                SymbolOrigin parsedOrigin = SymbolOrigin.valueOf(origin);
                if (parsedKind != key.kind() || parsedOrigin != key.origin()
                        || definitionRevision != key.definitionRevision()) {
                    throw new IOException("Stale cached YSM symbol: " + key.id());
                }
                YsmResolvedSymbol symbol = resolved == null ? null : resolved.toApi(parsedKind);
                List<MappingCandidate> parsedCandidates = candidates == null ? List.of()
                        : candidates.stream().map(candidate -> {
                            try {
                                return new MappingCandidate(candidate.toApi(parsedKind),
                                        candidate.confidence);
                            } catch (IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                        }).toList();
                return new MappingEntry(key, parsedStatus, confidence, symbol,
                        parsedCandidates, diagnostic);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw new IOException("Invalid cached YSM symbol: " + key.id(), exception);
            }
        }
    }

    static final class ResolvedJson {
        String internalName;
        String owner;
        String name;
        String descriptor;

        static ResolvedJson from(YsmResolvedSymbol symbol) {
            ResolvedJson json = new ResolvedJson();
            if (symbol instanceof YsmClassSymbol classSymbol) {
                json.internalName = classSymbol.internalName();
            } else if (symbol instanceof YsmMethodSymbol method) {
                json.owner = method.owner();
                json.name = method.name();
                json.descriptor = method.descriptor();
            } else if (symbol instanceof YsmFieldSymbol field) {
                json.owner = field.owner();
                json.name = field.name();
                json.descriptor = field.descriptor();
            }
            return json;
        }

        YsmResolvedSymbol toApi(SymbolKind kind) throws IOException {
            try {
                return switch (kind) {
                    case CLASS -> new YsmClassSymbol(internalName);
                    case METHOD -> new YsmMethodSymbol(owner, name, descriptor);
                    case FIELD -> new YsmFieldSymbol(owner, name, descriptor);
                };
            } catch (RuntimeException exception) {
                throw new IOException("Invalid resolved YSM symbol", exception);
            }
        }
    }

    static final class CandidateJson {
        String internalName;
        String owner;
        String name;
        String descriptor;
        double confidence;

        static CandidateJson from(MappingCandidate candidate) {
            CandidateJson json = new CandidateJson();
            ResolvedJson symbol = ResolvedJson.from(candidate.symbol());
            json.internalName = symbol.internalName;
            json.owner = symbol.owner;
            json.name = symbol.name;
            json.descriptor = symbol.descriptor;
            json.confidence = candidate.confidence();
            return json;
        }

        YsmResolvedSymbol toApi(SymbolKind kind) throws IOException {
            ResolvedJson resolved = new ResolvedJson();
            resolved.internalName = internalName;
            resolved.owner = owner;
            resolved.name = name;
            resolved.descriptor = descriptor;
            return resolved.toApi(kind);
        }
    }

    static final class ConsumerJson {
        int manifestSchemaVersion = 1;
        Map<String, RequestJson> requests = new TreeMap<>();
    }

    static final class RequestJson {
        int definitionRevision;
        String kind;
        String definitionSha256;
        String sourceAliasSha256;
        boolean required;

        static RequestJson from(YsmSymbolKey<?> key, String definitionSha256,
                                String sourceAliasSha256, boolean required) {
            RequestJson json = new RequestJson();
            json.definitionRevision = key.definitionRevision();
            json.kind = key.kind().name();
            json.definitionSha256 = definitionSha256;
            json.sourceAliasSha256 = sourceAliasSha256;
            json.required = required;
            return json;
        }
    }
}
