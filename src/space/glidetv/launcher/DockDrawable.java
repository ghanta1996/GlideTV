package space.glidetv.launcher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Translucent dock/card with a cheap gradient glow; no blur buffers or continuous rendering. */
final class DockDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final boolean card;
    private int accent = IconPalette.NEUTRAL;
    private boolean focused;
    private Shader fill, edge, glow;

    DockDrawable(float radius, boolean card) { this.radius = radius; this.card = card; }

    void setAccent(int color, boolean focus) {
        if (accent == color && focused == focus) return;
        accent = color; focused = focus; rebuild(); invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) { rebuild(); }

    private int tint(int alpha) { return (alpha << 24) | (accent & 0xffffff); }

    private void rebuild() {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float inset = card ? radius * .22f : radius * .12f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        fill = new LinearGradient(0, rect.top, 0, rect.bottom,
                card ? new int[]{0xf00a0e16, 0xe5080b12} : new int[]{0x9c202a36, 0xb309101b}, null, Shader.TileMode.CLAMP);
        edge = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{focused ? tint(225) : 0x75e6efff, tint(focused ? 130 : 45), 0x24ffffff},
                new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
        glow = new RadialGradient(rect.centerX(), rect.bottom, Math.max(1, rect.width() * .65f),
                new int[]{tint(card ? 80 : 68), tint(0)}, null, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        if (fill == null) return;
        paint.setStyle(Paint.Style.FILL); paint.setShader(fill);
        canvas.drawRoundRect(rect, radius, radius, paint);
        if (!card || focused) {
            paint.setShader(glow); canvas.drawRoundRect(rect, radius, radius, paint);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(focused ? 2.5f : 1.2f);
        paint.setShader(edge); canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
