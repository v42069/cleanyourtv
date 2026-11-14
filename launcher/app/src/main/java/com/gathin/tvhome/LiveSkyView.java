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

    private static final int MESH_W = 12;
    private static final int MESH_H = 8;
    private final float[] meshVerts = new float[(MESH_W + 1) * (MESH_H + 1) * 2];
    private final Path birdPath = new Path();

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
    private long lastFrame;

    public LiveSkyView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    // ---- what the activity calls ----------------------------------------------------------------

    void configure(LiveSkySettings s, boolean reducedMotion) {
        settings = s;
        this.reducedMotion = reducedMotion;
        setVisibility(s.enabled ? VISIBLE : GONE);
        if (!s.enabled) return;
        startReal = System.currentTimeMillis();
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
        if (moving && animated) postInvalidateDelayed(FRAME_MS);
    }

    private void updateFirewatchMesh(float t) {
        int w = getWidth();
        int h = getHeight();
        for (int j = 0; j <= MESH_H; j++) {
            float y = j * (float) h / MESH_H;
            float normY = y / h;
            // The trees start around 38% height down to bottom (100%)
            float swayWeight = normY < 0.38f ? 0f : (float) Math.pow((normY - 0.38f) / 0.62f, 1.4);
            for (int i = 0; i <= MESH_W; i++) {
                int idx = (j * (MESH_W + 1) + i) * 2;
                float baseX = i * (float) w / MESH_W;
                // Realistic compound sinusoidal wind sway
                float sway = swayWeight * 6.5f * (
                        (float) Math.sin(t * 1.4f + i * 0.5f) * 0.7f +
                        (float) Math.sin(t * 2.3f + i * 0.3f) * 0.3f
                );
                meshVerts[idx] = baseX + sway;
                meshVerts[idx + 1] = y;
            }
        }
    }

    private void drawFirewatchMoving(Canvas canvas, float dt, long frame, boolean moving) {
        float t = (frame - startReal) / 1000f;
        if (moving) {
            updateFirewatchMesh(t);
            canvas.drawBitmapMesh(scene, MESH_W, MESH_H, meshVerts, 0, null, 0, bitmapPaint);
        } else {
            canvas.drawBitmap(scene, src, dst, bitmapPaint);
        }

        double sunElev = LiveSky.sun(now(), settings.latitude, settings.longitude)[0];

        // 1. Twinkling stars at night
        if (sunElev < -6.0) {
            drawTwinklingStars(canvas, t);
        }

        // 2. Watchtower cabin glowing / breathing lantern light
        if (sunElev < 4.0) {
            float cx = getWidth() * 0.50f;
            float cy = getHeight() * 0.422f;
            float pulse = 0.70f + 0.30f * (float) Math.sin(t * 2.4f) + 0.12f * (float) Math.sin(t * 5.1f);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx, cy, 24f,
                    new int[]{argb(pulse * 190, 0xFFFFE082), argb(pulse * 70, 0xFFFFB300), 0},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, 24f, paint);
            paint.setShader(null);
        }

        // 3. Valley mist drifting through the canyon
        drawFirewatchMist(canvas, dt, t, sunElev);

        // 4. Soaring hawk across the canyon during daylight / sunset
        if (sunElev >= -3.0) {
            drawFirewatchHawk(canvas, t);
        }

        // 5. Floating ambient dust spores / embers
        drawFirewatchMotes(canvas, dt, t, sunElev);

        // 6. Rain/Snow/Thunder weather particles if weather is active
        drawParticles(canvas, dt);
        drawLightning(canvas, dt, frame, moving);
    }

    private void drawTwinklingStars(Canvas canvas, float t) {
        if (starX == null || starX.length == 0) return;
        int w = getWidth();
        int h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < Math.min(60, starX.length); i++) {
            float tw = (float) Math.sin(t * 2.5f + i * 1.7f);
            if (tw > 0.1f) {
                float sx = starX[i] * w / (float) TEX_W;
                float sy = starY[i] * h / (float) TEX_H * 0.75f;
                int alpha = (int) (tw * starA[i] * 210);
                paint.setColor(argb(alpha, 0xFFFFFFFF));
                canvas.drawCircle(sx, sy, starR[i] * 1.2f, paint);
            }
        }
    }

    private void drawFirewatchMist(Canvas canvas, float dt, float t, double sunElev) {
        int w = getWidth();
        int h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        int mistColor;
        if (sunElev < -6.0) {
            mistColor = 0x221B273E; // Deep night blue mist
        } else if (sunElev < 10.0) {
            mistColor = 0x30FFA866; // Warm sunset glow mist
        } else {
            mistColor = 0x26E0ECF8; // Daylight white mist
        }
        for (int m = 0; m < 2; m++) {
            float speed = (m == 0 ? 12f : 18f);
            float mx = ((t * speed + m * 480f) % (w + 400f)) - 200f;
            float my = h * (0.50f + m * 0.12f);
            tmp.set(mx - 240, my - 45, mx + 240, my + 45);
            paint.setShader(new RadialGradient(mx, my, 220f,
                    new int[]{mistColor, 0}, null, Shader.TileMode.CLAMP));
            canvas.drawOval(tmp, paint);
        }
        paint.setShader(null);
    }

    private void drawFirewatchHawk(Canvas canvas, float t) {
        int w = getWidth();
        int h = getHeight();
        float cycle = (t * 0.035f) % 1.0f;
        float bx = -50f + (w + 100f) * cycle;
        float by = h * 0.28f + (float) Math.sin(cycle * Math.PI * 4) * 20f;
        float flap = (float) Math.sin(t * 6f);
        birdPath.reset();
        birdPath.moveTo(bx, by);
        birdPath.quadTo(bx - 10, by - 4 + flap * 3, bx - 18, by + flap * 5);
        birdPath.quadTo(bx - 8, by + 1, bx, by);
        birdPath.quadTo(bx + 10, by - 4 + flap * 3, bx + 18, by + flap * 5);
        birdPath.quadTo(bx + 8, by + 1, bx, by);
        birdPath.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC111824);
        canvas.drawPath(birdPath, paint);
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
