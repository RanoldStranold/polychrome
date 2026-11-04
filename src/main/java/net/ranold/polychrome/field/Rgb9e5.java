package net.ranold.polychrome.field;

// GL_RGB9_E5: 9 bit mantissa per channel + shared 5 bit exponent. keeps channel ratios exact at any brightness.
// r | g << 9 | b << 18 | exp << 27, value = mantissa * 2^(exp - 24). must match polychrome.glsl
public final class Rgb9e5 {

    private static final int MANTISSA_BITS = 9;
    private static final int EXPONENT_BIAS = 15;
    private static final int MAX_EXPONENT = 31;
    private static final float MAX_VALUE = (float) ((511.0 / 512.0) * Math.pow(2, MAX_EXPONENT - EXPONENT_BIAS));

    private Rgb9e5() {
    }

    // 0 if every channel is under floor
    public static int pack(float r, float g, float b, float floor) {
        r = clamp(r);
        g = clamp(g);
        b = clamp(b);
        float max = Math.max(r, Math.max(g, b));
        if (max < floor || max <= 0f) {
            return 0;
        }
        int exponent = Math.max(-EXPONENT_BIAS - 1, Math.getExponent(max)) + 1 + EXPONENT_BIAS;
        double scale = Math.pow(2, exponent - EXPONENT_BIAS - MANTISSA_BITS);
        if ((int) Math.floor(max / scale + 0.5) == 1 << MANTISSA_BITS) {
            exponent++;
            scale *= 2;
        }
        if (exponent > MAX_EXPONENT) {
            exponent = MAX_EXPONENT;
            scale = Math.pow(2, exponent - EXPONENT_BIAS - MANTISSA_BITS);
        }
        int rm = Math.min(511, (int) Math.floor(r / scale + 0.5));
        int gm = Math.min(511, (int) Math.floor(g / scale + 0.5));
        int bm = Math.min(511, (int) Math.floor(b / scale + 0.5));
        if ((rm | gm | bm) == 0) {
            return 0;
        }
        return rm | gm << 9 | bm << 18 | exponent << 27;
    }

    public static float[] unpack(int packed) {
        double scale = Math.pow(2, (packed >>> 27) - EXPONENT_BIAS - MANTISSA_BITS);
        return new float[]{
                (float) ((packed & 511) * scale),
                (float) (((packed >>> 9) & 511) * scale),
                (float) (((packed >>> 18) & 511) * scale)
        };
    }

    private static float clamp(float value) {
        return value > 0f ? Math.min(value, MAX_VALUE) : 0f;
    }
}
