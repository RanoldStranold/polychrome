package net.ranold.polychrome.compat;

import net.minecraft.resources.Identifier;
import net.ranold.polychrome.Polychrome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.ranold.polychrome.far.FarColors;
import net.ranold.polychrome.far.FarIngest;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// colored light on voxy LODs. patches lod/quad_util.glsl as voxy loads it: adds far_colors.glsl,
// records the block in front of each quad in setupQuad, runs getLighting() through cl_farTint.
// if voxy changed any of those spots nothing gets patched and it warns. iris path left alone.
public final class VoxyCompat {

    private static final String QUAD_UTIL = "lod/quad_util.glsl";
    private static final String IMPORT_ANCHOR = "#import <voxy:lod/lighting.glsl>";
    private static final String SETUP_ANCHOR = "    if (generateAttributes) {";
    private static final String LIGHT_LINE = "    vec4 tinting = getLighting(lighting);";

    private static @Nullable String snippet;
    private static boolean patched;

    private VoxyCompat() {
    }

    // patched if it's voxy's quad code
    public static String patchShader(Identifier id, String source) {
        if (source == null || !id.getNamespace().equals("voxy") || !id.getPath().equals(QUAD_UTIL) || Polychrome.far() == null) {
            return source;
        }
        if (count(source, IMPORT_ANCHOR) != 1 || count(source, SETUP_ANCHOR) != 1 || count(source, LIGHT_LINE) != 1) {
            Polychrome.LOGGER.warn("Voxy's {} changed; distant terrain keeps white light", QUAD_UTIL);
            return source;
        }
        String code = snippet();
        if (code == null) {
            return source;
        }
        Polychrome.LOGGER.info("Colored Voxy's distant terrain shader");
        patched = true;
        return source
                .replace(IMPORT_ANCHOR, IMPORT_ANCHOR + "\n" + code)
                .replace(SETUP_ANCHOR, """
                            #ifdef LIGHTING_SAMPLER_BINDING
                            cl_quadBlock = cl_faceBlock(getLoDPosition(sPos), lodLevel, extractPos(rawQuad), face);
                            #endif
                        """ + SETUP_ANCHOR)
                .replace(LIGHT_LINE, "    vec4 tinting = cl_farTint(getLighting(lighting), lighting);");
    }

    // diagnostics
    public static boolean isShaderPatched() {
        return patched;
    }

    // chunk from the integrated server going to voxy. if it's in the player's dimension, color it for LODs too
    public static void chunkGenerated(LevelChunk chunk) {
        FarIngest ingest = Polychrome.farIngest();
        ClientLevel level = Minecraft.getInstance().level;
        if (ingest != null && level != null && chunk.getLevel() != level && chunk.getLevel().dimension().equals(level.dimension())) {
            ingest.chunkGenerated(chunk);
        }
    }

    // right after voxy binds its buffers
    public static void beforeTerrainDraw() {
        FarColors far = Polychrome.far();
        if (far != null) {
            far.bind();
        }
    }

    private static @Nullable String snippet() {
        if (snippet == null) {
            try (InputStream in = VoxyCompat.class.getResourceAsStream("/assets/polychrome/shaders/voxy/far_colors.glsl")) {
                if (in == null) {
                    throw new IOException("missing");
                }
                snippet = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Polychrome.LOGGER.error("Could not read the distant terrain shader code", e);
                return null;
            }
        }
        return snippet;
    }

    private static int count(String source, String part) {
        int n = 0;
        for (int i = source.indexOf(part); i >= 0; i = source.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }
}
