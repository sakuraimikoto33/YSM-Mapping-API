package net.okitsu.ysmmapping.api.mixin;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Loader-neutral storage for the runtime mappings used by Mixin refmap wrappers.
 *
 * <p>The separate bridge lets legacy Mixin versions consume the same mappings
 * without loading newer optional refmap interfaces.</p>
 */
public final class YsmRuntimeMappings {
    private static volatile UnaryOperator<String> classMapper = UnaryOperator.identity();
    private static volatile UnaryOperator<String> referenceMapper = UnaryOperator.identity();

    private YsmRuntimeMappings() {
    }

    public static synchronized void install(UnaryOperator<String> classes,
                                            UnaryOperator<String> references) {
        classMapper = Objects.requireNonNull(classes, "classes");
        referenceMapper = Objects.requireNonNull(references, "references");
    }

    public static String mapClass(String className) {
        return classMapper.apply(className);
    }

    public static String mapReference(String reference) {
        return referenceMapper.apply(reference);
    }
}
