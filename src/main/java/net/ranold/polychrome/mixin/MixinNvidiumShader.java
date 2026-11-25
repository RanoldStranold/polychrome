package net.ranold.polychrome.mixin;

import net.ranold.polychrome.Polychrome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// nvidium binds raw GL programs, so the light volume goes on its fixed binding points every time
@Pseudo
@Mixin(targets = "me.cortex.nvidium.gl.shader.Shader")
public abstract class MixinNvidiumShader {

    @Inject(method = "bind()V", at = @At("RETURN"), require = 0)
    private void polychrome$bindVolume(CallbackInfo ci) {
        Polychrome.volume().bindRawGl();
    }
}
