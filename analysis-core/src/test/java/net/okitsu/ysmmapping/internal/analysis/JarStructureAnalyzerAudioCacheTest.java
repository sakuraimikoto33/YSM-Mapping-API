package net.okitsu.ysmmapping.internal.analysis;

import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JarStructureAnalyzerAudioCacheTest {
    private static final String MANAGER = "example/ClientManager";
    private static final String MODEL = "example/ClientModel";
    private static final String RESOURCES = "example/ModelResources";
    private static final String TRACK = "example/AudioTrack";
    private static final String CACHE = "example/AudioCache";
    private static final String PROVIDER = "example/AudioProvider";
    private static final String STREAM = "example/AudioStreamSupport";

    @Test
    void resolvesTheModelToAudioStreamChain() throws Exception {
        ClassNode manager = type(MANAGER);
        MethodNode lookup = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lookup", "(Ljava/lang/String;)Ljava/util/Optional;", null, null);
        lookup.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        lookup.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, MODEL));
        lookup.instructions.add(new InsnNode(Opcodes.POP));
        lookup.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        lookup.instructions.add(new InsnNode(Opcodes.ARETURN));
        manager.methods.add(lookup);

        ClassNode entity = type("example/ModelEntity");
        MethodNode soundLookup = new MethodNode(Opcodes.ACC_PUBLIC, "sound",
                "(L" + MODEL + ";Ljava/lang/String;)Ljava/lang/Object;", null, null);
        soundLookup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        soundLookup.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MODEL,
                "resources", "()L" + RESOURCES + ";", false));
        soundLookup.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RESOURCES,
                "sounds", "()Ljava/util/Map;", false));
        soundLookup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        soundLookup.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        soundLookup.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, TRACK));
        soundLookup.instructions.add(new InsnNode(Opcodes.ARETURN));
        entity.methods.add(soundLookup);

        ClassNode cache = type(CACHE);
        cache.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "acquire", "(L" + MODEL + ";)L" + PROVIDER + ";", null, null));

        ClassNode provider = interfaceType(PROVIDER);
        provider.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "open", "(L" + TRACK + ";)L" + STREAM + ";", null, null));

        ClassNode stream = interfaceType(STREAM);
        stream.interfaces.add("net/minecraft/client/sounds/AudioStream");
        stream.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "closed", "()Z", null, null));

        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (ClassNode node : new ClassNode[]{manager, entity, type(MODEL),
                type(RESOURCES), type(TRACK), cache, provider, stream}) {
            classes.put(node.name, node);
        }

        JarStructureAnalyzer.ClientAudioCacheSymbols result =
                JarStructureAnalyzer.findClientAudioCacheSymbols(classes,
                        new YsmMethodSymbol(MANAGER, "lookup",
                                "(Ljava/lang/String;)Ljava/util/Optional;"));

        assertEquals("resources", result.modelResourcesGetter().name());
        assertEquals("sounds", result.soundsGetter().name());
        assertEquals("acquire", result.cacheAcquire().name());
        assertEquals("open", result.streamOpen().name());
    }

    private static ClassNode type(String name) {
        ClassNode node = new ClassNode();
        node.name = name;
        return node;
    }

    private static ClassNode interfaceType(String name) {
        ClassNode node = type(name);
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        return node;
    }
}
