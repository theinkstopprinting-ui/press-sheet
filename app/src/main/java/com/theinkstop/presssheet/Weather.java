package com.theinkstop.presssheet;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * Weather from Open-Meteo (free, no key, blends several national forecast models): the current
 * reading plus the next 24 hours and 7 days for the forecast pop-up. Town search also uses
 * Open-Meteo. All methods here block, so call them from a background thread.
 */
final class Weather {

    static final String DEFAULT_NAME = "Crystal Lake, IL";
    static final double DEFAULT_LAT = 42.2411;
    static final double DEFAULT_LON = -88.3162;
    static final String SOURCE = "Open-Meteo";

    /** One reading, ready to show. Temperatures are already in the chosen unit. */
    static final class Now {
        double temp = Double.NaN;
        double hi = Double.NaN;
        double lo = Double.NaN;
        double feels = Double.NaN;
        double humidity = Double.NaN;
        double wind = Double.NaN;
        String windUnit = "mph";
        int rain = -1;          // today's chance of rain, percent
        String words = "";
        String when = "";       // "Today" for the hi/lo line
        boolean day = true;
        String alert = "";
        String source = "";
        String place = "";
        long at;
        JSONArray hours = new JSONArray();   // {t, temp, words, day, pop}
        JSONArray days = new JSONArray();    // {d, hi, lo, words, pop, rise, set}

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("temp", num(temp)).put("hi", num(hi)).put("lo", num(lo)).put("feels", num(feels)).put("humidity", num(humidity))
                        .put("wind", num(wind)).put("windUnit", windUnit).put("rain", rain).put("words", words)
                        .put("when", when).put("day", day).put("alert", alert).put("source", source)
                        .put("place", place).put("at", at).put("hours", hours).put("days", days);
            } catch (Exception ignored) {
                // returns what it has
            }
            return o;
        }

        static Now fromJson(JSONObject o) {
            Now n = new Now();
            n.temp = o.optDouble("temp", Double.NaN);
            n.hi = o.optDouble("hi", Double.NaN);
            n.lo = o.optDouble("lo", Double.NaN);
            n.feels = o.optDouble("feels", Double.NaN);
            n.humidity = o.optDouble("humidity", Double.NaN);
            n.wind = o.optDouble("wind", Double.NaN);
            n.windUnit = o.optString("windUnit", "mph");
            n.rain = o.optInt("rain", -1);
            n.words = o.optString("words");
            n.when = o.optString("when");
            n.day = o.optBoolean("day", true);
            n.alert = o.optString("alert");
            n.source = o.optString("source");
            n.place = o.optString("place");
            n.at = o.optLong("at");
            JSONArray h = o.optJSONArray("hours");
            JSONArray d = o.optJSONArray("days");
            n.hours = h == null ? new JSONArray() : h;
            n.days = d == null ? new JSONArray() : d;
            return n;
        }
    }

    static final class Place {
        final String name;
        final double lat;
        final double lon;

        Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private Weather() {
    }

    /** JSON can't hold "not a number", so missing values are saved as null. */
    static Object num(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? JSONObject.NULL : (Object) v;
    }

    static Place place(SharedPreferences p) {
        return new Place(p.getString("wx_name", DEFAULT_NAME),
                Double.longBitsToDouble(p.getLong("wx_lat", Double.doubleToLongBits(DEFAULT_LAT))),
                Double.longBitsToDouble(p.getLong("wx_lon", Double.doubleToLongBits(DEFAULT_LON))));
    }

    static void savePlace(SharedPreferences p, Place pl) {
        p.edit().putString("wx_name", pl.name)
                .putLong("wx_lat", Double.doubleToLongBits(pl.lat))
                .putLong("wx_lon", Double.doubleToLongBits(pl.lon))
                .remove("wx_cache").apply();
    }

    static boolean celsius(SharedPreferences p) {
        return "C".equals(p.getString("wx_unit", "F"));
    }

    /** Fetches the weather. Returns null if Open-Meteo can't be reached. */
    static Now fetch(SharedPreferences prefs) {
        Place pl = place(prefs);
        Now n;
        try {
            n = fromOpenMeteo(pl, celsius(prefs));
        } catch (Exception ignored) {
            n = null;
        }
        if (n != null) {
            n.place = pl.name;
            n.at = System.currentTimeMillis();
            prefs.edit().putString("wx_cache", n.toJson().toString()).apply();
        }
        return n;
    }

    /** The last reading, or null (also null for readings saved by the old National Weather Service version). */
    static Now cached(SharedPreferences prefs) {
        String s = prefs.getString("wx_cache", null);
        if (s == null) {
            return null;
        }
        try {
            Now n = Now.fromJson(new JSONObject(s));
            return SOURCE.equals(n.source) ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Now fromOpenMeteo(Place pl, boolean wantC) throws Exception {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m"
                        + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                        + "&temperature_unit=%s&wind_speed_unit=%s&timezone=auto&forecast_days=7&forecast_hours=25",
                pl.lat, pl.lon, wantC ? "celsius" : "fahrenheit", wantC ? "kmh" : "mph");
        Net.Reply r = Net.get(url, null, null);
        if (!r.ok()) {
            return null;
        }
        JSONObject b = r.json();
        JSONObject cur = b.optJSONObject("current");
        if (cur == null) {
            return null;
        }
        Now n = new Now();
        n.source = SOURCE;
        n.windUnit = wantC ? "km/h" : "mph";
        n.temp = cur.optDouble("temperature_2m", Double.NaN);
        n.feels = cur.optDouble("apparent_temperature", Double.NaN);
        n.humidity = cur.optDouble("relative_humidity_2m", Double.NaN);
        n.wind = cur.optDouble("wind_speed_10m", Double.NaN);
        n.day = cur.optInt("is_day", 1) != 0;
        n.words = words(cur.optInt("weather_code", 3), n.day);
        String nowTime = cur.optString("time", "");

        JSONObject hourly = b.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray t = hourly.optJSONArray("time");
            JSONArray tp = hourly.optJSONArray("temperature_2m");
            JSONArray wc = hourly.optJSONArray("weather_code");
            JSONArray pp = hourly.optJSONArray("precipitation_probability");
            JSONArray dy = hourly.optJSONArray("is_day");
            for (int i = 0; t != null && i < t.length() && n.hours.length() < 24; i++) {
                String time = t.optString(i);
                if (!nowTime.isEmpty() && time.compareTo(nowTime.substring(0, Math.min(13, nowTime.length()))) < 0) {
                    continue;   // already past
                }
                boolean d = dy == null || dy.optInt(i, 1) != 0;
                n.hours.put(new JSONObject().put("t", time).put("temp", num(tp == null ? Double.NaN : tp.optDouble(i)))
                        .put("words", words(wc == null ? 3 : wc.optInt(i, 3), d)).put("day", d)
                        .put("pop", pp == null ? -1 : pp.optInt(i, -1)));
            }
        }

        JSONObject daily = b.optJSONObject("daily");
        if (daily != null) {
            JSONArray t = daily.optJSONArray("time");
            JSONArray mx = daily.optJSONArray("temperature_2m_max");
            JSONArray mn = daily.optJSONArray("temperature_2m_min");
            JSONArray wc = daily.optJSONArray("weather_code");
            JSONArray pp = daily.optJSONArray("precipitation_probability_max");
            JSONArray rise = daily.optJSONArray("sunrise");
            JSONArray set = daily.optJSONArray("sunset");
            for (int i = 0; t != null && i < t.length(); i++) {
                n.days.put(new JSONObject().put("d", t.optString(i))
                        .put("hi", num(mx == null ? Double.NaN : mx.optDouble(i))).put("lo", num(mn == null ? Double.NaN : mn.optDouble(i)))
                        .put("words", words(wc == null ? 3 : wc.optInt(i, 3), true))
                        .put("pop", pp == null ? -1 : pp.optInt(i, -1))
                        .put("rise", rise == null ? "" : rise.optString(i)).put("set", set == null ? "" : set.optString(i)));
            }
            if (n.days.length() > 0) {
                JSONObject d0 = n.days.getJSONObject(0);
                n.hi = d0.optDouble("hi", Double.NaN);
                n.lo = d0.optDouble("lo", Double.NaN);
                n.rain = d0.optInt("pop", -1);
                n.when = "Today";
            }
        }
        return n;
    }

    /** Plain words for an Open-Meteo (WMO) weather code. */
    static String words(int code, boolean day) {
        switch (code) {
            case 0:
                return day ? "Sunny" : "Clear";
            case 1:
                return day ? "Mostly sunny" : "Mostly clear";
            case 2:
                return "Partly cloudy";
            case 3:
                return "Cloudy";
            case 45:
            case 48:
                return "Fog";
            case 51:
            case 53:
            case 55:
                return "Drizzle";
            case 56:
            case 57:
                return "Freezing drizzle";
            case 61:
                return "Light rain";
            case 63:
                return "Rain";
            case 65:
                return "Heavy rain";
            case 66:
            case 67:
                return "Freezing rain";
            case 71:
                return "Light snow";
            case 73:
                return "Snow";
            case 75:
                return "Heavy snow";
            case 77:
                return "Snow grains";
            case 80:
            case 81:
            case 82:
                return "Showers";
            case 85:
            case 86:
                return "Snow showers";
            case 95:
                return "Thunderstorms";
            case 96:
            case 99:
                return "Storms with hail";
            default:
                return "Cloudy";
        }
    }

    /** Which of the bundled weather icons fits these words. */
    static int icon(String words, boolean day) {
        String w = words == null ? "" : words.toLowerCase(Locale.US);
        if (w.contains("thunder") || w.contains("storm")) return R.drawable.wx_storm;
        if (w.contains("snow") || w.contains("flurr") || w.contains("sleet") || w.contains("ice") || w.contains("freezing")) return R.drawable.wx_snow;
        if (w.contains("rain") || w.contains("shower") || w.contains("drizzle")) return R.drawable.wx_rain;
        if (w.contains("fog") || w.contains("haze") || w.contains("smoke") || w.contains("mist")) return R.drawable.wx_fog;
        if (w.contains("partly") || w.contains("mostly sunny") || w.contains("mostly clear")) return day ? R.drawable.wx_partly_day : R.drawable.wx_partly_night;
        if (w.contains("sunny") || w.contains("clear") || w.contains("fair")) return day ? R.drawable.wx_sun : R.drawable.wx_moon;
        return R.drawable.wx_cloud;
    }

    private static final String[][] STATES = {
            {"al", "alabama"}, {"ak", "alaska"}, {"az", "arizona"}, {"ar", "arkansas"}, {"ca", "california"}, {"co", "colorado"},
            {"ct", "connecticut"}, {"de", "delaware"}, {"fl", "florida"}, {"ga", "georgia"}, {"hi", "hawaii"}, {"id", "idaho"},
            {"il", "illinois"}, {"in", "indiana"}, {"ia", "iowa"}, {"ks", "kansas"}, {"ky", "kentucky"}, {"la", "louisiana"},
            {"me", "maine"}, {"md", "maryland"}, {"ma", "massachusetts"}, {"mi", "michigan"}, {"mn", "minnesota"},
            {"ms", "mississippi"}, {"mo", "missouri"}, {"mt", "montana"}, {"ne", "nebraska"}, {"nv", "nevada"},
            {"nh", "new hampshire"}, {"nj", "new jersey"}, {"nm", "new mexico"}, {"ny", "new york"}, {"nc", "north carolina"},
            {"nd", "north dakota"}, {"oh", "ohio"}, {"ok", "oklahoma"}, {"or", "oregon"}, {"pa", "pennsylvania"},
            {"ri", "rhode island"}, {"sc", "south carolina"}, {"sd", "south dakota"}, {"tn", "tennessee"}, {"tx", "texas"},
            {"ut", "utah"}, {"vt", "vermont"}, {"va", "virginia"}, {"wa", "washington"}, {"wv", "west virginia"},
            {"wi", "wisconsin"}, {"wy", "wyoming"}};

    /** Finds a town ("Crystal Lake, IL"). Returns null and puts a message in err[0] if it can't. */
    static Place findPlace(String text, String[] err) {
        String[] parts = text.split(",");
        String town = parts[0].trim();
        String want = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.US) : "";
        if (town.isEmpty()) {
            err[0] = "Type a town name.";
            return null;
        }
        String wantState = want;
        for (String[] s : STATES) {
            if (s[0].equals(want)) {
                wantState = s[1];
            }
        }
        try {
            Net.Reply r = Net.get("https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name="
                    + URLEncoder.encode(town, "UTF-8"), null, null);
            JSONArray list = r.ok() ? r.json().optJSONArray("results") : null;
            if (list == null || list.length() == 0) {
                err[0] = r.ok() ? "No town called “" + town + "” found." : "Couldn’t reach the town search.";
                return null;
            }
            JSONObject pick = list.getJSONObject(0);
            if (!wantState.isEmpty()) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject x = list.getJSONObject(i);
                    String a = x.optString("admin1").toLowerCase(Locale.US);
                    String cc = x.optString("country_code").toLowerCase(Locale.US);
                    if (a.startsWith(wantState) || cc.equals(want)) {
                        pick = x;
                        break;
                    }
                }
            }
            String admin = pick.optString("admin1");
            String abbr = "";
            for (String[] s : STATES) {
                if (s[1].equalsIgnoreCase(admin)) {
                    abbr = s[0].toUpperCase(Locale.US);
                }
            }
            String name = pick.optString("name") + (!abbr.isEmpty() ? ", " + abbr : !admin.isEmpty() ? ", " + admin : "");
            return new Place(name, pick.optDouble("latitude"), pick.optDouble("longitude"));
        } catch (Exception e) {
            err[0] = "Couldn’t reach the town search.";
            return null;
        }
    }
}
