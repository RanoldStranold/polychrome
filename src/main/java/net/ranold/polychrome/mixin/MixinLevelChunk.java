package net.ranold.polychrome.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// block changes that can change light: emission, dampening, occlusion shape, color. doors/levers/redstone mostly skipped
@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk {

    @Shadow
    @Final
    private Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void polychrome$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        BlockState previous = cir.getReturnValue();
        if (previous == null || !(this.level instanceof ClientLevel)) {
            return;
        }
        if (!LightEngine.hasDifferentLightProperties(previous, state) && LightColors.sourceOf(previous) == LightColors.sourceOf(state)) {
            return;
        }
        Polychrome.blockChanged(pos, state);
    }
}
