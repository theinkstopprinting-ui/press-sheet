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

/**
 * The installed apps, their icons, and your home screen: pages made of labeled sections
 * (Shop, Reselling, Home & family...), each holding app icons and folders, plus the dock.
 */
final class Store {

    /** One app in the phone's app list. */
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

    /** Something in a section: an app or a folder of apps. */
    static final class Item {
        String type = "app";   // "app" or "folder"
        String key = "";       // app
        String name = "";      // folder
        final List<String> apps = new ArrayList<>();   // folder

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("type", type);
            if ("folder".equals(type)) {
                o.put("name", name).put("apps", new JSONArray(apps));
            } else {
                o.put("key", key);
            }
            return o;
        }

        static Item fromJson(JSONObject o) {
            Item it = new Item();
            it.type = o.optString("type", "app");
            it.key = o.optString("key", "");
            it.name = o.optString("name", "");
            JSONArray a = o.optJSONArray("apps");
            for (int i = 0; a != null && i < a.length(); i++) {
                it.apps.add(a.optString(i));
            }
            return it;
        }

        static Item app(String key) {
            Item it = new Item();
            it.key = key;
            return it;
        }
    }

    /** A labeled group of icons on a page. `card` sections show the weather + InkFlow card instead. */
    static final class Section {
        String name = "";
        int page;
        boolean card;
        boolean clock;
        int color = -1;   // 0 cyan, 1 magenta, 2 yellow, 3 black (key), -1 none
        final List<Item> items = new ArrayList<>();

        boolean special() {
            return card || clock;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("name", name).put("page", page).put("card", card).put("clock", clock).put("color", color);
            JSONArray a = new JSONArray();
            for (Item i : items) {
                a.put(i.toJson());
            }
            o.put("items", a);
            return o;
        }

        static Section fromJson(JSONObject o) {
            Section s = new Section();
            s.name = o.optString("name", "");
            s.page = o.optInt("page");
            s.card = o.optBoolean("card");
            s.clock = o.optBoolean("clock");
            s.color = o.optInt("color", -1);
            JSONArray a = o.optJSONArray("items");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j != null) {
                    s.items.add(Item.fromJson(j));
                }
            }
            return s;
        }
    }

    final List<App> apps = new ArrayList<>();
    final Map<String, App> byKey = new HashMap<>();
    final List<Section> sections = new ArrayList<>();
    final List<String> dock = new ArrayList<>();   // 5 spots, "" = empty
    final List<String> recent = new ArrayList<>();
    int cols = 8;
    int pages = 3;
    int homePage = 0;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final LruCache<String, Bitmap> icons = new LruCache<>(500);
    private final int iconPx;

    Store(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
        this.iconPx = Math.round(56 * ctx.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ installed apps

    /** Reads the app list (slow: call off the main thread). */
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

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replace('’', '\'').trim();
    }

    /** Best app for some names: exact match first, then "starts with". */
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

    // ------------------------------------------------------------------ layout storage

    void load() {
        sections.clear();
        dock.clear();
        recent.clear();
        String s = prefs.getString("home4", null);
        if (s != null) {
            try {
                JSONObject o = new JSONObject(s);
                cols = Math.max(4, Math.min(8, o.optInt("cols", 8)));
                pages = Math.max(1, o.optInt("pages", 3));
                homePage = o.optInt("home", 0);
                JSONArray sec = o.getJSONArray("sections");
                for (int i = 0; i < sec.length(); i++) {
                    sections.add(Section.fromJson(sec.getJSONObject(i)));
                }
                JSONArray d = o.optJSONArray("dock");
                for (int i = 0; d != null && i < d.length(); i++) {
                    dock.add(d.optString(i, ""));
                }
                JSONArray r = o.optJSONArray("recent");
                for (int i = 0; r != null && i < r.length(); i++) {
                    recent.add(r.optString(i));
                }
                while (dock.size() < 5) {
                    dock.add("");
                }
                upgrade();
                return;
            } catch (Exception ignored) {
                sections.clear();
                dock.clear();
                recent.clear();
            }
        }
        fillDefaults();
        upgrade();
        save();
    }

    /** Adds the press-style extras to a layout saved by an older version: the clock and section colors. */
    private void upgrade() {
        if (prefs.getBoolean("press_v1", false)) {
            return;
        }
        boolean hasClock = false;
        for (Section s : sections) {
            hasClock |= s.clock;
        }
        if (!hasClock) {
            Section c = new Section();
            c.clock = true;
            c.page = homePage;
            int at = 0;
            for (int i = 0; i < sections.size(); i++) {
                if (sections.get(i).page == homePage) {
                    at = i;
                    break;
                }
            }
            sections.add(at, c);
        }
        int next = 0;
        for (Section s : sections) {
            if (!s.special() && s.color < 0) {
                s.color = next % 4;
                next++;
            }
        }
        prefs.edit().putBoolean("press_v1", true).apply();
        save();
    }

    void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("cols", cols).put("pages", pages).put("home", homePage);
            JSONArray sec = new JSONArray();
            for (Section x : sections) {
                sec.put(x.toJson());
            }
            o.put("sections", sec);
            o.put("dock", new JSONArray(dock));
            o.put("recent", new JSONArray(recent));
            prefs.edit().putString("home4", o.toString()).apply();
        } catch (Exception ignored) {
            // nothing sensible to do
        }
    }

    void reset() {
        prefs.edit().remove("home4").remove("press_v1").apply();
        load();
    }

    void noteLaunch(String key) {
        recent.remove(key);
        recent.add(0, key);
        while (recent.size() > 10) {
            recent.remove(recent.size() - 1);
        }
        save();
    }

    // ------------------------------------------------------------------ finding and moving things

    List<Section> onPage(int page) {
        List<Section> out = new ArrayList<>();
        for (Section s : sections) {
            if (s.page == page) {
                out.add(s);
            }
        }
        return out;
    }

    Section sectionOf(Item it) {
        for (Section s : sections) {
            if (s.items.contains(it)) {
                return s;
            }
        }
        return null;
    }

    /** True if the app is already on the home pages (in a section or a folder). */
    boolean onHome(String key) {
        for (Section s : sections) {
            for (Item i : s.items) {
                if (key.equals(i.key) || i.apps.contains(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Moves a section up (-1) or down (+1) among the sections on its page. */
    void shift(Section s, int dir) {
        List<Section> same = onPage(s.page);
        int at = same.indexOf(s);
        int to = at + dir;
        if (at < 0 || to < 0 || to >= same.size()) {
            return;
        }
        Section other = same.get(to);
        int a = sections.indexOf(s), b = sections.indexOf(other);
        sections.set(a, other);
        sections.set(b, s);
        save();
    }

    /** Puts a section at the end of another page. */
    void movePage(Section s, int page) {
        sections.remove(s);
        s.page = page;
        sections.add(s);
        pages = Math.max(pages, page + 1);
        save();
    }

    /** Drops empty pages at the end, but never below the home page or one page. */
    void trimPages() {
        int last = 0;
        for (Section s : sections) {
            last = Math.max(last, s.page);
        }
        pages = Math.max(1, Math.max(last + 1, homePage + 1));
        save();
    }

    // ------------------------------------------------------------------ first-run layout, sorted from John's Samsung pages

    private static String[] n(String... names) {
        return names;
    }

    private static final Object[][] DEFAULT_SECTIONS = {
            // page, heading, apps (each app is a list of names to look for; a String heading + String[][] = folder)
            {0, "", new Object[]{n("camera"), n("photos", "google photos", "gallery"), n("calendar"), n("maps", "google maps"),
                    n("samsung notes", "notes"), n("clock"), n("calculator"), n("gemini", "google gemini")}},
            {0, "#card", new Object[]{}},
            {0, "Shop", new Object[]{n("inkflow"), n("canva"), n("faire"), n("aftership"), n("paypal pos", "paypal zettle"),
                    n("fiverr"), n("dropbox"), n("nextcloud"), n("paperless"), n("backblaze")}},
            {0, "Reselling", new Object[]{n("ebay"), n("poshmark"), n("mercari"), n("depop"), n("etsy seller", "etsy"),
                    n("whatnot"), n("amazon seller"), n("lister", "snaplist")}},
            {1, "Home & family", new Object[]{n("ring"), n("blink"), n("myq"), n("amazon alexa", "alexa"), n("tapo"),
                    n("smartthings"), n("smart life"), n("home assistant"), n("roku", "the roku app"), n("family hub"),
                    n("family link", "google family link"), n("junicare"), n("jellyfin"), n("immich"), n("hallow"),
                    n("catholic", "laudate")}},
            {1, "Money & bills", new Object[]{
                    new Object[]{"Banking", new String[][]{{"arro"}, {"boost mobile", "boost"}, {"found"}, {"juzt"}, {"lili"},
                            {"mercury"}, {"nbkc"}, {"northone"}, {"paypal business", "paypal"}, {"pnc"}, {"relay"}, {"stripe"},
                            {"u.s. bank", "us bank"}, {"unifi"}, {"venmo"}}},
                    n("progressive"), n("aetna", "aetna health"), n("cvs flex", "otc health", "otc"), n("xfinity"),
                    n("cardcash", "card cash"), n("microsoft rewards", "rewards")}},
            {1, "Shopping & food", new Object[]{n("costco"), n("sam's club", "sams club"), n("amazon shopping", "amazon"),
                    n("murphy usa", "murphy"), n("slickdeals", "slick"), n("loyalty", "loyal"),
                    new Object[]{"Food & rides", new String[][]{{"uber eats"}, {"instacart"}, {"uber"}}}}},
            {2, "Games", new Object[]{n("coin master"), n("disney solitaire", "solitaire"), n("high roller", "video poker"),
                    n("tasty"), new Object[]{"Nintendo", new String[][]{{"nintendo switch", "nintendo"}, {"my nintendo"},
                    {"nintendo today"}, {"nintendo music"}}}}},
            {2, "Tools", new Object[]{new Object[]{"AI", new String[][]{{"claude"}, {"chatgpt"}}}, n("tailscale"), n("ntfy"),
                    n("cudy"), n("fing"), n("car scanner"), n("fretquest"), n("decktok"), n("engage"), n("lens", "google lens"),
                    n("play store", "google play store")}},
            {2, "Watch & listen", new Object[]{n("youtube"), n("youtube music", "yt music")}},
    };

    private void fillDefaults() {
        cols = 8;
        pages = 3;
        homePage = 0;
        Set<String> used = new HashSet<>();
        for (Object[] d : DEFAULT_SECTIONS) {
            Section s = new Section();
            s.page = (Integer) d[0];
            String head = (String) d[1];
            if ("#card".equals(head)) {
                s.card = true;
                sections.add(s);
                continue;
            }
            s.name = head;
            for (Object entry : (Object[]) d[2]) {
                if (entry instanceof String[]) {
                    App a = match((String[]) entry);
                    if (a != null && used.add(a.key)) {
                        s.items.add(Item.app(a.key));
                    }
                } else {
                    Object[] f = (Object[]) entry;
                    Item folder = new Item();
                    folder.type = "folder";
                    folder.name = (String) f[0];
                    for (String[] names : (String[][]) f[1]) {
                        App a = match(names);
                        if (a != null && used.add(a.key)) {
                            folder.apps.add(a.key);
                        }
                    }
                    if (!folder.apps.isEmpty()) {
                        s.items.add(folder);
                    }
                }
            }
            if (!s.items.isEmpty()) {
                sections.add(s);
            }
        }

        dock.add(key(match("phone")));
        dock.add(key(match("messages", "google messages")));
        dock.add(key(match("inkstop mail")));
        dock.add(key(match("chrome", "google chrome")));
        dock.add(key(match("starbucks")));
        trimPages();
    }

    private static String key(App a) {
        return a == null ? "" : a.key;
    }
}
