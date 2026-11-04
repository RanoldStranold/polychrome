package net.ranold.polychrome.shader;

import net.minecraft.resources.Identifier;
import net.ranold.polychrome.Polychrome;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// text patches on shaders as they load. each anchor line has to exist exactly once, if one is
// missing (pack replaced the shader, renderer changed it) that shader is left alone and just has no color.
// everything added is behind #ifdef POLYCHROME so without the define it's the original
public final class ShaderPatcher {

    private static final String VANILLA_INCLUDE = "#include <minecraft:sample_lightmap.glsl>";
    private static final Pattern VANILLA_CALL = Pattern.compile("sample_lightmap\\(\\s*Sampler2\\s*,\\s*UV2\\s*\\)");
    private static final Pattern DECLARES_POS = Pattern.compile("\\bvec3\\s+pos\\s*=");

    private static final Identifier SODIUM_TERRAIN = Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");
    private static final String SODIUM_INCLUDE = "#include <sodium:chunk_vertex.glsl>";
    private static final String SODIUM_LIGHT = "v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);";

    private ShaderPatcher() {
    }

    // only vertex shaders sample the lightmap
    public static String patch(Identifier id, boolean vertex, String source) {
        if (!vertex) {
            return source;
        }
        if (id.equals(SODIUM_TERRAIN)) {
            return patchSodiumTerrain(id, source);
        }
        if (source.contains(VANILLA_INCLUDE) && VANILLA_CALL.matcher(source).find()) {
            return patchVanillaStyle(id, source);
        }
        return source;
    }

    // vanilla style sample_lightmap(Sampler2, UV2). uses the shader's camera relative pos if it has one, else Position
    private static String patchVanillaStyle(Identifier id, String source) {
        if (count(source, VANILLA_INCLUDE) != 1) {
            Polychrome.LOGGER.warn("Not coloring shader {}: lightmap include found more than once", id);
            return source;
        }
        Matcher call = VANILLA_CALL.matcher(source);
        String position = DECLARES_POS.matcher(source).find() ? "pos" : "Position";
        String patched = call.replaceAll("cl_sample_lightmap(Sampler2, UV2, " + position + ")");
        patched = patched.replace(VANILLA_INCLUDE, VANILLA_INCLUDE + "\n#include <polychrome:polychrome_vanilla.glsl>");
        Polychrome.LOGGER.debug("Colored shader {} (position: {})", id, position);
        return patched;
    }

    private static String patchSodiumTerrain(Identifier id, String source) {
        if (count(source, SODIUM_INCLUDE) != 1 || count(source, SODIUM_LIGHT) != 1) {
            Polychrome.LOGGER.warn("Not coloring Sodium terrain: shader {} no longer matches", id);
            return source;
        }
        String patched = source.replace(SODIUM_INCLUDE, SODIUM_INCLUDE + "\n#include <polychrome:polychrome.glsl>");
        patched = patched.replace(SODIUM_LIGHT, """
                v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
                #ifdef POLYCHROME
                    v_Color = _vert_color * cl_tint(u_LightTex, _vert_tex_light_coord, texture(u_LightTex, _vert_tex_light_coord), position);
                #endif""");
        Polychrome.LOGGER.info("Colored Sodium terrain shader");
        return patched;
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
