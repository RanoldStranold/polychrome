package net.ranold.polychrome;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// voxy and sodium mixins only get added when that mod is installed, otherwise mixin logs "Error loading class" for each
public final class PolychromeMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (FabricLoader.getInstance().isModLoaded("voxy")) {
            mixins.addAll(List.of("MixinVoxyAutoIngest", "MixinVoxyRawIngest", "MixinVoxyRenderer", "MixinVoxyShaderLoader"));
        }
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            mixins.add("MixinSodiumConfigBuilder");
        }
        return mixins;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
