package net.okitsu.ysmmapping.api;

import java.util.Locale;
import java.util.Objects;

/** Common structural definition plus optional loader-specific refinements. */
public record YsmStructurePattern(YsmStructureConstraints common,
                                  YsmStructureConstraints fabric,
                                  YsmStructureConstraints neoforge) {
    public YsmStructurePattern {
        Objects.requireNonNull(common, "common");
    }

    public static YsmStructurePattern common(YsmStructureConstraints common) {
        return new YsmStructurePattern(common, null, null);
    }

    public YsmStructureConstraints refinement(String loader) {
        return switch (Objects.requireNonNull(loader, "loader").toLowerCase(Locale.ROOT)) {
            case "fabric" -> fabric == null ? YsmStructureConstraints.EMPTY : fabric;
            case "neoforge" -> neoforge == null ? YsmStructureConstraints.EMPTY : neoforge;
            default -> throw new IllegalArgumentException("Unsupported loader: " + loader);
        };
    }

    String canonicalForm() {
        return "common{" + common.canonicalForm() + "}|fabric{"
                + (fabric == null ? "" : fabric.canonicalForm()) + "}|neoforge{"
                + (neoforge == null ? "" : neoforge.canonicalForm()) + '}';
    }
}
