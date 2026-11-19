package space.glidetv.launcher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Layered dock/card glass with static sheen and shadow; no blur buffers or continuous rendering. */
final class DockDrawable extends Drawable {
    private static final int LIGHT = 0xffdbe8ff;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final boolean card;
    private boolean focused;
    private Shader fill, edge, glow, sheen;

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
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset - 2);
        fill = new LinearGradient(0, rect.top, 0, rect.bottom,
                card ? new int[]{0xd80d1420, 0xc8080c14} : new int[]{0xa83b3f46, 0xb8171a20},
                null, Shader.TileMode.CLAMP);
        edge = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                card ? new int[]{focused ? 0xe1f3fbff : 0x75e6efff, light(focused ? 120 : 40), 0x24ffffff}
                     : new int[]{0x52ffffff, 0x2effffff, 0x1cffffff},
                new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
        glow = new RadialGradient(rect.centerX(), rect.bottom, Math.max(1, rect.width() * .65f),
                new int[]{light(card ? 70 : 54), light(0)}, null, Shader.TileMode.CLAMP);
        sheen = new LinearGradient(0, rect.top, 0, rect.top + rect.height() * .48f,
                new int[]{card ? 0x3dffffff : 0x30ffffff, 0x00ffffff}, null, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        if (fill == null) return;
        paint.setShader(null); paint.setStyle(Paint.Style.FILL);
        paint.setColor(card ? 0x4d020711 : 0x58020710);
        canvas.save(); canvas.translate(0, card ? 3f : 4f);
        canvas.drawRoundRect(rect, radius, radius, paint); canvas.restore();
        paint.setStyle(Paint.Style.FILL); paint.setShader(fill);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(sheen); canvas.drawRoundRect(rect, radius, radius, paint);
        if (!card || focused) {
            paint.setShader(glow); canvas.drawRoundRect(rect, radius, radius, paint);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(focused ? 2.5f : 1.2f);
        paint.setShader(edge); canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
    }

    @Override public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius); outline.setAlpha(card ? .2f : .26f);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
