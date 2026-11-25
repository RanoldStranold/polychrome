package net.ranold.polychrome.mixin;

import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import net.ranold.polychrome.shader.NvidiumPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// nvidium terrain shader source, after its own imports and defines
@Pseudo
@Mixin(targets = "me.cortex.nvidium.sodiumCompat.ShaderLoader")
public abstract class MixinNvidiumShaderLoader {

    @Inject(method = "parse(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/renderer/ShaderDefines$Builder;)Ljava/lang/String;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void polychrome$patch(Identifier id, ShaderDefines.Builder defines, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(NvidiumPatcher.patch(id, cir.getReturnValue()));
    }
}
