package net.ranold.polychrome.mixin;

import net.minecraft.resources.Identifier;
import net.ranold.polychrome.compat.VoxyCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// @Pseudo so it loads without voxy, require = 0 so a voxy rename only loses LOD color
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader$ShaderLoadingParser")
public abstract class MixinVoxyShaderLoader {

    @Inject(method = "loadShaderAsset", at = @At("RETURN"), cancellable = true, require = 0)
    private static void polychrome$patchShader(Identifier id, CallbackInfoReturnable<String> cir) {
        String patched = VoxyCompat.patchShader(id, cir.getReturnValue());
        if (patched != cir.getReturnValue()) {
            cir.setReturnValue(patched);
        }
    }
}
