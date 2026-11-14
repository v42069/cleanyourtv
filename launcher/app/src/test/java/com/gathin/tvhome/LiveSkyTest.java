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
}
