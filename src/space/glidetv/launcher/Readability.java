package space.glidetv.launcher;

final class Readability {
    private Readability() {}

    static float sceneLuminance(int top, int bottom) {
        return clamp(relativeLuminance(top) * .62f + relativeLuminance(bottom) * .38f);
    }

    static float videoLuminance(boolean daytime, int weatherCode) {
        if (!daytime) return .07f;
        if (weatherCode >= 51) return .38f;
        if (weatherCode >= 45) return .46f;
        if (weatherCode >= 3) return .55f;
        return .74f;
    }

    static int scrimAlpha(float luminance) {
        return Math.round(30 + clamp(luminance) * 118);
    }

    private static float relativeLuminance(int color) {
        float red = linear((color >> 16) & 255);
        float green = linear((color >> 8) & 255);
        float blue = linear(color & 255);
        return .2126f * red + .7152f * green + .0722f * blue;
    }

    private static float linear(int component) {
        float value = component / 255f;
        return value <= .04045f ? value / 12.92f : (float)Math.pow((value + .055f) / 1.055f, 2.4);
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
