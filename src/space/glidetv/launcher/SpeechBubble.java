package space.glidetv.launcher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/**
 * Speech balloon: a glass stadium body with a curved horn that tapers to a point at {@link #setTail}.
 * Body and horn are unioned into one path, so the lit rim runs unbroken around the whole shape.
 */
final class SpeechBubble extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path(), body = new Path(), horn = new Path();
    private final RectF rect = new RectF();
    private final float tailHeight;
    private float tailX = Float.NaN, radius;
    private Shader fill, sheen, rim;

    SpeechBubble(float tailHeight) { this.tailHeight = tailHeight; }

    /** Horizontal point, in the drawable's own coordinates, that the horn aims at. */
    void setTail(float x) {
        if (Math.abs(x - tailX) < .5f) return;
        tailX = x; rebuild(); invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) { rebuild(); }

    private void rebuild() {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        rect.set(b.left + 2, b.top + 2, b.right - 2, b.bottom - 2 - tailHeight);
        radius = rect.height() * .5f;
        body.reset(); body.addRoundRect(rect, radius, radius, Path.Direction.CW);
        shape.reset(); shape.set(body);
        if (!Float.isNaN(tailX)) {
            float mouth = Math.min(tailHeight * .55f, rect.width() * .45f);
            float tip = Math.max(rect.left + radius * .7f, Math.min(rect.right - radius * .7f, tailX));
            float mouthX = tip + mouth * 1.22f, base = rect.bottom - 1, tipY = base + tailHeight;
            horn.reset();
            horn.moveTo(mouthX + mouth * .5f, base);
            // Outer side sweeps down to the point; the inner side curves back under the body.
            horn.cubicTo(mouthX + mouth * .34f, base + tailHeight * .5f, tip + mouth * .16f, base + tailHeight * .72f, tip, tipY);
            horn.cubicTo(tip + mouth * .12f, base + tailHeight * .42f, mouthX - mouth * .3f, base + tailHeight * .3f, mouthX - mouth * .5f, base);
            horn.close();
            shape.op(horn, Path.Op.UNION);
        }
        fill = new LinearGradient(0, rect.top, 0, rect.bottom + tailHeight,
                new int[]{0x66425973, 0x76152235, 0x84080e19}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
        sheen = new LinearGradient(0, rect.top, 0, rect.top + rect.height() * .5f,
                new int[]{0x42ffffff, 0x00ffffff}, null, Shader.TileMode.CLAMP);
        rim = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0xe4f5fbff, 0x42ffffff, 0x9eb9d3ed}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        if (fill == null) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null); paint.setColor(0x56020812);
        canvas.save(); canvas.translate(0, 3f); canvas.drawPath(shape, paint); canvas.restore();
        paint.setShader(fill); canvas.drawPath(shape, paint);
        paint.setShader(sheen); canvas.drawPath(shape, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f);
        paint.setShader(rim); canvas.drawPath(shape, paint);
        paint.setShader(null);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
