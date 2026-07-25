package net.okitsu.ysmmapping.internal.analysis;

import java.io.IOException;

/** A completed JAR read whose bytecode did not expose a uniquely compatible YSM surface. */
public final class StructuralAnalysisException extends IOException {
    private static final long serialVersionUID = 1L;

    public StructuralAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
