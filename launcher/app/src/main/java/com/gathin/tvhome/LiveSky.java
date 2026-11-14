package com.gathin.tvhome;

/**
 * Pure maths for the live sky: where the sun and moon are and what colour the sky is. No Android
 * types, so it runs in JVM tests. Positions use the low-precision solar formulas from the
 * Astronomical Almanac (good to about a degree, plenty for a backdrop).
 */
final class LiveSky {
    private LiveSky() {
    }

    /** A known new moon, 2000-01-06 18:14 UTC, and the mean synodic month. */
    private static final long NEW_MOON_MS = 947_182_440_000L;
    private static final double SYNODIC_DAYS = 29.530588853;

    /** {elevation, azimuth} in degrees of the sun; azimuth from north, east positive. */
    static double[] sun(long epochMs, double latitude, double longitude) {
        return position(epochMs, latitude, longitude, 0);
    }

    /**
     * {elevation, azimuth} of the moon, approximated as a body on the sun's path that trails it by the
     * phase angle: a full moon is opposite the sun, a first quarter rises at noon. It ignores the moon's
     * 5 degree tilt, which a backdrop does not need.
     */
    static double[] moon(long epochMs, double latitude, double longitude) {
        return position(epochMs, latitude, longitude, moonPhase(epochMs) * 2 * Math.PI);
    }

    /** Moon phase, 0 = new, 0.25 = first quarter, 0.5 = full, 0.75 = last quarter. */
    static double moonPhase(long epochMs) {
        double days = (epochMs - NEW_MOON_MS) / 86_400_000.0;
        double p = (days / SYNODIC_DAYS) % 1.0;
        return p < 0 ? p + 1 : p;
    }

    private static double[] position(long epochMs, double latitude, double longitude, double raOffset) {
        double n = epochMs / 86_400_000.0 + 2440587.5 - 2451545.0;
        double meanLong = Math.toRadians(norm(280.460 + 0.9856474 * n));
        double anomaly = Math.toRadians(norm(357.528 + 0.9856003 * n));
        double eclLong = meanLong + Math.toRadians(1.915 * Math.sin(anomaly) + 0.020 * Math.sin(2 * anomaly));
        double obliquity = Math.toRadians(23.439 - 0.0000004 * n);
        double ra = Math.atan2(Math.cos(obliquity) * Math.sin(eclLong), Math.cos(eclLong)) + raOffset;
        double dec = Math.asin(Math.sin(obliquity) * Math.sin(eclLong));
        double gmstHours = (18.697374558 + 24.06570982441908 * n) % 24;
        double hourAngle = Math.toRadians(gmstHours * 15 + longitude) - ra;
        double lat = Math.toRadians(latitude);
        double elevation = Math.asin(Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(hourAngle));
        double azimuth = Math.atan2(-Math.sin(hourAngle), Math.tan(dec) * Math.cos(lat) - Math.sin(lat) * Math.cos(hourAngle));
        return new double[]{Math.toDegrees(elevation), norm(Math.toDegrees(azimuth))};
    }

    private static double norm(double degrees) {
        double d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }

    // ---- palette ------------------------------------------------------------------------------

    /** Sky colours by the sun's elevation: top of the sky, the middle, and the horizon. */
    private static final double[] ELEVATIONS = {-90, -18, -12, -6, -2, 2, 8, 20, 90};
    private static final int[][] STOPS = {
            {0xFF03050D, 0xFF070D1E, 0xFF0D1830},
            {0xFF03050D, 0xFF070D1E, 0xFF0D1830},
            {0xFF060B1C, 0xFF101A38, 0xFF1E2A50},
            {0xFF0D1636, 0xFF2D2E5E, 0xFF6B4A78},
            {0xFF16255A, 0xFF5B4C86, 0xFFE07A5F},
            {0xFF2A4C8A, 0xFF9C7FA6, 0xFFFFA764},
            {0xFF2D63A8, 0xFF7FA6CF, 0xFFF3C99A},
            {0xFF2462B0, 0xFF5E9AD6, 0xFFA9D2EE},
            {0xFF1A56A6, 0xFF4A8CD0, 0xFF96C7EC},
    };
    private static final int[] OVERCAST_DAY = {0xFF56616E, 0xFF76818D, 0xFF959FA9};
    private static final int[] OVERCAST_NIGHT = {0xFF07090F, 0xFF0E121A, 0xFF171B24};

    /** {top, mid, horizon} for the sun's elevation, greyed by {@code overcast} (0 clear to 1 fully grey). */
    static int[] palette(double elevation, float overcast) {
        int i = 0;
        while (i < ELEVATIONS.length - 2 && elevation > ELEVATIONS[i + 1]) i++;
        float t = (float) ((elevation - ELEVATIONS[i]) / (ELEVATIONS[i + 1] - ELEVATIONS[i]));
        float day = daylight(elevation);
        int[] out = new int[3];
        for (int k = 0; k < 3; k++) {
            int clear = Sky.lerpColor(STOPS[i][k], STOPS[i + 1][k], t);
            int grey = Sky.lerpColor(OVERCAST_NIGHT[k], OVERCAST_DAY[k], day);
            out[k] = Sky.lerpColor(clear, grey, overcast);
        }
        return out;
    }

    /** 0 at night to 1 in daylight, across civil twilight. */
    static float daylight(double elevation) {
        return clamp((float) ((elevation + 6) / 12));
    }

    /** 0 to 1: how strong the warm sunrise/sunset glow is (peaks with the sun on the horizon). */
    static float glow(double elevation) {
        return clamp((float) (1 - Math.abs(elevation - 1) / 8));
    }

    /** 0 to 1: how visible the stars are. */
    static float stars(double elevation) {
        return clamp((float) ((-elevation - 4) / 8));
    }

    static class FirewatchPhase {
        final int primary;
        final int secondary;
        final float blend;

        FirewatchPhase(int primary, int secondary, float blend) {
            this.primary = primary;
            this.secondary = secondary;
            this.blend = blend;
        }
    }

    /**
     * Determines which Firewatch landscape phase (0_night .. 7_dusk) and crossfade blend factor
     * to show, based on solar elevation and whether the sun is rising (morning) or setting (evening).
     */
    static FirewatchPhase firewatchPhase(double elevation, boolean rising) {
        if (rising) {
            if (elevation < -12.0) {
                return new FirewatchPhase(0, 0, 0f); // 0_night
            } else if (elevation < -1.0) {
                float t = (float) ((elevation - (-12.0)) / (-1.0 - (-12.0)));
                return new FirewatchPhase(0, 1, clamp(t)); // 0_night -> 1_dawn
            } else if (elevation < 6.0) {
                float t = (float) ((elevation - (-1.0)) / (6.0 - (-1.0)));
                return new FirewatchPhase(1, 2, clamp(t)); // 1_dawn -> 2_sunrise
            } else if (elevation < 18.0) {
                float t = (float) ((elevation - 6.0) / (18.0 - 6.0));
                return new FirewatchPhase(2, 3, clamp(t)); // 2_sunrise -> 3_early_morning
            } else if (elevation < 26.0) {
                float t = (float) ((elevation - 18.0) / (26.0 - 18.0));
                return new FirewatchPhase(3, 4, clamp(t)); // 3_early_morning -> 4_day
            } else {
                return new FirewatchPhase(4, 4, 0f); // 4_day
            }
        } else {
            if (elevation >= 26.0) {
                return new FirewatchPhase(4, 4, 0f); // 4_day
            } else if (elevation >= 18.0) {
                float t = (float) ((26.0 - elevation) / (26.0 - 18.0));
                return new FirewatchPhase(4, 5, clamp(t)); // 4_day -> 5_golden_hour
            } else if (elevation >= 6.0) {
                float t = (float) ((18.0 - elevation) / (18.0 - 6.0));
                return new FirewatchPhase(5, 6, clamp(t)); // 5_golden_hour -> 6_sunset
            } else if (elevation >= -1.0) {
                float t = (float) ((6.0 - elevation) / (6.0 - (-1.0)));
                return new FirewatchPhase(6, 7, clamp(t)); // 6_sunset -> 7_dusk
            } else if (elevation >= -12.0) {
                float t = (float) ((-1.0 - elevation) / (-1.0 - (-12.0)));
                return new FirewatchPhase(7, 0, clamp(t)); // 7_dusk -> 0_night
            } else {
                return new FirewatchPhase(0, 0, 0f); // 0_night
            }
        }
    }

    static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
