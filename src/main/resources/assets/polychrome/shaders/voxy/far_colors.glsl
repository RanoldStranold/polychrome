// colored block light on voxy LODs, inserted into lod/quad_util.glsl by VoxyCompat.
// layout must match far/FarTable, encoding field/Rgb9e5
#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = 9, std430) readonly restrict buffer CL_FarColors {
    uint cl_far[];
};

// block in front of the quad's face, set in setupQuad
ivec3 cl_quadBlock = ivec3(0);

ivec3 cl_faceBlock(ivec3 lodPos, uint lodLevel, vec3 voxel, uint face) {
    int size = 1 << lodLevel;
    ivec3 start = ((lodPos << lodLevel) << 5) + ivec3(voxel) * size;
    ivec3 normal = face == 0u ? ivec3(0, -1, 0) : face == 1u ? ivec3(0, 1, 0)
            : face == 2u ? ivec3(0, 0, -1) : face == 3u ? ivec3(0, 0, 1)
            : face == 4u ? ivec3(-1, 0, 0) : ivec3(1, 0, 0);
    return start + ivec3(size / 2) + normal * size;
}

uint cl_farHash(uint lo, uint hi) {
    uint h = lo * 0x9E3779B1u ^ hi * 0x85EBCA77u;
    h ^= h >> 15;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12;
    return h;
}

vec3 cl_farDecode(uint bits) {
    float scale = exp2(float(bits >> 27u) - 24.0);
    return vec3(float(bits & 511u), float((bits >> 9u) & 511u), float((bits >> 18u) & 511u)) * scale;
}

// start of a section's 8 cells in cl_far, 0xFFFFFFFF if not in the table
uint cl_farSection(ivec3 s) {
    uint mask = cl_far[0];
    uint lo = (uint(s.y) & 0xFFFFFu) | ((uint(s.z) & 0xFFFu) << 20);
    uint hi = ((uint(s.z) >> 12) & 0x3FFu) | ((uint(s.x) & 0x3FFFFFu) << 10);
    uint i = cl_farHash(lo, hi) & mask;
    for (int probe = 0; probe < 64; probe++) {
        uint e = 4u + i * 3u;
        uint slot = cl_far[e + 2u];
        if (slot == 0xFFFFFFFFu) {
            return 0xFFFFFFFFu;
        }
        if (cl_far[e] == lo && cl_far[e + 1u] == hi) {
            return cl_far[3] + slot * 8u;
        }
        i = (i + 1u) & mask;
    }
    return 0xFFFFFFFFu;
}

// white if unknown. trilinear over the 8 nearest 8x8x8 cells so lights fade into each other instead of 8 block steps
vec3 cl_farHue(ivec3 b) {
    vec3 f = (vec3(b) + 0.5) / 8.0 - 0.5;
    ivec3 base = ivec3(floor(f));
    vec3 t = f - vec3(base);
    vec3 c = vec3(0.0);
    ivec3 cachedSection = ivec3(0x7FFFFFFF);
    uint cachedData = 0xFFFFFFFFu;
    for (int k = 0; k < 8; k++) {
        ivec3 o = ivec3(k & 1, (k >> 1) & 1, (k >> 2) & 1);
        ivec3 cell = base + o;
        ivec3 s = cell >> 1;
        if (s != cachedSection) {
            cachedSection = s;
            cachedData = cl_farSection(s);
        }
        if (cachedData != 0xFFFFFFFFu) {
            vec3 w = mix(vec3(1.0) - t, t, vec3(o));
            uint index = (uint(cell.y & 1) << 2) | (uint(cell.z & 1) << 1) | uint(cell.x & 1);
            c += (w.x * w.y * w.z) * cl_farDecode(cl_far[cachedData + index]);
        }
    }
    if (max(c.r, max(c.g, c.b)) <= 0.0) {
        // no lit cell nearby (quad coarser than 8 blocks): use the whole section
        uint data = cl_farSection(b >> 4);
        if (data != 0xFFFFFFFFu) {
            for (uint k = 0u; k < 8u; k++) {
                c += cl_farDecode(cl_far[data + k]);
            }
        }
    }
    float peak = max(c.r, max(c.g, c.b));
    return peak > 0.0 ? c / peak : vec3(1.0);
}

// only what block light adds over sky light, same as near terrain
vec4 cl_farTint(vec4 lit, uint lightId) {
    if (cl_far[2] == 0u || (lightId >> 4) == 0u) {
        return lit;
    }
    vec3 skyOnly = min(textureLod(lightSampler, getLightmapUv(lightId & 0xFu), 0).rgb, lit.rgb);
    vec3 tint = mix(vec3(1.0), cl_farHue(cl_quadBlock), uintBitsToFloat(cl_far[1]));
    return vec4(skyOnly + (lit.rgb - skyOnly) * tint, lit.a);
}

#endif
