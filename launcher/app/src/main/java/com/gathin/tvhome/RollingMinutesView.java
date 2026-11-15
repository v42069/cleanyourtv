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
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import java.util.Calendar;
import java.util.Locale;

/**
 * Clock minutes display that rolls vertically like a mechanical odometer / dial wheel on each minute change.
 * Only changing digits roll; unchanged digits stay steady.
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

    private float digitWidth = 0f;
    private float digitSpacing = 0f;

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
        rollAnimator.setDuration(190); // Crisp, snappy rotation
        rollAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0.1f, 1f));
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
        // Measure widest digit for equal-width dial wheels
        float maxD = 0f;
        for (int i = 0; i <= 9; i++) {
            maxD = Math.max(maxD, textPaint.measureText(String.valueOf(i)));
        }
        digitWidth = maxD;
        digitSpacing = -0.04f * textPaint.getTextSize(); // matches letterSpacing

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float sampleHeight = (fm.bottom - fm.top);

        float totalW = digitWidth * 2f + digitSpacing + 4f * density;
        int w = resolveSize((int) Math.ceil(totalW), widthMeasureSpec);
        int h = resolveSize((int) Math.ceil(sampleHeight), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (currentMinute < 0) return;

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = (getHeight() - (fm.descent + fm.ascent)) / 2f;
        float slotHeight = getHeight() * 0.92f;

        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());

        int currTens = currentMinute / 10;
        int currUnits = currentMinute % 10;
        int prevTens = (previousMinute >= 0) ? (previousMinute / 10) : currTens;
        int prevUnits = (previousMinute >= 0) ? (previousMinute % 10) : currUnits;

        float xTens = 0f;
        float xUnits = digitWidth + digitSpacing;

        // Draw tens digit wheel
        drawDigitWheel(canvas, xTens, baseline, slotHeight, prevTens, currTens, currTens != prevTens);

        // Draw units digit wheel (rolls every minute)
        drawDigitWheel(canvas, xUnits, baseline, slotHeight, prevUnits, currUnits, true);

        canvas.restore();
    }

    private void drawDigitWheel(Canvas canvas, float x, float baseline, float slotHeight,
                                int prevDigit, int currDigit, boolean shouldRoll) {
        if (!shouldRoll || rollProgress >= 1.0f) {
            textPaint.setAlpha(255);
            canvas.drawText(String.valueOf(currDigit), x, baseline, textPaint);
            return;
        }

        float p = rollProgress;
        float rollY = p * slotHeight;

        // Outgoing digit rolls up with steep alpha fade
        float outAlphaF = (float) Math.pow(1f - p, 1.8);
        int outAlpha = Math.max(0, Math.min(255, (int) (outAlphaF * 255)));
        textPaint.setAlpha(outAlpha);
        canvas.drawText(String.valueOf(prevDigit), x, baseline - rollY, textPaint);

        // Incoming digit rolls in from below with accelerating alpha bloom
        float inAlphaF = (float) Math.pow(p, 1.8);
        int inAlpha = Math.max(0, Math.min(255, (int) (inAlphaF * 255)));
        textPaint.setAlpha(inAlpha);
        canvas.drawText(String.valueOf(currDigit), x, baseline + (slotHeight - rollY), textPaint);
    }
}
