package de.mrm.codepool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import de.mrm.codepool.core.FieldPlayback;
import de.mrm.codepool.core.ScalarField;

/** A single, bounded drawing loop. Owns no service and never holds a wake lock. */
public final class FieldView extends View {
    private final FieldPlayback playback = new FieldPlayback();
    private final Bitmap bitmap = Bitmap.createBitmap(ScalarField.SIZE, ScalarField.SIZE, Bitmap.Config.ARGB_8888);
    private final int[] pixels = new int[ScalarField.SIZE * ScalarField.SIZE];
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect destination = new Rect();

    private boolean foreground, windowVisible;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!playback.isRunning()) return;
            playback.frame(SystemClock.elapsedRealtime());
            updateBitmap();
            invalidate();
            postOnAnimationDelayed(this, 24);
        }
    };

    public FieldView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public boolean isAnimationEnabled() {
        return playback.isEnabled();
    }

    public boolean isAnimationRunning() {
        return playback.isRunning();
    }

    public void setAnimationEnabled(boolean enabled) {
        if (enabled) {
            if (!playback.isEnabled()) {
                restartAnimation();
            }
        } else {
            stopAnimation();
        }
    }

    public void restartAnimation() {
        playback.start(SystemClock.elapsedRealtime());
        updateBitmap();
        updateScheduling();
        invalidate();
    }

    public void stopAnimation() {
        playback.stop();
        removeCallbacks(frame);
        invalidate();
    }

    public void setForeground(boolean value) {
        foreground = value;
        updateScheduling();
    }

    private void updateScheduling() {
        removeCallbacks(frame);

        if (foreground && isAttachedToWindow() && windowVisible) {
            playback.resume();
        } else {
            playback.pause();
        }

        if (playback.isRunning()) {
            postOnAnimation(frame);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        windowVisible = getWindowVisibility() == VISIBLE;
        updateScheduling();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        playback.pause();
        windowVisible = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        updateScheduling();
    }

    private void updateBitmap() {
        ScalarField field = playback.field();
        for (int y = 0; y < ScalarField.SIZE; y++) {
            for (int x = 0; x < ScalarField.SIZE; x++) {
                float value = field.value(x, y);
                float intensity = Math.min(1f, Math.abs(value) * 3f);
                int alpha = (int) (100f * intensity);
                pixels[y * ScalarField.SIZE + x] = (alpha << 24) | (value >= 0 ? 0x0080E6C4 : 0x002CACDE);
            }
        }
        bitmap.setPixels(pixels, 0, ScalarField.SIZE, 0, 0, ScalarField.SIZE, ScalarField.SIZE);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!playback.isEnabled()) return;
        destination.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bitmap, null, destination, paint);
    }
}
