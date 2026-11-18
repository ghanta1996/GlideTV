package space.glidetv.launcher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Liquid-glass bubble: optional glass body, a specular sheen along the top and a lit rim. */
final class BubbleDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(), gloss = new RectF();
    private final float radius;
    private final boolean filled;
    private boolean focused;
    private float glossRadius;
    private Shader body, sheen, rim;

    BubbleDrawable(float radius, boolean filled) { this.radius = radius; this.filled = filled; }

    void setFocused(boolean focus) {
        if (focused == focus) return;
        focused = focus; rebuild(); invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) { rebuild(); }

    private void rebuild() {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        rect.set(b.left + 2, b.top + 2, b.right - 2, b.bottom - 2);
        body = new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{focused ? 0x55a7c3dd : 0x26e8f4ff, focused ? 0x3a3d566b : 0x14384b68}, null, Shader.TileMode.CLAMP);
        // A short highlight across the crown is what reads as light sitting on glass.
        float inset = Math.min(rect.width(), rect.height()) * .11f;
        gloss.set(rect.left + inset, rect.top + inset * .45f, rect.right - inset, rect.top + rect.height() * .42f);
        glossRadius = Math.min(gloss.height() * .5f, radius);
        sheen = new LinearGradient(0, gloss.top, 0, gloss.bottom,
                new int[]{focused ? 0x5cffffff : 0x30ffffff, 0x00ffffff}, null, Shader.TileMode.CLAMP);
        rim = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{focused ? 0xf2eef8ff : 0x7ce9f3ff, 0x1affffff, focused ? 0xbfc3dcff : 0x40b4c5e5},
                new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        if (rim == null) return;
        paint.setStyle(Paint.Style.FILL);
        if (filled) { paint.setShader(body); canvas.drawRoundRect(rect, radius, radius, paint); }
        if (gloss.height() > 0) { paint.setShader(sheen); canvas.drawRoundRect(gloss, glossRadius, glossRadius, paint); }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(focused ? 2.5f : 1.2f);
        paint.setShader(rim); canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
