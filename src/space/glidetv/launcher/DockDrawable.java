package space.glidetv.launcher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Translucent dock/card with a cheap gradient glow; no blur buffers or continuous rendering. */
final class DockDrawable extends Drawable {
    private static final int LIGHT = 0xffdbe8ff;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final boolean card;
    private boolean focused;
    private Shader fill, edge, glow;

    DockDrawable(float radius, boolean card) { this.radius = radius; this.card = card; }

    void setFocused(boolean focus) {
        if (focused == focus) return;
        focused = focus; rebuild(); invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) { rebuild(); }

    private static int light(int alpha) { return (alpha << 24) | (LIGHT & 0xffffff); }

    private void rebuild() {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float inset = card ? radius * .22f : 1.5f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        fill = new LinearGradient(0, rect.top, 0, rect.bottom,
                card ? new int[]{0xf00a0e16, 0xe5080b12} : new int[]{0xbc2a2a2e, 0xc817171a}, null, Shader.TileMode.CLAMP);
        edge = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                card ? new int[]{focused ? 0xe1f3fbff : 0x75e6efff, light(focused ? 120 : 40), 0x24ffffff}
                     : new int[]{0x52ffffff, 0x2effffff, 0x1cffffff},
                new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
        glow = new RadialGradient(rect.centerX(), rect.bottom, Math.max(1, rect.width() * .65f),
                new int[]{light(card ? 70 : 54), light(0)}, null, Shader.TileMode.CLAMP);
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
