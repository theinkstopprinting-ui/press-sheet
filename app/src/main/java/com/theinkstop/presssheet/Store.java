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

/** The installed apps, their icons, and your home screen: pages of icons, folders, the info card and the dock. */
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

    /** Something on a home page: an app, a folder, or the weather + InkFlow card. */
    static final class Item {
        String type;          // "app", "folder", "card"
        int page;
        int row;
        int col;
        int spanX = 1;
        int spanY = 1;
        String key = "";      // app
        String name = "";     // folder
        final List<String> apps = new ArrayList<>();   // folder

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("type", type).put("page", page).put("row", row).put("col", col).put("sx", spanX).put("sy", spanY);
            if ("app".equals(type)) {
                o.put("key", key);
            }
            if ("folder".equals(type)) {
                o.put("name", name).put("apps", new JSONArray(apps));
            }
            return o;
        }

        static Item fromJson(JSONObject o) {
            Item it = new Item();
            it.type = o.optString("type", "app");
            it.page = o.optInt("page");
            it.row = o.optInt("row");
            it.col = o.optInt("col");
            it.spanX = Math.max(1, o.optInt("sx", 1));
            it.spanY = Math.max(1, o.optInt("sy", 1));
            it.key = o.optString("key", "");
            it.name = o.optString("name", "");
            JSONArray a = o.optJSONArray("apps");
            for (int i = 0; a != null && i < a.length(); i++) {
                it.apps.add(a.optString(i));
            }
            return it;
        }

        boolean covers(int p, int r, int c) {
            return page == p && r >= row && r < row + spanY && c >= col && c < col + spanX;
        }
    }

    final List<App> apps = new ArrayList<>();
    final Map<String, App> byKey = new HashMap<>();
    final List<Item> items = new ArrayList<>();
    final List<String> dock = new ArrayList<>();   // 5 spots, "" = empty
    final List<String> recent = new ArrayList<>();
    int cols = 8;
    int rows = 8;
    int pages = 2;
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
        items.clear();
        dock.clear();
        recent.clear();
        String s = prefs.getString("home3", null);
        if (s != null) {
            try {
                JSONObject o = new JSONObject(s);
                cols = o.optInt("cols", 8);
                rows = o.optInt("rows", 8);
                pages = Math.max(1, o.optInt("pages", 2));
                homePage = o.optInt("home", 0);
                JSONArray it = o.getJSONArray("items");
                for (int i = 0; i < it.length(); i++) {
                    items.add(Item.fromJson(it.getJSONObject(i)));
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
                return;
            } catch (Exception ignored) {
                items.clear();
                dock.clear();
                recent.clear();
            }
        }
        fillDefaults();
        save();
    }

    void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("cols", cols).put("rows", rows).put("pages", pages).put("home", homePage);
            JSONArray it = new JSONArray();
            for (Item i : items) {
                it.put(i.toJson());
            }
            o.put("items", it);
            o.put("dock", new JSONArray(dock));
            o.put("recent", new JSONArray(recent));
            prefs.edit().putString("home3", o.toString()).apply();
        } catch (Exception ignored) {
            // nothing sensible to do
        }
    }

    void reset() {
        prefs.edit().remove("home3").apply();
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

    // ------------------------------------------------------------------ placing things

    Item at(int page, int row, int col) {
        for (Item i : items) {
            if (i.covers(page, row, col)) {
                return i;
            }
        }
        return null;
    }

    /** True if an item of this size fits at (page,row,col) without overlapping anything but `ignore`. */
    boolean fits(int page, int row, int col, int sx, int sy, Item ignore) {
        if (row < 0 || col < 0 || row + sy > rows || col + sx > cols) {
            return false;
        }
        for (int r = row; r < row + sy; r++) {
            for (int c = col; c < col + sx; c++) {
                Item o = at(page, r, c);
                if (o != null && o != ignore) {
                    return false;
                }
            }
        }
        return true;
    }

    /** First free spot, starting at `fromPage`; adds a page if every page is full. Returns {page,row,col}. */
    int[] freeSpot(int fromPage, int sx, int sy) {
        for (int p = fromPage; p < pages; p++) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (fits(p, r, c, sx, sy, null)) {
                        return new int[]{p, r, c};
                    }
                }
            }
        }
        for (int p = 0; p < fromPage && p < pages; p++) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (fits(p, r, c, sx, sy, null)) {
                        return new int[]{p, r, c};
                    }
                }
            }
        }
        pages++;
        return new int[]{pages - 1, 0, 0};
    }

    Item addApp(String key, int page, int row, int col) {
        Item it = new Item();
        it.type = "app";
        it.key = key;
        it.page = page;
        it.row = row;
        it.col = col;
        items.add(it);
        save();
        return it;
    }

    /** Changes the grid size, moving anything that no longer fits to a free spot. */
    void resize(int newCols, int newRows) {
        cols = newCols;
        rows = newRows;
        List<Item> misfits = new ArrayList<>();
        for (Item i : items) {
            i.spanX = Math.min(i.spanX, cols);
            if (i.row + i.spanY > rows || i.col + i.spanX > cols) {
                misfits.add(i);
            }
        }
        items.removeAll(misfits);
        for (Item i : misfits) {
            int[] s = freeSpot(i.page, i.spanX, i.spanY);
            i.page = s[0];
            i.row = s[1];
            i.col = s[2];
            items.add(i);
        }
        save();
    }

    /** Drops pages at the end that hold nothing. */
    void trimPages() {
        int last = 0;
        for (Item i : items) {
            last = Math.max(last, i.page);
        }
        pages = Math.max(1, Math.max(last + 1, homePage + 1));
        save();
    }

    // ------------------------------------------------------------------ first-run layout: John's two Samsung pages

    // {page, row, col, names...} for apps; folders and the card are added below.
    private static final Object[][] DEFAULT_APPS = {
            // page 1 (the page with the photo showing)
            {0, 3, 3, new String[]{"murphy usa", "murphy"}}, {0, 3, 4, new String[]{"aetna", "aetna health"}},
            {0, 4, 2, new String[]{"progressive"}}, {0, 4, 3, new String[]{"xfinity"}}, {0, 4, 4, new String[]{"cudy"}},
            {0, 4, 5, new String[]{"catholic", "laudate"}}, {0, 4, 6, new String[]{"cvs flex", "otc", "cvs"}},
            {0, 5, 0, new String[]{"costco"}}, {0, 5, 1, new String[]{"claude"}}, {0, 5, 2, new String[]{"backblaze"}},
            {0, 5, 3, new String[]{"fretquest"}}, {0, 5, 5, new String[]{"paperless"}}, {0, 5, 6, new String[]{"ntfy"}},
            {0, 5, 7, new String[]{"tailscale"}},
            {0, 6, 3, new String[]{"tapo"}}, {0, 6, 4, new String[]{"decktok"}}, {0, 6, 6, new String[]{"immich"}},
            {0, 6, 7, new String[]{"jellyfin"}},
            {0, 7, 3, new String[]{"home assistant"}}, {0, 7, 6, new String[]{"smart life", "smartthings"}},
            // page 2 (the full page)
            {1, 0, 0, new String[]{"youtube music", "yt music"}}, {1, 0, 2, new String[]{"disney solitaire", "solitaire"}},
            {1, 0, 3, new String[]{"high roller", "video poker"}}, {1, 0, 5, new String[]{"car scanner"}},
            {1, 0, 6, new String[]{"maps", "google maps"}}, {1, 0, 7, new String[]{"play store", "google play store"}},
            {1, 1, 0, new String[]{"calendar"}}, {1, 1, 1, new String[]{"fiverr"}}, {1, 1, 2, new String[]{"myq"}},
            {1, 1, 3, new String[]{"engage"}}, {1, 1, 4, new String[]{"tasty"}}, {1, 1, 5, new String[]{"loyal"}},
            {1, 1, 6, new String[]{"coin master"}}, {1, 1, 7, new String[]{"photos", "google photos", "gallery"}},
            {1, 2, 1, new String[]{"family link", "google family link"}}, {1, 2, 2, new String[]{"clock"}},
            {1, 2, 3, new String[]{"junicare"}}, {1, 2, 7, new String[]{"ring"}},
            {1, 3, 0, new String[]{"samsung notes", "notes"}}, {1, 3, 2, new String[]{"amazon alexa", "alexa"}},
            {1, 3, 3, new String[]{"sam's club", "sams club"}}, {1, 3, 4, new String[]{"card"}},
            {1, 3, 5, new String[]{"family hub"}}, {1, 3, 6, new String[]{"hallow"}}, {1, 3, 7, new String[]{"calculator"}},
            {1, 4, 0, new String[]{"roku", "the roku app"}}, {1, 4, 1, new String[]{"fing"}},
            {1, 4, 2, new String[]{"smart life", "smartthings"}}, {1, 4, 4, new String[]{"amazon shopping", "amazon"}},
            {1, 4, 6, new String[]{"gemini", "google gemini"}}, {1, 4, 7, new String[]{"lens", "google lens"}},
            {1, 5, 2, new String[]{"microsoft rewards", "rewards"}}, {1, 5, 3, new String[]{"slickdeals", "slick"}},
            {1, 5, 4, new String[]{"faire"}}, {1, 5, 5, new String[]{"inkflow"}}, {1, 5, 6, new String[]{"lister", "snaplist"}},
            {1, 5, 7, new String[]{"blink"}},
            {1, 6, 0, new String[]{"paypal pos", "paypal zettle"}}, {1, 6, 2, new String[]{"nextcloud"}},
            {1, 6, 3, new String[]{"aftership"}}, {1, 6, 4, new String[]{"canva"}}, {1, 6, 6, new String[]{"dropbox"}},
            {1, 6, 7, new String[]{"camera"}},
            {1, 7, 0, new String[]{"amazon seller"}}, {1, 7, 1, new String[]{"poshmark"}}, {1, 7, 2, new String[]{"ebay"}},
            {1, 7, 3, new String[]{"etsy seller", "etsy"}}, {1, 7, 4, new String[]{"mercari"}}, {1, 7, 5, new String[]{"depop"}},
            {1, 7, 6, new String[]{"whatnot"}}, {1, 7, 7, new String[]{"youtube"}},
    };

    // {page, row, col, folder name, [contents]}
    private static final Object[][] DEFAULT_FOLDERS = {
            {1, 3, 1, "Nintendo", new String[][]{{"nintendo switch", "nintendo"}, {"my nintendo"}}},
            {1, 5, 1, "Food & rides", new String[][]{{"uber eats"}, {"instacart"}, {"uber"}}},
            {1, 6, 1, "Banking", new String[][]{{"arro"}, {"boost mobile", "boost"}, {"found"}, {"juzt"}, {"lili"}, {"mercury"},
                    {"nbkc"}, {"northone"}, {"paypal business", "paypal"}, {"pnc"}, {"relay"}, {"stripe"}, {"u.s. bank", "us bank"},
                    {"unifi"}, {"venmo"}}},
            {1, 6, 5, "AI", new String[][]{{"claude"}, {"chatgpt"}}},
    };

    private void fillDefaults() {
        cols = 8;
        rows = 8;
        pages = 2;
        homePage = 0;
        for (Object[] d : DEFAULT_APPS) {
            App a = match((String[]) d[3]);
            if (a == null) {
                continue;
            }
            Item it = new Item();
            it.type = "app";
            it.key = a.key;
            it.page = (Integer) d[0];
            it.row = (Integer) d[1];
            it.col = (Integer) d[2];
            items.add(it);
        }
        for (Object[] f : DEFAULT_FOLDERS) {
            Item it = new Item();
            it.type = "folder";
            it.page = (Integer) f[0];
            it.row = (Integer) f[1];
            it.col = (Integer) f[2];
            it.name = (String) f[3];
            for (String[] names : (String[][]) f[4]) {
                App a = match(names);
                if (a != null && !it.apps.contains(a.key)) {
                    it.apps.add(a.key);
                }
            }
            if (!it.apps.isEmpty()) {
                items.add(it);
            }
        }
        Item card = new Item();
        card.type = "card";
        card.page = 1;
        card.row = 2;
        card.col = 4;
        card.spanX = 3;
        items.add(card);

        dock.add(key(match("phone")));
        dock.add(key(match("messages", "google messages")));
        dock.add(key(match("inkstop mail")));
        dock.add(key(match("chrome", "google chrome")));
        dock.add(key(match("starbucks")));
    }

    private static String key(App a) {
        return a == null ? "" : a.key;
    }
}
