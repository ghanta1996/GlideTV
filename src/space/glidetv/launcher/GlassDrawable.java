package space.glidetv.launcher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Layered translucent control glass with a soft inset shadow and crown highlight. */
public final class GlassDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(), gloss = new RectF();
    private final float radius;
    private final boolean focused, outlineOnly;
    private Shader fill, sheen, rim;

    public GlassDrawable(float radius, boolean focused) { this(radius, focused, false); }

    public GlassDrawable(float radius, boolean focused, boolean outlineOnly) {
        this.radius = radius;
        this.focused = focused;
        this.outlineOnly = outlineOnly;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        rect.set(bounds.left + 2, bounds.top + 2, bounds.right - 2, bounds.bottom - 3);
        float inset = Math.min(rect.width(), rect.height()) * .12f;
        gloss.set(rect.left + inset, rect.top + inset * .45f, rect.right - inset,
                rect.top + rect.height() * .46f);
        fill = new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{focused ? 0x668fb4ce : 0x36eef8ff, focused ? 0x42394f63 : 0x1834495d},
                null, Shader.TileMode.CLAMP);
        sheen = new LinearGradient(0, gloss.top, 0, gloss.bottom,
                new int[]{focused ? 0x70ffffff : 0x42ffffff, 0x00ffffff},
                null, Shader.TileMode.CLAMP);
        rim = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{focused ? 0xfff3fbff : 0xa6edf8ff, 0x24ffffff,
                        focused ? 0xb8b9e0ff : 0x56a9c5e5},
                new float[]{0, .52f, 1}, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(focused ? 0x4d030914 : 0x33030914);
        canvas.save();
        canvas.translate(0, focused ? 3f : 2f);
        canvas.drawRoundRect(rect, radius, radius, paint);
        canvas.restore();
        if (!outlineOnly) {
            paint.setShader(fill);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
        if (gloss.height() > 0) {
            paint.setShader(sheen);
            float glossRadius = Math.min(radius, gloss.height() * .5f);
            canvas.drawRoundRect(gloss, glossRadius, glossRadius, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 2.5f : 1.2f);
        paint.setShader(rim);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
    }

    @Override public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius);
        outline.setAlpha(focused ? .32f : .18f);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
