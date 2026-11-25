package net.ranold.polychrome.api;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import net.ranold.polychrome.gpu.LightVolume;

// public api for other mods, called by reflection (Torchlit). a promise: never rename or change these,
// add new methods instead. never throw; any thread except colorAt (render thread)
public final class PolychromeApi {

    private PolychromeApi() {
    }

    // true while the world is drawn with colored light
    public static boolean enabled() {
        return Polychrome.tintEnabled();
    }

    // 0xRRGGBB light color of an item as the world shows it (held or placed): written items color, else its
    // block's, else white. dimension tint and the shader's saturation included
    public static int itemColor(ItemStack stack) {
        try {
            return saturated(LightColors.colorOf(stack) & 0xFFFFFF);
        } catch (RuntimeException e) {
            return 0xFFFFFF;
        }
    }

    // 0xRRGGBB hue of block light at a block as the world shows it there (dynamic lights included), brightest
    // channel 255. white where uncolored, unknown, colors off, or not called on the render thread
    public static int colorAt(BlockPos pos) {
        try {
            if (!Polychrome.tintEnabled() || !RenderSystem.isOnRenderThread() || Minecraft.getInstance().level == null) {
                return 0xFFFFFF;
            }
            return Polychrome.volume().hueAt(pos.getX(), pos.getY(), pos.getZ());
        } catch (RuntimeException e) {
            return 0xFFFFFF;
        }
    }

    // must match polychrome.glsl: mix(white, hue, saturation)
    private static int saturated(int rgb) {
        float s = LightVolume.SATURATION;
        int r = Math.round(255 + (((rgb >> 16) & 0xFF) - 255) * s);
        int g = Math.round(255 + (((rgb >> 8) & 0xFF) - 255) * s);
        int b = Math.round(255 + ((rgb & 0xFF) - 255) * s);
        return r << 16 | g << 8 | b;
    }
}
