package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves a public Molang registration, never an inferred replacement formula. */
public final class MolangQuerySemanticAnalyzer {
    private static final String QUERY_NAME = "ysm.ground_speed2";
    private static final String QUERY_LOCAL_NAME = "ground_speed2";
    private static final String METAFACTORY_DESCRIPTOR =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;";

    public Analysis analyze(YsmClassIndex index) {
        return analyze(Objects.requireNonNull(index, "index").byName());
    }

    Analysis analyze(Map<String, ClassNode> classes) {
        try {
            List<Registration> registrations = new ArrayList<>();
            for (ClassNode owner : classes.values()) {
                for (MethodNode method : owner.methods) {
                    if (containsAnchor(method)) {
                        registrations.addAll(registrations(owner, method));
                    }
                }
            }
            if (registrations.size() != 1) {
                return unresolved("Expected one unambiguous public query registration; found "
                        + registrations.size());
            }
            Registration registration = registrations.get(0);
            if (registration.localName() && !hasNamespaceBinding(classes, registration.owner())) {
                return unresolved("Public query namespace binding is unsupported");
            }
            Query query = resolve(classes, registration);
            if (query == null) {
                return unresolved("Public query implementation or context contract is unsupported");
            }
            return new Analysis(Map.of(
                    YsmSymbols.MOLANG_GROUND_SPEED2_QUERY, query.implementation(),
                    YsmSymbols.MOLANG_QUERY_CONTEXT_GET, query.contextAccessor()), Map.of());
        } catch (AnalyzerException | RuntimeException exception) {
            // Exception messages from ASM can contain private names/instructions.
            return unresolved("Public query registration could not be safely analyzed");
        }
    }

    private static boolean containsAnchor(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant
                    && isQueryName(constant.cst)) {
                return true;
            }
        }
        return false;
    }

    private static List<Registration> registrations(ClassNode owner, MethodNode method)
            throws AnalyzerException {
        Frame<SourceValue>[] frames = new Analyzer<>(new OriginInterpreter())
                .analyze(owner.name, method);
        List<Registration> registrations = new ArrayList<>();
        int instructionIndex = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            Frame<SourceValue> frame = frames[instructionIndex++];
            if (!(instruction instanceof MethodInsnNode call) || frame == null) {
                continue;
            }
            Type[] arguments = Type.getArgumentTypes(call.desc);
            int firstArgument = frame.getStackSize() - arguments.length;
            if (firstArgument < 0) {
                continue;
            }
            int anchors = 0;
            boolean ambiguousAnchor = false;
            boolean localName = false;
            List<InvokeDynamicInsnNode> lambdas = new ArrayList<>();
            for (int index = 0; index < arguments.length; index++) {
                SourceValue source = frame.getStack(firstArgument + index);
                boolean hasAnchor = source.insns.stream().anyMatch(value ->
                        value instanceof LdcInsnNode constant
                                && isQueryName(constant.cst));
                if (hasAnchor) {
                    anchors++;
                    ambiguousAnchor |= source.insns.size() != 1
                            || !acceptsStringConstant(arguments[index]);
                    localName |= source.insns.stream().anyMatch(value ->
                            value instanceof LdcInsnNode constant
                                    && QUERY_LOCAL_NAME.equals(constant.cst));
                }
                AbstractInsnNode origin = singleOrigin(source);
                if (origin instanceof InvokeDynamicInsnNode dynamic
                        && (arguments[index].equals(Type.getReturnType(dynamic.desc))
                                || arguments[index].equals(Type.getType(Object.class)))) {
                    lambdas.add(dynamic);
                }
            }
            if (anchors == 0) {
                continue;
            }
            // Count malformed registrations too: a second/ambiguous anchor must not be
            // hidden just because its lambda shape cannot be resolved.
            registrations.add(new Registration(anchors == 1 && !ambiguousAnchor
                    && lambdas.size() == 1 ? lambdas.get(0) : null, owner.name, localName));
        }
        return registrations;
    }

    private static AbstractInsnNode singleOrigin(SourceValue source) {
        return source.insns.size() == 1 ? source.insns.iterator().next() : null;
    }

    private static boolean isQueryName(Object value) {
        return QUERY_NAME.equals(value) || QUERY_LOCAL_NAME.equals(value);
    }

    private static boolean acceptsStringConstant(Type argument) {
        // The exact LDC origin proves String even when a generic registry erases it to Object.
        return argument.equals(Type.getType(String.class))
                || argument.equals(Type.getType(Object.class));
    }

    private static Query resolve(Map<String, ClassNode> classes, Registration registration)
            throws AnalyzerException {
        InvokeDynamicInsnNode lambda = registration.lambda();
        if (lambda == null || lambda.bsm.getTag() != Opcodes.H_INVOKESTATIC
                || lambda.bsm.isInterface()
                || !lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                || !lambda.bsm.getName().equals("metafactory")
                || !lambda.bsm.getDesc().equals(METAFACTORY_DESCRIPTOR)
                || Type.getArgumentTypes(lambda.desc).length != 0
                || Type.getReturnType(lambda.desc).getSort() != Type.OBJECT
                || lambda.bsmArgs.length != 3
                || !(lambda.bsmArgs[0] instanceof Type sam)
                || sam.getSort() != Type.METHOD
                || !(lambda.bsmArgs[1] instanceof Handle implementation)
                || !(lambda.bsmArgs[2] instanceof Type instantiated)
                || instantiated.getSort() != Type.METHOD
                || implementation.getTag() != Opcodes.H_INVOKESTATIC
                || implementation.isInterface()) {
            return null;
        }
        Type[] arguments = Type.getArgumentTypes(implementation.getDesc());
        if (arguments.length != 1 || arguments[0].getSort() != Type.OBJECT
                || !Type.getReturnType(implementation.getDesc()).equals(Type.FLOAT_TYPE)
                || !java.util.Arrays.equals(instantiated.getArgumentTypes(), arguments)
                || !(instantiated.getReturnType().equals(Type.FLOAT_TYPE)
                        || instantiated.getReturnType().equals(Type.getType(Float.class))
                        || instantiated.getReturnType().equals(Type.getType(Object.class)))
                || sam.getArgumentTypes().length != 1
                || sam.getArgumentTypes()[0].getSort() != Type.OBJECT
                || !(sam.getArgumentTypes()[0].equals(arguments[0])
                        || sam.getArgumentTypes()[0].equals(Type.getType(Object.class)))
                || !(sam.getReturnType().equals(instantiated.getReturnType())
                        || sam.getReturnType().equals(Type.getType(Object.class))
                                && instantiated.getReturnType().equals(Type.getType(Float.class)))
                || !validFactory(classes, lambda, sam)) {
            return null;
        }
        ClassNode input = classes.get(arguments[0].getInternalName());
        ClassNode owner = classes.get(implementation.getOwner());
        MethodNode target = declared(owner, implementation.getName(), implementation.getDesc());
        if (input == null || (input.access & Opcodes.ACC_INTERFACE) == 0
                || target == null || (target.access & Opcodes.ACC_STATIC) == 0
                || (target.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
            return null;
        }
        Set<YsmMethodSymbol> accessors = new LinkedHashSet<>();
        for (AbstractInsnNode instruction : target.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(input.name)) {
                Type result = Type.getReturnType(call.desc);
                MethodNode accessor = declared(input, call.name, call.desc);
                ClassNode context = result.getSort() == Type.OBJECT
                        ? classes.get(result.getInternalName()) : null;
                if (call.getOpcode() != Opcodes.INVOKEINTERFACE || !call.itf
                        || Type.getArgumentTypes(call.desc).length != 0
                        || accessor == null
                        || (accessor.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT))
                                != (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT)
                        || (accessor.access & Opcodes.ACC_STATIC) != 0
                        || context == null || (context.access & Opcodes.ACC_INTERFACE) != 0) {
                    return null;
                }
                accessors.add(new YsmMethodSymbol(call.owner, call.name, call.desc));
            }
        }
        if (accessors.size() != 1) {
            return null;
        }
        YsmMethodSymbol accessor = accessors.iterator().next();
        InputUseInterpreter interpreter = new InputUseInterpreter(input.name, accessor);
        new Analyzer<>(interpreter).analyze(owner.name, target);
        if (!interpreter.usedContextAccessor) {
            return null;
        }
        return new Query(new YsmMethodSymbol(implementation.getOwner(), implementation.getName(),
                implementation.getDesc()), accessor);
    }

    /** Proves the short name belongs to the public namespace, including a lazy singleton. */
    private static boolean hasNamespaceBinding(Map<String, ClassNode> classes, String queryOwner)
            throws AnalyzerException {
        int bindings = 0;
        for (ClassNode owner : classes.values()) {
            for (MethodNode method : owner.methods) {
                boolean namespacePresent = false;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LdcInsnNode constant && "ysm".equals(constant.cst)) {
                        namespacePresent = true;
                        break;
                    }
                }
                if (!namespacePresent) continue;
                Frame<SourceValue>[] frames = new Analyzer<>(new OriginInterpreter())
                        .analyze(owner.name, method);
                int instructionIndex = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    Frame<SourceValue> frame = frames[instructionIndex++];
                    if (!(instruction instanceof MethodInsnNode call) || frame == null) continue;
                    Type[] arguments = Type.getArgumentTypes(call.desc);
                    if (arguments.length != 2 || frame.getStackSize() < 2) continue;
                    int start = frame.getStackSize() - 2;
                    int namespaceArgument = -1;
                    for (int index = 0; index < 2; index++) {
                        if (acceptsStringConstant(arguments[index])
                                && singleOrigin(frame.getStack(start + index))
                                        instanceof LdcInsnNode constant
                                && "ysm".equals(constant.cst)) {
                            namespaceArgument = index;
                        }
                    }
                    if (namespaceArgument < 0) continue;
                    AbstractInsnNode value = singleOrigin(frame.getStack(
                            start + 1 - namespaceArgument));
                    if (value instanceof MethodInsnNode getter
                            && Type.getArgumentTypes(getter.desc).length == 0
                            && Type.getReturnType(getter.desc).equals(Type.getType(Object.class))
                            && (getter.getOpcode() == Opcodes.INVOKEVIRTUAL
                                    || getter.getOpcode() == Opcodes.INVOKEINTERFACE)) {
                        Frame<SourceValue> getterFrame = frames[method.instructions.indexOf(getter)];
                        if (getterFrame != null && getterFrame.getStackSize() > 0
                                && singleOrigin(getterFrame.getStack(getterFrame.getStackSize() - 1))
                                        instanceof FieldInsnNode holder
                                && holder.getOpcode() == Opcodes.GETSTATIC
                                && holder.owner.equals(queryOwner)
                                && lazyFieldConstructsOwner(classes, holder, getter.owner)) {
                            bindings++;
                        }
                    }
                }
            }
        }
        return bindings == 1;
    }

    private static boolean lazyFieldConstructsOwner(Map<String, ClassNode> classes,
            FieldInsnNode holder, String getterOwner) throws AnalyzerException {
        ClassNode owner = classes.get(holder.owner);
        Type holderType = Type.getType(holder.desc);
        if (owner == null || holderType.getSort() != Type.OBJECT
                || !holderType.getInternalName().equals(getterOwner)
                || owner.fields.stream().filter(field -> field.name.equals(holder.name)
                        && field.desc.equals(holder.desc)
                        && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                                == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)).count() != 1) {
            return false;
        }
        MethodNode initializer = declared(owner, "<clinit>", "()V");
        if (initializer == null) return false;
        Frame<SourceValue>[] frames = new Analyzer<>(new OriginInterpreter())
                .analyze(owner.name, initializer);
        int writes = 0;
        int factories = 0;
        int instructionIndex = 0;
        for (AbstractInsnNode instruction : initializer.instructions) {
            Frame<SourceValue> frame = frames[instructionIndex++];
            if (!(instruction instanceof FieldInsnNode write)
                    || write.getOpcode() != Opcodes.PUTSTATIC || !write.owner.equals(holder.owner)
                    || !write.name.equals(holder.name) || !write.desc.equals(holder.desc)) continue;
            writes++;
            if (frame == null || frame.getStackSize() == 0) continue;
            AbstractInsnNode origin = singleOrigin(frame.getStack(frame.getStackSize() - 1));
            if (origin instanceof MethodInsnNode factory
                    && factory.getOpcode() == Opcodes.INVOKESTATIC
                    && Type.getReturnType(factory.desc).equals(holderType)
                    && suppliesOwnerConstructor(owner, initializer, frames, factory)) {
                factories++;
            } else if (origin instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals(holderType.getInternalName())) {
                int constructors = 0;
                for (AbstractInsnNode candidate : initializer.instructions) {
                    if (!(candidate instanceof MethodInsnNode constructor)
                            || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                            || !constructor.name.equals("<init>")
                            || !constructor.owner.equals(allocation.desc)) continue;
                    Frame<SourceValue> callFrame = frames[initializer.instructions.indexOf(constructor)];
                    int receiver = callFrame == null ? -1 : callFrame.getStackSize()
                            - Type.getArgumentTypes(constructor.desc).length - 1;
                    if (receiver >= 0 && singleOrigin(callFrame.getStack(receiver)) == allocation
                            && suppliesOwnerConstructor(owner, initializer, frames, constructor)) {
                        constructors++;
                    }
                }
                if (constructors == 1) factories++;
            }
        }
        return writes == 1 && factories == 1;
    }

    private static boolean suppliesOwnerConstructor(ClassNode owner, MethodNode initializer,
            Frame<SourceValue>[] frames, MethodInsnNode factory) {
        Frame<SourceValue> frame = frames[initializer.instructions.indexOf(factory)];
        Type[] arguments = Type.getArgumentTypes(factory.desc);
        int start = frame == null ? -1 : frame.getStackSize() - arguments.length;
        if (start < 0) return false;
        int suppliers = 0;
        for (int index = 0; index < arguments.length; index++) {
            if (!arguments[index].equals(Type.getType(java.util.function.Supplier.class))) continue;
            AbstractInsnNode source = singleOrigin(frame.getStack(start + index));
            if (!(source instanceof InvokeDynamicInsnNode lambda)
                    || !lambda.name.equals("get")
                    || !lambda.desc.equals("()Ljava/util/function/Supplier;")
                    || lambda.bsm.getTag() != Opcodes.H_INVOKESTATIC
                    || !lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                    || !lambda.bsm.getName().equals("metafactory")
                    || !lambda.bsm.getDesc().equals(METAFACTORY_DESCRIPTOR)
                    || lambda.bsmArgs.length != 3
                    || !Type.getMethodType("()Ljava/lang/Object;").equals(lambda.bsmArgs[0])
                    || !(lambda.bsmArgs[1] instanceof Handle constructor)
                    || constructor.getTag() != Opcodes.H_NEWINVOKESPECIAL
                    || constructor.isInterface() || !constructor.getOwner().equals(owner.name)
                    || !constructor.getName().equals("<init>")
                    || !constructor.getDesc().equals("()V")
                    || !Type.getMethodType("()L" + owner.name + ";").equals(lambda.bsmArgs[2])
                    || declared(owner, "<init>", "()V") == null) continue;
            suppliers++;
        }
        return suppliers == 1;
    }

    private static boolean validFactory(Map<String, ClassNode> classes,
            InvokeDynamicInsnNode lambda, Type sam) {
        String name = Type.getReturnType(lambda.desc).getInternalName();
        if (name.equals("java/util/function/Function")) {
            return lambda.name.equals("apply")
                    && sam.getDescriptor().equals("(Ljava/lang/Object;)Ljava/lang/Object;");
        }
        ClassNode function = classes.get(name);
        MethodNode method = declared(function, lambda.name, sam.getDescriptor());
        return function != null && (function.access & Opcodes.ACC_INTERFACE) != 0
                && method != null
                && (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT))
                        == (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT)
                && (method.access & Opcodes.ACC_STATIC) == 0;
    }

    private static MethodNode declared(ClassNode owner, String name, String descriptor) {
        if (owner == null) {
            return null;
        }
        List<MethodNode> methods = owner.methods.stream().filter(value ->
                value.name.equals(name) && value.desc.equals(descriptor)).toList();
        return methods.size() == 1 ? methods.get(0) : null;
    }

    private static Analysis unresolved(String message) {
        return new Analysis(Map.of(), Map.of(
                YsmSymbols.MOLANG_GROUND_SPEED2_QUERY, message,
                YsmSymbols.MOLANG_QUERY_CONTEXT_GET, message));
    }

    /** Keep direct origins through local stores/loads, stack copies, and reference casts. */
    private static class OriginInterpreter extends SourceInterpreter {
        private OriginInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
            return value;
        }

        @Override
        public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            return instruction.getOpcode() == Opcodes.CHECKCAST ? value
                    : super.unaryOperation(instruction, value);
        }
    }

    /** The proxy may supply only the mapped accessor; reject every other use of its input. */
    private static final class InputUseInterpreter extends OriginInterpreter {
        private final AbstractInsnNode parameter = new VarInsnNode(Opcodes.ALOAD, 0);
        private final String inputType;
        private final YsmMethodSymbol accessor;
        private boolean usedContextAccessor;

        private InputUseInterpreter(String inputType, YsmMethodSymbol accessor) {
            this.inputType = inputType;
            this.accessor = accessor;
        }

        @Override
        public SourceValue newParameterValue(boolean instance, int local, Type type) {
            return !instance && local == 0 ? new SourceValue(type.getSize(), parameter)
                    : super.newParameterValue(instance, local, type);
        }

        @Override
        public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            if (isInput(value) && !(instruction instanceof TypeInsnNode cast
                    && cast.getOpcode() == Opcodes.CHECKCAST
                    && (cast.desc.equals(inputType) || cast.desc.equals("java/lang/Object")))) {
                throw unsupportedInput();
            }
            return super.unaryOperation(instruction, value);
        }

        @Override
        public SourceValue binaryOperation(AbstractInsnNode instruction,
                SourceValue first, SourceValue second) {
            if (isInput(first) || isInput(second)) throw unsupportedInput();
            return super.binaryOperation(instruction, first, second);
        }

        @Override
        public SourceValue ternaryOperation(AbstractInsnNode instruction,
                SourceValue first, SourceValue second, SourceValue third) {
            if (isInput(first) || isInput(second) || isInput(third)) throw unsupportedInput();
            return super.ternaryOperation(instruction, first, second, third);
        }

        @Override
        public SourceValue naryOperation(AbstractInsnNode instruction,
                List<? extends SourceValue> values) {
            boolean mappedCall = instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE && call.itf
                    && call.owner.equals(accessor.owner()) && call.name.equals(accessor.name())
                    && call.desc.equals(accessor.descriptor());
            boolean inputUsed = values.stream().anyMatch(this::isInput);
            if (mappedCall) {
                if (values.size() != 1 || !isInput(values.get(0))
                        || values.get(0).insns.size() != 1) throw unsupportedInput();
                usedContextAccessor = true;
            } else if (inputUsed) {
                throw unsupportedInput();
            }
            return super.naryOperation(instruction, values);
        }

        @Override
        public void returnOperation(AbstractInsnNode instruction,
                SourceValue value, SourceValue expected) {
            if (isInput(value)) throw unsupportedInput();
            super.returnOperation(instruction, value, expected);
        }

        private boolean isInput(SourceValue value) {
            return value.insns.contains(parameter);
        }

        private static IllegalArgumentException unsupportedInput() {
            return new IllegalArgumentException("Unsupported query input use");
        }
    }

    public record Analysis(Map<YsmSymbolKey<?>, YsmResolvedSymbol> symbols,
                           Map<YsmSymbolKey<?>, String> diagnostics) {
        public Analysis {
            symbols = Map.copyOf(symbols);
            diagnostics = Map.copyOf(diagnostics);
        }
    }

    private record Registration(InvokeDynamicInsnNode lambda, String owner, boolean localName) {
    }

    private record Query(YsmMethodSymbol implementation, YsmMethodSymbol contextAccessor) {
    }
}
