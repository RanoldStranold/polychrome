package net.ranold.polychrome.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.ranold.polychrome.Polychrome;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;

// nvidium draws terrain with its own mesh shaders. its barycentric mode lights per pixel with no world position,
// so every terrain shader is put in its per-vertex mode (an nvidium option) and the vertex light gets cl_tint
public final class NvidiumPatcher {

    private static final String BARYCENTRIC = "#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC";
    private static final String SOLID_LIGHT = "OUT[id].v_colour = computeMultiplier(V);";
    private static final String SOLID_BEFORE = "void putVertex(";
    private static final String TRANSLUCENT_LIGHT = "OUT[outId].v_colour = tint.rgb;";
    private static final String TRANSLUCENT_BEFORE = "void emitVertex(";

    // last load of each terrain mesh shader matched, for the game tests
    public static volatile boolean solidColored, translucentColored;

    private NvidiumPatcher() {
    }

    // source is already flattened (imports resolved, defines in)
    public static String patch(Identifier id, String source) {
        if (!id.getNamespace().equals("nvidium") || !id.getPath().startsWith("terrain/")) {
            return source;
        }
        // same in every terrain shader, mesh and fragment have to agree
        String patched = source.replace(BARYCENTRIC + "\n", "\n");
        switch (id.getPath()) {
            case "terrain/mesh.glsl" -> {
                String done = inject(id, patched, SOLID_BEFORE, SOLID_LIGHT,
                        "OUT[id].v_colour = cl_nvidiumLight(V, decodeVertexPosition(V) + origin + subchunkOffset.xyz);");
                solidColored = !done.equals(patched);
                patched = done;
            }
            case "terrain/translucent/mesh.glsl" -> {
                String done = inject(id, patched, TRANSLUCENT_BEFORE, TRANSLUCENT_LIGHT,
                        "OUT[outId].v_colour = cl_nvidiumLight(V, pos + subchunkOffset.xyz);");
                translucentColored = !done.equals(patched);
                patched = done;
            }
            default -> {
            }
        }
        return patched;
    }

    private static String inject(Identifier id, String source, String before, String light, String replacement) {
        String include = include();
        if (include == null || count(source, before) != 1 || count(source, light) != 1) {
            Polychrome.LOGGER.warn("Not coloring Nvidium terrain: shader {} no longer matches", id);
            return source;
        }
        String helpers = "#define POLYCHROME\n#define CL_RAW_GL\n" + include + """

                vec4 cl_nvidiumLightSample(Vertex V, vec3 cameraRelative) {
                    vec2 lightUv = decodeLightUV(V);
                    return cl_tint(tex_light, lightUv, sampleLight(lightUv), cameraRelative);
                }

                vec3 cl_nvidiumLight(Vertex V, vec3 cameraRelative) {
                    vec4 tint = decodeVertexColour(V);
                    tint *= cl_nvidiumLightSample(V, cameraRelative);
                    tint *= tint.w;
                    return tint.xyz;
                }

                """;
        String patched = source.replace(before, helpers + before).replace(light, replacement);
        Polychrome.LOGGER.info("Colored Nvidium terrain shader {}", id.getPath());
        return patched;
    }

    private static @Nullable String include() {
        Identifier file = Identifier.fromNamespaceAndPath("polychrome", "shaders/include/polychrome.glsl");
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(file).orElse(null);
        if (resource == null) {
            return null;
        }
        try (Reader reader = resource.openAsReader()) {
            return org.apache.commons.io.IOUtils.toString(reader);
        } catch (IOException e) {
            return null;
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
