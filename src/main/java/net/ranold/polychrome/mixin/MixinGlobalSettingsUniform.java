package net.ranold.polychrome.mixin;

import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.world.phys.Vec3;
import net.ranold.polychrome.Polychrome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// vanilla writes per frame globals (camera too) here, outside any pass. volume updates with the same camera so they match
@Mixin(GlobalSettingsUniform.class)
public abstract class MixinGlobalSettingsUniform {

    @Inject(method = "update", at = @At("TAIL"))
    private void polychrome$updateVolume(int width, int height, double glintAlpha, long gameTime, float worldPartialTicks,
                                              int menuBlurRadius, Vec3 cameraPos, boolean useRgss, CallbackInfo ci) {
        Polychrome.onFrame(cameraPos);
    }
}
