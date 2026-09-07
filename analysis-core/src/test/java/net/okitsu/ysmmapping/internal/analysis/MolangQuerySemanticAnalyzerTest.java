package net.okitsu.ysmmapping.internal.analysis;

import com.google.gson.Gson;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MolangQuerySemanticAnalyzerTest {
    @TempDir
    Path temporary;

    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    @Test
    void resolvesTheRegisteredFinalFunctionAndContextAccessorAfterRenaming() {
        for (String prefix : new String[]{"example/One", "test/Renamed"}) {
            Fixture fixture = fixture(prefix);
            assertResolved(fixture);
            Map<String, ClassNode> reversed = new LinkedHashMap<>();
            fixture.classes().values().stream().sorted((a, b) -> b.name.compareTo(a.name))
                    .forEach(value -> reversed.put(value.name, value));
            assertEquals(analyze(fixture).symbols(),
                    new MolangQuerySemanticAnalyzer().analyze(reversed).symbols());
        }
    }

    @Test
    void tracesRegistrationArgumentsThroughLocalVariables() {
        Fixture fixture = fixture("example/Locals");
        fixture.registration().instructions.clear();
        fixture.registration().instructions.add(new LdcInsnNode("ysm.ground_speed2"));
        fixture.registration().instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        fixture.registration().instructions.add(lambda(fixture, "evaluate"));
        fixture.registration().instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        fixture.registration().instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixture.registration().instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        register(fixture);
        fixture.registration().instructions.add(new InsnNode(Opcodes.RETURN));
        assertResolved(fixture);
    }

    @Test
    void ignoresNearbyLambdaThatIsNotPassedToTheRegistration() {
        Fixture fixture = fixture("example/Decoy");
        fixture.registration().instructions.insertBefore(
                fixture.registration().instructions.getFirst(), lambda(fixture, "unrelated"));
        fixture.registration().instructions.insert(
                fixture.registration().instructions.getFirst(), new InsnNode(Opcodes.POP));
        assertResolved(fixture);
    }

    @Test
    void anchorAndLambdaPassedToDifferentCallsCannotResolve() {
        Fixture fixture = fixture("example/Separate");
        fixture.registration().instructions.clear();
        fixture.registration().instructions.add(new LdcInsnNode("ysm.ground_speed2"));
        fixture.registration().instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "example/Log", "record", "(Ljava/lang/String;)V", false));
        fixture.registration().instructions.add(lambda(fixture, "evaluate"));
        fixture.registration().instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "example/Log", "record", "(L" + fixture.function().name + ";)V", false));
        fixture.registration().instructions.add(new InsnNode(Opcodes.RETURN));
        assertUnresolved(fixture);
    }

    @Test
    void duplicateRegistrationFailsClosedEvenWhenTheTargetIsTheSame() {
        Fixture fixture = fixture("example/Duplicate");
        fixture.registration().instructions.remove(fixture.registration().instructions.getLast());
        fixture.registration().instructions.add(new LdcInsnNode("ysm.ground_speed2"));
        fixture.registration().instructions.add(lambda(fixture, "evaluate"));
        register(fixture);
        fixture.registration().instructions.add(new InsnNode(Opcodes.RETURN));
        assertUnresolved(fixture);
    }

    @Test
    void wrongImplementationHandleFailsClosed() {
        Fixture fixture = fixture("example/Handle");
        dynamic(fixture).bsmArgs[1] = new Handle(Opcodes.H_INVOKEVIRTUAL,
                fixture.owner().name, "evaluate", fixture.query().desc, false);
        assertUnresolved(fixture);
    }

    @Test
    void implementationMustExistAndBeStatic() {
        Fixture fixture = fixture("example/Missing");
        fixture.owner().methods.remove(fixture.query());
        assertUnresolved(fixture);
        fixture = fixture("example/Instance");
        fixture.query().access &= ~Opcodes.ACC_STATIC;
        assertUnresolved(fixture);
    }

    @Test
    void implementationDescriptorAndMetafactoryTypeMustAgree() {
        Fixture fixture = fixture("example/Descriptor");
        dynamic(fixture).bsmArgs[2] = Type.getMethodType("(Ljava/lang/Object;)F");
        assertUnresolved(fixture);
    }

    @Test
    void unsupportedBootstrapCannotBeMistakenForAQueryLambda() {
        Fixture fixture = fixture("example/Bootstrap");
        dynamic(fixture).bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "example/Bootstrap", "metafactory", METAFACTORY.getDesc(), false);
        assertUnresolved(fixture);
    }

    @Test
    void queryInputMustBeAnInterface() {
        Fixture fixture = fixture("example/Concrete");
        fixture.input().access &= ~Opcodes.ACC_INTERFACE;
        assertUnresolved(fixture);
    }

    @Test
    void supportsStandardFunctionFloatBoxing() {
        Fixture fixture = fixture("example/Boxing");
        dynamic(fixture).desc = "()Ljava/util/function/Function;";
        dynamic(fixture).bsmArgs[0] = Type.getMethodType(
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        dynamic(fixture).bsmArgs[2] = Type.getMethodType(
                "(L" + fixture.input().name + ";)Ljava/lang/Float;");
        for (var instruction : fixture.registration().instructions) {
            if (instruction instanceof MethodInsnNode call) {
                call.desc = "(Ljava/lang/String;Ljava/util/function/Function;)V";
            }
        }
        assertResolved(fixture);
    }

    @Test
    void supportsAnInternalGenericSamWithObjectBoxing() {
        Fixture fixture = fixture("example/GenericSam");
        fixture.function().methods.get(0).desc = "(Ljava/lang/Object;)Ljava/lang/Object;";
        dynamic(fixture).bsmArgs[0] = Type.getMethodType(
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        dynamic(fixture).bsmArgs[2] = Type.getMethodType(
                "(L" + fixture.input().name + ";)Ljava/lang/Object;");
        assertResolved(fixture);
    }

    @Test
    void shortNameRequiresItsPublicNamespace() {
        Fixture fixture = fixture("example/Unqualified");
        ((LdcInsnNode) fixture.registration().instructions.getFirst()).cst = "ground_speed2";
        assertUnresolved(fixture);
    }

    @Test
    void resolvesShortNameThroughItsLazilyConstructedNamespace() {
        for (boolean constructor : new boolean[]{false, true}) {
            Fixture fixture = fixture("example/Lazy" + constructor);
            lazyNamespace(fixture, constructor);
            assertResolved(fixture);
        }
    }

    @Test
    void erasedRegistryArgumentsStillRetainTheExactStringAndLambdaOrigins() {
        Fixture fixture = fixture("example/ErasedRegistration");
        for (var instruction : fixture.registration().instructions) {
            if (instruction instanceof MethodInsnNode call) {
                call.desc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
                fixture.registration().instructions.insert(call, new InsnNode(Opcodes.POP));
                break;
            }
        }
        assertResolved(fixture);
    }

    @Test
    void erasedNamespaceArgumentsStillBindTheExactLazyInstance() {
        Fixture fixture = fixture("example/ErasedNamespace");
        MethodNode namespace = lazyNamespace(fixture, false);
        for (var instruction : namespace.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && Type.getArgumentTypes(call.desc).length == 2) {
                call.desc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
                namespace.instructions.insert(call, new InsnNode(Opcodes.POP));
                break;
            }
        }
        assertResolved(fixture);
    }

    @Test
    void wrongNamespaceCannotQualifyTheShortName() {
        Fixture fixture = fixture("example/WrongNamespace");
        MethodNode namespace = lazyNamespace(fixture, false);
        ((LdcInsnNode) namespace.instructions.getFirst()).cst = "unrelated";
        assertUnresolved(fixture);
    }

    @Test
    void lazyFactoryMustConstructTheQueryRegistrationOwner() {
        Fixture fixture = fixture("example/DecoyFactory");
        lazyNamespace(fixture, false);
        for (var instruction : initializer(fixture).instructions) {
            if (instruction instanceof InvokeDynamicInsnNode supplier) {
                supplier.bsmArgs[1] = new Handle(Opcodes.H_NEWINVOKESPECIAL,
                        "example/OtherNamespace", "<init>", "()V", false);
            }
        }
        assertUnresolved(fixture);
    }

    @Test
    void correctLazyFactoryMustInitializeTheSameFieldUsedByTheNamespaceCall() {
        Fixture fixture = fixture("example/DecoyField");
        lazyNamespace(fixture, false);
        for (var instruction : initializer(fixture).instructions) {
            if (instruction instanceof FieldInsnNode field) field.name = "unrelated";
        }
        assertUnresolved(fixture);
    }

    @Test
    void namespaceNameAndLazyInstanceMustReachTheSameCall() {
        Fixture fixture = fixture("example/SeparateNamespace");
        MethodNode namespace = lazyNamespace(fixture, false);
        namespace.instructions.insert(namespace.instructions.getFirst(),
                new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Logger", "record",
                        "(Ljava/lang/String;)V", false));
        namespace.instructions.insert(namespace.instructions.get(1),
                new LdcInsnNode("other"));
        assertUnresolved(fixture);
    }

    @Test
    void functionalInterfaceMustHaveTheDeclaredSam() {
        Fixture fixture = fixture("example/Sam");
        fixture.function().methods.clear();
        assertUnresolved(fixture);
        fixture = fixture("example/NotInterface");
        fixture.function().access &= ~Opcodes.ACC_INTERFACE;
        assertUnresolved(fixture);
    }

    @Test
    void queryInputCannotEscapeToAnotherHelper() {
        Fixture fixture = fixture("example/Escape");
        fixture.query().instructions.insertBefore(fixture.query().instructions.getFirst(),
                new VarInsnNode(Opcodes.ALOAD, 0));
        fixture.query().instructions.insert(fixture.query().instructions.getFirst(),
                new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Helper", "consume",
                        "(L" + fixture.input().name + ";)V", false));
        assertUnresolved(fixture);
    }

    @Test
    void contextAccessorMustUseTheOriginalInput() {
        Fixture fixture = fixture("example/OtherInput");
        fixture.query().instructions.set(fixture.query().instructions.getFirst(),
                new FieldInsnNode(Opcodes.GETSTATIC, fixture.owner().name, "otherInput",
                        "L" + fixture.input().name + ";"));
        assertUnresolved(fixture);
    }

    @Test
    void mergedOriginalAndNullInputCannotResolve() {
        Fixture fixture = fixture("example/MixedInput");
        fixture.query().instructions.remove(fixture.query().instructions.getFirst());
        var accessor = fixture.query().instructions.getFirst();
        LabelNode other = new LabelNode();
        LabelNode end = new LabelNode();
        fixture.query().instructions.insertBefore(accessor, new InsnNode(Opcodes.ICONST_0));
        fixture.query().instructions.insertBefore(accessor,
                new JumpInsnNode(Opcodes.IFEQ, other));
        fixture.query().instructions.insertBefore(accessor, new VarInsnNode(Opcodes.ALOAD, 0));
        fixture.query().instructions.insertBefore(accessor,
                new JumpInsnNode(Opcodes.GOTO, end));
        fixture.query().instructions.insertBefore(accessor, other);
        fixture.query().instructions.insertBefore(accessor, new InsnNode(Opcodes.ACONST_NULL));
        fixture.query().instructions.insertBefore(accessor, end);
        assertUnresolved(fixture);
    }

    @Test
    void queryInputCannotBeInspectedOrCastToAnotherImplementation() {
        for (int opcode : new int[]{Opcodes.INSTANCEOF, Opcodes.CHECKCAST}) {
            Fixture fixture = fixture("example/Inspected" + opcode);
            var first = fixture.query().instructions.getFirst();
            fixture.query().instructions.insertBefore(first, new VarInsnNode(Opcodes.ALOAD, 0));
            fixture.query().instructions.insertBefore(first,
                    new TypeInsnNode(opcode, "example/ConcreteInput"));
            fixture.query().instructions.insertBefore(first, new InsnNode(Opcodes.POP));
            assertUnresolved(fixture);
        }
    }

    @Test
    void inheritedExtraInputDependencyCannotResolve() {
        Fixture fixture = fixture("example/ParentInput");
        var first = fixture.query().instructions.getFirst();
        fixture.query().instructions.insertBefore(first, new VarInsnNode(Opcodes.ALOAD, 0));
        fixture.query().instructions.insertBefore(first,
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, "example/Parent", "flag", "()Z", true));
        fixture.query().instructions.insertBefore(first, new InsnNode(Opcodes.POP));
        assertUnresolved(fixture);
    }

    @Test
    void queryCannotRequireTwoDifferentContextAccessors() {
        Fixture fixture = fixture("example/Accessors");
        fixture.input().methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "other", "()L" + fixture.context().name + ";", null, null));
        fixture.query().instructions.insertBefore(fixture.query().instructions.getFirst(),
                new VarInsnNode(Opcodes.ALOAD, 0));
        fixture.query().instructions.insert(fixture.query().instructions.getFirst(),
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, fixture.input().name, "other",
                        "()L" + fixture.context().name + ";", true));
        fixture.query().instructions.insert(fixture.query().instructions.get(1),
                new InsnNode(Opcodes.POP));
        assertUnresolved(fixture);
    }

    @Test
    void mergedRegistrationLambdaFailsClosed() {
        Fixture fixture = fixture("example/Merged");
        fixture.registration().instructions.clear();
        LabelNode other = new LabelNode();
        LabelNode end = new LabelNode();
        fixture.registration().instructions.add(new LdcInsnNode("ysm.ground_speed2"));
        fixture.registration().instructions.add(new InsnNode(Opcodes.ICONST_0));
        fixture.registration().instructions.add(new JumpInsnNode(Opcodes.IFEQ, other));
        fixture.registration().instructions.add(lambda(fixture, "evaluate"));
        fixture.registration().instructions.add(new JumpInsnNode(Opcodes.GOTO, end));
        fixture.registration().instructions.add(other);
        fixture.registration().instructions.add(lambda(fixture, "unrelated"));
        fixture.registration().instructions.add(end);
        register(fixture);
        fixture.registration().instructions.add(new InsnNode(Opcodes.RETURN));
        assertUnresolved(fixture);
    }

    @Test
    void malformedInstructionsReturnOnlySafeSemanticDiagnostics() {
        Fixture fixture = fixture("example/BadStack");
        fixture.registration().instructions.insert(new InsnNode(Opcodes.POP));
        assertUnresolved(fixture);
        analyze(fixture).diagnostics().values().forEach(value ->
                assertTrue(!value.contains(fixture.owner().name)));
    }

    @Test
    void nonOptInProfileEmitsNeitherQueryResultsNorQueryDiagnostics() throws Exception {
        Fixture fixture = fixture("com/elfmcys/yesstevemodel/synthetic/Disabled");
        JarStructureAnalyzer.PartialAnalysis result = partial(fixture, false);
        assertTrue(!result.symbols().containsKey(YsmSymbols.MOLANG_GROUND_SPEED2_QUERY));
        assertTrue(!result.symbols().containsKey(YsmSymbols.MOLANG_QUERY_CONTEXT_GET));
        assertTrue(!result.diagnostics().containsKey(YsmSymbols.MOLANG_GROUND_SPEED2_QUERY));
        assertTrue(!result.diagnostics().containsKey(YsmSymbols.MOLANG_QUERY_CONTEXT_GET));
    }

    @Test
    void optInQueryResolvesWhenPacketAnalysisFails() throws Exception {
        Fixture fixture = fixture("com/elfmcys/yesstevemodel/synthetic/Independent");
        JarStructureAnalyzer.PartialAnalysis result = partial(fixture, true);
        assertTrue(result.diagnostics().containsKey(YsmSymbols.REGISTRATION_METHOD));
        assertEquals(analyze(fixture).symbols().get(YsmSymbols.MOLANG_GROUND_SPEED2_QUERY),
                result.symbols().get(YsmSymbols.MOLANG_GROUND_SPEED2_QUERY));
        assertTrue(result.symbols().containsKey(YsmSymbols.MOLANG_QUERY_CONTEXT_GET));
    }

    @Test
    void failedQueryDoesNotEraseIndependentConfigResolution() throws Exception {
        Fixture fixture = fixture("com/elfmcys/yesstevemodel/synthetic/Retained");
        fixture.owner().methods.remove(fixture.query());
        ClassNode config = type("com/elfmcys/yesstevemodel/synthetic/Config", false);
        config.fields.add(new FieldNode(Opcodes.ACC_STATIC, "disabled", "Z", null, null));
        MethodNode initializer = method("initialize", "()V");
        initializer.instructions.add(new LdcInsnNode("ClientNotDisplayModels"));
        initializer.instructions.add(new InsnNode(Opcodes.POP));
        initializer.instructions.add(new InsnNode(Opcodes.ICONST_0));
        initializer.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, config.name,
                "disabled", "Z"));
        initializer.instructions.add(new InsnNode(Opcodes.RETURN));
        config.methods.add(initializer);
        fixture.classes().put(config.name, config);
        JarStructureAnalyzer.PartialAnalysis result = partial(fixture, true);
        assertTrue(result.diagnostics().containsKey(YsmSymbols.MOLANG_GROUND_SPEED2_QUERY));
        assertTrue(result.symbols().containsKey(YsmSymbols.CLIENT_NOT_DISPLAY_MODELS));
    }

    private JarStructureAnalyzer.PartialAnalysis partial(Fixture fixture, boolean optIn)
            throws Exception {
        for (ClassNode node : fixture.classes().values()) {
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            Path path = temporary.resolve(node.name + ".class");
            Files.createDirectories(path.getParent());
            Files.write(path, writer.toByteArray());
        }
        Map<String, Object> loader = new LinkedHashMap<>();
        loader.put("livingEntity", "example/Living");
        loader.put("itemStack", "example/Stack");
        loader.put("equipmentSlot", "example/Slot");
        loader.put("items", "example/Items");
        loader.put("poseStack", "example/Pose");
        loader.put("multiBuffer", "example/Buffer");
        loader.put("entityTypes", List.of("example/Entity"));
        loader.put("playerTypes", List.of("example/Player"));
        loader.put("connectionTypes", List.of("example/Connection"));
        loader.put("componentTypes", List.of("example/Component"));
        var symbols = YsmSymbols.all().stream().filter(key -> optIn
                || !YsmSymbols.optionalProfileSymbolGroups().get(0).contains(key.id()))
                .map(key -> Map.of("id", key.id(), "kind", key.kind().name(),
                        "definitionRevision", 1)).toList();
        byte[] json = new Gson().toJson(Map.of("formatVersion", 1,
                "minecraftVersion", "synthetic", "loaders", Map.of("forge", loader),
                "channelIdentifiers", List.of("example:channel"), "packets",
                List.of(Map.of("id", 1, "name", "TEST", "direction", "BOTH")),
                "symbols", symbols)).getBytes(StandardCharsets.UTF_8);
        AnalysisProfile profile = AnalysisProfile.load(new ByteArrayInputStream(json), "test");
        return new JarStructureAnalyzer(profile).analyzePartial(new YsmArtifact("test",
                "synthetic", "forge", "test", "0".repeat(128)), YsmClassIndex.read(temporary));
    }

    private static MolangQuerySemanticAnalyzer.Analysis analyze(Fixture fixture) {
        return new MolangQuerySemanticAnalyzer().analyze(fixture.classes());
    }

    private static void assertResolved(Fixture fixture) {
        MolangQuerySemanticAnalyzer.Analysis result = analyze(fixture);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertEquals(new YsmMethodSymbol(fixture.owner().name, "evaluate", fixture.query().desc),
                result.symbols().get(YsmSymbols.MOLANG_GROUND_SPEED2_QUERY));
        assertEquals(new YsmMethodSymbol(fixture.input().name, "context",
                        "()L" + fixture.context().name + ";"),
                result.symbols().get(YsmSymbols.MOLANG_QUERY_CONTEXT_GET));
    }

    private static void assertUnresolved(Fixture fixture) {
        MolangQuerySemanticAnalyzer.Analysis result = analyze(fixture);
        assertTrue(result.symbols().isEmpty());
        assertEquals(2, result.diagnostics().size());
    }

    private static Fixture fixture(String prefix) {
        ClassNode owner = type(prefix + "Queries", false);
        ClassNode input = type(prefix + "Input", true);
        ClassNode context = type(prefix + "Context", false);
        ClassNode function = type(prefix + "Function", true);
        input.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "context", "()L" + context.name + ";", null, null));
        function.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "apply", "(L" + input.name + ";)F", null, null));
        MethodNode query = method("evaluate", "(L" + input.name + ";)F");
        query.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        query.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, input.name,
                "context", "()L" + context.name + ";", true));
        query.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, context.name,
                "value", "()F", false));
        query.instructions.add(new InsnNode(Opcodes.FRETURN));
        owner.methods.add(query);
        MethodNode registration = method("registerAll", "()V");
        owner.methods.add(registration);
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (ClassNode node : new ClassNode[]{owner, input, context, function}) {
            classes.put(node.name, node);
        }
        Fixture fixture = new Fixture(classes, owner, input, context, function, query,
                registration);
        registration.instructions.add(new LdcInsnNode("ysm.ground_speed2"));
        registration.instructions.add(lambda(fixture, "evaluate"));
        register(fixture);
        registration.instructions.add(new InsnNode(Opcodes.RETURN));
        return fixture;
    }

    private static MethodNode lazyNamespace(Fixture fixture, boolean holderConstructor) {
        ((LdcInsnNode) fixture.registration().instructions.getFirst()).cst = "ground_speed2";
        ClassNode holder = type(fixture.owner().name + "Holder", false);
        fixture.classes().put(holder.name, holder);
        fixture.owner().fields.add(new FieldNode(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "singleton", "L" + holder.name + ";", null, null));
        MethodNode constructor = method("<init>", "()V");
        constructor.access &= ~Opcodes.ACC_STATIC;
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                fixture.owner().name, fixture.registration().name, "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        fixture.owner().methods.add(constructor);
        MethodNode initializer = method("<clinit>", "()V");
        if (holderConstructor) {
            initializer.instructions.add(new TypeInsnNode(Opcodes.NEW, holder.name));
            initializer.instructions.add(new InsnNode(Opcodes.DUP));
        }
        initializer.instructions.add(new InvokeDynamicInsnNode("get",
                "()Ljava/util/function/Supplier;", METAFACTORY,
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_NEWINVOKESPECIAL, fixture.owner().name, "<init>", "()V", false),
                Type.getMethodType("()L" + fixture.owner().name + ";")));
        initializer.instructions.add(holderConstructor
                ? new MethodInsnNode(Opcodes.INVOKESPECIAL, holder.name, "<init>",
                        "(Ljava/util/function/Supplier;)V", false)
                : new MethodInsnNode(Opcodes.INVOKESTATIC, holder.name, "memoize",
                        "(Ljava/util/function/Supplier;)L" + holder.name + ";", false));
        initializer.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, fixture.owner().name,
                "singleton", "L" + holder.name + ";"));
        initializer.instructions.add(new InsnNode(Opcodes.RETURN));
        fixture.owner().methods.add(initializer);
        ClassNode namespace = type(fixture.owner().name + "Namespaces", false);
        MethodNode registration = method("registerNamespace", "()V");
        registration.instructions.add(new LdcInsnNode("ysm"));
        registration.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, fixture.owner().name,
                "singleton", "L" + holder.name + ";"));
        registration.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, holder.name,
                "value", "()Ljava/lang/Object;", false));
        registration.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "example/Namespaces", "add", "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        registration.instructions.add(new InsnNode(Opcodes.RETURN));
        namespace.methods.add(registration);
        fixture.classes().put(namespace.name, namespace);
        return registration;
    }

    private static MethodNode initializer(Fixture fixture) {
        return fixture.owner().methods.stream().filter(value -> value.name.equals("<clinit>"))
                .findFirst().orElseThrow();
    }

    private static InvokeDynamicInsnNode dynamic(Fixture fixture) {
        for (var instruction : fixture.registration().instructions) {
            if (instruction instanceof InvokeDynamicInsnNode value) return value;
        }
        throw new AssertionError("No lambda");
    }

    private static InvokeDynamicInsnNode lambda(Fixture fixture, String name) {
        Type descriptor = Type.getMethodType(fixture.query().desc);
        return new InvokeDynamicInsnNode("apply", "()L" + fixture.function().name + ";",
                METAFACTORY, descriptor, new Handle(Opcodes.H_INVOKESTATIC,
                fixture.owner().name, name, fixture.query().desc, false), descriptor);
    }

    private static void register(Fixture fixture) {
        fixture.registration().instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                fixture.owner().name, "add", "(Ljava/lang/String;L"
                        + fixture.function().name + ";)V", false));
    }

    private static ClassNode type(String name, boolean isInterface) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.name = name;
        node.superName = "java/lang/Object";
        node.access = Opcodes.ACC_PUBLIC | (isInterface
                ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0);
        return node;
    }

    private static MethodNode method(String name, String descriptor) {
        MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, descriptor, null, null);
        node.maxLocals = 4;
        node.maxStack = 8;
        return node;
    }

    private record Fixture(Map<String, ClassNode> classes, ClassNode owner, ClassNode input,
                           ClassNode context, ClassNode function, MethodNode query,
                           MethodNode registration) {
    }
}
