package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WeatherTest {

    @Test
    public void mapsWmoCodesCorrectly() {
        assertEquals(Weather.CLEAR, Weather.kindOf(0));
        assertEquals(Weather.PARTLY, Weather.kindOf(1));
        assertEquals(Weather.PARTLY, Weather.kindOf(2));
        assertEquals(Weather.OVERCAST, Weather.kindOf(3));
        assertEquals(Weather.FOG, Weather.kindOf(45));
        assertEquals(Weather.DRIZZLE, Weather.kindOf(51));
        assertEquals(Weather.RAIN, Weather.kindOf(61));
        assertEquals(Weather.RAIN, Weather.kindOf(80));
        assertEquals(Weather.SNOW, Weather.kindOf(71));
        assertEquals(Weather.THUNDER, Weather.kindOf(95));
    }

    @Test
    public void parsesOpenMeteoJson() {
        String json = "{\n"
                + "  \"current\": {\n"
                + "    \"temperature_2m\": 29.4,\n"
                + "    \"weather_code\": 0,\n"
                + "    \"cloud_cover\": 10,\n"
                + "    \"wind_speed_10m\": 12.5\n"
                + "  }\n"
                + "}";
        Weather w = Weather.Client.parse(json, 1000L);
        assertNotNull(w);
        assertEquals(Weather.CLEAR, w.kind);
        assertEquals(29.4f, w.temperature, 0.01f);
        assertEquals(10, w.cloud);
        assertEquals(12.5f, w.wind, 0.01f);
        assertEquals(1000L, w.fetchedAt);
    }

    @Test
    public void formatsWeatherChip() {
        Weather w = new Weather(Weather.CLEAR, 28.2f, 0, 5, 0);
        String dayChip = w.chip(false, false);
        assertTrue(dayChip.contains("28°C"));
        assertTrue(dayChip.contains("\u2600")); // ☀

        String nightChip = w.chip(true, false);
        assertTrue(nightChip.contains("28°C"));
        assertTrue(nightChip.contains("\u263D")); // ☽
    }

    @Test
    public void debugWeatherProducesPlausibleValues() {
        Weather w = Weather.debug(Weather.RAIN);
        assertNotNull(w);
        assertEquals(Weather.RAIN, w.kind);
        assertTrue(w.cloud >= 90);
    }
}
