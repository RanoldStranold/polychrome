#ifndef POLYCHROME_VANILLA_GLSL
#define POLYCHROME_VANILLA_GLSL

// drop in for vanilla sample_lightmap() plus camera relative position. without POLYCHROME it's vanilla

#include <polychrome:polychrome.glsl>

vec4 cl_sample_lightmap(sampler2D lightMap, ivec2 uv, vec3 cameraRelative) {
    vec4 lit = sample_lightmap(lightMap, uv);
#ifdef POLYCHROME
    vec2 coord = clamp((uv / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    lit = cl_tint(lightMap, coord, lit, cameraRelative);
#endif
    return lit;
}

#endif
