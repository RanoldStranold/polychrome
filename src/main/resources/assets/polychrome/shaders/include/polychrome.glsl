#ifndef POLYCHROME_GLSL
#define POLYCHROME_GLSL

// recolors the block light part of a lightmap sample with the hue around a position, brightness stays the lightmap's.
// must match gpu/LightVolume (layout) and field/Rgb9e5 (encoding)

#ifdef POLYCHROME

layout(std140) uniform CL_Header {
    ivec4 CL_Camera;  // xyz camera block, w 1 if this draw is the world
    vec4 CL_Params;  // xyz camera pos - block pos, w saturation
};

uniform isamplerBuffer CL_Pages;
uniform usamplerBuffer CL_Cells;

// cells are hue * brightness. below this fade back to white so faint edge cells dont flicker
const float CL_PRESENCE = 1.0e-3;

// one cell = 2x2x2 blocks
vec3 cl_cell(ivec3 coarse, ivec3 cameraSection) {
    ivec3 section = coarse >> 3;
    ivec3 d = abs(section - cameraSection);
    if (d.x > 15 || d.y > 15 || d.z > 15) {
        return vec3(0.0);
    }
    int page = (section.x & 31) | ((section.z & 31) << 5) | ((section.y & 31) << 10);
    int slot = texelFetch(CL_Pages, page).r;
    if (slot < 0) {
        return vec3(0.0);
    }
    ivec3 local = coarse & 7;
    uint packed = texelFetch(CL_Cells, (slot << 9) | (local.y << 6) | (local.z << 3) | local.x).r;
    float scale = exp2(float(packed >> 27u) - 24.0);
    return vec3(float(packed & 511u), float((packed >> 9u) & 511u), float((packed >> 18u) & 511u)) * scale;
}

// trilinear over the 8 cells around it, unlit ones add nothing. camera relative for precision
vec3 cl_hue(vec3 cameraRelative) {
    ivec3 cameraBlock = CL_Camera.xyz;
    ivec3 cameraHalf = cameraBlock >> 1;
    vec3 s = (vec3(cameraBlock & 1) + cameraRelative + CL_Params.xyz) * 0.5 - 0.5;
    vec3 base = floor(s);
    vec3 f = s - base;
    ivec3 cell = ivec3(base) + cameraHalf;
    ivec3 cameraSection = cameraBlock >> 4;

    vec3 sum = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        ivec3 o = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 w = mix(vec3(1.0) - f, f, vec3(o));
        sum += (w.x * w.y * w.z) * cl_cell(cell + o, cameraSection);
    }

    float peak = max(sum.r, max(sum.g, sum.b));
    if (peak <= 0.0) {
        return vec3(1.0);
    }
    return mix(vec3(1.0), sum / peak, clamp(peak / CL_PRESENCE, 0.0, 1.0));
}

// lit = lightmap sample. only what block light adds over sky light gets recolored, never brighter than lit
vec4 cl_tint(sampler2D lightMap, vec2 lightCoord, vec4 lit, vec3 cameraRelative) {
    if (CL_Camera.w == 0) {
        return lit;
    }
    // sources own blocks have no color on the GPU (HueField.pack) so lava doesnt tint itself
    vec3 tint = mix(vec3(1.0), cl_hue(cameraRelative), CL_Params.w);
    vec3 skyOnly = min(texture(lightMap, vec2(0.5 / 16.0, lightCoord.y)).rgb, lit.rgb);
    return vec4(skyOnly + (lit.rgb - skyOnly) * tint, lit.a);
}

#endif

#endif
