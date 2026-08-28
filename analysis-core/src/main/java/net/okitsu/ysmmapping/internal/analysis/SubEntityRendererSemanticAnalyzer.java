package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.MappingCandidate;
import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import net.okitsu.ysmmapping.api.YsmStructureConstraints;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves official YSM's obfuscated projectile, hook, and vehicle render helpers. */
public final class SubEntityRendererSemanticAnalyzer {
    private static final int PUBLIC_STATIC = 9;

    public Analysis analyze(YsmClassIndex classIndex, String loader,
            AnalysisProfile.LoaderTypes loaderTypes) {
        Objects.requireNonNull(classIndex, "classIndex");
        Objects.requireNonNull(loaderTypes, "loaderTypes");
        // Loader profiles place the base Entity type first; the preview helper uses it exactly.
        return analyze(new WholeJarStructureAnalyzer().analyze(classIndex), loader,
                loaderTypes.entityTypes().get(0), loaderTypes.poseStack());
    }

    Analysis analyze(WholeJarStructureGraph graph, String loader,
            String entityType, String poseStack) {
        Objects.requireNonNull(graph, "graph");
        Shapes shapes = shapes(loader);
        String previewDescriptor = "(L" + Objects.requireNonNull(entityType, "entityType")
                + ";L" + Objects.requireNonNull(poseStack, "poseStack") + ";F)V";
        Map<YsmSymbolKey<?>, YsmResolvedSymbol> symbols = new LinkedHashMap<>();
        Map<YsmSymbolKey<?>, String> diagnostics = new LinkedHashMap<>();
        StructurePatternResolver resolver = new StructurePatternResolver();

        resolve(resolver, graph, YsmSymbols.RENDERER_CUSTOM_PROJECTILE_RENDER,
                method(shapes.booleanDescriptor()), owner(shapes.projectileOwnerMember()),
                symbols, diagnostics);
        resolve(resolver, graph, YsmSymbols.RENDERER_CUSTOM_FISHING_HOOK_RENDER,
                method(shapes.booleanDescriptor()), owner(shapes.fishingOwnerMember()),
                symbols, diagnostics);
        resolve(resolver, graph, YsmSymbols.RENDERER_CUSTOM_VEHICLE_RENDER,
                method(shapes.booleanDescriptor()), owner(shapes.vehicleOwnerMember()),
                symbols, diagnostics);
        resolveExactDescriptor(graph,
                YsmSymbols.RENDERER_MODEL_PREVIEW_RENDER_VEHICLE,
                previewDescriptor, symbols, diagnostics);
        return new Analysis(symbols, diagnostics);
    }

    private static void resolve(StructurePatternResolver resolver,
            WholeJarStructureGraph graph, YsmSymbolKey<?> key,
            YsmStructureConstraints common, YsmStructureConstraints refinement,
            Map<YsmSymbolKey<?>, YsmResolvedSymbol> symbols,
            Map<YsmSymbolKey<?>, String> diagnostics) {
        List<MappingCandidate> candidates = resolver.resolve(
                SymbolKind.METHOD, common, refinement, graph);
        if (candidates.size() == 1) {
            symbols.put(key, candidates.get(0).symbol());
            return;
        }
        diagnostics.put(key, candidates.isEmpty()
                ? "No structurally valid candidate"
                : candidates.size() + " structurally valid candidates");
    }

    private static YsmStructureConstraints method(String descriptor) {
        return YsmStructureConstraints.builder()
                .requiredAccess(PUBLIC_STATIC)
                .descriptorShape(descriptor)
                .build();
    }

    private static YsmStructureConstraints owner(String memberShape) {
        return YsmStructureConstraints.builder()
                .memberShape(memberShape)
                .build();
    }

    private static void resolveExactDescriptor(WholeJarStructureGraph graph,
            YsmSymbolKey<?> key, String descriptor,
            Map<YsmSymbolKey<?>, YsmResolvedSymbol> symbols,
            Map<YsmSymbolKey<?>, String> diagnostics) {
        List<YsmMethodSymbol> candidates = graph.classes().stream()
                .flatMap(owner -> owner.methods().stream()
                        .filter(method -> (method.access() & PUBLIC_STATIC) == PUBLIC_STATIC)
                        .filter(method -> descriptor.equals(method.runtimeDescriptor()))
                        .map(method -> new YsmMethodSymbol(owner.runtimeName(),
                                method.runtimeName(), method.runtimeDescriptor())))
                .toList();
        if (candidates.size() == 1) {
            symbols.put(key, candidates.get(0));
            return;
        }
        diagnostics.put(key, candidates.isEmpty()
                ? "No structurally valid candidate"
                : candidates.size() + " structurally valid candidates");
    }

    private static Shapes shapes(String loader) {
        return switch (Objects.requireNonNull(loader, "loader")
                .toLowerCase(Locale.ROOT)) {
            case "fabric" -> new Shapes(
                    "(L@minecraft;FFL@minecraft;L@minecraft;I)Z",
                    "(FFL@minecraft;L@minecraft;IL@ysm;)Ljava/lang/Boolean;",
                    "(FFFL@minecraft;L@minecraft;FFFFF)V",
                    "(L@minecraft;FF)F");
            case "forge", "neoforge" -> new Shapes(
                    "(L@minecraft;FFLcom/mojang/blaze3d/vertex/PoseStack;L@minecraft;I)Z",
                    "(FFLcom/mojang/blaze3d/vertex/PoseStack;L@minecraft;IL@ysm;)Ljava/lang/Boolean;",
                    "(FFFLcom/mojang/blaze3d/vertex/VertexConsumer;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;FFFFF)V",
                    "(L@minecraft;FF)F");
            default -> throw new IllegalArgumentException(
                    "Unsupported loader for sub-entity renderer analysis: " + loader);
        };
    }

    public record Analysis(Map<YsmSymbolKey<?>, YsmResolvedSymbol> symbols,
                           Map<YsmSymbolKey<?>, String> diagnostics) {
        public Analysis {
            symbols = Map.copyOf(symbols);
            diagnostics = Map.copyOf(diagnostics);
        }
    }

    private record Shapes(String booleanDescriptor, String projectileOwnerMember,
                          String fishingOwnerMember, String vehicleOwnerMember) {
    }
}
