package net.okitsu.ysmmapping.internal.mixin;

import net.okitsu.ysmmapping.api.YsmSourceAlias;
import net.okitsu.ysmmapping.api.YsmSymbols;
import net.okitsu.ysmmapping.internal.bootstrap.RequestManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinAliasValidatorTest {
    @Test
    void rejectsOnlyTheMixinWhoseCompiledAnnotationDiffers() {
        String mixin = "net.okitsu.example.YsmMixin";
        YsmSourceAlias alias = YsmSourceAlias.methodAlias(
                "net.okitsu.example.ysmref.NetworkRegistration", "send",
                "(Ljava/lang/Object;)V");
        RequestManifest manifest = new RequestManifest(Map.of(
                YsmSymbols.CLIENT_SEND_METHOD, true), Map.of(
                YsmSymbols.CLIENT_SEND_METHOD.id(), alias), Map.of(), Map.of(
                mixin, List.of(YsmSymbols.CLIENT_SEND_METHOD.id())));

        assertNull(MixinAliasValidator.validateStrings(mixin, manifest, List.of(
                alias.owner(), alias.name() + alias.descriptor())));
        String failure = MixinAliasValidator.validateStrings(mixin, manifest, List.of(
                alias.owner(), "different(Ljava/lang/Object;)V"));
        assertTrue(failure.contains(YsmSymbols.CLIENT_SEND_METHOD.id()));
    }
}
