package com.theinkstop.presssheet;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The installed apps, their icons, and your layout: categories, favorites and dock. */
final class Store {

    /** One app icon in the phone's app list. */
    static final class App {
        final String label;
        final String pkg;
        final String cls;
        final String key;

        App(String label, String pkg, String cls) {
            this.label = label;
            this.pkg = pkg;
            this.cls = cls;
            this.key = pkg + "/" + cls;
        }
    }

    /** A category: a band on Press Bed and a tab in the drawer. */
    static final class Cat {
        String id;
        String name;
        String color;      // cyan, yellow, magenta, black, white
        final List<String> apps = new ArrayList<>();
    }

    static final String[] COLORS = {"cyan", "yellow", "magenta", "black", "white"};

    final List<App> apps = new ArrayList<>();
    final Map<String, App> byKey = new HashMap<>();
    final List<Cat> cats = new ArrayList<>();
    final List<String> dock = new ArrayList<>();       // 5 spots, "" = empty
    final List<String> favs = new ArrayList<>();       // 4 spots, "" = empty
    final List<String> recent = new ArrayList<>();

    private final Context ctx;
    private final SharedPreferences prefs;
    private final LruCache<String, Bitmap> icons = new LruCache<>(400);
    private final int iconPx;

    Store(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
        this.iconPx = Math.round(56 * ctx.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ installed apps

    /** Reads the app list (slow: call off the main thread). Returns a new list. */
    List<App> queryApps() {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : found) {
            if (r.activityInfo == null || ctx.getPackageName().equals(r.activityInfo.packageName)) {
                continue;
            }
            CharSequence l = r.loadLabel(pm);
            App a = new App(l == null ? r.activityInfo.packageName : l.toString().trim(), r.activityInfo.packageName, r.activityInfo.name);
            if (seen.add(a.key)) {
                out.add(a);
            }
        }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return out;
    }

    void setApps(List<App> list) {
        apps.clear();
        apps.addAll(list);
        byKey.clear();
        for (App a : list) {
            byKey.put(a.key, a);
        }
    }

    App app(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        App a = byKey.get(key);
        if (a == null) {
            // the app was updated and its main screen renamed: fall back to the same package
            String pkg = key.contains("/") ? key.substring(0, key.indexOf('/')) : key;
            for (App x : apps) {
                if (x.pkg.equals(pkg)) {
                    return x;
                }
            }
        }
        return a;
    }

    Bitmap cachedIcon(String key) {
        return icons.get(key);
    }

    /** Draws an app's icon as a bitmap (slow: off the main thread). */
    Bitmap loadIcon(App a) {
        Bitmap hit = icons.get(a.key);
        if (hit != null) {
            return hit;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable d;
            try {
                d = pm.getActivityIcon(new ComponentName(a.pkg, a.cls));
            } catch (PackageManager.NameNotFoundException e) {
                d = pm.getApplicationIcon(a.pkg);
            }
            Bitmap bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, iconPx, iconPx);
            d.draw(c);
            icons.put(a.key, bmp);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    void forgetIcons() {
        icons.evictAll();
    }

    // ------------------------------------------------------------------ layout

    void load() {
        cats.clear();
        dock.clear();
        favs.clear();
        recent.clear();
        String s = prefs.getString("layout2", null);
        if (s != null) {
            try {
                JSONObject o = new JSONObject(s);
                JSONArray cs = o.getJSONArray("cats");
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.getJSONObject(i);
                    Cat cat = new Cat();
                    cat.id = c.optString("id", "c" + i);
                    cat.name = c.optString("name", "Apps");
                    cat.color = c.optString("color", "white");
                    JSONArray a = c.optJSONArray("apps");
                    for (int k = 0; a != null && k < a.length(); k++) {
                        cat.apps.add(a.getString(k));
                    }
                    cats.add(cat);
                }
                copy(o.optJSONArray("dock"), dock, 5);
                copy(o.optJSONArray("favs"), favs, 4);
                copy(o.optJSONArray("recent"), recent, 0);
                return;
            } catch (Exception ignored) {
                cats.clear();
                dock.clear();
                favs.clear();
                recent.clear();
            }
        }
        fillDefaults();
        save();
    }

    private static void copy(JSONArray a, List<String> into, int size) {
        for (int i = 0; a != null && i < a.length(); i++) {
            into.add(a.optString(i, ""));
        }
        while (size > 0 && into.size() < size) {
            into.add("");
        }
        while (size > 0 && into.size() > size) {
            into.remove(into.size() - 1);
        }
    }

    void save() {
        try {
            JSONObject o = new JSONObject();
            JSONArray cs = new JSONArray();
            for (Cat c : cats) {
                JSONObject j = new JSONObject();
                j.put("id", c.id).put("name", c.name).put("color", c.color);
                JSONArray a = new JSONArray();
                for (String k : c.apps) {
                    a.put(k);
                }
                j.put("apps", a);
                cs.put(j);
            }
            o.put("cats", cs);
            o.put("dock", new JSONArray(dock));
            o.put("favs", new JSONArray(favs));
            o.put("recent", new JSONArray(recent));
            prefs.edit().putString("layout2", o.toString()).apply();
        } catch (Exception ignored) {
            // nothing sensible to do
        }
    }

    void reset() {
        prefs.edit().remove("layout2").apply();
        load();
    }

    void noteLaunch(String key) {
        recent.remove(key);
        recent.add(0, key);
        while (recent.size() > 8) {
            recent.remove(recent.size() - 1);
        }
        save();
    }

    Cat catOf(String key) {
        for (Cat c : cats) {
            if (c.apps.contains(key)) {
                return c;
            }
        }
        return null;
    }

    /** Puts an app in one category (taking it out of any other). null = no category. */
    void moveTo(String key, Cat target) {
        for (Cat c : cats) {
            c.apps.remove(key);
        }
        if (target != null) {
            target.apps.add(key);
        }
        save();
    }

    Cat addCategory(String name) {
        Cat c = new Cat();
        c.id = "c" + System.currentTimeMillis();
        c.name = name;
        c.color = COLORS[cats.size() % COLORS.length];
        cats.add(c);
        save();
        return c;
    }

    // ------------------------------------------------------------------ first-run defaults (from John's screenshots)

    private static final Object[][] DEFAULT_CATS = {
            {"Shop", "cyan", new String[]{"inkflow", "inkstop mail", "canva", "fiverr", "faire", "nextcloud", "dropbox", "backblaze",
                    "tailscale", "ntfy", "unifi", "paypal pos", "paypal zettle"}},
            {"Reselling", "yellow", new String[]{"lister", "snaplist", "whatnot", "ebay", "poshmark", "mercari", "depop", "etsy",
                    "amazon seller", "aftership"}},
            {"Home", "magenta", new String[]{"family hub", "home assistant", "smart life", "smartthings", "tapo", "ring", "blink",
                    "myq", "amazon alexa", "alexa", "roku", "the roku app", "jellyfin", "immich", "paperless", "fing", "cudy", "xfinity"}},
            {"Family", "white", new String[]{"family link", "google family link", "junicare", "fretquest", "engage", "hallow", "catholic", "laudate"}},
            {"Games", "black", new String[]{"disney solitaire", "solitaire", "high roller", "video poker", "tasty", "coin master", "loyal",
                    "decktok", "nintendo"}},
            {"Money", "cyan", new String[]{"arro", "found", "juzt", "lili", "mercury", "nbkc", "northone", "paypal business", "paypal",
                    "pnc", "relay", "stripe", "u.s. bank", "us bank", "venmo", "boost", "progressive", "aetna", "cvs", "otc", "card"}},
            {"Shopping & food", "magenta", new String[]{"amazon shopping", "amazon", "costco", "sam's club", "sam’s club", "sams club",
                    "instacart", "uber eats", "uber", "starbucks", "murphy", "slickdeals", "rewards", "microsoft rewards"}},
            {"Everyday", "yellow", new String[]{"calendar", "clock", "samsung notes", "notes", "calculator", "maps", "google maps", "photos",
                    "google photos", "camera", "play store", "google play store", "lens", "google lens", "gemini", "claude", "chatgpt",
                    "youtube music", "yt music", "youtube", "car scanner"}},
    };

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replace('’', '\'').trim();
    }

    /** Best app for a set of names: exact match first, then "starts with". */
    App match(String... names) {
        for (String n : names) {
            for (App a : apps) {
                if (norm(a.label).equals(n)) {
                    return a;
                }
            }
        }
        for (String n : names) {
            for (App a : apps) {
                if (norm(a.label).startsWith(n)) {
                    return a;
                }
            }
        }
        return null;
    }

    private void fillDefaults() {
        // every (name, category) pair, longest name first, so "uber eats" wins over "uber"
        List<Object[]> pairs = new ArrayList<>();
        for (int i = 0; i < DEFAULT_CATS.length; i++) {
            Cat c = new Cat();
            c.id = "c" + i;
            c.name = (String) DEFAULT_CATS[i][0];
            c.color = (String) DEFAULT_CATS[i][1];
            cats.add(c);
            String[] names = (String[]) DEFAULT_CATS[i][2];
            for (int k = 0; k < names.length; k++) {
                pairs.add(new Object[]{names[k], c, k});
            }
        }
        Collections.sort(pairs, (x, y) -> ((String) y[0]).length() - ((String) x[0]).length());
        Map<String, Object[]> placed = new HashMap<>();  // app key -> pair
        for (App a : apps) {
            String l = norm(a.label);
            for (Object[] p : pairs) {
                String n = (String) p[0];
                if (l.equals(n) || l.startsWith(n + " ") || l.startsWith(n + ":") || (n.length() >= 5 && l.startsWith(n))) {
                    placed.put(a.key, p);
                    break;
                }
            }
        }
        // keep each category in the order its names are listed above
        for (Cat c : cats) {
            List<Object[]> mine = new ArrayList<>();
            for (Map.Entry<String, Object[]> e : placed.entrySet()) {
                if (e.getValue()[1] == c) {
                    mine.add(new Object[]{e.getKey(), e.getValue()[2]});
                }
            }
            Collections.sort(mine, (x, y) -> (Integer) x[1] - (Integer) y[1]);
            for (Object[] m : mine) {
                c.apps.add((String) m[0]);
            }
        }
        dock.add(key(match("phone")));
        dock.add(key(match("messages", "google messages")));
        dock.add(key(match("inkstop mail")));
        dock.add(key(match("chrome", "google chrome")));
        dock.add(key(match("starbucks")));
        favs.add(key(match("inkflow")));
        favs.add(key(match("lister", "snaplist")));
        favs.add(key(match("family hub")));
        favs.add("");
    }

    private static String key(App a) {
        return a == null ? "" : a.key;
    }
}
