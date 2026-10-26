package com.alexbrazier.icloudframe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Current weather from MET Norway (no API key needed), as in the web app's server/weather.ts. */
final class Weather {
    final double temperature, high, low;
    final String condition;
    final int rainChance;

    private Weather(double temperature, double high, double low, String condition, int rainChance) {
        this.temperature = temperature;
        this.high = high;
        this.low = low;
        this.condition = condition;
        this.rainChance = rainChance;
    }

    private static final Map<String, String> CONDITIONS = new HashMap<>();

    static {
        String[][] pairs = {
                {"clearsky", "Clear sky"}, {"fair", "Fair"}, {"partlycloudy", "Partly cloudy"},
                {"cloudy", "Cloudy"}, {"fog", "Fog"}, {"rain", "Rain"}, {"lightrain", "Light rain"},
                {"heavyrain", "Heavy rain"}, {"rainshowers", "Rain showers"},
                {"lightrainshowers", "Light rain showers"}, {"heavyrainshowers", "Heavy rain showers"},
                {"sleet", "Sleet"}, {"lightsleet", "Light sleet"}, {"heavysleet", "Heavy sleet"},
                {"sleetshowers", "Sleet showers"}, {"snow", "Snow"}, {"lightsnow", "Light snow"},
                {"heavysnow", "Heavy snow"}, {"snowshowers", "Snow showers"},
                {"lightsnowshowers", "Light snow showers"}, {"heavysnowshowers", "Heavy snow showers"},
                {"rainandthunder", "Rain and thunder"}, {"heavyrainandthunder", "Heavy rain and thunder"},
                {"lightrainandthunder", "Light rain and thunder"}, {"rainshowersandthunder", "Thundery showers"},
                {"snowandthunder", "Snow and thunder"}, {"sleetandthunder", "Sleet and thunder"},
        };
        for (String[] p : pairs) CONDITIONS.put(p[0], p[1]);
    }

    /** Looks up a place name with Open-Meteo's free geocoding: {label, lat, lon}, or null. */
    static String[] geocode(String place) throws IOException {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                + java.net.URLEncoder.encode(place.trim(), "UTF-8");
        Net.Response res = Net.get(url, 256 * 1024);
        if (res.status != 200) throw new IOException("Place search returned HTTP " + res.status);
        try {
            JSONArray results = new JSONObject(res.text()).optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            JSONObject p = results.getJSONObject(0);
            StringBuilder label = new StringBuilder(p.getString("name"));
            for (String k : new String[] {"admin1", "country"}) {
                String v = p.optString(k);
                if (!v.isEmpty() && !v.equals(p.getString("name"))) label.append(", ").append(v);
            }
            return new String[] {label.toString(), String.format(Locale.US, "%.4f", p.getDouble("latitude")),
                    String.format(Locale.US, "%.4f", p.getDouble("longitude"))};
        } catch (JSONException e) {
            throw new IOException("Unexpected place data");
        }
    }

    static Weather fetch(String lat, String lon) throws IOException {
        double la = Double.parseDouble(lat), lo = Double.parseDouble(lon);
        // MET Norway asks for at most 4 decimals, which also keeps their cache hits high
        String url = String.format(Locale.US,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", la, lo);
        Net.Response res = Net.get(url, 4 * 1024 * 1024);
        if (res.status != 200) throw new IOException("Weather returned HTTP " + res.status);
        try {
            JSONArray series = new JSONObject(res.text()).getJSONObject("properties").getJSONArray("timeseries");
            JSONObject now = series.getJSONObject(0).getJSONObject("data");
            double temp = now.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
            String symbol = symbol(now, "next_1_hours");
            if (symbol.isEmpty()) symbol = symbol(now, "next_6_hours");

            // High, low and rain chance over the next 24 hours
            int n = Math.min(24, series.length()), rainy = 0;
            double high = temp, low = temp;
            for (int i = 0; i < n; i++) {
                JSONObject d = series.getJSONObject(i).getJSONObject("data");
                double t = d.getJSONObject("instant").getJSONObject("details").optDouble("air_temperature", temp);
                high = Math.max(high, t);
                low = Math.min(low, t);
                JSONObject next = d.optJSONObject("next_1_hours");
                if (next != null && next.optJSONObject("details") != null
                        && next.getJSONObject("details").optDouble("precipitation_amount", 0) > 0) rainy++;
            }
            return new Weather(temp, high, low, condition(symbol), n == 0 ? 0 : Math.round(rainy * 100f / n));
        } catch (JSONException e) {
            throw new IOException("Unexpected weather data");
        }
    }

    private static String symbol(JSONObject data, String period) {
        JSONObject p = data.optJSONObject(period);
        JSONObject summary = p == null ? null : p.optJSONObject("summary");
        return summary == null ? "" : summary.optString("symbol_code");
    }

    private static String condition(String symbol) {
        String code = symbol.replaceAll("_day|_night|_polartwilight", "");
        String name = CONDITIONS.get(code);
        return name != null ? name : code;
    }

    String format(boolean fahrenheit) {
        return String.format(Locale.getDefault(), "%s %s\nH %s  L %s%s",
                degrees(temperature, fahrenheit), condition, degrees(high, fahrenheit), degrees(low, fahrenheit),
                rainChance > 0 ? "  ·  " + rainChance + "% rain" : "");
    }

    private static String degrees(double celsius, boolean fahrenheit) {
        double v = fahrenheit ? celsius * 9 / 5 + 32 : celsius;
        return Math.round(v) + "°";
    }
}
