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

final class JarStructureAnalyzerTextureCacheTest {
    private static final String MANAGER = "example/ClientManager";
    private static final String MODEL = "example/ClientModel";
    private static final String DATA = "example/ModelData";
    private static final String TEXTURES = "example/OrderedTextures";
    private static final String CACHE = "example/TextureCache";
    private static final String LEASE = "example/TextureLease";

    @Test
    void resolvesTheModelToTextureLeaseChain() throws Exception {
        ClassNode manager = type(MANAGER);
        MethodNode lookup = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lookup", "(Ljava/lang/String;)Ljava/util/Optional;", null, null);
        lookup.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        lookup.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, MODEL));
        lookup.instructions.add(new InsnNode(Opcodes.POP));
        lookup.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        lookup.instructions.add(new InsnNode(Opcodes.ARETURN));
        manager.methods.add(lookup);

        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "cacheDefault", "(L" + MODEL + ";)V", null, null);
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MODEL,
                "data", "()L" + DATA + ";", false));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DATA,
                "textures", "()L" + TEXTURES + ";", false));
        helper.instructions.add(new InsnNode(Opcodes.ICONST_0));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TEXTURES,
                "at", "(I)Ljava/lang/Object;", false));
        helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,
                "net/minecraft/client/renderer/texture/AbstractTexture"));
        helper.instructions.add(new InsnNode(Opcodes.ICONST_1));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CACHE,
                "acquire", "(Lnet/minecraft/client/renderer/texture/AbstractTexture;Z)L"
                        + LEASE + ";", false));
        helper.instructions.add(new InsnNode(Opcodes.POP));
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        manager.methods.add(helper);

        ClassNode lease = type(LEASE);
        lease.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "location", "()Ljava/util/Optional;", null, null));

        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (ClassNode node : new ClassNode[]{manager, type(MODEL), type(DATA),
                type(TEXTURES), type(CACHE), lease}) {
            classes.put(node.name, node);
        }

        JarStructureAnalyzer.ClientTextureCacheSymbols result =
                JarStructureAnalyzer.findClientTextureCacheSymbols(classes,
                        new YsmMethodSymbol(MANAGER, "lookup",
                                "(Ljava/lang/String;)Ljava/util/Optional;"));

        assertEquals("data", result.modelDataGetter().name());
        assertEquals("textures", result.texturesGetter().name());
        assertEquals("acquire", result.cacheAcquire().name());
        assertEquals("location", result.locationGetter().name());
    }

    private static ClassNode type(String name) {
        ClassNode node = new ClassNode();
        node.name = name;
        return node;
    }
}
