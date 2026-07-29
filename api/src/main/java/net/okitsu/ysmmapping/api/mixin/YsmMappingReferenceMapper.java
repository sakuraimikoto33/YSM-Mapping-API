package net.okitsu.ysmmapping.api.mixin;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.refmap.IClassReferenceMapper;
import org.spongepowered.asm.mixin.refmap.IReferenceMapper;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Runtime refmap wrapper used by consumer mixin configs for YSM references. */
public class YsmMappingReferenceMapper implements IReferenceMapper, IClassReferenceMapper {
    private final IReferenceMapper delegate;

    public YsmMappingReferenceMapper(MixinEnvironment environment, IReferenceMapper delegate) {
        Objects.requireNonNull(environment, "environment");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Called by the prerequisite bootstrap before consumer mixin targets are parsed. */
    public static synchronized void installRuntimeMappings(UnaryOperator<String> classes,
                                                           UnaryOperator<String> references) {
        YsmRuntimeMappings.install(classes, references);
    }

    @Override
    public boolean isDefault() {
        return false;
    }

    @Override
    public String getResourceName() {
        return "YSM Mapping API runtime mappings";
    }

    @Override
    public String getStatus() {
        return "Using YSM Mapping API runtime mappings";
    }

    @Override
    public String getContext() {
        return delegate.getContext();
    }

    @Override
    public void setContext(String context) {
        delegate.setContext(context);
    }

    @Override
    public String remap(String className, String reference) {
        String mapped = YsmRuntimeMappings.mapReference(reference);
        return mapped.equals(reference) ? delegate.remap(className, reference) : mapped;
    }

    @Override
    public String remapWithContext(String context, String className, String reference) {
        String mapped = YsmRuntimeMappings.mapReference(reference);
        return mapped.equals(reference)
                ? delegate.remapWithContext(context, className, reference) : mapped;
    }

    @Override
    public String remapClassName(String className, String inputClassName) {
        String mapped = YsmRuntimeMappings.mapClass(inputClassName);
        if (!mapped.equals(inputClassName)) {
            return mapped;
        }
        if (delegate instanceof IClassReferenceMapper classReferenceMapper) {
            return classReferenceMapper.remapClassName(className, inputClassName);
        }
        return delegate.remap(className, inputClassName);
    }

    @Override
    public String remapClassNameWithContext(String context, String className,
                                            String inputClassName) {
        String mapped = YsmRuntimeMappings.mapClass(inputClassName);
        if (!mapped.equals(inputClassName)) {
            return mapped;
        }
        if (delegate instanceof IClassReferenceMapper classReferenceMapper) {
            return classReferenceMapper.remapClassNameWithContext(
                    context, className, inputClassName);
        }
        return delegate.remapWithContext(context, className, inputClassName);
    }
}
