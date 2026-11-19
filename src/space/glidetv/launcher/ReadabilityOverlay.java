package space.glidetv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;

final class ReadabilityOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = Readability.scrimAlpha(.5f);
    private ValueAnimator transition;

    ReadabilityOverlay(Context context) {
        super(context);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setSceneLuminance(float luminance) {
        int target = Readability.scrimAlpha(luminance);
        if (target == alpha) return;
        if (transition != null) transition.cancel();
        transition = ValueAnimator.ofInt(alpha, target);
        transition.setDuration(480);
        transition.setInterpolator(new android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f));
        transition.addUpdateListener(animation -> {
            alpha = (Integer)animation.getAnimatedValue();
            invalidate();
        });
        transition.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth(), height = getHeight();
        if (width == 0 || height == 0) return;
        paint.setShader(new LinearGradient(0, 0, 0, height * .62f,
                new int[]{Color.argb(alpha, 2, 8, 18), Color.argb(alpha * 2 / 5, 2, 8, 18), Color.TRANSPARENT},
                new float[]{0, .48f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height * .62f, paint);
        paint.setShader(new LinearGradient(0, height * .38f, 0, height,
                new int[]{Color.TRANSPARENT, Color.argb(alpha / 3, 2, 8, 18), Color.argb(alpha, 2, 8, 18)},
                new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, height * .38f, width, height, paint);
        paint.setShader(null);
    }

    @Override protected void onDetachedFromWindow() {
        if (transition != null) transition.cancel();
        super.onDetachedFromWindow();
    }
}
