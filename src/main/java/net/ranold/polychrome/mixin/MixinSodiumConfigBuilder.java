package net.ranold.polychrome.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.ranold.polychrome.Settings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// our on/off right under brightness on sodium's general page (3rd option of the first group: render
// distance, simulation distance, brightness). only added when sodium is installed (PolychromeMixinPlugin).
// require = 0: a sodium update just loses the option, the mod menu screen still has it
@Mixin(value = SodiumConfigBuilder.class, remap = false)
public abstract class MixinSodiumConfigBuilder {

    @WrapOperation(method = "buildGeneralPage", require = 0, at = @At(value = "INVOKE", ordinal = 2,
            target = "Lnet/caffeinemc/mods/sodium/api/config/structure/OptionGroupBuilder;addOption(Lnet/caffeinemc/mods/sodium/api/config/structure/OptionBuilder;)Lnet/caffeinemc/mods/sodium/api/config/structure/OptionGroupBuilder;"))
    private OptionGroupBuilder polychrome$addToggle(OptionGroupBuilder group, OptionBuilder brightness,
            Operation<OptionGroupBuilder> original, @Local(argsOnly = true) ConfigBuilder builder) {
        OptionGroupBuilder result = original.call(group, brightness);
        return original.call(result, builder.createBooleanOption(Identifier.fromNamespaceAndPath("polychrome", "general.enabled"))
                .setName(Component.translatable("polychrome.options.enabled"))
                .setTooltip(Component.translatable("polychrome.options.enabled.tooltip"))
                .setStorageHandler(() -> { })
                .setDefaultValue(true)
                .setImpact(OptionImpact.LOW)
                .setBinding(Settings::setEnabled, Settings::enabled));
    }
}
