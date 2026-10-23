package dev.roseglass;

/** Independent vanilla and visual square chunk radii. No Minecraft dependencies. */
public final class Visibility {

    private Visibility() {}

    public static int chunk(double block) {
        return ((int) Math.floor(block)) >> 4;
    }

    public static long distance(int playerX, int playerZ, int targetX, int targetZ) {
        return Math.max(Math.abs((long) playerX - targetX), Math.abs((long) playerZ - targetZ));
    }

    public static boolean show(long distance, boolean retained, boolean live, int vanillaRadius, int visualRadius) {
        return retained && !live && distance > vanillaRadius && distance <= visualRadius;
    }

    public static boolean insideNative(long distance, int radius) {
        return distance >= 0 && distance <= radius;
    }

    public static boolean border(long distance, boolean enabled, int vanillaRadius, int visualRadius) {
        return enabled && distance <= visualRadius && distance >= Math.max(0L, (long) vanillaRadius - 2)
                && insideNative(distance, vanillaRadius);
    }

    public static boolean keepMesh(long distance, boolean ready, int visualRadius) {
        return ready && distance >= 0 && distance <= visualRadius;
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static float farPlane(int visualRadius) {
        // Double arithmetic before multiplying: /distance accepts any positive Java int.
        return (float) Math.max(2048.0, (visualRadius + 2.0) * 16.0 * Math.sqrt(2.0) + 512.0);
    }

    public static float fogEnd(int visualRadius) { return (float) (visualRadius * 16.0); }
    public static float fogStart(int visualRadius) { return fogEnd(visualRadius) * 0.75F; }
}
