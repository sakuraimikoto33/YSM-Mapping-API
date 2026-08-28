package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubEntityRendererSemanticAnalyzerTest {
    @Test
    void resolvesEveryForgeRendererRoleByStructure() {
        SubEntityRendererSemanticAnalyzer.Analysis result = analyze(false, false, true);

        assertEquals(5, result.symbols().size());
        assertEquals("projectile-owner", method(result,
                YsmSymbols.RENDERER_CUSTOM_PROJECTILE_RENDER).owner());
        assertEquals("fishing-owner", method(result,
                YsmSymbols.RENDERER_CUSTOM_FISHING_HOOK_RENDER).owner());
        assertEquals("vehicle-owner", method(result,
                YsmSymbols.RENDERER_CUSTOM_VEHICLE_RENDER).owner());
        assertEquals("preview-owner", method(result,
                YsmSymbols.RENDERER_MODEL_PREVIEW_RENDER_VEHICLE).owner());
        assertEquals("preview-owner", method(result,
                YsmSymbols.RENDERER_MODEL_PREVIEW_RENDER_PLAYER_OVERLAY).owner());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void resolvesEveryFabricRendererRoleByStructure() {
        SubEntityRendererSemanticAnalyzer.Analysis result = analyze(true, false, true, true);

        assertEquals(5, result.symbols().size());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void rejectsAnAmbiguousRoleWithoutDiscardingIndependentResults() {
        SubEntityRendererSemanticAnalyzer.Analysis result = analyze(false, true, true);

        assertFalse(result.symbols().containsKey(
                YsmSymbols.RENDERER_CUSTOM_PROJECTILE_RENDER));
        assertTrue(result.diagnostics().get(
                YsmSymbols.RENDERER_CUSTOM_PROJECTILE_RENDER)
                .contains("2 structurally valid candidates"));
        assertEquals(4, result.symbols().size());
    }

    @Test
    void reportsAMissingRoleWithoutGuessing() {
        SubEntityRendererSemanticAnalyzer.Analysis result = analyze(false, false, false);

        assertFalse(result.symbols().containsKey(
                YsmSymbols.RENDERER_MODEL_PREVIEW_RENDER_VEHICLE));
        assertFalse(result.symbols().containsKey(
                YsmSymbols.RENDERER_MODEL_PREVIEW_RENDER_PLAYER_OVERLAY));
        assertEquals("No structurally valid candidate", result.diagnostics().get(
                YsmSymbols.RENDERER_MODEL_PREVIEW_RENDER_VEHICLE));
        assertEquals(3, result.symbols().size());
    }

    @Test
    void rejectsDuplicateExactPreviewDescriptors() {
        SubEntityRendererSemanticAnalyzer.Analysis result =
                analyze(false, false, true, false, true, "forge");

        assertFalse(result.symbols().containsKey(
                YsmSymbols.RENDERER_MODEL_PREVIEW_RENDER_VEHICLE));
        assertTrue(result.diagnostics().get(
                YsmSymbols.RENDERER_MODEL_PREVIEW_RENDER_VEHICLE)
                .contains("2 structurally valid candidates"));
        assertEquals(3, result.symbols().size());
    }

    @Test
    void acceptsNeoForgeLoaderFamily() {
        SubEntityRendererSemanticAnalyzer.Analysis result =
                analyze(false, false, true, false, false, "neoforge");

        assertEquals(5, result.symbols().size());
        assertTrue(result.diagnostics().isEmpty());
    }

    private static SubEntityRendererSemanticAnalyzer.Analysis analyze(
            boolean fabric, boolean duplicateProjectile, boolean includePreview) {
        return analyze(fabric, duplicateProjectile, includePreview, false, false,
                fabric ? "fabric" : "forge");
    }

    private static SubEntityRendererSemanticAnalyzer.Analysis analyze(
            boolean fabric, boolean duplicateProjectile, boolean includePreview,
            boolean includeGenericPreviewDecoys) {
        return analyze(fabric, duplicateProjectile, includePreview,
                includeGenericPreviewDecoys, false, fabric ? "fabric" : "forge");
    }

    private static SubEntityRendererSemanticAnalyzer.Analysis analyze(
            boolean fabric, boolean duplicateProjectile, boolean includePreview,
            boolean includeGenericPreviewDecoys, boolean duplicatePreview,
            String loader) {
        String booleanShape = fabric
                ? "(L@minecraft;FFL@minecraft;L@minecraft;I)Z"
                : "(L@minecraft;FFLcom/mojang/blaze3d/vertex/PoseStack;L@minecraft;I)Z";
        String projectileMarker = fabric
                ? "(FFL@minecraft;L@minecraft;IL@ysm;)Ljava/lang/Boolean;"
                : "(FFLcom/mojang/blaze3d/vertex/PoseStack;L@minecraft;IL@ysm;)Ljava/lang/Boolean;";
        String fishingMarker = fabric
                ? "(FFFL@minecraft;L@minecraft;FFFFF)V"
                : "(FFFLcom/mojang/blaze3d/vertex/VertexConsumer;"
                        + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;FFFFF)V";
        String entityType = fabric
                ? "net/minecraft/class_1297"
                : "net/minecraft/world/entity/Entity";
        String poseStack = fabric
                ? "net/minecraft/class_4587"
                : "com/mojang/blaze3d/vertex/PoseStack";
        String previewShape = "(L" + entityType + ";L" + poseStack + ";F)V";
        String overlayShape = "neoforge".equals(loader)
                ? "(L@minecraft;L@minecraft;FFFFIF)V"
                : "(L@minecraft;L@minecraft;DDFFIF)V";

        List<WholeJarStructureGraph.ClassStructure> classes = new ArrayList<>();
        classes.add(owner("projectile-owner", booleanShape, projectileMarker));
        if (duplicateProjectile) {
            classes.add(owner("second-projectile-owner", booleanShape, projectileMarker));
        }
        classes.add(owner("fishing-owner", booleanShape, fishingMarker));
        classes.add(owner("vehicle-owner", booleanShape, "(L@minecraft;FF)F"));
        if (includePreview) {
            classes.add(previewOwner("preview-owner", previewShape, overlayShape));
        }
        if (duplicatePreview) {
            classes.add(previewOwner("second-preview-owner", previewShape, overlayShape));
        }
        if (includeGenericPreviewDecoys) {
            classes.add(owner("preview-decoy-a",
                    "(Lnet/minecraft/class_1309;L" + poseStack + ";F)V",
                    "(L@minecraft;L@minecraft;F)V", null));
            classes.add(owner("preview-decoy-b",
                    "(Lnet/minecraft/class_1657;L" + poseStack + ";F)V",
                    "(L@minecraft;L@minecraft;F)V", null));
        }
        WholeJarStructureGraph graph = new WholeJarStructureGraph(
                "test-fingerprint", classes);
        return new SubEntityRendererSemanticAnalyzer().analyze(
                graph, loader, entityType, poseStack);
    }

    private static WholeJarStructureGraph.ClassStructure owner(
            String name, String targetShape, String markerShape) {
        return owner(name, targetShape, targetShape, markerShape);
    }

    private static WholeJarStructureGraph.ClassStructure owner(
            String name, String targetDescriptor, String targetShape,
            String markerShape) {
        List<WholeJarStructureGraph.MethodStructure> methods = new ArrayList<>();
        methods.add(method("target", targetDescriptor, targetShape, 9));
        if (markerShape != null) {
            methods.add(method("marker", markerShape, markerShape, 0));
        }
        return new WholeJarStructureGraph.ClassStructure(
                name, "anonymous-" + name, "fingerprint-" + name,
                0, "java/lang/Object", List.of(), "",
                List.of(), methods, List.of());
    }

    private static WholeJarStructureGraph.ClassStructure previewOwner(
            String name, String previewDescriptor, String overlayShape) {
        return new WholeJarStructureGraph.ClassStructure(
                name, "anonymous-" + name, "fingerprint-" + name,
                0, "java/lang/Object", List.of(), "",
                List.of(), List.of(
                        method("preview", previewDescriptor,
                                "(L@minecraft;L@minecraft;F)V", 9),
                        method("overlay", "(Lexample/Gui;Lexample/Player;DDFFIF)V",
                                overlayShape, 8)),
                List.of());
    }

    private static WholeJarStructureGraph.MethodStructure method(
            String name, String runtimeDescriptor, String descriptorShape, int access) {
        return new WholeJarStructureGraph.MethodStructure(
                name, runtimeDescriptor, "fingerprint-" + name + '-' + descriptorShape,
                access, descriptorShape, "opcode", "constant",
                List.of(), List.of(), List.of());
    }

    private static YsmMethodSymbol method(
            SubEntityRendererSemanticAnalyzer.Analysis result,
            YsmSymbolKey<?> key) {
        return (YsmMethodSymbol) result.symbols().get(key);
    }
}
