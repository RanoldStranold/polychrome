package net.ranold.polychrome.color;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorSpecTest {

    private static ColorSpec parse(String json) {
        return ColorSpec.parse(JsonParser.parseString(json));
    }

    private static final Function<ColorSpec.Texture, Integer> NO_TEXTURES = tex -> {
        throw new ColorSpec.BadColor("no textures here");
    };

    @Test
    void everyWrittenFormIsRead() {
        assertEquals(new ColorSpec.Fixed(0xFFB060), parse("\"#FFB060\""));
        assertEquals(new ColorSpec.Fixed(0xFFB060), parse("\"ffb060\""));
        assertEquals(new ColorSpec.Fixed(0x123456), parse("{\"color\": \"#123456\"}"));
        assertEquals(new ColorSpec.SetRef("fire"), parse("\"@fire\""));
        assertEquals(new ColorSpec.SetRef("fire"), parse("{\"set\": \"fire\"}"));
        ColorSpec.Texture whole = assertInstanceOf(ColorSpec.Texture.class, parse("{\"texture\": \"minecraft:block/sea_lantern\"}"));
        assertEquals("minecraft:block/sea_lantern", whole.id());
        assertEquals(null, whole.region());
        ColorSpec.Texture part = assertInstanceOf(ColorSpec.Texture.class, parse("{\"texture\": \"mod:block/torch\", \"region\": [7, 6, 2, 2]}"));
        assertEquals(7, part.region()[0]);
        assertEquals(2, part.region()[3]);
    }

    @Test
    void mistakesAreReportedNotGuessed() {
        assertThrows(ColorSpec.BadColor.class, () -> parse("\"#FFF\""));
        assertThrows(ColorSpec.BadColor.class, () -> parse("\"#GGGGGG\""));
        assertThrows(ColorSpec.BadColor.class, () -> parse("\"@\""));
        assertThrows(ColorSpec.BadColor.class, () -> parse("{\"texture\": \"a:b\", \"region\": [0, 0, 2]}"));
        assertThrows(ColorSpec.BadColor.class, () -> parse("{\"texture\": \"a:b\", \"region\": [0, 0, 0, 2]}"));
        assertThrows(ColorSpec.BadColor.class, () -> parse("{\"brightness\": 3}"));
        assertThrows(ColorSpec.BadColor.class, () -> parse("12"));
    }

    @Test
    void averageIsWeightedByOpacityAndIgnoresClearPixels() {
        int red = 0xFFFF0000, clearBlue = 0x000000FF, halfGreen = 0x8000FF00;
        int[] image = {red, clearBlue, halfGreen, red};
        int rgb = ColorSpec.averageColor(image, 2, 2, null);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        assertEquals(255, r, "red is the brightest channel, scaled to 255");
        assertEquals(0, b, "a fully transparent pixel counts for nothing");
        assertTrue(g > 50 && g < 80, "half-opaque green counts for about a quarter of two red pixels: " + g);
    }

    @Test
    void aRegionPicksPartOfTheImage() {
        // 4x4 gray with a 2x2 orange flame at 1,1. region should only see the flame
        int[] image = new int[16];
        java.util.Arrays.fill(image, 0xFF808080);
        for (int y = 1; y <= 2; y++) {
            for (int x = 1; x <= 2; x++) {
                image[y * 4 + x] = 0xFFFF8000;
            }
        }
        assertEquals(0xFF8000, ColorSpec.averageColor(image, 4, 4, new int[] {1, 1, 2, 2}));
        int whole = ColorSpec.averageColor(image, 4, 4, null);
        assertTrue((whole & 0xFF) > 0, "the whole block is paler: gray mixes in");
        assertThrows(ColorSpec.BadColor.class, () -> ColorSpec.averageColor(image, 4, 4, new int[] {3, 3, 2, 2}));
        assertThrows(ColorSpec.BadColor.class, () -> ColorSpec.averageColor(new int[4], 2, 2, null), "nothing visible");
    }

    @Test
    void onlyTheHueMatters() {
        assertEquals(ColorSpec.averageColor(new int[] {0xFF402010}, 1, 1, null), ColorSpec.averageColor(new int[] {0xFFFF8040}, 1, 1, null));
    }

    @Test
    void setsFollowOtherSetsAndLoopsAreCaught() {
        Map<String, ColorSpec> sets = new HashMap<>();
        sets.put("fire", new ColorSpec.Fixed(0xFFB060));
        sets.put("campfire", new ColorSpec.SetRef("fire"));
        sets.put("bonfire", new ColorSpec.SetRef("campfire"));
        sets.put("a", new ColorSpec.SetRef("b"));
        sets.put("b", new ColorSpec.SetRef("a"));
        sets.put("lost", new ColorSpec.SetRef("nowhere"));
        Map<String, String> problems = new HashMap<>();
        Map<String, Integer> resolved = ColorSpec.resolveSets(sets, NO_TEXTURES, problems);
        assertEquals(0xFFB060, resolved.get("bonfire"));
        assertTrue(problems.containsKey("a") && problems.containsKey("b"), "a loop: " + problems);
        assertTrue(problems.get("lost").contains("nowhere"));
        assertEquals(0xFFB060, ColorSpec.resolve(new ColorSpec.SetRef("campfire"), sets, NO_TEXTURES, new HashSet<>()));
    }

    @Test
    void aSetCanBeATexture() {
        Map<String, ColorSpec> sets = Map.of("glow", new ColorSpec.Texture("mod:block/glow", null));
        int rgb = ColorSpec.resolve(new ColorSpec.SetRef("glow"), sets, tex -> 0x00FF00, new HashSet<>());
        assertEquals(0x00FF00, rgb);
    }
}
