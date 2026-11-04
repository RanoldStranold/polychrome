package net.ranold.polychrome.mixin;

import net.minecraft.world.level.chunk.LevelChunk;
import net.ranold.polychrome.compat.VoxyCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// voxy ingests chunks the player never loads (chunky pregen), color those too. see VoxyCompat.chunkGenerated
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService")
public abstract class MixinVoxyAutoIngest {

    @Inject(method = "tryAutoIngestChunk", at = @At("HEAD"), require = 0)
    private static void polychrome$chunkGenerated(LevelChunk chunk, CallbackInfoReturnable<Boolean> cir) {
        VoxyCompat.chunkGenerated(chunk);
    }
}
