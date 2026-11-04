package net.ranold.polychrome.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.ranold.polychrome.Polychrome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;
import java.util.Iterator;

// light from the server goes straight into storage without the change callback, so MixinClientChunkCache
// never sees it. chunk light comes after the chunk, without this worldgen campfires / lava lakes stay white
// until something nearby changes. read only
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(method = "readSectionList", at = @At("HEAD"))
    private void polychrome$lightReceived(int chunkX, int chunkZ, LevelLightEngine lightEngine, LightLayer layer,
            BitSet yMask, BitSet emptyYMask, Iterator<byte[]> updates, boolean scheduleRebuild, CallbackInfo ci) {
        if (layer != LightLayer.BLOCK) {
            return;
        }
        int minSection = lightEngine.getMinLightSection();
        BitSet changed = (BitSet) yMask.clone();
        changed.or(emptyYMask);
        for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
            Polychrome.lightChanged(SectionPos.asLong(chunkX, minSection + i, chunkZ));
        }
    }
}
