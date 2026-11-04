package net.ranold.polychrome.field;

// LambDynamicLights light (held torch, burning mob). never reaches the light engine so HueField
// mixes it in when packing. luminance 1..15, rgb 0xRRGGBB
public record DynamicLight(double x, double y, double z, int luminance, int rgb) {

    // LDL MAX_RADIUS
    public static final double REACH = 7.75;

    // same as LDL SpatialLookupEntry.lightAtPos: linear falloff to block center, 0 at REACH, through walls
    public double levelAt(int bx, int by, int bz) {
        double dx = bx - this.x + 0.5, dy = by - this.y + 0.5, dz = bz - this.z + 0.5;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > REACH * REACH) {
            return 0.0;
        }
        return Math.max(0.0, this.luminance - Math.sqrt(distanceSquared) / REACH * 15.0);
    }

    public boolean reaches(int sx, int sy, int sz) {
        return near(this.x, sx) && near(this.y, sy) && near(this.z, sz);
    }

    private static boolean near(double coordinate, int section) {
        // block centers within REACH: ceil(c - REACH - 0.5) .. floor(c + REACH - 0.5)
        int min = (int) Math.ceil(coordinate - REACH - 0.5) >> 4;
        int max = (int) Math.floor(coordinate + REACH - 0.5) >> 4;
        return section >= min && section <= max;
    }
}
