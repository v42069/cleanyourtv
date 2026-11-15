package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import java.util.Calendar;
import java.util.Locale;

/**
 * Clock minutes display that smoothly rolls vertically like an odometer / dial wheel on each minute change.
 */
public class RollingMinutesView extends View {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;

    private int currentMinute = -1;
    private int previousMinute = -1;
    private float rollProgress = 1.0f;
    @Nullable
    private ValueAnimator rollAnimator;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            int min = cal.get(Calendar.MINUTE);
            if (min != currentMinute) {
                previousMinute = currentMinute;
                currentMinute = min;
                if (previousMinute >= 0 && isAttachedToWindow() && getVisibility() == VISIBLE) {
                    startRollAnimation();
                } else {
                    rollProgress = 1.0f;
                    invalidate();
                }
            }
            long msUntilNext = 1000 - (now % 1000);
            handler.postDelayed(this, Math.max(50, msUntilNext));
        }
    };

    public RollingMinutesView(Context context) {
        this(context, null);
    }

    public RollingMinutesView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RollingMinutesView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        float spSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 52f, getResources().getDisplayMetrics());
        textPaint.setTextSize(spSize);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setShadowLayer(6f * density, 0, 2f * density, 0xCC000000);
        textPaint.setLetterSpacing(-0.04f);
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
        rollAnimator.setDuration(260); // 260ms smooth continuous 60fps roll
        rollAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
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
    public int getBaseline() {
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        return (int) Math.round((getHeight() - (fm.descent + fm.ascent)) / 2f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Measure widest digit pair so width is constant
        float maxW = 0f;
        for (int i = 0; i < 60; i++) {
            maxW = Math.max(maxW, textPaint.measureText(String.format(Locale.US, "%02d", i)));
        }
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float sampleHeight = (fm.bottom - fm.top);

        int w = resolveSize((int) Math.ceil(maxW + 4f * density), widthMeasureSpec);
        int h = resolveSize((int) Math.ceil(sampleHeight), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (currentMinute < 0) return;

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = (getHeight() - (fm.descent + fm.ascent)) / 2f;
        float slotHeight = (fm.bottom - fm.top) * 0.95f;

        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());

        if (rollProgress < 1.0f && previousMinute >= 0) {
            float p = rollProgress;
            float rollY = p * slotHeight;

            // Outgoing minute rolls up with smooth linear fade
            String prevStr = String.format(Locale.US, "%02d", previousMinute);
            int prevAlpha = Math.max(0, Math.min(255, (int) ((1f - p) * 255)));
            textPaint.setAlpha(prevAlpha);
            canvas.drawText(prevStr, 0, baseline - rollY, textPaint);

            // Incoming minute rolls in from below with smooth linear bloom
            String currStr = String.format(Locale.US, "%02d", currentMinute);
            int currAlpha = Math.max(0, Math.min(255, (int) (p * 255)));
            textPaint.setAlpha(currAlpha);
            canvas.drawText(currStr, 0, baseline + (slotHeight - rollY), textPaint);
        } else {
            String currStr = String.format(Locale.US, "%02d", currentMinute);
            textPaint.setAlpha(255);
            canvas.drawText(currStr, 0, baseline, textPaint);
        }

        canvas.restore();
    }
}
