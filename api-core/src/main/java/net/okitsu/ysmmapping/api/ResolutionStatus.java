package net.okitsu.ysmmapping.api;

public enum ResolutionStatus {
    STRUCTURAL,
    BEST_EFFORT,
    AMBIGUOUS,
    NOT_FOUND,
    INCOMPATIBLE;

    public boolean resolved() {
        return this == STRUCTURAL || this == BEST_EFFORT;
    }
}
