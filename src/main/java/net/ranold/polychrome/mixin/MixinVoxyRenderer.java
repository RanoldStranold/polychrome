package net.ranold.polychrome.mixin;

import net.ranold.polychrome.compat.VoxyCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// bind distant color after voxy binds its terrain buffers
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer")
public abstract class MixinVoxyRenderer {

    @Inject(method = "bindRenderingBuffers", at = @At("RETURN"), require = 0)
    private void polychrome$bindFarColors(CallbackInfo ci) {
        VoxyCompat.beforeTerrainDraw();
    }
}
