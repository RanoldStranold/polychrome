package net.ranold.polychrome.mixin;

import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.ranold.polychrome.shader.ShaderPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// patch shaders as they're read, any namespace, so pack replacements still get patched if anchors match
@Mixin(ShaderManager.class)
public abstract class MixinShaderManager {

    @WrapOperation(
            method = "loadShader",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/Resource;readAllAsString()Ljava/lang/String;")
    )
    private static String polychrome$patchSource(Resource resource, Operation<String> original,
                                                      Identifier location, Resource sameResource, ShaderType type,
                                                      ImmutableMap.Builder<?, String> output) {
        String source = original.call(resource);
        Identifier id = type.idConverter().fileToId(location);
        return ShaderPatcher.patch(id, type == ShaderType.VERTEX, source);
    }
}
