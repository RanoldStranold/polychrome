package net.ranold.polychrome.compat;

import dev.lambdaurora.lambdynlights.LambDynLights;
import dev.lambdaurora.lambdynlights.engine.source.DynamicLightSource;
import dev.lambdaurora.lambdynlights.engine.source.EntityDynamicLightSource;
import net.minecraft.client.particle.FireflyParticle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import net.ranold.polychrome.field.DynamicLight;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

// reads LambDynamicLights sources so they get color. linked against LDL, only loaded when it's
// installed (Polychrome.DYNAMIC_LIGHTS). color picked like LDL picks luminance. beams not read yet, stay white
public final class DynamicLightsCompat {

    private static final DynamicLight[] NONE = new DynamicLight[0];
    private static Field sourcesField;
    private static boolean failed;
    // LDL's Fast / Fastest modes refresh luminance only every 5-20 ticks, so a torch just put away (or
    // swapped for a dimmer light) still shines at its old level a moment: keep the color it last had. game thread
    private static final Map<DynamicLightSource, Integer> lastColor = new WeakHashMap<>();

    // what the entity has that gives light, and how bright LDL would make it
    private record Pick(int rgb, int luminance) {
    }

    private DynamicLightsCompat() {
    }

    // game thread
    public static DynamicLight[] gather() {
        Set<DynamicLightSource> sources = sources();
        if (sources == null || sources.isEmpty()) {
            return NONE;
        }
        List<DynamicLight> lights = new ArrayList<>(sources.size());
        for (DynamicLightSource source : sources) {
            if (!(source instanceof EntityDynamicLightSource light)) {
                continue;  // light shape (beam), not read yet
            }
            int luminance = light.getLuminance();
            if (luminance <= 0) {
                continue;
            }
            int rgb;
            if (source instanceof Entity entity) {
                Pick pick = colorOf(entity);
                Integer last = lastColor.get(source);
                if (pick.luminance() >= luminance || last == null) {
                    rgb = pick.luminance() > 0 ? pick.rgb() : LightColors.white();
                    lastColor.put(source, rgb);
                } else {
                    rgb = last;
                }
            } else {
                rgb = source instanceof FireflyParticle ? LightColors.colorOf(Blocks.FIREFLY_BUSH.defaultBlockState()) : LightColors.white();
            }
            lights.add(new DynamicLight(light.getDynamicLightX(), light.getDynamicLightY(), light.getDynamicLightZ(), luminance, rgb & 0xFFFFFF));
        }
        return lights.toArray(DynamicLight[]::new);
    }

    @SuppressWarnings("unchecked")
    private static Set<DynamicLightSource> sources() {
        if (failed) {
            return null;
        }
        try {
            if (sourcesField == null) {
                sourcesField = LambDynLights.class.getDeclaredField("dynamicLightSources");
                sourcesField.setAccessible(true);
            }
            return (Set<DynamicLightSource>) sourcesField.get(LambDynLights.get());
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            Polychrome.LOGGER.warn("Could not read LambDynamicLights' light sources; dynamic light stays uncolored", e);
            return null;
        }
    }

    // same order as LDL: fire first (always 15), then the brightest of held/carried items and the entity's own light.
    // luminance 0 if nothing we read gives light
    private static Pick colorOf(Entity entity) {
        if (entity.isOnFire()) {
            return new Pick(LightColors.colorOf(Blocks.FIRE.defaultBlockState()), 15);
        }
        int best = -1, bestRgb = LightColors.white();
        // own light (blaze, magma cube) if its type has a color
        int own = LightColors.colorOf(entity.getType());
        if (own >= 0) {
            best = LambDynLights.getLuminanceFrom(entity);
            bestRgb = own;
        }
        for (ItemStack stack : carried(entity)) {
            if (stack.isEmpty()) {
                continue;
            }
            int l = LambDynLights.get().itemLightSourceManager().getLuminance(stack);
            if (l > best) {
                best = l;
                bestRgb = LightColors.colorOf(stack);
            }
        }
        if (entity instanceof Enderman enderman) {
            BlockState block = enderman.getCarriedBlock();
            if (block != null && block.getLightEmission() > best) {
                best = block.getLightEmission();
                bestRgb = LightColors.colorOf(block);
            }
        }
        // something we dont read (glowing effect, another mod) gives 0: white
        return new Pick(bestRgb, Math.max(best, 0));
    }

    private static List<ItemStack> carried(Entity entity) {
        if (entity instanceof LivingEntity living) {
            List<ItemStack> stacks = new ArrayList<>(EquipmentSlot.VALUES.size());
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                stacks.add(living.getItemBySlot(slot));
            }
            return stacks;
        }
        if (entity instanceof ItemEntity item) {
            return List.of(item.getItem());
        }
        if (entity instanceof ItemFrame frame) {
            return List.of(frame.getItem());
        }
        if (entity instanceof ItemSupplier thrown) {
            return List.of(thrown.getItem());
        }
        return List.of();
    }
}
