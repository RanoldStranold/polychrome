package net.ranold.polychrome.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.ranold.polychrome.Polychrome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// client light engine reports every changed section here (same callback sodium rebuilds meshes from). read only
@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache {

    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void polychrome$lightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        if (layer == LightLayer.BLOCK) {
            Polychrome.lightChanged(pos.asLong());
        }
    }
}
