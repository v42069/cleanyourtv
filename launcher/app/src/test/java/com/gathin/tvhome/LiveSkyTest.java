package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LiveSkyTest {

    // Mumbai coordinates: 19.0760 N, 72.8777 E
    private static final double MUMBAI_LAT = 19.0760;
    private static final double MUMBAI_LON = 72.8777;

    @Test
    public void sunIsHighAtNoonInMumbai() {
        // 2026-03-21 12:30 IST is about 07:00 UTC
        long noonEpochMs = 1774076400000L;
        double[] sun = LiveSky.sun(noonEpochMs, MUMBAI_LAT, MUMBAI_LON);
        assertTrue("elevation at noon should be > 60 degrees, got " + sun[0], sun[0] > 60.0);
    }

    @Test
    public void sunIsBelowHorizonAtMidnight() {
        // 2026-03-21 00:00 IST is 1774031400000L
        long midnightEpochMs = 1774031400000L;
        double[] sun = LiveSky.sun(midnightEpochMs, MUMBAI_LAT, MUMBAI_LON);
        assertTrue("elevation at midnight should be negative, got " + sun[0], sun[0] < -30.0);
    }

    @Test
    public void moonPhaseReturnsNormalizedRange() {
        long epoch = System.currentTimeMillis();
        double phase = LiveSky.moonPhase(epoch);
        assertTrue("phase should be >= 0", phase >= 0.0);
        assertTrue("phase should be <= 1", phase <= 1.0);
    }

    @Test
    public void paletteGeneratesThreeValidColors() {
        int[] palette = LiveSky.palette(15.0, 0f);
        assertEquals(3, palette.length);
        // Alpha should be 0xFF for all 3 stops
        for (int c : palette) {
            assertEquals(0xFF, (c >>> 24) & 0xFF);
        }
    }

    @Test
    public void firewatchPhaseAtDeepNight() {
        LiveSky.FirewatchPhase phase = LiveSky.firewatchPhase(-30.0, false);
        assertEquals(0, phase.primary); // 0_night
        assertEquals(0, phase.secondary);
        assertEquals(0f, phase.blend, 0.001f);
    }

    @Test
    public void firewatchPhaseAtMidday() {
        LiveSky.FirewatchPhase phase = LiveSky.firewatchPhase(45.0, true);
        assertEquals(4, phase.primary); // 4_day
        assertEquals(4, phase.secondary);
        assertEquals(0f, phase.blend, 0.001f);
    }

    @Test
    public void firewatchPhaseAtSunset() {
        // Evening sunset: elevation around 0 degrees, setting
        LiveSky.FirewatchPhase phase = LiveSky.firewatchPhase(0.0, false);
        assertEquals(6, phase.primary); // 6_sunset
        assertEquals(7, phase.secondary); // transitioning to 7_dusk
        assertTrue("blend should be between 0 and 1", phase.blend >= 0f && phase.blend <= 1f);
    }

    @Test
    public void firewatchPhaseAtSunrise() {
        // Morning sunrise: elevation around 2 degrees, rising
        LiveSky.FirewatchPhase phase = LiveSky.firewatchPhase(2.0, true);
        assertEquals(1, phase.primary); // 1_dawn
        assertEquals(2, phase.secondary); // transitioning to 2_sunrise
        assertTrue("blend should be between 0 and 1", phase.blend >= 0f && phase.blend <= 1f);
    }
}
