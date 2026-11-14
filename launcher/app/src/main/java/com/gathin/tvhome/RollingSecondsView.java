package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import java.util.Calendar;
import java.util.Locale;

/**
 * Seconds display for the main clock that smoothly rolls like an odometer / mechanical dial wheel.
 */
public class RollingSecondsView extends View {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;

    private int currentSeconds = -1;
    private int previousSeconds = -1;
    private float rollProgress = 1.0f;
    @Nullable
    private ValueAnimator rollAnimator;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            int sec = cal.get(Calendar.SECOND);
            if (sec != currentSeconds) {
                previousSeconds = currentSeconds;
                currentSeconds = sec;
                if (previousSeconds >= 0 && isAttachedToWindow() && getVisibility() == VISIBLE) {
                    startRollAnimation();
                } else {
                    rollProgress = 1.0f;
                    invalidate();
                }
            }
            long msUntilNext = 1000 - (now % 1000);
            handler.postDelayed(this, Math.max(25, msUntilNext));
        }
    };

    public RollingSecondsView(Context context) {
        this(context, null);
    }

    public RollingSecondsView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RollingSecondsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        init();
    }

    private void init() {
        textPaint.setTextSize(26f * density);
        textPaint.setColor(0xE6FFFFFF);
        textPaint.setShadowLayer(4f * density, 0, 1.5f * density, 0xCC000000);
        textPaint.setLetterSpacing(0.02f);
        try {
            Typeface tf = ResourcesCompat.getFont(getContext(), R.font.outfit_light);
            if (tf != null) textPaint.setTypeface(tf);
            else textPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        } catch (Exception e) {
            textPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
    }

    private void startRollAnimation() {
        if (rollAnimator != null) {
            rollAnimator.cancel();
        }
        rollAnimator = ValueAnimator.ofFloat(0f, 1f);
        rollAnimator.setDuration(260);
        rollAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        rollAnimator.addUpdateListener(anim -> {
            rollProgress = (float) anim.getAnimatedValue();
            invalidate();
        });
        rollAnimator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(tickRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(tickRunnable);
        if (rollAnimator != null) {
            rollAnimator.cancel();
            rollAnimator = null;
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            handler.post(tickRunnable);
        } else {
            handler.removeCallbacks(tickRunnable);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float sampleWidth = textPaint.measureText(": 59") + 4f * density;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float sampleHeight = (fm.bottom - fm.top) * 1.2f;
        int w = resolveSize((int) Math.ceil(sampleWidth), widthMeasureSpec);
        int h = resolveSize((int) Math.ceil(sampleHeight), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (currentSeconds < 0) return;

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textH = fm.bottom - fm.top;
        float baseline = (getHeight() - (fm.descent + fm.ascent)) / 2f;
        float slotHeight = textH * 0.95f;

        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());

        if (rollProgress < 1.0f && previousSeconds >= 0) {
            // Old seconds roll up and fade out
            String prevStr = String.format(Locale.US, ": %02d", previousSeconds);
            int prevAlpha = Math.max(0, Math.min(255, (int) ((1f - rollProgress) * 230)));
            textPaint.setAlpha(prevAlpha);
            canvas.drawText(prevStr, 0, baseline - rollProgress * slotHeight, textPaint);

            // New seconds roll in from below and fade in
            String currStr = String.format(Locale.US, ": %02d", currentSeconds);
            int currAlpha = Math.max(0, Math.min(255, (int) (rollProgress * 230)));
            textPaint.setAlpha(currAlpha);
            canvas.drawText(currStr, 0, baseline + (1f - rollProgress) * slotHeight, textPaint);
        } else {
            String currStr = String.format(Locale.US, ": %02d", currentSeconds);
            textPaint.setAlpha(230);
            canvas.drawText(currStr, 0, baseline, textPaint);
        }

        canvas.restore();
    }
}
