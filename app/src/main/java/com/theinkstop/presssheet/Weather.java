package com.theinkstop.presssheet;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * Weather from the US National Weather Service (api.weather.gov: free, no key, official alerts),
 * with Open-Meteo as the backup when the NWS doesn't answer. Town search uses Open-Meteo's
 * place search. All methods here block, so call them from a background thread.
 */
final class Weather {

    static final String DEFAULT_NAME = "Crystal Lake, IL";
    static final double DEFAULT_LAT = 42.2411;
    static final double DEFAULT_LON = -88.3162;

    /** One reading, ready to show. Temperatures are already in the chosen unit. */
    static final class Now {
        double temp = Double.NaN;
        double hi = Double.NaN;
        double lo = Double.NaN;
        String words = "";
        String when = "";      // "Today" / "Tonight" for the hi/lo line
        boolean day = true;
        String alert = "";      // e.g. "Winter Storm Warning"
        String source = "";
        String place = "";
        long at;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("temp", temp).put("hi", hi).put("lo", lo).put("words", words).put("when", when)
                        .put("day", day).put("alert", alert).put("source", source).put("place", place).put("at", at);
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
            n.words = o.optString("words");
            n.when = o.optString("when");
            n.day = o.optBoolean("day", true);
            n.alert = o.optString("alert");
            n.source = o.optString("source");
            n.place = o.optString("place");
            n.at = o.optLong("at");
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

    static Place place(SharedPreferences p) {
        return new Place(p.getString("wx_name", DEFAULT_NAME),
                Double.longBitsToDouble(p.getLong("wx_lat", Double.doubleToLongBits(DEFAULT_LAT))),
                Double.longBitsToDouble(p.getLong("wx_lon", Double.doubleToLongBits(DEFAULT_LON))));
    }

    static void savePlace(SharedPreferences p, Place pl) {
        p.edit().putString("wx_name", pl.name)
                .putLong("wx_lat", Double.doubleToLongBits(pl.lat))
                .putLong("wx_lon", Double.doubleToLongBits(pl.lon))
                .remove("nws_forecast").remove("nws_hourly").remove("nws_for")
                .remove("wx_cache").apply();
    }

    static boolean celsius(SharedPreferences p) {
        return "C".equals(p.getString("wx_unit", "F"));
    }

    /** Fetches the weather: NWS first, Open-Meteo if that fails. Returns null if both fail. */
    static Now fetch(SharedPreferences prefs) {
        Place pl = place(prefs);
        boolean c = celsius(prefs);
        Now n = null;
        try {
            n = fromNws(prefs, pl, c);
        } catch (Exception ignored) {
            n = null;
        }
        if (n == null) {
            try {
                n = fromOpenMeteo(pl, c);
            } catch (Exception ignored) {
                n = null;
            }
        }
        if (n != null) {
            n.place = pl.name;
            n.at = System.currentTimeMillis();
            prefs.edit().putString("wx_cache", n.toJson().toString()).apply();
        }
        return n;
    }

    static Now cached(SharedPreferences prefs) {
        String s = prefs.getString("wx_cache", null);
        if (s == null) {
            return null;
        }
        try {
            return Now.fromJson(new JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    private static String coords(Place pl) {
        return String.format(Locale.US, "%.4f,%.4f", pl.lat, pl.lon);
    }

    private static double toUnit(double f, String unit, boolean wantC) {
        if (Double.isNaN(f)) {
            return f;
        }
        boolean isC = "C".equalsIgnoreCase(unit);
        if (wantC && !isC) {
            return (f - 32) * 5 / 9;
        }
        if (!wantC && isC) {
            return f * 9 / 5 + 32;
        }
        return f;
    }

    private static Now fromNws(SharedPreferences prefs, Place pl, boolean wantC) throws Exception {
        String key = coords(pl);
        String forecast = prefs.getString("nws_forecast", null);
        String hourly = prefs.getString("nws_hourly", null);
        if (forecast == null || hourly == null || !key.equals(prefs.getString("nws_for", ""))) {
            Net.Reply r = Net.get("https://api.weather.gov/points/" + key, null, "application/geo+json");
            if (!r.ok()) {
                return null;
            }
            JSONObject props = r.json().optJSONObject("properties");
            if (props == null) {
                return null;
            }
            forecast = props.optString("forecast", null);
            hourly = props.optString("forecastHourly", null);
            if (forecast == null || hourly == null) {
                return null;
            }
            prefs.edit().putString("nws_forecast", forecast).putString("nws_hourly", hourly).putString("nws_for", key).apply();
        }

        Now n = new Now();
        n.source = "National Weather Service";

        Net.Reply h = Net.get(hourly, null, "application/geo+json");
        if (!h.ok()) {
            prefs.edit().remove("nws_for").apply(); // grid can move: look it up again next time
            return null;
        }
        JSONObject hp = h.json().optJSONObject("properties");
        JSONArray hperiods = hp == null ? null : hp.optJSONArray("periods");
        if (hperiods == null || hperiods.length() == 0) {
            return null;
        }
        JSONObject now = hperiods.getJSONObject(0);
        n.temp = toUnit(now.optDouble("temperature", Double.NaN), now.optString("temperatureUnit", "F"), wantC);
        n.words = now.optString("shortForecast");
        n.day = now.optBoolean("isDaytime", true);

        try {
            Net.Reply d = Net.get(forecast, null, "application/geo+json");
            JSONObject dp = d.ok() ? d.json().optJSONObject("properties") : null;
            JSONArray periods = dp == null ? null : dp.optJSONArray("periods");
            if (periods != null && periods.length() > 0) {
                JSONObject p0 = periods.getJSONObject(0);
                String u = p0.optString("temperatureUnit", "F");
                if (p0.optBoolean("isDaytime", true)) {
                    n.hi = toUnit(p0.optDouble("temperature", Double.NaN), u, wantC);
                    if (periods.length() > 1) {
                        n.lo = toUnit(periods.getJSONObject(1).optDouble("temperature", Double.NaN), u, wantC);
                    }
                    n.when = "Today";
                } else {
                    n.lo = toUnit(p0.optDouble("temperature", Double.NaN), u, wantC);
                    n.when = "Tonight";
                }
            }
        } catch (Exception ignored) {
            // hi/lo is a nice-to-have
        }

        try {
            Net.Reply a = Net.get("https://api.weather.gov/alerts/active?point=" + key, null, "application/geo+json");
            JSONArray feats = a.ok() ? a.json().optJSONArray("features") : null;
            int best = -1;
            if (feats != null) {
                for (int i = 0; i < feats.length(); i++) {
                    JSONObject pr = feats.getJSONObject(i).optJSONObject("properties");
                    if (pr == null) {
                        continue;
                    }
                    int rank = severity(pr.optString("severity"));
                    if (rank > best) {
                        best = rank;
                        n.alert = pr.optString("event");
                    }
                }
            }
        } catch (Exception ignored) {
            // alerts are a nice-to-have
        }
        return n;
    }

    private static int severity(String s) {
        switch (s == null ? "" : s) {
            case "Extreme":
                return 4;
            case "Severe":
                return 3;
            case "Moderate":
                return 2;
            case "Minor":
                return 1;
            default:
                return 0;
        }
    }

    private static Now fromOpenMeteo(Place pl, boolean wantC) throws Exception {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                        + "&temperature_unit=%s&timezone=auto&forecast_days=1",
                pl.lat, pl.lon, wantC ? "celsius" : "fahrenheit");
        Net.Reply r = Net.get(url, null, null);
        if (!r.ok()) {
            return null;
        }
        JSONObject b = r.json();
        JSONObject cur = b.optJSONObject("current");
        JSONObject daily = b.optJSONObject("daily");
        if (cur == null) {
            return null;
        }
        Now n = new Now();
        n.source = "Open-Meteo";
        n.temp = cur.optDouble("temperature_2m", Double.NaN);
        n.day = cur.optInt("is_day", 1) != 0;
        n.words = words(cur.optInt("weather_code", 3));
        if (daily != null) {
            JSONArray mx = daily.optJSONArray("temperature_2m_max");
            JSONArray mn = daily.optJSONArray("temperature_2m_min");
            if (mx != null && mx.length() > 0) {
                n.hi = mx.optDouble(0, Double.NaN);
            }
            if (mn != null && mn.length() > 0) {
                n.lo = mn.optDouble(0, Double.NaN);
            }
            n.when = "Today";
        }
        return n;
    }

    private static String words(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly Clear";
        if (code == 2) return "Partly Cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code == 66 || code == 67) return "Freezing Rain";
        if ((code >= 61 && code <= 65) || (code >= 80 && code <= 82)) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorms";
        return "Cloudy";
    }

    /** Which of the bundled weather icons fits these words. */
    static int icon(String words, boolean day) {
        String w = words == null ? "" : words.toLowerCase(Locale.US);
        if (w.contains("thunder") || w.contains("t-storm")) return R.drawable.wx_storm;
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
