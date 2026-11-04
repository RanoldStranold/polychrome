package net.ranold.polychrome.color;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

// one color as a pack writes it: "#RRGGBB", "@set" or {"texture": ..., "region": [x, y, w, h]}.
// only hue matters. no minecraft classes so it can be unit tested
public sealed interface ColorSpec {

    record Fixed(int rgb) implements ColorSpec {
    }

    record SetRef(String name) implements ColorSpec {
    }

    // null = whole image, else {x, y, w, h}
    record Texture(String id, int @Nullable [] region) implements ColorSpec {
    }

    final class BadColor extends RuntimeException {
        public BadColor(String message) {
            super(message);
        }
    }

    static ColorSpec parse(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String text = element.getAsString().trim();
            if (text.startsWith("@")) {
                return new SetRef(setName(text.substring(1)));
            }
            return new Fixed(parseHex(text));
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("color")) {
                return new Fixed(parseHex(object.get("color").getAsString()));
            }
            if (object.has("set")) {
                return new SetRef(setName(object.get("set").getAsString()));
            }
            if (object.has("texture")) {
                int[] region = null;
                if (object.has("region")) {
                    JsonArray array = object.getAsJsonArray("region");
                    if (array.size() != 4) {
                        throw new BadColor("region must be [x, y, width, height]");
                    }
                    region = new int[4];
                    for (int i = 0; i < 4; i++) {
                        region[i] = array.get(i).getAsInt();
                    }
                    if (region[0] < 0 || region[1] < 0 || region[2] <= 0 || region[3] <= 0) {
                        throw new BadColor("region must have x, y >= 0 and width, height > 0");
                    }
                }
                return new Texture(object.get("texture").getAsString().trim(), region);
            }
        }
        throw new BadColor("expected \"#RRGGBB\", \"@set\" or an object with \"color\", \"set\" or \"texture\"");
    }

    private static String setName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new BadColor("empty set name");
        }
        return trimmed;
    }

    static int parseHex(String text) {
        String hex = text.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6) {
            throw new BadColor("colors are written #RRGGBB, not '" + text + "'");
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new BadColor("colors are written #RRGGBB, not '" + text + "'");
        }
    }

    // average of visible pixels (0xAARRGGBB), weighted by alpha, in region or whole image.
    // animated textures average all frames. scaled so brightest channel = 255
    static int averageColor(int[] argb, int width, int height, int @Nullable [] region) {
        int x0 = 0, y0 = 0, w = width, h = height;
        if (region != null) {
            x0 = region[0];
            y0 = region[1];
            w = region[2];
            h = region[3];
            if (x0 + w > width || y0 + h > height) {
                throw new BadColor("region " + x0 + "," + y0 + " " + w + "x" + h + " is outside the " + width + "x" + height + " image");
            }
        }
        double r = 0, g = 0, b = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int pixel = argb[y * width + x];
                double a = ((pixel >>> 24) & 0xFF) / 255.0;
                r += ((pixel >> 16) & 0xFF) * a;
                g += ((pixel >> 8) & 0xFF) * a;
                b += (pixel & 0xFF) * a;
            }
        }
        double max = Math.max(r, Math.max(g, b));
        if (max <= 0) {
            throw new BadColor("no visible, non-black pixels to take a color from");
        }
        return (int) Math.round(r / max * 255) << 16 | (int) Math.round(g / max * 255) << 8 | (int) Math.round(b / max * 255);
    }

    // sets can point at other sets. loops / dead names make those sets unusable (reported in problems by set name).
    // texture can throw BadColor
    static Map<String, Integer> resolveSets(Map<String, ColorSpec> sets, Function<Texture, Integer> texture, Map<String, String> problems) {
        Map<String, Integer> resolved = new HashMap<>();
        for (String name : sets.keySet()) {
            try {
                resolved.put(name, resolve(new SetRef(name), sets, texture, new HashSet<>()));
            } catch (BadColor e) {
                problems.put(name, e.getMessage());
            }
        }
        return resolved;
    }

    // follows set references
    static int resolve(ColorSpec spec, Map<String, ColorSpec> sets, Function<Texture, Integer> texture, Set<String> visiting) {
        return switch (spec) {
            case Fixed fixed -> fixed.rgb();
            case Texture tex -> texture.apply(tex);
            case SetRef ref -> {
                ColorSpec target = sets.get(ref.name());
                if (target == null) {
                    throw new BadColor("no color set named '" + ref.name() + "'");
                }
                if (!visiting.add(ref.name())) {
                    throw new BadColor("color set '" + ref.name() + "' refers back to itself");
                }
                int rgb = resolve(target, sets, texture, visiting);
                visiting.remove(ref.name());
                yield rgb;
            }
        };
    }
}
