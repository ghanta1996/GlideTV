package space.glidetv.launcher;

/** Small, dependency-free palette for launcher icons. Ignores transparent/neutral backgrounds. */
final class IconPalette {
    static final int NEUTRAL = 0xffb8c8d8;

    static int fromPixels(int[] pixels) {
        if (pixels == null) return NEUTRAL;
        int[] weights = new int[512], red = new int[512], green = new int[512], blue = new int[512];
        for (int pixel : pixels) {
            int alpha = pixel >>> 24;
            if (alpha < 128) continue;
            int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            if (max < 48 || max - min < 24 || (max - min) / (float) max < .20f) continue;
            int bucket = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
            // Saturated logo strokes beat muted backgrounds, without giving a lone pixel priority.
            int weight = 1 + (max - min) * 3 / 255;
            weights[bucket] += weight;
            red[bucket] += r * weight; green[bucket] += g * weight; blue[bucket] += b * weight;
        }
        int best = 0;
        for (int i = 1; i < weights.length; i++) if (weights[i] > weights[best]) best = i;
        if (weights[best] == 0) return NEUTRAL;
        int r = red[best] / weights[best], g = green[best] / weights[best], b = blue[best] / weights[best];
        // Lift dark brand colors enough to remain visible on a TV, preserving their hue.
        float lift = Math.max(1f, 190f / Math.max(r, Math.max(g, b)));
        return 0xff000000 | (Math.min(255, Math.round(r * lift)) << 16)
                | (Math.min(255, Math.round(g * lift)) << 8) | Math.min(255, Math.round(b * lift));
    }

    private IconPalette() {}
}
