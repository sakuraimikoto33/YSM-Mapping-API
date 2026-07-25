package net.okitsu.ysmmapping.internal.mixin;

import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmSourceAlias;
import net.okitsu.ysmmapping.internal.bootstrap.RequestManifest;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Confirms that a consumer's compiled Mixin annotations still use its declared aliases. */
public final class MixinAliasValidator {
    private MixinAliasValidator() {
    }

    public static String validate(ClassLoader loader, String mixinClassName,
                                  RequestManifest manifest) {
        String resource = mixinClassName.replace('.', '/') + ".class";
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) return "compiled Mixin class is unavailable";
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            List<String> annotationStrings = new ArrayList<>();
            annotations(node.visibleAnnotations, annotationStrings);
            annotations(node.invisibleAnnotations, annotationStrings);
            node.fields.forEach(field -> {
                annotations(field.visibleAnnotations, annotationStrings);
                annotations(field.invisibleAnnotations, annotationStrings);
            });
            node.methods.forEach(method -> {
                annotations(method.visibleAnnotations, annotationStrings);
                annotations(method.invisibleAnnotations, annotationStrings);
            });
            return validateStrings(mixinClassName, manifest, annotationStrings);
        } catch (IOException | RuntimeException exception) {
            return "compiled Mixin aliases could not be inspected: "
                    + exception.getClass().getSimpleName();
        }
    }

    static String validateStrings(String mixinClassName, RequestManifest manifest,
                                  Collection<String> annotationStrings) {
        List<String> requirements = manifest.mixinRequirements().get(mixinClassName);
        if (requirements == null) return null;
        for (String id : requirements) {
            YsmSourceAlias alias = manifest.sourceAliases().get(id);
            if (alias == null) continue;
            String owner = alias.owner();
            boolean ownerFound = annotationStrings.stream().anyMatch(value ->
                    value.equals(owner) || value.equals(owner.replace('.', '/')));
            if (!ownerFound) return id + ": compiled Mixin owner differs from sourceAlias";
            if (alias.kind() == SymbolKind.METHOD) {
                String reference = alias.name() + alias.descriptor();
                if (!annotationStrings.contains(reference)) {
                    return id + ": compiled Mixin method differs from sourceAlias";
                }
            } else if (alias.kind() == SymbolKind.FIELD) {
                String reference = alias.name() + ':' + alias.descriptor();
                if (annotationStrings.stream().noneMatch(value -> value.equals(reference)
                        || value.endsWith(reference))) {
                    return id + ": compiled Mixin field differs from sourceAlias";
                }
            }
        }
        return null;
    }

    private static void annotations(List<AnnotationNode> annotations, List<String> output) {
        if (annotations == null) return;
        annotations.forEach(annotation -> values(annotation.values, output));
    }

    private static void values(List<?> values, List<String> output) {
        if (values == null) return;
        for (int index = 1; index < values.size(); index += 2) value(values.get(index), output);
    }

    private static void value(Object value, List<String> output) {
        if (value instanceof String text) {
            output.add(text);
        } else if (value instanceof Type type) {
            output.add(type.getClassName());
        } else if (value instanceof AnnotationNode annotation) {
            values(annotation.values, output);
        } else if (value instanceof List<?> list) {
            list.forEach(item -> value(item, output));
        }
    }
}
