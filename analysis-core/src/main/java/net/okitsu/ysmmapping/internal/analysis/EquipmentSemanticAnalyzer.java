package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Role-based resolver for the curated equipment/rendering surface. */
public final class EquipmentSemanticAnalyzer implements Opcodes {
    private static final String YSM = "com/elfmcys/yesstevemodel/";
    private static final String INT_LIST = "Lit/unimi/dsi/fastutil/ints/IntList;";
    private static final String LIST = "Ljava/util/List;";

    public Map<YsmSymbolKey<?>, YsmResolvedSymbol> analyze(
            Path jar, AnalysisProfile profile, String loader)
            throws IOException {
        return analyze(YsmClassIndex.read(jar), profile, loader);
    }

    public Map<YsmSymbolKey<?>, YsmResolvedSymbol> analyze(
            YsmClassIndex classIndex, AnalysisProfile profile, String loader) {
        Names names = Names.from(profile.loader(loader));
        Map<String, ClassNode> classes = classIndex.byName();
        Map<YsmSymbolKey<?>, YsmResolvedSymbol> result = new LinkedHashMap<>();

        MethodRef elytraLookup = unique(classes, method -> isStatic(method)
                && method.desc.equals("(L" + names.livingEntity + ";)L" + names.itemStack + ";")
                && referencesFieldOwner(method, names.items)
                && referencesFieldOwner(method, names.equipmentSlot)
                && referencesFieldOwner(method, names.itemStack));
        put(result, YsmSymbols.EQUIPMENT_ELYTRA_ITEM_GETTER, elytraLookup);

        MethodRef elytraRender = elytraLookup == null ? null : unique(classes, method ->
                isRender(method, names) && calls(method, elytraLookup)
                        && classContains(methodOwner(classes, method), "textures/entity/elytra.png"));
        put(result, YsmSymbols.RENDERER_ELYTRA_LAYER_RENDER, elytraRender);

        MethodRef entityGetter = null;
        MethodRef modelGetter = null;
        if (elytraRender != null) {
            MethodNode render = node(classes, elytraRender);
            String customPlayer = Type.getArgumentTypes(elytraRender.descriptor)[3].getInternalName();
            entityGetter = firstCall(render, call -> call.owner.equals(customPlayer)
                    && call.desc.startsWith("()")
                    && names.entityTypes.contains(Type.getReturnType(call.desc).getInternalName()));
            modelGetter = firstCall(render, call -> call.owner.equals(customPlayer)
                    && call.desc.startsWith("()") && looksLikeAnimatedModel(
                    classes.get(Type.getReturnType(call.desc).getInternalName())));
        }
        put(result, YsmSymbols.CUSTOM_PLAYER_ENTITY_GETTER, entityGetter);
        put(result, YsmSymbols.CUSTOM_PLAYER_CURRENT_MODEL_GETTER, modelGetter);

        String animatedModel = modelGetter == null ? null
                : Type.getReturnType(modelGetter.descriptor).getInternalName();
        List<YsmSymbolKey<YsmMethodSymbol>> locators = List.of(
                YsmSymbols.ANIMATED_MODEL_LEFT_HAND_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_RIGHT_HAND_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_ELYTRA_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_TAC_PISTOL_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_TAC_RIFLE_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_LEFT_WAIST_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_RIGHT_WAIST_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_LEFT_SHOULDER_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_RIGHT_SHOULDER_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_BLADE_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_SHEATH_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_HEAD_BONES_GETTER,
                YsmSymbols.ANIMATED_MODEL_BACKPACK_BONES_GETTER);
        Map<YsmSymbolKey<YsmMethodSymbol>, MethodRef> locatorRefs = new LinkedHashMap<>();
        for (int index = 0; index < locators.size(); index++) {
            MethodRef value = animatedModel == null ? null
                    : findLocator(classes, animatedModel, index);
            locatorRefs.put(locators.get(index), value);
            put(result, locators.get(index), value);
        }

        MethodRef allHead = animatedModel == null ? null : findAllHead(classes, animatedModel);
        put(result, YsmSymbols.ANIMATED_MODEL_ALL_HEAD_BONE_GETTER, allHead);
        MethodRef prepLocator = unique(classes, method -> isStatic(method)
                && method.desc.equals("(L" + names.poseStack + ";" + LIST + ")Z"));
        put(result, YsmSymbols.RENDER_UTILS_PREP_MATRIX_FOR_LOCATOR, prepLocator);
        MethodRef prepBone = allHead == null ? null
                : findPrepBone(classes, prepLocator, allHead, names.poseStack);
        put(result, YsmSymbols.RENDER_UTILS_PREP_MATRIX_FOR_BONE, prepBone);

        MethodRef armorGetter = elytraLookup == null ? null : uniqueInOwner(classes,
                elytraLookup.owner, method -> isStatic(method)
                        && method.desc.equals("(L" + names.livingEntity + ";L"
                        + names.equipmentSlot + ";)L" + names.itemStack + ";"));
        put(result, YsmSymbols.EQUIPMENT_ARMOR_ITEM_GETTER, armorGetter);
        MethodRef renderElytra = elytraRender == null || animatedModel == null ? null
                : firstCall(node(classes, elytraRender), call -> call.owner.equals(elytraRender.owner)
                && call.desc.equals("(L" + names.poseStack + ";L" + animatedModel + ";)V"));
        put(result, YsmSymbols.RENDERER_ELYTRA_LAYER_RENDER_ELYTRA, renderElytra);

        if (elytraRender != null && prepLocator != null) {
            put(result, YsmSymbols.RENDERER_ARMOR_LAYER_RENDER,
                    findLayer(classes, prepLocator, elytraRender,
                            refs(locatorRefs, YsmSymbols.ANIMATED_MODEL_HEAD_BONES_GETTER)));
            put(result, YsmSymbols.RENDERER_ITEM_IN_HAND_LAYER_RENDER,
                    findLayer(classes, prepLocator, elytraRender, refs(locatorRefs,
                            YsmSymbols.ANIMATED_MODEL_LEFT_HAND_BONES_GETTER,
                            YsmSymbols.ANIMATED_MODEL_RIGHT_HAND_BONES_GETTER)));
            put(result, YsmSymbols.RENDERER_PARROT_LAYER_RENDER,
                    findLayer(classes, prepLocator, elytraRender, refs(locatorRefs,
                            YsmSymbols.ANIMATED_MODEL_LEFT_SHOULDER_BONES_GETTER,
                            YsmSymbols.ANIMATED_MODEL_RIGHT_SHOULDER_BONES_GETTER)));
            put(result, YsmSymbols.RENDERER_BACKPACK_LAYER_RENDER,
                    findLayer(classes, prepLocator, elytraRender,
                            refs(locatorRefs, YsmSymbols.ANIMATED_MODEL_BACKPACK_BONES_GETTER)));
        }
        matrixHelpers(classes, prepBone).forEach((key, value) -> put(result, key, value));
        return Map.copyOf(result);
    }

    @SafeVarargs
    private static List<MethodRef> refs(Map<YsmSymbolKey<YsmMethodSymbol>, MethodRef> values,
            YsmSymbolKey<YsmMethodSymbol>... keys) {
        List<MethodRef> result = new ArrayList<>();
        for (YsmSymbolKey<YsmMethodSymbol> key : keys) {
            MethodRef value = values.get(key);
            if (value != null) result.add(value);
        }
        return result.size() == keys.length ? result : List.of();
    }

    private static void put(Map<YsmSymbolKey<?>, YsmResolvedSymbol> target,
            YsmSymbolKey<YsmMethodSymbol> key, MethodRef value) {
        if (value != null) target.put(key,
                new YsmMethodSymbol(value.owner, value.name, value.descriptor));
    }

    private static Map<YsmSymbolKey<YsmMethodSymbol>, MethodRef> matrixHelpers(
            Map<String, ClassNode> classes, MethodRef prepBone) {
        if (prepBone == null) return Map.of();
        ClassNode owner = classes.get(prepBone.owner);
        MethodNode method = node(classes, prepBone);
        String voidDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                Type.getArgumentTypes(prepBone.descriptor));
        List<MethodRef> voidCalls = new ArrayList<>();
        MethodRef scale = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(owner.name)) continue;
            MethodRef ref = ref(call);
            if (call.desc.equals(voidDescriptor)) voidCalls.add(ref);
            else if (call.desc.equals(prepBone.descriptor) && !call.name.equals(prepBone.name)) scale = ref;
        }
        if (voidCalls.size() != 4 || scale == null) return Map.of();
        MethodRef combined = uniqueInOwner(classes, owner.name, candidate ->
                candidate.desc.equals(voidDescriptor) && calls(candidate, voidCalls.get(1))
                        && calls(candidate, voidCalls.get(2)));
        if (combined == null) return Map.of();
        return Map.of(
                YsmSymbols.RENDER_UTILS_TRANSLATE_MATRIX_TO_BONE, voidCalls.get(0),
                YsmSymbols.RENDER_UTILS_TRANSLATE_TO_PIVOT_POINT, voidCalls.get(1),
                YsmSymbols.RENDER_UTILS_ROTATE_MATRIX_AROUND_BONE, voidCalls.get(2),
                YsmSymbols.RENDER_UTILS_SCALE_MATRIX_FOR_BONE, scale,
                YsmSymbols.RENDER_UTILS_TRANSLATE_AWAY_FROM_PIVOT_POINT, voidCalls.get(3),
                YsmSymbols.RENDER_UTILS_TRANSLATE_AND_ROTATE_MATRIX_FOR_BONE, combined);
    }

    private static MethodRef findLayer(Map<String, ClassNode> classes, MethodRef prep,
            MethodRef template, List<MethodRef> getters) {
        if (getters.isEmpty()) return null;
        List<MethodRef> matches = new ArrayList<>();
        for (ClassNode owner : classes.values()) {
            boolean role = owner.methods.stream().anyMatch(method -> calls(method, prep)
                    && getters.stream().allMatch(getter -> calls(method, getter)));
            if (!role) continue;
            owner.methods.stream().filter(method -> method.desc.equals(template.descriptor))
                    .map(method -> ref(owner, method)).forEach(matches::add);
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static MethodRef findPrepBone(Map<String, ClassNode> classes, MethodRef prep,
            MethodRef allHead, String poseStack) {
        String bone = Type.getReturnType(allHead.descriptor).getDescriptor();
        String descriptor = "(L" + poseStack + ";" + bone + ")Z";
        String helperDescriptor = "(L" + poseStack + ";" + bone + ")V";
        if (prep != null) {
            MethodRef preferred = uniqueInOwner(classes, prep.owner,
                    method -> isStatic(method) && method.desc.equals(descriptor)
                            && helperCallCount(method, prep.owner, helperDescriptor) >= 3);
            if (preferred != null) return preferred;
        }
        return unique(classes, method -> isStatic(method) && method.desc.equals(descriptor)
                && helperCallCount(method, null, helperDescriptor) >= 3);
    }

    private static int helperCallCount(MethodNode method, String owner, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.desc.equals(descriptor)
                    && (owner == null || call.owner.equals(owner))) count++;
        }
        return count;
    }

    private static MethodRef findAllHead(Map<String, ClassNode> classes, String animatedName) {
        ClassNode animated = classes.get(animatedName);
        String indexField = staticIntForString(animated, "AllHead");
        if (indexField == null) return null;
        for (MethodNode constructor : animated.methods) {
            if (!constructor.name.equals("<init>")) continue;
            AbstractInsnNode[] instructions = constructor.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                if (!(instructions[i] instanceof FieldInsnNode put) || put.getOpcode() != PUTFIELD
                        || !put.owner.equals(animatedName) || !put.desc.startsWith("L" + YSM)) continue;
                if (windowHasField(instructions, i, GETSTATIC, animatedName, indexField, "I", 16))
                    return getter(animated, put.name, put.desc);
            }
        }
        return null;
    }

    private static MethodRef findLocator(Map<String, ClassNode> classes, String animatedName,
            int locatorIndex) {
        ClassNode animated = classes.get(animatedName);
        String modelData = animated.methods.stream().filter(method -> method.name.equals("<init>"))
                .map(method -> Type.getArgumentTypes(method.desc)).filter(args -> args.length == 1
                        && args[0].getSort() == Type.OBJECT
                        && args[0].getInternalName().startsWith(YSM))
                .map(args -> args[0].getInternalName()).findFirst().orElse(null);
        if (modelData == null) return null;
        String sourceField = locatorSourceField(classes.get(modelData), locatorIndex);
        if (sourceField == null) return null;
        for (MethodNode constructor : animated.methods) {
            if (!constructor.name.equals("<init>")) continue;
            AbstractInsnNode[] instructions = constructor.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                if (instructions[i] instanceof FieldInsnNode put && put.getOpcode() == PUTFIELD
                        && put.owner.equals(animatedName) && put.desc.equals(LIST)
                        && windowHasField(instructions, i, GETFIELD, modelData, sourceField,
                        INT_LIST, 10)) return getter(animated, put.name, LIST);
            }
        }
        return null;
    }

    private static String locatorSourceField(ClassNode modelData, int value) {
        if (modelData == null) return null;
        for (MethodNode method : modelData.methods) {
            if (!method.name.equals("<init>") || !method.desc.contains("[[Ljava/lang/String;")) continue;
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                if (instructions[i] instanceof FieldInsnNode put && put.getOpcode() == PUTFIELD
                        && put.owner.equals(modelData.name) && put.desc.equals(INT_LIST)
                        && windowHasInt(instructions, i, value, 12)
                        && windowHasOpcode(instructions, i, AALOAD, 12)) return put.name;
            }
        }
        return null;
    }

    private static MethodRef getter(ClassNode owner, String fieldName, String fieldDescriptor) {
        List<MethodRef> matches = owner.methods.stream()
                .filter(method -> method.desc.equals("()" + fieldDescriptor)
                        && referencesField(method, owner.name, fieldName, fieldDescriptor))
                .map(method -> ref(owner, method)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String staticIntForString(ClassNode owner, String value) {
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                if (!(instructions[i] instanceof LdcInsnNode ldc) || !value.equals(ldc.cst)) continue;
                for (int j = i + 1; j < Math.min(instructions.length, i + 10); j++)
                    if (instructions[j] instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC
                            && put.owner.equals(owner.name) && put.desc.equals("I")) return put.name;
            }
        }
        return null;
    }

    private static boolean looksLikeAnimatedModel(ClassNode owner) {
        if (owner == null) return false;
        long listGetters = owner.methods.stream().filter(method -> method.desc.equals("()" + LIST)).count();
        boolean constructor = owner.methods.stream().filter(method -> method.name.equals("<init>"))
                .map(method -> Type.getArgumentTypes(method.desc)).anyMatch(args -> args.length == 1
                        && args[0].getSort() == Type.OBJECT
                        && args[0].getInternalName().startsWith(YSM));
        return constructor && listGetters >= 8;
    }

    private static boolean isRender(MethodNode method, Names names) {
        Type[] args = Type.getArgumentTypes(method.desc);
        if (!Type.getReturnType(method.desc).equals(Type.VOID_TYPE) || args.length != 10
                || !args[0].getInternalName().equals(names.poseStack)
                || !args[1].getInternalName().equals(names.multiBuffer)
                || args[2].getSort() != Type.INT || args[3].getSort() != Type.OBJECT
                || !args[3].getInternalName().startsWith(YSM)) return false;
        for (int i = 4; i < args.length; i++) if (args[i].getSort() != Type.FLOAT) return false;
        return true;
    }

    private static MethodRef unique(Map<String, ClassNode> classes,
            java.util.function.Predicate<MethodNode> test) {
        List<MethodRef> matches = new ArrayList<>();
        for (ClassNode owner : classes.values()) owner.methods.stream().filter(test)
                .map(method -> ref(owner, method)).forEach(matches::add);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static MethodRef uniqueInOwner(Map<String, ClassNode> classes, String owner,
            java.util.function.Predicate<MethodNode> test) {
        ClassNode node = classes.get(owner);
        if (node == null) return null;
        List<MethodRef> matches = node.methods.stream().filter(test)
                .map(method -> ref(node, method)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static ClassNode methodOwner(Map<String, ClassNode> classes, MethodNode method) {
        for (ClassNode owner : classes.values()) if (owner.methods.contains(method)) return owner;
        return null;
    }

    private static MethodRef firstCall(MethodNode method,
            java.util.function.Predicate<MethodInsnNode> test) {
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && test.test(call)) return ref(call);
        return null;
    }

    private static boolean classContains(ClassNode owner, String value) {
        if (owner == null) return false;
        for (MethodNode method : owner.methods) for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof LdcInsnNode ldc && value.equals(ldc.cst)) return true;
        return false;
    }

    private static boolean calls(MethodNode method, MethodRef target) {
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(target.owner)
                    && call.name.equals(target.name) && call.desc.equals(target.descriptor)) return true;
        return false;
    }

    private static boolean referencesFieldOwner(MethodNode method, String owner) {
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof FieldInsnNode field && field.owner.equals(owner)) return true;
        return false;
    }

    private static boolean referencesField(MethodNode method, String owner, String name, String desc) {
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof FieldInsnNode field && field.owner.equals(owner)
                    && field.name.equals(name) && field.desc.equals(desc)) return true;
        return false;
    }

    private static boolean windowHasField(AbstractInsnNode[] values, int end, int opcode,
            String owner, String name, String desc, int width) {
        for (int i = Math.max(0, end - width); i < end; i++)
            if (values[i] instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && field.owner.equals(owner) && field.name.equals(name)
                    && field.desc.equals(desc)) return true;
        return false;
    }

    private static boolean windowHasInt(AbstractInsnNode[] values, int end, int expected, int width) {
        for (int i = Math.max(0, end - width); i < end; i++) {
            Integer value = intValue(values[i]);
            if (value != null && value == expected) return true;
        }
        return false;
    }

    private static boolean windowHasOpcode(AbstractInsnNode[] values, int end, int opcode, int width) {
        for (int i = Math.max(0, end - width); i < end; i++) if (values[i].getOpcode() == opcode) return true;
        return false;
    }

    private static Integer intValue(AbstractInsnNode value) {
        return switch (value.getOpcode()) {
            case ICONST_M1 -> -1; case ICONST_0 -> 0; case ICONST_1 -> 1; case ICONST_2 -> 2;
            case ICONST_3 -> 3; case ICONST_4 -> 4; case ICONST_5 -> 5;
            case BIPUSH, SIPUSH -> ((IntInsnNode) value).operand;
            case LDC -> value instanceof LdcInsnNode ldc && ldc.cst instanceof Integer number
                    ? number : null;
            default -> null;
        };
    }

    private static boolean isStatic(MethodNode method) { return (method.access & ACC_STATIC) != 0; }
    private static MethodNode node(Map<String, ClassNode> classes, MethodRef ref) {
        return classes.get(ref.owner).methods.stream().filter(method -> method.name.equals(ref.name)
                && method.desc.equals(ref.descriptor)).findFirst().orElseThrow();
    }
    private static MethodRef ref(ClassNode owner, MethodNode method) {
        return new MethodRef(owner.name, method.name, method.desc);
    }
    private static MethodRef ref(MethodInsnNode method) {
        return new MethodRef(method.owner, method.name, method.desc);
    }

    private static Map<String, ClassNode> readClasses(Path jarPath) throws IOException {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<JarEntry> entries = jar.stream().filter(entry -> !entry.isDirectory()
                    && entry.getName().startsWith(YSM) && entry.getName().endsWith(".class"))
                    .sorted(java.util.Comparator.comparing(JarEntry::getName)).toList();
            for (JarEntry entry : entries) try (InputStream input = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classes.put(node.name, node);
            }
        }
        return classes;
    }

    private record MethodRef(String owner, String name, String descriptor) {}
    private record Names(String livingEntity, String itemStack, String equipmentSlot,
                         String items, String poseStack, String multiBuffer,
                         List<String> entityTypes) {
        static Names from(AnalysisProfile.LoaderTypes value) {
            return new Names(value.livingEntity(), value.itemStack(), value.equipmentSlot(),
                    value.items(), value.poseStack(), value.multiBuffer(), value.entityTypes());
        }
    }
}
