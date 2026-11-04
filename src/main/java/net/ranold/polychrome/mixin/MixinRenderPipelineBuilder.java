package net.ranold.polychrome.mixin;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.ranold.polychrome.gpu.LightVolume;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Set;

// pipelines that sample a lightmap (Sampler2 vanilla, u_LightTex sodium) get the volume bindings + POLYCHROME define.
// pipeline/lightmap itself has neither so it's untouched (torchlit hooks this method for that one)
@Mixin(RenderPipeline.Builder.class)
public abstract class MixinRenderPipelineBuilder {

    @Shadow
    private Optional<Set<BindGroupLayout>> bindGroupLayouts;

    @Shadow
    public abstract RenderPipeline.Builder withBindGroupLayout(BindGroupLayout bindGroupLayout);

    @Shadow
    public abstract RenderPipeline.Builder withShaderDefine(String key);

    @Inject(method = "build", at = @At("HEAD"))
    private void polychrome$addLightVolume(CallbackInfoReturnable<RenderPipeline> cir) {
        if (this.bindGroupLayouts.isEmpty() || this.bindGroupLayouts.get().contains(LightVolume.LAYOUT)) {
            return;
        }
        boolean samplesLightmap = false;
        for (BindGroupLayout layout : this.bindGroupLayouts.get()) {
            for (BindGroupLayout.UniformDescription uniform : layout.uniforms()) {
                if (uniform.name().equals("Sampler2") || uniform.name().equals("u_LightTex")) {
                    samplesLightmap = true;
                }
            }
        }
        if (samplesLightmap) {
            this.withBindGroupLayout(LightVolume.LAYOUT);
            this.withShaderDefine("POLYCHROME");
        }
    }
}
