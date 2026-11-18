package space.glidetv.launcher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/**
 * The tile's outline doubles as the resume indicator: a dim track with a bright segment that starts
 * at the bottom-left and runs the way a progress bar would before carrying on around the tile.
 */
final class ProgressRim extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path(), done = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF rect = new RectF(), corner = new RectF();
    private final float radius;
    private float progress;
    private boolean focused;
    private int tint = IconPalette.NEUTRAL;

    ProgressRim(float radius) {
        this.radius = radius;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void set(float value, boolean focus, int color) {
        if (Math.abs(value - progress) < .002f && focused == focus && tint == color) return;
        progress = value; focused = focus; tint = color; rebuild(); invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) { rebuild(); }

    private void rebuild() {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float inset = 2.5f, r = Math.min(radius, Math.min(b.width(), b.height()) * .5f - inset);
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        outline.reset();
        outline.moveTo(rect.left + r, rect.bottom);
        outline.lineTo(rect.right - r, rect.bottom);
        corner.set(rect.right - 2 * r, rect.bottom - 2 * r, rect.right, rect.bottom);
        outline.arcTo(corner, 90, -90);
        outline.lineTo(rect.right, rect.top + r);
        corner.set(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r);
        outline.arcTo(corner, 0, -90);
        outline.lineTo(rect.left + r, rect.top);
        corner.set(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r);
        outline.arcTo(corner, 270, -90);
        outline.lineTo(rect.left, rect.bottom - r);
        corner.set(rect.left, rect.bottom - 2 * r, rect.left + 2 * r, rect.bottom);
        outline.arcTo(corner, 180, -90);
        outline.close();
        done.reset();
        if (progress > 0) {
            measure.setPath(outline, false);
            measure.getSegment(0, Math.min(1f, progress) * measure.getLength(), done, true);
        }
    }

    @Override public void draw(Canvas canvas) {
        if (outline.isEmpty()) return;
        // A dark pass first, so the rim stays legible over pale artwork.
        paint.setStrokeWidth(focused ? 10f : 8f); paint.setColor(0x73000610);
        canvas.drawPath(outline, paint);
        paint.setStrokeWidth(focused ? 8f : 6f); paint.setColor(focused ? 0x73ffffff : 0x47ffffff);
        canvas.drawPath(outline, paint);
        if (!done.isEmpty()) {
            paint.setStrokeWidth(focused ? 4.8f : 4f); paint.setColor(tint);
            canvas.drawPath(done, paint);
        }
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
