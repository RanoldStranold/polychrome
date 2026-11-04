package net.ranold.polychrome.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import net.minecraft.client.Minecraft;
import net.ranold.polychrome.Polychrome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// bind the light volume wherever a lightmap gets bound. passes ignore names their pipeline doesnt
// declare so it's harmless without POLYCHROME, and it catches other mods' passes too
@Mixin(FrontendRenderPass.class)
public abstract class MixinFrontendRenderPass {

    @Inject(
            method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
            at = @At("HEAD")
    )
    private void polychrome$bindVolume(String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
        if (view == null || !(name.equals("Sampler2") || name.equals("u_LightTex"))) {
            return;
        }
        boolean world = view == Minecraft.getInstance().gameRenderer.levelLightmap();
        Polychrome.volume().bind((RenderPass) this, world);
    }
}
