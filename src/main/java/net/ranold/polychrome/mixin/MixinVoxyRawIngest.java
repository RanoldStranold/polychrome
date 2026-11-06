package net.ranold.polychrome.mixin;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.ranold.polychrome.compat.VoxyCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// sections other mods hand voxy one by one (voxygen), never through tryAutoIngestChunk
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService")
public abstract class MixinVoxyRawIngest {

    @Inject(method = "rawIngest(Lme/cortex/voxy/commonImpl/WorldIdentifier;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z",
            at = @At("HEAD"), require = 0)
    private static void polychrome$sectionIngested(@Coerce Object worldId, LevelChunkSection section, int cx, int cy, int cz,
                                                   DataLayer blockLight, DataLayer skyLight, CallbackInfoReturnable<Boolean> cir) {
        VoxyCompat.sectionIngested(worldId, section, cx, cy, cz, blockLight);
    }
}
