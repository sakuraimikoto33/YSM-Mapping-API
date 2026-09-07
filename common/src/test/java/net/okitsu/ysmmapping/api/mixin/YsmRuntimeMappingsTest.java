package net.okitsu.ysmmapping.api.mixin;

import net.okitsu.ysmmapping.internal.mixin.YsmRuntimeRemapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YsmRuntimeMappingsTest {
    private static final String BRIDGE = Type.getInternalName(YsmRuntimeMappings.class);
    private static final String WRAPPER = Type.getInternalName(YsmMappingReferenceMapper.class);
    private static final String REFERENCE_MAPPER =
            "org/spongepowered/asm/mixin/refmap/IReferenceMapper";
    private static final String CLASS_REFERENCE_MAPPER =
            "org/spongepowered/asm/mixin/refmap/IClassReferenceMapper";

    @BeforeEach
    @AfterEach
    void resetMappings() {
        YsmRuntimeMappings.install(UnaryOperator.identity(), UnaryOperator.identity());
    }

    @Test
    void identityMappingsPreserveUnrecognizedClassesAndMemberReferences() {
        String className = "example/UnmappedOwner";
        assertSame(className, YsmRuntimeMappings.mapClass(className));
        for (String reference : List.of("unmapped()V", "Lexample/Owner;unmapped(I)F",
                "unmapped:I", "Lexample/Owner;unmapped:Ljava/lang/String;")) {
            assertSame(reference, YsmRuntimeMappings.mapReference(reference));
        }
    }

    @Test
    void installedClassAndReferenceFunctionsStayIndependentAndLeaveUnknownInputsAlone() {
        Map<String, String> classes = Map.of("example/Alias", "example/Resolved");
        Map<String, String> references = Map.of("alias()V", "resolved()V");
        YsmRuntimeMappings.install(value -> classes.getOrDefault(value, value),
                value -> references.getOrDefault(value, value));

        assertEquals("example/Resolved", YsmRuntimeMappings.mapClass("example/Alias"));
        assertEquals("resolved()V", YsmRuntimeMappings.mapReference("alias()V"));
        assertEquals("alias()V", YsmRuntimeMappings.mapClass("alias()V"));
        assertEquals("example/Alias", YsmRuntimeMappings.mapReference("example/Alias"));
        assertEquals("unknown(I)F", YsmRuntimeMappings.mapReference("unknown(I)F"));
    }

    @Test
    void existingWrapperInstallerPublishesToTheSameLoaderNeutralBridge() {
        YsmMappingReferenceMapper.installRuntimeMappings(
                value -> "class/" + value, value -> "reference/" + value);

        assertEquals("class/Owner", YsmRuntimeMappings.mapClass("Owner"));
        assertEquals("reference/call()V", YsmRuntimeMappings.mapReference("call()V"));

        YsmRuntimeMappings.install(UnaryOperator.identity(), UnaryOperator.identity());
        assertEquals("Owner", YsmRuntimeMappings.mapClass("Owner"));
        assertEquals("call()V", YsmRuntimeMappings.mapReference("call()V"));
    }

    @Test
    void bothInstallEntrypointsRejectMissingMappingFunctions() {
        assertThrows(NullPointerException.class,
                () -> YsmRuntimeMappings.install(null, UnaryOperator.identity()));
        assertThrows(NullPointerException.class,
                () -> YsmRuntimeMappings.install(UnaryOperator.identity(), null));
        assertThrows(NullPointerException.class,
                () -> YsmMappingReferenceMapper.installRuntimeMappings(
                        null, UnaryOperator.identity()));
        assertThrows(NullPointerException.class,
                () -> YsmMappingReferenceMapper.installRuntimeMappings(
                        UnaryOperator.identity(), null));
    }

    @Test
    void bridgeDoesNotLinkToOptionalMixinInterfacesOrTheWrapper() throws IOException {
        ClassNode bridge = readClass(YsmRuntimeMappings.class);
        assertTrue(bridge.interfaces.isEmpty());
        for (MethodNode method : bridge.methods) {
            assertTrue(calls(method).stream().noneMatch(call -> call.owner.equals(WRAPPER)
                    || call.owner.startsWith("org/spongepowered/asm/")));
        }
    }

    @Test
    void wrapperBytecodeKeepsBridgeLookupBeforeExistingDelegateFallbacks() throws IOException {
        // A real MixinEnvironment requires a game launcher. Inspect the public wrapper's
        // call sites here rather than initialize a launcher or bypass its constructor.
        ClassNode wrapper = readClass(YsmMappingReferenceMapper.class);
        assertFalse(wrapper.fields.stream().anyMatch(field ->
                (field.access & Opcodes.ACC_STATIC) != 0));
        assertLookupAndFallback(wrapper, "remap", "mapReference",
                REFERENCE_MAPPER, "remap");
        assertLookupAndFallback(wrapper, "remapWithContext", "mapReference",
                REFERENCE_MAPPER, "remapWithContext");
        assertLookupAndFallback(wrapper, "remapClassName", "mapClass",
                CLASS_REFERENCE_MAPPER, "remapClassName");
        assertLookupAndFallback(wrapper, "remapClassName", "mapClass",
                REFERENCE_MAPPER, "remap");
        assertLookupAndFallback(wrapper, "remapClassNameWithContext", "mapClass",
                CLASS_REFERENCE_MAPPER, "remapClassNameWithContext");
        assertLookupAndFallback(wrapper, "remapClassNameWithContext", "mapClass",
                REFERENCE_MAPPER, "remapWithContext");
    }

    @Test
    void bootstrapInstallsBridgeWithoutLoadingTheNewerRefmapWrapper() throws IOException {
        MethodNode install = readClass(YsmRuntimeRemapper.class).methods.stream()
                .filter(method -> method.name.equals("install")
                        && Type.getArgumentTypes(method.desc).length == 4)
                .findFirst().orElseThrow();
        List<MethodInsnNode> calls = calls(install);
        assertEquals(1, calls.stream().filter(call -> call.owner.equals(BRIDGE)
                && call.name.equals("install")).count());
        assertTrue(calls.stream().noneMatch(call -> call.owner.equals(WRAPPER)));
    }

    private static void assertLookupAndFallback(ClassNode owner, String name,
                                                String bridgeMethod, String delegateOwner,
                                                String delegateMethod) {
        MethodNode method = owner.methods.stream().filter(candidate -> candidate.name.equals(name))
                .findFirst().orElseThrow();
        List<MethodInsnNode> calls = calls(method);
        int lookup = callIndex(calls, BRIDGE, bridgeMethod);
        int equality = callIndex(calls, "java/lang/String", "equals");
        int fallback = callIndex(calls, delegateOwner, delegateMethod);
        assertTrue(lookup >= 0 && equality > lookup && fallback > equality,
                name + " must check the shared mapping before " + delegateMethod);
    }

    private static int callIndex(List<MethodInsnNode> calls, String owner, String name) {
        for (int index = 0; index < calls.size(); index++) {
            MethodInsnNode call = calls.get(index);
            if (call.owner.equals(owner) && call.name.equals(name)) return index;
        }
        return -1;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) result.add(call);
        }
        return result;
    }

    private static ClassNode readClass(Class<?> type) throws IOException {
        try (var input = type.getResourceAsStream('/' + Type.getInternalName(type) + ".class")) {
            assertNotNull(input, type.getName());
            ClassNode result = new ClassNode();
            new ClassReader(input).accept(result, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return result;
        }
    }
}
