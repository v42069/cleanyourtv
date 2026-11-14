package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for the live sky, from Open-Meteo (free, no key, no account). Fetched on a background
 * thread at most every 30 minutes while Home is showing, and kept in a file so the sky has weather
 * straight after boot or with the network down.
 */
final class Weather {
    static final String CLEAR = "clear", PARTLY = "partly_cloudy", OVERCAST = "overcast", FOG = "fog",
            DRIZZLE = "drizzle", RAIN = "rain", THUNDER = "thunder", SNOW = "snow";

    final String kind;
    final float temperature;
    /** Cloud cover in percent. */
    final int cloud;
    /** Wind speed in km/h. */
    final float wind;
    final long fetchedAt;

    Weather(String kind, float temperature, int cloud, float wind, long fetchedAt) {
        this.kind = kind;
        this.temperature = temperature;
        this.cloud = cloud;
        this.wind = wind;
        this.fetchedAt = fetchedAt;
    }

    /** The WMO weather interpretation code Open-Meteo returns, folded into the sky's few states. */
    static String kindOf(int code) {
        switch (code) {
            case 0: return CLEAR;
            case 1: case 2: return PARTLY;
            case 3: return OVERCAST;
            case 45: case 48: return FOG;
            case 51: case 53: case 55: case 56: case 57: return DRIZZLE;
            case 61: case 63: case 65: case 66: case 67: case 80: case 81: case 82: return RAIN;
            case 71: case 73: case 75: case 77: case 85: case 86: return SNOW;
            case 95: case 96: case 99: return THUNDER;
            default: return code > 3 ? OVERCAST : CLEAR;
        }
    }

    /** A made-up state for the debug key, with a plausible cloud cover. */
    @Nullable
    static Weather debug(String kind) {
        int cloud;
        switch (kind) {
            case CLEAR: cloud = 5; break;
            case PARTLY: cloud = 45; break;
            case FOG: cloud = 60; break;
            case DRIZZLE: cloud = 80; break;
            case OVERCAST: case RAIN: case THUNDER: case SNOW: cloud = 95; break;
            default: return null;
        }
        return new Weather(kind, kind.equals(SNOW) ? -2 : 27, cloud, kind.equals(THUNDER) ? 30 : 12, System.currentTimeMillis());
    }

    /** "☀ 28°" for the header. */
    String chip(boolean night, boolean fahrenheit) {
        float t = fahrenheit ? temperature * 9 / 5 + 32 : temperature;
        return icon(night) + "  " + Math.round(t) + "°" + (fahrenheit ? "F" : "C");
    }

    private String icon(boolean night) {
        switch (kind) {
            case CLEAR: return night ? "\u263D" : "\u2600";      // ☽ ☀
            case PARTLY: return night ? "\u263D" : "\u26C5";     // ☽ ⛅
            case OVERCAST: return "\u2601";                      // ☁
            case FOG: return "\u2248";                           // ≈
            case DRIZZLE: case RAIN: return "\u2602";            // ☂
            case THUNDER: return "\u26A1";                       // ⚡
            case SNOW: return "\u2744";                          // ❄
            default: return "";
        }
    }

    // ---- fetching -----------------------------------------------------------------------------

    interface Listener {
        void onWeather(Weather weather);
    }

    static final class Client {
        private static final long REFRESH_MS = 30 * 60_000L;
        private static final long RETRY_MS = 5 * 60_000L;

        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private final File cache;
        private final String url;
        private final Listener listener;
        @Nullable private Weather current;
        private long lastAttempt;
        private boolean inFlight;

        Client(Context context, double latitude, double longitude, Listener listener) {
            this.cache = new File(context.getFilesDir(), "weather.json");
            this.listener = listener;
            this.url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,weather_code,cloud_cover,wind_speed_10m&timezone=auto", latitude, longitude);
            this.current = read(cache);
        }

        @Nullable Weather current() {
            return current;
        }

        /** Fetches when the last weather is older than 30 minutes; cheap to call every minute. */
        void refreshIfStale() {
            long now = System.currentTimeMillis();
            if (inFlight || now - lastAttempt < RETRY_MS) return;
            if (current != null && now - current.fetchedAt < REFRESH_MS) return;
            inFlight = true;
            lastAttempt = now;
            executor.execute(() -> {
                Weather fetched = null;
                try {
                    fetched = parse(download(url), System.currentTimeMillis());
                    if (fetched != null) write(cache, fetched);
                } catch (Exception ignored) {
                    // Offline or the service is down: keep the last weather and try again in five minutes.
                }
                Weather result = fetched;
                main.post(() -> {
                    inFlight = false;
                    if (result != null) {
                        current = result;
                        listener.onWeather(result);
                    }
                });
            });
        }

        void shutdown() {
            executor.shutdownNow();
        }

        private static String download(String address) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            } finally {
                c.disconnect();
            }
        }

        @Nullable
        static Weather parse(String body, long fetchedAt) {
            try {
                JSONObject cur = new JSONObject(body).getJSONObject("current");
                return new Weather(kindOf(cur.getInt("weather_code")), (float) cur.getDouble("temperature_2m"),
                        cur.optInt("cloud_cover", 0), (float) cur.optDouble("wind_speed_10m", 8), fetchedAt);
            } catch (Throwable t) {
                try {
                    int code = findInt(body, "\"weather_code\":");
                    float temp = findFloat(body, "\"temperature_2m\":");
                    int cloud = findInt(body, "\"cloud_cover\":");
                    float wind = findFloat(body, "\"wind_speed_10m\":");
                    return new Weather(kindOf(code), temp, cloud, wind, fetchedAt);
                } catch (Exception e) {
                    return null;
                }
            }
        }

        private static int findInt(String text, String key) {
            int idx = text.indexOf(key);
            if (idx < 0) return 0;
            int start = idx + key.length();
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
            int end = start;
            while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '-')) end++;
            return Integer.parseInt(text.substring(start, end));
        }

        private static float findFloat(String text, String key) {
            int idx = text.indexOf(key);
            if (idx < 0) return 0f;
            int start = idx + key.length();
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
            int end = start;
            while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '-' || text.charAt(end) == '.')) end++;
            return Float.parseFloat(text.substring(start, end));
        }

        private static void write(File file, Weather w) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                JSONObject cur = new JSONObject();
                cur.put("weather_code", codeOf(w.kind));
                cur.put("temperature_2m", w.temperature);
                cur.put("cloud_cover", w.cloud);
                cur.put("wind_speed_10m", w.wind);
                JSONObject o = new JSONObject();
                o.put("current", cur);
                o.put("fetched_at", w.fetchedAt);
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }

        @Nullable
        private static Weather read(File file) {
            if (!file.isFile()) return null;
            try (FileInputStream in = new FileInputStream(file)) {
                String body = new String(readAll(in), StandardCharsets.UTF_8);
                return parse(body, new JSONObject(body).optLong("fetched_at", 0));
            } catch (Exception e) {
                return null;
            }
        }

        private static int codeOf(String kind) {
            switch (kind) {
                case PARTLY: return 2;
                case OVERCAST: return 3;
                case FOG: return 45;
                case DRIZZLE: return 53;
                case RAIN: return 63;
                case SNOW: return 73;
                case THUNDER: return 95;
                default: return 0;
            }
        }

        private static byte[] readAll(InputStream in) throws java.io.IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
