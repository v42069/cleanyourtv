package com.gathin.tvhome;

import android.content.res.Resources;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.TimeZone;

/**
 * Theme keys for the live day/night wallpaper (LiveSkyView), read from a theme's config.json:
 * <pre>
 *   "live_sky": true,                      draw the live sky instead of the photo wallpaper
 *   "live_sky_latitude": "19.0760",        where the sun, moon and weather are (defaults to config.xml latitude)
 *   "live_sky_longitude": "72.8777",
 *   "weather": true,                       fetch the current weather from Open-Meteo every 30 minutes
 *   "weather_chip": true,                  show "☀ 28°" under the date
 *   "weather_fahrenheit": false,
 *   "live_sky_timelapse_seconds": 0,       debug: play a whole day in this many seconds
 *   "live_sky_debug_minute": -1,           debug: freeze the clock at this minute of the day
 *   "live_sky_debug_weather": ""           debug: clear, partly_cloudy, overcast, fog, drizzle, rain, thunder, snow
 * </pre>
 */
final class LiveSkySettings {
    static final LiveSkySettings OFF = new LiveSkySettings(false, "procedural", 0, 0, false, false, false, 0, -1, "");

    final boolean enabled;
    final String style;
    final double latitude;
    final double longitude;
    final boolean weather;
    final boolean weatherChip;
    final boolean fahrenheit;
    final int timelapseSeconds;
    final int debugMinute;
    final String debugWeather;

    boolean isFirewatch() {
        return "firewatch".equalsIgnoreCase(style);
    }

    private LiveSkySettings(boolean enabled, String style, double latitude, double longitude, boolean weather, boolean weatherChip,
                            boolean fahrenheit, int timelapseSeconds, int debugMinute, String debugWeather) {
        this.enabled = enabled;
        this.style = style == null || style.trim().isEmpty() ? "procedural" : style.trim();
        this.latitude = latitude;
        this.longitude = longitude;
        this.weather = weather;
        this.weatherChip = weatherChip;
        this.fahrenheit = fahrenheit;
        this.timelapseSeconds = timelapseSeconds;
        this.debugMinute = debugMinute;
        this.debugWeather = debugWeather;
    }

    static LiveSkySettings from(@Nullable JSONObject json, Resources res) {
        if (json == null || !json.optBoolean("live_sky", false)) return OFF;
        String style = json.optString("live_sky_style", json.optBoolean("firewatch", false) ? "firewatch" : "procedural").trim();
        double lat = number(json.optString("live_sky_latitude", ""), number(res.getString(R.string.latitude), Double.NaN));
        double lon = number(json.optString("live_sky_longitude", ""), number(res.getString(R.string.longitude), Double.NaN));
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            // No place: a latitude in the tropics and the longitude of the time zone keep sunrise and sunset close.
            lat = 20;
            lon = TimeZone.getDefault().getRawOffset() / 3_600_000.0 * 15;
        }
        lat = Math.max(-89, Math.min(89, lat));
        lon = Math.max(-180, Math.min(180, lon));
        boolean weather = json.optBoolean("weather", true);
        return new LiveSkySettings(true, style, lat, lon, weather, weather && json.optBoolean("weather_chip", true),
                json.optBoolean("weather_fahrenheit", false),
                Math.max(0, Math.min(3600, json.optInt("live_sky_timelapse_seconds", 0))),
                Math.max(-1, Math.min(1439, json.optInt("live_sky_debug_minute", -1))),
                json.optString("live_sky_debug_weather", "").trim());
    }

    private static double number(String text, double fallback) {
        if (text == null || text.trim().isEmpty()) return fallback;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
