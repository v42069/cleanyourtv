package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Random;

/**
 * Live wallpaper for Home (a theme's "live_sky": true): a sky that follows the real sun and moon over
 * mountains and a lake, with the current weather on top (see {@link Weather}).
 *
 * Cost is kept to what a 2 GB TV can carry: everything that only changes with the clock (sky, stars,
 * sun, moon, mountains, lake) is painted into one 960x540 bitmap once a minute and stretched over the
 * screen; only the clouds, rain, snow and lightning move, at 20 frames a second, and nothing is drawn
 * while Home is hidden. reduced_motion keeps the picture and stops the motion.
 */
public class LiveSkyView extends View {

    private static final int TEX_W = 960;
    private static final int TEX_H = 540;
    /** The lake's shore line, as a fraction of the height. */
    private static final float LAKE = 0.70f;
    private static final long FRAME_MS = 50;

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private LiveSkySettings settings = LiveSkySettings.OFF;
    private boolean reducedMotion;
    private boolean running;
    @Nullable private Weather weather;

    private Bitmap scene;
    private Canvas sceneCanvas;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cloudPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect(0, 0, TEX_W, TEX_H);
    private final RectF dst = new RectF();
    private final RectF tmp = new RectF();
    private long renderedMinute = Long.MIN_VALUE;
    private boolean dirty = true;

    private Path far, mid, near;
    private float[] starX, starY, starR, starA;

    // What the last scene render worked out, for the moving layers.
    private float daylight, glow;
    private int cloudTint = 0xFFF5F7FA;

    private Bitmap[] cloudSprites;
    private int cloudCount;
    private final float[] cloudX = new float[12], cloudY = new float[12], cloudScale = new float[12], cloudAlpha = new float[12];
    private final int[] cloudSprite = new int[12];

    private static final String[] FIREWATCH_FILES = {
            "0_night.jpg",
            "1_dawn.jpg",
            "2_sunrise.jpg",
            "3_early_morning.jpg",
            "4_day.jpg",
            "5_golden_hour.jpg",
            "6_sunset.jpg",
            "7_dusk.jpg"
    };
    private final Bitmap[] firewatchBitmaps = new Bitmap[8];

    private static final int MESH_W = 32;
    private static final int MESH_H = 24;
    private final float[] meshVerts = new float[(MESH_W + 1) * (MESH_H + 1) * 2];
    private final Path birdPath = new Path();
    private final Path eaglePath = new Path();
    private boolean eaglePathBuilt = false;
    private RadialGradient mistShaderDay;
    private RadialGradient mistShaderSunset;
    private RadialGradient mistShaderNight;
    private final Paint meteorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] moteX = new float[25];
    private final float[] moteY = new float[25];
    private final float[] moteVx = new float[25];
    private final float[] moteVy = new float[25];
    private final float[] moteSize = new float[25];
    private boolean motesInit = false;

    private String particleKind = "";
    private int particleCount;
    private final float[] px = new float[200], py = new float[200], pv = new float[200], pl = new float[200];
    private float slant;
    private long nextFlash;
    private float flash;

    private final Random random = new Random(11);
    private long startReal;
    private long startUptime;
    private long lastFrame;

    public LiveSkyView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        meteorPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    // ---- what the activity calls ----------------------------------------------------------------

    void configure(LiveSkySettings s, boolean reducedMotion) {
        settings = s;
        this.reducedMotion = reducedMotion;
        setVisibility(s.enabled ? VISIBLE : GONE);
        if (!s.enabled) return;
        startReal = System.currentTimeMillis();
        startUptime = SystemClock.uptimeMillis();
        buildStars();
        if (!s.isFirewatch()) {
            buildTerrain();
            buildClouds();
        }
        Weather forced = s.debugWeather.isEmpty() ? null : Weather.debug(s.debugWeather);
        if (forced != null) weather = forced;
        setupWeather();
        dirty = true;
        invalidate();
    }

    void setWeather(@Nullable Weather w) {
        if (w == null || !settings.enabled || !settings.debugWeather.isEmpty()) return;
        weather = w;
        setupWeather();
        dirty = true;
        invalidate();
    }

    /** Home shown or hidden: the moving layers only run while it is shown. */
    void setRunning(boolean run) {
        running = run;
        lastFrame = 0;
        if (run && settings.enabled) invalidate();
    }

    /** Once a minute from Home's clock tick. */
    void update() {
        if (settings.enabled) invalidate();
    }

    /** True while the sun is down, for the weather chip's moon icon. */
    boolean isNight() {
        return LiveSky.sun(now(), settings.latitude, settings.longitude)[0] < -2;
    }

    // ---- time --------------------------------------------------------------------------------

    private long now() {
        long real = System.currentTimeMillis();
        if (settings.debugMinute >= 0) return midnight(real) + settings.debugMinute * 60_000L;
        if (settings.timelapseSeconds > 0) return startReal + (real - startReal) * 86_400L / settings.timelapseSeconds;
        return real;
    }

    private static long midnight(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ---- drawing -----------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dst.set(0, 0, w, h);
        resetParticles();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!settings.enabled || getWidth() == 0) return;
        long now = now();
        long minute = now / 60_000L;
        if (dirty || minute != renderedMinute) {
            if (settings.isFirewatch()) {
                renderFirewatchScene(now);
            } else {
                renderScene(now);
            }
            renderedMinute = minute;
            dirty = false;
        }

        long frame = SystemClock.uptimeMillis();
        boolean moving = running && !reducedMotion;
        float dt = !moving || lastFrame == 0 ? 0f : Math.min(0.2f, (frame - lastFrame) / 1000f);
        lastFrame = frame;

        if (settings.isFirewatch()) {
            drawFirewatchMoving(canvas, dt, frame, moving);
        } else {
            canvas.drawBitmap(scene, src, dst, bitmapPaint);
            float scale = getWidth() / (float) TEX_W;
            drawClouds(canvas, dt, scale);
            drawParticles(canvas, dt);
            drawLightning(canvas, dt, frame, moving);
        }

        boolean animated = settings.isFirewatch() || cloudCount > 0 || particleCount > 0 || flash > 0 || settings.timelapseSeconds > 0;
        if (moving && animated) {
            if (settings.isFirewatch()) {
                postInvalidateOnAnimation();
            } else {
                postInvalidateDelayed(FRAME_MS);
            }
        }
    }

    private void updateFirewatchMesh(float t) {
        int w = getWidth();
        int h = getHeight();

        // PS4 Firewatch dynamic theme tree animation:
        // 1. Sky, distant mountains, cliffs, watchtower and horizon are 100% rock-solid.
        // 2. Only the pine tree forest canopy gently swings in a natural mountain breeze.
        // 3. Trunk base at the ground is firmly anchored (0 sway at y = h).
        // 4. Subtle, soothing amplitude (max 5.0px) with a slow ~5.5s natural cycle.
        float maxSway = 5.0f; // gentle, subtle sway (matches PS4 theme)

        for (int j = 0; j <= MESH_H; j++) {
            float y = j * (float) h / MESH_H;
            float normY = y / h;

            for (int i = 0; i <= MESH_W; i++) {
                int idx = (j * (MESH_W + 1) + i) * 2;
                float baseX = i * (float) w / MESH_W;

                // Anchor outer screen borders rock-solid to prevent any edge distortion
                if (i == 0 || i == MESH_W || j == 0 || j == MESH_H) {
                    meshVerts[idx] = baseX;
                    meshVerts[idx + 1] = y;
                    continue;
                }

                float normX = i / (float) MESH_W;

                // Firewatch tree line: left cliff ~0.68, center valley ~0.74, right ridge ~0.55
                float treeTop = normX < 0.5f
                        ? 0.68f + 0.06f * (normX / 0.5f)
                        : 0.74f - 0.19f * ((normX - 0.5f) / 0.5f);

                // Above the tree line: completely static mountain peaks, rocks, and sky
                if (normY <= treeTop) {
                    meshVerts[idx] = baseX;
                    meshVerts[idx + 1] = y;
                    continue;
                }

                // In the tree zone: anchored at treeTop (0) and anchored at ground (0 at normY=1.0)
                // Tree canopy (~15-30% below tree top) experiences gentle pendulum swing
                float treeDepth = (normY - treeTop) / (1.0f - treeTop); // 0.0 at top, 1.0 at ground
                float treeWeight = (float) Math.sin(treeDepth * Math.PI) * (1.0f - 0.35f * treeDepth);

                // Smooth horizontal border taper
                float edgeFactor = (float) Math.sin(normX * Math.PI);

                // Slow, graceful wind breeze rolling across the mountain valley (~5.5s period)
                float windPhase = t * 1.15f - normX * 1.8f;
                float breeze = (float) Math.sin(windPhase) * 0.85f + (float) Math.sin(t * 0.60f + normX) * 0.15f;

                float treeSway = maxSway * treeWeight * breeze * edgeFactor;

                meshVerts[idx] = baseX + treeSway;
                meshVerts[idx + 1] = y; // strictly y: NO vertical dipping or ground bouncing
            }
        }
    }

    private void drawFirewatchMoving(Canvas canvas, float dt, long frame, boolean moving) {
        if (startUptime == 0) startUptime = frame;
        float t = Math.max(0f, (frame - startUptime) / 1000f);
        if (moving) {
            updateFirewatchMesh(t);
            canvas.drawBitmapMesh(scene, MESH_W, MESH_H, meshVerts, 0, null, 0, bitmapPaint);
        } else {
            canvas.drawBitmap(scene, src, dst, bitmapPaint);
        }

        double sunElev = LiveSky.sun(now(), settings.latitude, settings.longitude)[0];

        // 1. Twinkling stars at night
        if (sunElev < -4.0) {
            drawTwinklingStars(canvas, t);
        }

        // 2. Shooting star every 1 minute
        drawShootingStar(canvas, t, sunElev);

        // 3. Watchtower cabin glowing / breathing lantern light
        if (sunElev < 5.0) {
            float cx = getWidth() * 0.50f;
            float cy = getHeight() * 0.422f;
            float pulse = 0.72f + 0.28f * (float) Math.sin(t * 2.4f) + 0.12f * (float) Math.sin(t * 5.1f);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx, cy, 32f,
                    new int[]{argb(pulse * 210, 0xFFFFE082), argb(pulse * 85, 0xFFFFB300), 0},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, 32f, paint);
            paint.setShader(null);
        }

        // 4. Valley mist drifting through the canyon
        drawFirewatchMist(canvas, dt, t, sunElev);

        // 5. Soaring eagle across the sky (visible day, sunset, and night)
        drawFirewatchEagle(canvas, t, sunElev);

        // 6. Floating ambient dust spores / embers
        drawFirewatchMotes(canvas, dt, t, sunElev);

        // 7. Rain/Snow/Thunder weather particles if weather is active
        drawParticles(canvas, dt);
        drawLightning(canvas, dt, frame, moving);
    }

    private void drawShootingStar(Canvas canvas, float t, double sunElev) {
        int w = getWidth();
        int h = getHeight();

        // Exact 60s cycle synchronized with minute odometer change
        long nowMs = now();
        float secondInMin = (nowMs % 60_000L) / 1000f; // 0.0 to 60.0

        // Streaks for 1.8s between second 0.8 and 2.6 right as the minute turns
        if (secondInMin < 0.8f || secondInMin > 2.6f) return;
        float progress = (secondInMin - 0.8f) / 1.8f; // 0.0 to 1.0

        // Use the current minute number as random seed for varied paths
        long minuteNumber = nowMs / 60_000L;
        Random rng = new Random(minuteNumber * 1337L + 7L);

        float startX = w * (0.12f + rng.nextFloat() * 0.50f);
        float startY = h * (0.04f + rng.nextFloat() * 0.16f);
        float angleDeg = 24.0f + rng.nextFloat() * 16.0f; // 24 to 40 degrees downward angle
        float angleRad = (float) Math.toRadians(angleDeg);

        float totalTravel = w * (0.32f + rng.nextFloat() * 0.14f);
        float currentDist = progress * totalTravel;

        float headX = startX + (float) Math.cos(angleRad) * currentDist;
        float headY = startY + (float) Math.sin(angleRad) * currentDist;

        float tailLen = Math.min(260f, currentDist * 0.90f);
        float tailX = headX - (float) Math.cos(angleRad) * tailLen;
        float tailY = headY - (float) Math.sin(angleRad) * tailLen;

        // Brightness curve: fast burst, then fades out smoothly
        float alpha;
        if (progress < 0.15f) {
            alpha = progress / 0.15f;
        } else if (progress > 0.65f) {
            alpha = (1.0f - progress) / 0.35f;
        } else {
            alpha = 1.0f;
        }

        // Distinct colors for daytime vs sunset/night
        int glowColor = sunElev > 0 ? 0xFFFFB300 : 0xFF80D8FF; // warm amber in daylight, electric cyan at night
        int headColor = argb(alpha * 255, 0xFFFFFFFF);
        int trailMid = argb(alpha * 210, glowColor);
        int tailColor = argb(0, glowColor);

        // Draw glowing tapered tail
        meteorPaint.setStyle(Paint.Style.STROKE);
        meteorPaint.setStrokeWidth(3.8f);
        meteorPaint.setShader(new LinearGradient(headX, headY, tailX, tailY,
                new int[]{headColor, trailMid, tailColor},
                new float[]{0f, 0.30f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawLine(headX, headY, tailX, tailY, meteorPaint);
        meteorPaint.setShader(null);

        // Draw brilliant head spark and outer glow
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(argb(alpha * 150, glowColor));
        canvas.drawCircle(headX, headY, 7.5f, paint);
        paint.setColor(argb(alpha * 255, 0xFFFFFFFF));
        canvas.drawCircle(headX, headY, 3.0f, paint);
    }

    private void initEaglePath() {
        if (eaglePathBuilt) return;
        eaglePath.reset();
        // Head / hooked beak at front
        eaglePath.moveTo(30f, 0f);
        eaglePath.lineTo(20f, -6f);
        // Left wing leading edge
        eaglePath.quadTo(10f, -24f, -6f, -58f);
        // Primary slotted wing feathers
        eaglePath.lineTo(-15f, -56f);
        eaglePath.lineTo(-11f, -50f);
        eaglePath.lineTo(-20f, -46f);
        eaglePath.lineTo(-15f, -40f);
        eaglePath.lineTo(-24f, -36f);
        // Left wing trailing edge to tail
        eaglePath.quadTo(-12f, -18f, -18f, -7f);
        // Wedge tail
        eaglePath.lineTo(-36f, -13f);
        eaglePath.lineTo(-42f, 0f);
        eaglePath.lineTo(-36f, 13f);
        // Right wing trailing edge
        eaglePath.lineTo(-18f, 7f);
        eaglePath.quadTo(-12f, 18f, -24f, 36f);
        // Right primary feathers
        eaglePath.lineTo(-15f, 40f);
        eaglePath.lineTo(-20f, 46f);
        eaglePath.lineTo(-11f, 50f);
        eaglePath.lineTo(-15f, 56f);
        // Right wing leading edge back to head
        eaglePath.lineTo(-6f, 58f);
        eaglePath.quadTo(10f, 24f, 20f, 6f);
        eaglePath.close();
        eaglePathBuilt = true;
    }

    private void drawFirewatchEagle(Canvas canvas, float t, double sunElev) {
        int w = getWidth();
        int h = getHeight();

        // One sweeping pass every 26 seconds
        float flightPeriod = 26.0f;
        float flightDuration = 18.0f; // 18 seconds across screen, 8 seconds cooldown
        float cycleTime = (t % flightPeriod + flightPeriod) % flightPeriod;
        if (cycleTime > flightDuration) return;

        float p = cycleTime / flightDuration; // 0.0 to 1.0
        // Flies from left (-130px) to right (w + 130px)
        float ex = -130f + (w + 260f) * p;
        // Thermal soaring altitude wave: gently climbs and descends across the canyon
        float ey = h * (0.21f + 0.05f * (float) Math.sin(p * Math.PI * 2.0) + 0.015f * (float) Math.cos(t * 0.9f));

        initEaglePath();

        // Raptor soaring rhythm: long steady glides with occasional majestic wing flaps
        float flapPhase = (t % 6.0f);
        boolean flapping = flapPhase < 1.6f;
        float flap = flapping ? (float) Math.sin(flapPhase * Math.PI * 4.0) : 0f;

        canvas.save();
        canvas.translate(ex, ey);
        // Gentle bank angle
        float bank = (float) Math.sin(p * Math.PI * 2.0) * 3.5f;
        canvas.rotate(bank);
        // Wing beat scaling
        if (flapping) {
            canvas.scale(1.0f, 1.0f - flap * 0.28f);
        }

        paint.setStyle(Paint.Style.FILL);
        if (sunElev < -6.0) {
            // Night: moonlit silhouette with soft glowing moonlight outline
            paint.setColor(0xE6263548);
            canvas.drawPath(eaglePath, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setColor(0xBB9CC2E8);
            canvas.drawPath(eaglePath, paint);
            paint.setStyle(Paint.Style.FILL);
        } else if (sunElev < 8.0) {
            // Sunset / golden hour: rich warm twilight silhouette
            paint.setColor(0xF018151E);
            canvas.drawPath(eaglePath, paint);
        } else {
            // Daytime: crisp dark raptor silhouette
            paint.setColor(0xF0141A22);
            canvas.drawPath(eaglePath, paint);
        }
        canvas.restore();
    }

    private void drawTwinklingStars(Canvas canvas, float t) {
        if (starX == null || starX.length == 0) return;
        int w = getWidth();
        int h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < Math.min(80, starX.length); i++) {
            float tw = (float) Math.sin(t * 2.6f + i * 1.8f);
            if (tw > 0.05f) {
                float sx = starX[i] * w / (float) TEX_W;
                float sy = starY[i] * h / (float) TEX_H * 0.75f;
                int alpha = (int) (tw * starA[i] * 240);
                paint.setColor(argb(alpha, 0xFFFFFFFF));
                canvas.drawCircle(sx, sy, starR[i] * 1.3f, paint);
            }
        }
    }

    private void drawFirewatchMist(Canvas canvas, float dt, float t, double sunElev) {
        int w = getWidth();
        int h = getHeight();
        paint.setStyle(Paint.Style.FILL);

        if (mistShaderDay == null) {
            mistShaderNight = new RadialGradient(0, 0, 260f, new int[]{0x487692B2, 0}, null, Shader.TileMode.CLAMP);
            mistShaderSunset = new RadialGradient(0, 0, 260f, new int[]{0x58FFAC74, 0}, null, Shader.TileMode.CLAMP);
            mistShaderDay = new RadialGradient(0, 0, 260f, new int[]{0x45E4EFF8, 0}, null, Shader.TileMode.CLAMP);
        }

        RadialGradient mist = (sunElev < -5.0) ? mistShaderNight : ((sunElev < 10.0) ? mistShaderSunset : mistShaderDay);
        paint.setShader(mist);

        for (int m = 0; m < 3; m++) {
            float speed = (m == 0 ? 14f : (m == 1 ? 20f : 10f));
            float mx = ((t * speed + m * 420f) % (w + 500f)) - 250f;
            float my = h * (0.45f + m * 0.09f);
            canvas.save();
            canvas.translate(mx, my);
            tmp.set(-280, -55, 280, 55);
            canvas.drawOval(tmp, paint);
            canvas.restore();
        }
        paint.setShader(null);
    }


    private void initMotes() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        Random r = new Random(42);
        for (int i = 0; i < moteX.length; i++) {
            moteX[i] = r.nextFloat() * w;
            moteY[i] = h * 0.3f + r.nextFloat() * h * 0.7f;
            moteVx[i] = 12f + r.nextFloat() * 18f;
            moteVy[i] = -6f - r.nextFloat() * 10f;
            moteSize[i] = 1.2f + r.nextFloat() * 2.2f;
        }
        motesInit = true;
    }

    private void drawFirewatchMotes(Canvas canvas, float dt, float t, double sunElev) {
        int w = getWidth();
        int h = getHeight();
        if (!motesInit) initMotes();
        paint.setStyle(Paint.Style.FILL);
        int moteColor = sunElev < -6.0 ? 0x99B0D0FF : (sunElev < 10.0 ? 0xBBEFA660 : 0x88FFFFFF);
        for (int i = 0; i < moteX.length; i++) {
            moteX[i] += moteVx[i] * dt;
            moteY[i] += moteVy[i] * dt + (float) Math.sin(t * 1.5f + i) * 0.4f;
            if (moteX[i] > w + 20 || moteY[i] < h * 0.25f) {
                moteX[i] = -15f;
                moteY[i] = h * 0.45f + random.nextFloat() * h * 0.55f;
            }
            paint.setColor(moteColor);
            canvas.drawCircle(moteX[i], moteY[i], moteSize[i], paint);
        }
    }

    private Bitmap getFirewatchBitmap(int index) {
        if (index < 0 || index >= 8) return null;
        if (firewatchBitmaps[index] != null && !firewatchBitmaps[index].isRecycled()) {
            return firewatchBitmaps[index];
        }
        try (java.io.InputStream is = getContext().getAssets().open("firewatch/" + FIREWATCH_FILES[index])) {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 2;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            firewatchBitmaps[index] = android.graphics.BitmapFactory.decodeStream(is, null, opts);
        } catch (Exception ignored) {
        }
        return firewatchBitmaps[index];
    }

    private void renderFirewatchScene(long now) {
        if (scene == null) {
            scene = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
            sceneCanvas = new Canvas(scene);
        }
        Canvas c = sceneCanvas;
        c.drawColor(0, PorterDuff.Mode.CLEAR);

        double[] sun = LiveSky.sun(now, settings.latitude, settings.longitude);
        double[] sunNext = LiveSky.sun(now + 60_000L, settings.latitude, settings.longitude);
        boolean rising = sunNext[0] >= sun[0];
        LiveSky.FirewatchPhase phase = LiveSky.firewatchPhase(sun[0], rising);

        Bitmap b1 = getFirewatchBitmap(phase.primary);
        if (b1 != null) {
            paint.setAlpha(255);
            paint.setColorFilter(null);
            c.drawBitmap(b1, null, src, paint);
        }

        if (phase.blend > 0.005f && phase.primary != phase.secondary) {
            Bitmap b2 = getFirewatchBitmap(phase.secondary);
            if (b2 != null) {
                paint.setAlpha((int) (phase.blend * 255));
                paint.setColorFilter(null);
                c.drawBitmap(b2, null, src, paint);
                paint.setAlpha(255);
            }
        }

        for (int i = 0; i < 8; i++) {
            if (i != phase.primary && i != phase.secondary && firewatchBitmaps[i] != null) {
                firewatchBitmaps[i].recycle();
                firewatchBitmaps[i] = null;
            }
        }

        float overcast = overcast();
        if (overcast > 0.05f) {
            int tint = Sky.lerpColor(0x00000000, 0x44141E2E, overcast);
            c.drawColor(tint, PorterDuff.Mode.SRC_ATOP);
        }

        daylight = LiveSky.daylight(sun[0]);
        glow = LiveSky.glow(sun[0]);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (int i = 0; i < 8; i++) {
            if (firewatchBitmaps[i] != null) {
                firewatchBitmaps[i].recycle();
                firewatchBitmaps[i] = null;
            }
        }
    }

    private void renderScene(long now) {
        if (scene == null) {
            scene = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
            sceneCanvas = new Canvas(scene);
        }
        Canvas c = sceneCanvas;
        c.drawColor(0, PorterDuff.Mode.CLEAR);
        double[] sun = LiveSky.sun(now, settings.latitude, settings.longitude);
        double[] moon = LiveSky.moon(now, settings.latitude, settings.longitude);
        float overcast = overcast();
        int[] sky = LiveSky.palette(sun[0], overcast);
        daylight = LiveSky.daylight(sun[0]);
        glow = LiveSky.glow(sun[0]) * (1 - overcast * 0.85f);
        float lakeY = TEX_H * LAKE;
        paint.setStyle(Paint.Style.FILL);

        // Sky.
        paint.setShader(new LinearGradient(0, 0, 0, lakeY, sky, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, TEX_W, lakeY, paint);
        float sunX = xFor(sun[1]);
        if (glow > 0.01f) {
            paint.setShader(new RadialGradient(sunX, lakeY, TEX_W * 0.6f,
                    new int[]{argb(glow * 190, 0xFFFFA866), 0x00FFA866}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, TEX_W, lakeY, paint);
        }
        paint.setShader(null);

        // Stars.
        float stars = LiveSky.stars(sun[0]) * (1 - overcast);
        if (stars > 0.01f) {
            for (int i = 0; i < starX.length; i++) {
                paint.setColor(argb(stars * starA[i] * 255, 0xFFFFFFFF));
                c.drawCircle(starX[i], starY[i], starR[i], paint);
            }
        }

        // Moon, then sun.
        float moonAlpha = LiveSky.clamp((float) (moon[0] + 2) / 6) * (1 - 0.7f * daylight) * (1 - overcast * 0.9f);
        if (moonAlpha > 0.02f) drawMoon(c, xFor(moon[1]), yFor(moon[0], lakeY), 13, LiveSky.moonPhase(now), moonAlpha);
        float sunAlpha = LiveSky.clamp((float) (sun[0] + 3) / 4) * (1 - overcast * 0.95f);
        int sunColor = Sky.lerpColor(0xFFFFA45C, 0xFFFFF6E0, LiveSky.clamp((float) sun[0] / 25));
        if (sunAlpha > 0.02f) {
            float sy = yFor(sun[0], lakeY);
            paint.setShader(new RadialGradient(sunX, sy, 85, new int[]{argb(sunAlpha * 150, sunColor), sunColor & 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(sunX, sy, 85, paint);
            paint.setShader(null);
            paint.setColor(argb(sunAlpha * 255, sunColor));
            c.drawCircle(sunX, sy, 15, paint);
        }

        // Mountains: far ones fade into the horizon colour, near ones go dark.
        int farTint = Sky.lerpColor(Sky.lerpColor(Sky.lerpColor(0xFF141C30, 0xFF6F87A0, daylight), 0xFF7A5A78, glow * 0.5f), sky[2], 0.3f);
        int midTint = Sky.lerpColor(Sky.lerpColor(Sky.lerpColor(0xFF0B1120, 0xFF3E5B6C, daylight), 0xFF3D2A47, glow * 0.4f), sky[2], 0.12f);
        int nearTint = Sky.lerpColor(Sky.lerpColor(0xFF05080F, 0xFF1F3A2C, daylight), 0xFF1C1424, glow * 0.3f);
        drawMountains(c, lakeY, farTint, midTint, nearTint, overcast);

        // Lake: the sky's colours, darker, with the mountains mirrored in it.
        int waterTop = Sky.lerpColor(Sky.lerpColor(sky[2], 0xFF000000, 0.35f), 0xFF2E6F7A, 0.2f * daylight);
        paint.setShader(new LinearGradient(0, lakeY, 0, TEX_H,
                new int[]{waterTop, Sky.lerpColor(sky[1], 0xFF000000, 0.5f), Sky.lerpColor(sky[0], 0xFF000000, 0.6f)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, lakeY, TEX_W, TEX_H, paint);
        paint.setShader(null);
        c.save();
        c.translate(0, lakeY);
        c.scale(1, -0.85f);
        c.translate(0, -lakeY);
        paint.setColor(argb(110, Sky.lerpColor(farTint, 0xFF000000, 0.25f)));
        c.drawPath(far, paint);
        paint.setColor(argb(140, Sky.lerpColor(midTint, 0xFF000000, 0.25f)));
        c.drawPath(mid, paint);
        paint.setColor(argb(170, nearTint));
        c.drawPath(near, paint);
        c.restore();
        // Glitter under the sun or the moon.
        float lightX = sunAlpha > 0.05f ? sunX : xFor(moon[1]);
        float light = Math.max(sunAlpha * (sun[0] > 0 ? 1 : 0), moonAlpha * 0.6f);
        int lightColor = sunAlpha > 0.05f ? sunColor : 0xFFE6ECF5;
        if (light > 0.05f) {
            Random g = new Random(5);
            for (int i = 0; i < 26; i++) {
                float y = lakeY + 3 + i * 6;
                float half = (5 + i * 1.6f) * (0.6f + g.nextFloat() * 0.8f);
                paint.setColor(argb(light * (200 - i * 6), lightColor));
                c.drawRect(lightX - half, y, lightX + half, y + 1.6f, paint);
            }
        }
        paint.setColor(argb(30 + 40 * daylight, 0xFFFFFFFF));
        c.drawRect(0, lakeY, TEX_W, lakeY + 1, paint);

        // Fog lying on the lake.
        if (weather != null && Weather.FOG.equals(weather.kind)) {
            int fog = Sky.lerpColor(0xFF1A2030, 0xFFD3DAE2, daylight);
            paint.setShader(new LinearGradient(0, lakeY - 150, 0, TEX_H,
                    new int[]{fog & 0x00FFFFFF, argb(190, fog), argb(130, fog)}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, lakeY - 150, TEX_W, TEX_H, paint);
            paint.setShader(null);
        }

        // A soft dark band behind the clock, so white text reads on a bright day sky.
        paint.setShader(new LinearGradient(0, 0, 0, TEX_H * 0.4f,
                new int[]{argb(60 + 70 * daylight, 0xFF000000), 0}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, TEX_W, TEX_H * 0.4f, paint);
        paint.setShader(null);

        // Cloud colour for the moving layer.
        int tint = Sky.lerpColor(Sky.lerpColor(0xFF2C3346, 0xFFF5F7FA, daylight), 0xFFF2AE92, glow * 0.75f);
        float storm = weather == null ? 0 : stormDarkness(weather.kind);
        cloudTint = Sky.lerpColor(tint, Sky.lerpColor(0xFF161A24, 0xFF707A86, daylight), storm);
    }

    private void drawMountains(Canvas c, float lakeY, int farTint, int midTint, int nearTint, float overcast) {
        paint.setColor(farTint);
        c.drawPath(far, paint);
        // Snow on the tallest far peaks, catching the sunrise and sunset.
        c.save();
        c.clipPath(far);
        int snow = Sky.lerpColor(Sky.lerpColor(0xFF39445E, 0xFFE9EFF6, daylight), 0xFFF6B68C, glow * 0.6f);
        snow = Sky.lerpColor(snow, farTint, overcast * 0.4f);
        float line = lakeY - 112;
        paint.setShader(new LinearGradient(0, line - 40, 0, line + 14, new int[]{argb(235, snow), snow & 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, TEX_W, line + 14, paint);
        paint.setShader(null);
        c.restore();
        paint.setColor(midTint);
        c.drawPath(mid, paint);
        paint.setColor(nearTint);
        c.drawPath(near, paint);
    }

    private void drawMoon(Canvas c, float x, float y, float r, double phase, float alpha) {
        paint.setShader(new RadialGradient(x, y, r * 4.5f, new int[]{argb(alpha * 70, 0xFFDDE4FF), 0x00DDE4FF},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 4.5f, paint);
        paint.setShader(null);
        paint.setColor(argb(alpha * 90, 0xFF2A2F3C));
        c.drawCircle(x, y, r, paint);
        double cos = Math.cos(phase * 2 * Math.PI);
        boolean waxing = phase < 0.5;
        boolean crescent = cos > 0;
        float ew = (float) (r * Math.abs(cos));
        Path p = new Path();
        p.moveTo(x, y - r);
        tmp.set(x - r, y - r, x + r, y + r);
        p.arcTo(tmp, -90, waxing ? 180 : -180, false);
        tmp.set(x - ew, y - r, x + ew, y + r);
        p.arcTo(tmp, 90, waxing ? (crescent ? -180 : 180) : (crescent ? 180 : -180), false);
        p.close();
        paint.setColor(argb(alpha * 255, 0xFFF3F1E7));
        c.drawPath(p, paint);
    }

    private void drawClouds(Canvas canvas, float dt, float scale) {
        if (cloudCount == 0) return;
        float speed = 2.5f + (weather == null ? 8 : weather.wind) * 0.18f;
        cloudPaint.setColorFilter(new LightingColorFilter(cloudTint & 0x00FFFFFF, 0));
        for (int i = 0; i < cloudCount; i++) {
            Bitmap sprite = cloudSprites[cloudSprite[i]];
            float w = sprite.getWidth() * cloudScale[i];
            float h = sprite.getHeight() * cloudScale[i];
            cloudX[i] += speed * dt * (0.7f + cloudScale[i] * 0.3f);
            if (cloudX[i] > TEX_W) cloudX[i] = -w - random.nextFloat() * 120;
            cloudPaint.setAlpha(Math.round(cloudAlpha[i] * 255));
            dst.set(cloudX[i] * scale, cloudY[i] * scale, (cloudX[i] + w) * scale, (cloudY[i] + h) * scale);
            canvas.drawBitmap(sprite, null, dst, cloudPaint);
        }
        dst.set(0, 0, getWidth(), getHeight());
    }

    private void drawParticles(Canvas canvas, float dt) {
        if (particleCount == 0) return;
        int w = getWidth(), h = getHeight();
        boolean snow = Weather.SNOW.equals(particleKind);
        rainPaint.setColor(snow ? argb(200, Sky.lerpColor(0xFF9AA6BC, 0xFFFFFFFF, daylight))
                : argb(snow ? 0 : 110, Sky.lerpColor(0xFF8C9AB4, 0xFFDCE6F2, daylight)));
        rainPaint.setStrokeWidth(2f);
        rainPaint.setStrokeCap(Paint.Cap.ROUND);
        float t = SystemClock.uptimeMillis() / 1000f;
        for (int i = 0; i < particleCount; i++) {
            py[i] += pv[i] * dt;
            px[i] += pv[i] * slant * dt;
            if (py[i] > h || px[i] > w + 60) {
                py[i] = -pl[i] - random.nextFloat() * h * 0.3f;
                px[i] = random.nextFloat() * (w + 300) - 300;
            }
            if (snow) {
                rainPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(px[i] + (float) Math.sin(t * 1.3f + i) * 14, py[i], pl[i], rainPaint);
            } else {
                canvas.drawLine(px[i], py[i], px[i] + pl[i] * slant, py[i] + pl[i], rainPaint);
            }
        }
    }

    private void drawLightning(Canvas canvas, float dt, long frame, boolean moving) {
        if (weather == null || !Weather.THUNDER.equals(weather.kind) || !moving) {
            flash = 0;
            return;
        }
        if (nextFlash == 0) nextFlash = frame + 3000;
        if (frame >= nextFlash) {
            flash = 1f;
            nextFlash = frame + 5000 + random.nextInt(9000);
        }
        if (flash > 0) {
            canvas.drawColor(argb(flash * 150, 0xFFE6EBFF));
            flash = Math.max(0f, flash - dt * 4f);
        }
    }

    // ---- weather to layers -------------------------------------------------------------------

    private float overcast() {
        if (weather == null) return 0f;
        float cover = weather.cloud / 100f;
        switch (weather.kind) {
            case Weather.CLEAR: return 0f;
            case Weather.PARTLY: return cover * 0.3f;
            case Weather.FOG: return 0.55f;
            default: return 0.45f + cover * 0.4f;
        }
    }

    private static float stormDarkness(String kind) {
        switch (kind) {
            case Weather.OVERCAST: return 0.35f;
            case Weather.DRIZZLE: return 0.45f;
            case Weather.RAIN: case Weather.SNOW: return 0.6f;
            case Weather.THUNDER: return 0.75f;
            default: return 0f;
        }
    }

    private void setupWeather() {
        String kind = weather == null ? "" : weather.kind;
        if (settings.isFirewatch()) {
            cloudCount = 0;
            particleKind = kind;
            float wind = weather == null ? 8 : weather.wind;
            slant = 0.1f + Math.min(0.35f, wind / 120f);
            switch (kind) {
                case Weather.DRIZZLE: particleCount = 70; break;
                case Weather.RAIN: particleCount = 150; break;
                case Weather.THUNDER: particleCount = 180; break;
                case Weather.SNOW: particleCount = 130; slant = wind / 400f; break;
                default: particleCount = 0;
            }
            if (reducedMotion) particleCount = 0;
            flash = 0;
            nextFlash = 0;
            resetParticles();
            return;
        }
        int count;
        float minScale, maxScale, alpha;
        switch (kind) {
            case Weather.CLEAR: count = 2; minScale = 0.7f; maxScale = 1.0f; alpha = 0.45f; break;
            case Weather.PARTLY: count = 5; minScale = 0.9f; maxScale = 1.3f; alpha = 0.85f; break;
            case Weather.OVERCAST: count = 10; minScale = 1.3f; maxScale = 1.9f; alpha = 0.9f; break;
            case Weather.FOG: count = 4; minScale = 1.0f; maxScale = 1.4f; alpha = 0.6f; break;
            case Weather.DRIZZLE: count = 8; minScale = 1.2f; maxScale = 1.7f; alpha = 0.88f; break;
            case Weather.RAIN: case Weather.THUNDER: case Weather.SNOW:
                count = 11; minScale = 1.4f; maxScale = 2.0f; alpha = 0.95f; break;
            default: count = 3; minScale = 0.8f; maxScale = 1.1f; alpha = 0.55f; break;
        }
        Random r = new Random(kind.hashCode());
        cloudCount = count;
        for (int i = 0; i < count; i++) {
            cloudSprite[i] = r.nextInt(cloudSprites.length);
            cloudScale[i] = minScale + r.nextFloat() * (maxScale - minScale);
            cloudAlpha[i] = alpha * (0.75f + r.nextFloat() * 0.25f);
            cloudX[i] = -150 + (TEX_W + 150) * (i + r.nextFloat() * 0.6f) / count;
            cloudY[i] = TEX_H * (0.02f + r.nextFloat() * 0.28f);
        }
        particleKind = kind;
        float wind = weather == null ? 8 : weather.wind;
        slant = 0.1f + Math.min(0.35f, wind / 120f);
        switch (kind) {
            case Weather.DRIZZLE: particleCount = 70; break;
            case Weather.RAIN: particleCount = 150; break;
            case Weather.THUNDER: particleCount = 180; break;
            case Weather.SNOW: particleCount = 130; slant = wind / 400f; break;
            default: particleCount = 0;
        }
        if (reducedMotion) particleCount = 0;
        flash = 0;
        nextFlash = 0;
        resetParticles();
    }

    private void resetParticles() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        boolean snow = Weather.SNOW.equals(particleKind);
        for (int i = 0; i < particleCount; i++) {
            px[i] = random.nextFloat() * (w + 300) - 300;
            py[i] = random.nextFloat() * h;
            pv[i] = snow ? 45 + random.nextFloat() * 65 : 1100 + random.nextFloat() * 500;
            pl[i] = snow ? 2 + random.nextFloat() * 2.5f : 18 + random.nextFloat() * 16;
        }
    }

    // ---- built once ---------------------------------------------------------------------------

    private void buildTerrain() {
        float lakeY = TEX_H * LAKE;
        far = ridge(new Random(7), lakeY, 42, 128, 0.58f);
        mid = ridge(new Random(21), lakeY, 22, 82, 0.55f);
        near = ridge(new Random(42), lakeY, 6, 42, 0.5f);
    }

    /** A mountain silhouette by midpoint displacement, lower at the edges where the sun rises and sets. */
    private static Path ridge(Random r, float base, float minHeight, float range, float roughness) {
        int n = 129;
        float[] h = new float[n];
        h[0] = r.nextFloat();
        h[n - 1] = r.nextFloat();
        float disp = 1f;
        for (int step = n - 1; step > 1; step /= 2) {
            for (int i = step / 2; i < n; i += step) {
                h[i] = (h[i - step / 2] + h[i + step / 2]) / 2 + (r.nextFloat() * 2 - 1) * disp;
            }
            disp *= roughness;
        }
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float v : h) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        Path p = new Path();
        p.moveTo(0, base + 2);
        for (int i = 0; i < n; i++) {
            float x = i * TEX_W / (float) (n - 1);
            float envelope = 0.4f + 0.6f * (float) Math.sin(Math.PI * x / TEX_W);
            float height = (minHeight + (h[i] - lo) / (hi - lo) * range) * envelope;
            p.lineTo(x, base - height);
        }
        p.lineTo(TEX_W, base + 2);
        p.close();
        return p;
    }

    private void buildStars() {
        Random r = new Random(3);
        int n = 170;
        starX = new float[n];
        starY = new float[n];
        starR = new float[n];
        starA = new float[n];
        for (int i = 0; i < n; i++) {
            starX[i] = r.nextFloat() * TEX_W;
            starY[i] = (float) Math.pow(r.nextFloat(), 1.5) * TEX_H * LAKE * 0.8f;
            starR[i] = 0.5f + r.nextFloat() * 0.9f;
            starA[i] = 0.35f + r.nextFloat() * 0.65f;
        }
    }

    private void buildClouds() {
        if (cloudSprites != null) return;
        cloudSprites = new Bitmap[3];
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int s = 0; s < cloudSprites.length; s++) {
            int w = 260, h = 100;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Random r = new Random(100 + s);
            for (int i = 0; i < 16; i++) {
                float cx = w * (0.14f + r.nextFloat() * 0.72f);
                float middle = 1 - Math.abs(cx / w - 0.5f) * 1.1f;
                float rad = h * (0.2f + r.nextFloat() * 0.22f) * (0.6f + middle * 0.6f);
                float cy = h * 0.62f - rad * 0.35f * r.nextFloat();
                p.setShader(new RadialGradient(cx, cy, rad, new int[]{0xC8FFFFFF, 0x80FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rad, p);
            }
            cloudSprites[s] = b;
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** The sun's or moon's azimuth to x: east on the left, west on the right, facing south. */
    private static float xFor(double azimuth) {
        return TEX_W * (0.06f + 0.88f * LiveSky.clamp((float) ((azimuth - 90) / 180)));
    }

    private static float yFor(double elevation, float horizon) {
        return horizon - (float) Math.sin(Math.toRadians(elevation)) * (horizon - 36);
    }

    private static int argb(float alpha, int color) {
        int a = Math.max(0, Math.min(255, Math.round(alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
