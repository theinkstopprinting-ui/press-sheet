package com.theinkstop.presssheet;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SectionIndexer;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Press Sheet: a Samsung-style home screen. Pages of icons over your wallpaper, folders, a dock,
 * and a small weather + InkFlow card, starting from the layout of John's own Samsung pages.
 * Swipe up for every app (search + A to Z); swipe down for notifications.
 */
public class MainActivity extends Activity {

    private static final int M = 0xFFFF7DB5, Y = 0xFFFFD60A, K = 0xFF14161C;
    private static final long WX_EVERY = 20 * 60 * 1000L;
    private static final String[][] INKFLOW_PAGES = {
            {"New order", "/orders/new"}, {"Orders board", "/orders"}, {"Dashboard", "/dashboard"}, {"Production", "/production"},
            {"Printers", "/printers"}, {"Customers", "/customers"}, {"Calendar", "/calendar"}, {"Tasks", "/tasks"},
            {"Inventory", "/inventory"}, {"Mockup studio", "/studio"}, {"Scan a work order", "/scan"}};

    /** Shared by every copy of the screen and never shut down, so late work can't hit a closed pool. */
    private static final ExecutorService bg = Executors.newFixedThreadPool(3);

    private SharedPreferences prefs;
    private Store store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean appsReady;
    private boolean dark;
    private int ground, card, text, sub, chip;

    private SwipeRoot root;
    private Pager pager;
    private LinearLayout pagesRow, dots, dockRow;
    private LinearLayout drawer, recentBox, actionsBox;
    private EditText search;
    private GridView grid;
    private FrameLayout folderLayer;
    private final List<ScrollView> pageScrolls = new ArrayList<>();
    private int cellW;
    private int startPage = -1;

    private Weather.Now weather;
    private boolean wxBusy;
    private JSONObject live;
    private String liveError = "";
    private long liveAt;
    private boolean liveBusy;
    private final List<View> cards = new ArrayList<>();

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadApps();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshWeather(false);
            refreshLive(false);
            ui.postDelayed(this, 60_000L);
        }
    };

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("presssheet", MODE_PRIVATE);
        store = new Store(this, prefs);
        weather = Weather.cached(prefs);
        try {
            String s = prefs.getString("if_live", null);
            live = s == null ? null : new JSONObject(s);
        } catch (Exception ignored) {
            live = null;
        }
        applyTestExtras(getIntent());
        if (getIntent() != null && getIntent().hasExtra("page")) {
            startPage = getIntent().getIntExtra("page", 0);
        }
        root = new SwipeRoot(this);
        setContentView(root);
        buildFrame();
        reloadApps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packages, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packages, f);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(packages);
        } catch (Exception ignored) {
            // not registered
        }
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    /** Home pressed while already home: close whatever is open and go to the home page. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (applyTestExtras(intent)) {
            buildFrame();
        }
        if (intent != null && "drawer".equals(intent.getStringExtra("open"))) {
            ui.postDelayed(() -> openDrawer(false), 300);
            return;
        }
        if (intent != null && "folder".equals(intent.getStringExtra("open"))) {
            ui.postDelayed(this::openFirstFolder, 300);
            return;
        }
        if (intent != null && "edit".equals(intent.getStringExtra("open"))) {
            ui.postDelayed(() -> setEditing(true), 300);
            return;
        }
        closeFolder();
        closeDrawer();
        setEditing(false);
        if (pager != null) {
            int p = intent != null && intent.hasExtra("page") ? intent.getIntExtra("page", 0) : store.homePage;
            ui.postDelayed(() -> pager.goTo(p, true), 120);
        }
    }

    @Override
    public void onBackPressed() {
        if (folderLayer != null && folderLayer.getVisibility() == View.VISIBLE) {
            closeFolder();
        } else if (editing) {
            setEditing(false);
        } else {
            closeDrawer();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildFrame();
    }

    /** For the automatic emulator test: --es theme light|dark|phone. */
    private boolean applyTestExtras(Intent i) {
        if (i == null) {
            return false;
        }
        String theme = i.getStringExtra("theme");
        if ("light".equals(theme) || "dark".equals(theme) || "phone".equals(theme)) {
            prefs.edit().putString("theme", theme).apply();
            return true;
        }
        return false;
    }

    private boolean gone() {
        return isFinishing() || isDestroyed();
    }

    private void reloadApps() {
        bg.execute(() -> {
            List<Store.App> list = store.queryApps();
            ui.post(() -> {
                if (gone()) {
                    return;
                }
                store.setApps(list);
                store.forgetIcons();
                if (!appsReady) {
                    store.load();
                    appsReady = true;
                    if (startPage < 0) {
                        startPage = store.homePage;
                    }
                }
                renderAll();
                Intent in = getIntent();
                if (in != null && "drawer".equals(in.getStringExtra("open"))) {
                    in.removeExtra("open");
                    ui.postDelayed(() -> openDrawer(false), 400);
                } else if (in != null && "folder".equals(in.getStringExtra("open"))) {
                    in.removeExtra("open");
                    ui.postDelayed(this::openFirstFolder, 400);
                }
            });
        });
    }

    private void openFirstFolder() {
        for (int pass = 0; pass < 2; pass++) {
            for (Store.Section s : store.sections) {
                if (pass == 0 && s.page != pager.page) {
                    continue;
                }
                for (Store.Item it : s.items) {
                    if ("folder".equals(it.type)) {
                        openFolder(it);
                        return;
                    }
                }
            }
        }
    }

    // ================================================================== colors (drawer, folders, menus)

    private boolean isDark() {
        String t = prefs.getString("theme", "phone");
        if ("dark".equals(t)) {
            return true;
        }
        if ("light".equals(t)) {
            return false;
        }
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void palette() {
        dark = isDark();
        ground = dark ? 0xFF0E1015 : 0xFFF2F4F7;
        card = dark ? 0xFF1C1F27 : 0xFFFFFFFF;
        text = dark ? 0xFFF2F3F6 : K;
        sub = dark ? 0xFFA7ADBA : 0xFF4A5160;
        chip = dark ? 0xFF2A2E38 : 0xFFE4E7EC;
    }

    private void setBars(int color, boolean darkIcons) {
        Window w = getWindow();
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color);
        View d = w.getDecorView();
        int flags = d.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        d.setSystemUiVisibility(darkIcons ? (flags | light) : (flags & ~light));
    }

    private void homeBars() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
            setBars(ground, !dark);
        } else {
            setBars(Color.TRANSPARENT, false);
        }
    }

    // ================================================================== small helpers

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tv(String s, float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void shadow(TextView t) {
        t.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private ImageView icon(int res, int sizeDp, int tint) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private Drawable tinted(int res, int color) {
        Drawable dr = getDrawable(res);
        if (dr != null) {
            dr = dr.mutate();
            dr.setTint(color);
        }
        return dr;
    }

    private void bindIcon(ImageView iv, Store.App a) {
        iv.setTag(a == null ? null : a.key);
        if (a == null) {
            iv.setImageDrawable(null);
            return;
        }
        Bitmap b = store.cachedIcon(a.key);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(null);
        bg.execute(() -> {
            Bitmap loaded = store.loadIcon(a);
            ui.post(() -> {
                if (!gone() && a.key.equals(iv.getTag()) && loaded != null) {
                    iv.setImageBitmap(loaded);
                }
            });
        });
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    // ================================================================== the frame: pages, dots, dock, drawer, folder layer

    /** Press colors: cyan, magenta, yellow, key (black). */
    private static final int[] PRESS = {0xFF00AEEF, 0xFFEC008C, 0xFFFFE600, 0xFF14161C};
    private static final String[] PRESS_NAMES = {"Cyan", "Magenta", "Yellow", "Black", "No color"};

    private Typeface display, strong;
    private boolean editing;
    private LinearLayout editBar;
    private TextView editPill;
    private Store.Item dragging;

    private void loadFonts() {
        if (display != null) {
            return;
        }
        try {
            display = Typeface.createFromAsset(getAssets(), "fonts/BricolageGrotesque_800ExtraBold.ttf");
            strong = Typeface.createFromAsset(getAssets(), "fonts/DMSans_700Bold.ttf");
        } catch (Exception e) {
            display = Typeface.DEFAULT_BOLD;
            strong = Typeface.DEFAULT_BOLD;
        }
    }

    private void buildFrame() {
        if (gone()) {
            return;
        }
        palette();
        loadFonts();
        root.removeAllViews();
        int keep = pager == null ? -1 : pager.page;

        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setFitsSystemWindows(true);

        editBar = new LinearLayout(this);
        editBar.setGravity(Gravity.CENTER_VERTICAL);
        editBar.setBackground(round(0xE614161C, 20));
        editBar.setPadding(dp(16), dp(8), dp(8), dp(8));
        TextView et = tv("Customizing · press and hold an icon, then drag it", 13, true, Color.WHITE);
        et.setTypeface(strong);
        editBar.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = tv("Done", 15, true, K);
        done.setTypeface(strong);
        done.setBackground(round(PRESS[2], 16));
        done.setPadding(dp(18), dp(10), dp(18), dp(10));
        done.setOnClickListener(v -> setEditing(false));
        editBar.addView(done);
        editBar.setVisibility(editing ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams ebl = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ebl.setMargins(dp(SIDE), dp(6), dp(SIDE), dp(2));
        home.addView(editBar, ebl);

        pager = new Pager(this);
        if (keep >= 0) {
            pager.page = keep;
        }
        pagesRow = new LinearLayout(this);
        pager.addView(pagesRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        home.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout dotsRow = new FrameLayout(this);
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(8), 0, dp(8));
        dotsRow.addView(dots, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        editPill = tv("✎ Edit", 12, true, Color.WHITE);
        editPill.setTypeface(strong);
        editPill.setBackground(round(0x66000000, 14));
        editPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        editPill.setContentDescription("Customize the home screen");
        editPill.setOnClickListener(v -> setEditing(!editing));
        FrameLayout.LayoutParams epl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        epl.rightMargin = dp(14);
        dotsRow.addView(editPill, epl);
        home.addView(dotsRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dockRow = new LinearLayout(this);
        dockRow.setPadding(dp(6), 0, dp(6), dp(8));
        home.addView(dockRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = buildDrawer();
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        folderLayer = new FrameLayout(this);
        folderLayer.setBackgroundColor(0x99000000);
        folderLayer.setVisibility(View.GONE);
        folderLayer.setOnClickListener(v -> closeFolder());
        root.addView(folderLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        homeBars();
        renderAll();
    }

    private void setEditing(boolean on) {
        if (editing == on) {
            return;
        }
        editing = on;
        dragging = null;
        if (editBar != null) {
            editBar.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (editPill != null) {
            editPill.setText(on ? "Done" : "✎ Edit");
        }
        store.trimPages();
        renderPages();
    }

    private void renderAll() {
        if (gone() || pager == null || !appsReady) {
            return;
        }
        if (pager.getWidth() == 0 || pager.getHeight() == 0) {
            pager.post(this::renderAll);   // wait until the screen has a size
            return;
        }
        renderPages();
        renderDock();
        if (drawer.getVisibility() == View.VISIBLE) {
            renderDrawer();
        }
    }

    // ================================================================== pages of labeled sections

    private static final int SIDE = 8;   // dp of space at the page edges

    private void renderPages() {
        int pageW = pager.getWidth();
        int pageH = pager.getHeight();
        if (pageW == 0 || pageH == 0) {
            if (pager != null) {
                pager.post(this::renderAll);
            }
            return;
        }
        cellW = (pageW - dp(SIDE * 2) - dp(12)) / Math.max(1, store.cols);
        int keep = startPage >= 0 ? startPage : pager.page;
        startPage = -1;
        pagesRow.removeAllViews();
        pageScrolls.clear();
        cards.clear();
        int count = Math.max(1, store.pages) + (editing ? 1 : 0);   // a spare blank page while customizing
        for (int p = 0; p < count; p++) {
            pagesRow.addView(buildPage(p), new LinearLayout.LayoutParams(pageW, pageH));
        }
        pager.pageCount = count;
        final int target = Math.max(0, Math.min(keep, count - 1));
        pager.page = target;
        renderDots(target);
        pager.post(() -> pager.goTo(target, false));
    }

    private View buildPage(int p) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(SIDE), dp(10), dp(SIDE), dp(10));
        col.setOnLongClickListener(v -> {
            if (editing) {
                pageMenu(p);
            } else {
                setEditing(true);
            }
            return true;
        });
        sv.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        List<Store.Section> list = store.onPage(p);
        for (Store.Section s : list) {
            View v = s.clock ? clockView(s) : s.card ? cardView(s) : sectionView(s);
            LinearLayout.LayoutParams l = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l.bottomMargin = dp(10);
            col.addView(v, l);
        }
        if (editing) {
            col.addView(newSectionDrop(p));
            col.addView(pageTools(p));
        } else if (list.isEmpty()) {
            TextView hint = tv("Empty page\nTap Edit to add a section", 14, true, Color.WHITE);
            hint.setTypeface(strong);
            shadow(hint);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(dp(4), 1f);
            hint.setPadding(0, dp(120), 0, 0);
            col.addView(hint, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        pageScrolls.add(sv);
        return sv;
    }

    private int iconSize() {
        return Math.max(dp(30), Math.min(Math.round(cellW * 0.74f), dp(56)));
    }

    private GradientDrawable panel() {
        return round(0x66000000, 22);
    }

    /** A small press-style color swatch. */
    private View swatch(int colorIndex, int sizeDp) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(3));
        if (colorIndex >= 0 && colorIndex < 4) {
            g.setColor(PRESS[colorIndex]);
            if (colorIndex == 3) {
                g.setStroke(dp(1.5f), 0xCCFFFFFF);
            }
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(1.5f), 0xAAFFFFFF, dp(3), dp(2));
        }
        v.setBackground(g);
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** A small round button used on the panels while customizing. */
    private TextView chipButton(String label, View.OnClickListener click) {
        TextView b = tv(label, 12, true, Color.WHITE);
        b.setTypeface(strong);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(0x33FFFFFF, 12));
        b.setPadding(dp(10), dp(7), dp(10), dp(7));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams l = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.leftMargin = dp(5);
        b.setLayoutParams(l);
        return b;
    }

    /** The row of buttons on top of each panel while customizing. */
    private View editRow(Store.Section s) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(6), dp(4), dp(2), dp(6));
        if (!s.special()) {
            View sw = swatch(s.color, 22);
            sw.setContentDescription("Section color");
            sw.setOnClickListener(v -> {
                s.color = s.color >= 3 ? -1 : s.color + 1;
                saveAndRender();
            });
            r.addView(sw);
            TextView name = tv(s.name.isEmpty() ? "No heading ✎" : s.name.toUpperCase(Locale.US) + " ✎", 13, true, Color.WHITE);
            name.setTypeface(display);
            name.setLetterSpacing(0.06f);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(10), 0, dp(4), 0);
            name.setOnClickListener(v -> askText("Section heading", s.name, t -> {
                s.name = t;
                saveAndRender();
            }));
            r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(chipButton("+ Apps", v -> sectionApps(s)));
        } else {
            TextView name = tv(sectionName(s).toUpperCase(Locale.US), 13, true, Color.WHITE);
            name.setTypeface(display);
            name.setLetterSpacing(0.06f);
            name.setPadding(dp(4), 0, dp(4), 0);
            r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        r.addView(chipButton("▲", v -> {
            store.shift(s, -1);
            renderPages();
        }));
        r.addView(chipButton("▼", v -> {
            store.shift(s, 1);
            renderPages();
        }));
        r.addView(chipButton("⋯", v -> sectionMenu(s)));
        return r;
    }

    /** One labeled group: a heading with its press color, then its icons 8 (or your setting) across. */
    private View sectionView(Store.Section s) {
        List<Store.Item> shown = new ArrayList<>();
        for (Store.Item it : s.items) {
            if ("folder".equals(it.type) || store.app(it.key) != null) {
                shown.add(it);
            }
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(panel());
        box.setPadding(dp(6), s.name.isEmpty() && !editing ? dp(8) : dp(6), dp(6), dp(6));
        box.setOnLongClickListener(v -> {
            if (editing) {
                sectionMenu(s);
            } else {
                setEditing(true);
            }
            return true;
        });
        box.setOnDragListener((v, e) -> dropOnSection(v, e, s, -1));

        if (editing) {
            box.addView(editRow(s));
        } else if (!s.name.isEmpty()) {
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(8), dp(6), dp(8), dp(6));
            if (s.color >= 0) {
                View sw = swatch(s.color, 11);
                LinearLayout.LayoutParams sl = lp(dp(11), dp(11));
                sl.rightMargin = dp(9);
                head.addView(sw, sl);
            }
            TextView h = tv(s.name.toUpperCase(Locale.US), 13, true, Color.WHITE);
            h.setTypeface(display);
            h.setLetterSpacing(0.07f);
            head.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(regMark(14, 0x88FFFFFF));
            head.setOnClickListener(v -> sectionMenu(s));
            head.setOnLongClickListener(v -> {
                setEditing(true);
                return true;
            });
            box.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (shown.isEmpty()) {
            TextView e = tv(editing ? "Drag apps here, or tap + Apps" : "No apps yet — tap the heading to add some", 12, false, 0xDDFFFFFF);
            e.setPadding(dp(8), dp(10), dp(8), dp(12));
            e.setOnClickListener(v -> sectionApps(s));
            box.addView(e);
            return box;
        }
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % store.cols == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.topMargin = i == 0 ? 0 : dp(6);
                box.addView(row, rl);
            }
            Store.Item it = shown.get(i);
            View cell = "folder".equals(it.type) ? folderView(it) : appView(it, store.app(it.key));
            final int at = s.items.indexOf(it);
            cell.setOnDragListener((v, e) -> dropOnSection(v, e, s, at));
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        int fill = (store.cols - shown.size() % store.cols) % store.cols;
        for (int i = 0; i < fill && row != null; i++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        return box;
    }

    /** A registration mark (the circle-and-cross from a press sheet). */
    private View regMark(int sizeDp, int color) {
        View v = new View(this) {
            private final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.32f;
                pt.setColor(color);
                pt.setStyle(android.graphics.Paint.Style.STROKE);
                pt.setStrokeWidth(Math.max(1.5f, w / 12f));
                c.drawCircle(w / 2, h / 2, r, pt);
                c.drawLine(w / 2, 0, w / 2, h, pt);
                c.drawLine(0, h / 2, w, h / 2, pt);
            }
        };
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** The cyan-magenta-yellow-black bar printed along the edge of every press sheet. */
    private View colorBar(int squareDp) {
        LinearLayout bar = new LinearLayout(this);
        for (int i = 0; i < 4; i++) {
            View sq = swatch(i, squareDp);
            LinearLayout.LayoutParams l = lp(dp(squareDp), dp(squareDp));
            if (i > 0) {
                l.leftMargin = dp(3);
            }
            bar.addView(sq, l);
        }
        return bar;
    }

    // ---------------------------------------------------------------- dragging icons while customizing

    private boolean dropOnSection(View v, android.view.DragEvent e, Store.Section s, int at) {
        switch (e.getAction()) {
            case android.view.DragEvent.ACTION_DRAG_STARTED:
                return editing && dragging != null;
            case android.view.DragEvent.ACTION_DRAG_ENTERED:
                if (at >= 0) {
                    v.setAlpha(0.45f);
                }
                return true;
            case android.view.DragEvent.ACTION_DRAG_EXITED:
            case android.view.DragEvent.ACTION_DRAG_ENDED:
                v.setAlpha(1f);
                return true;
            case android.view.DragEvent.ACTION_DROP:
                v.setAlpha(1f);
                Store.Item it = dragging;
                dragging = null;
                if (it == null) {
                    return false;
                }
                Store.Section from = store.sectionOf(it);
                int target = at;
                if (from == s && target >= 0) {
                    int was = s.items.indexOf(it);
                    if (was == target) {
                        return true;
                    }
                    if (was < target) {
                        target--;
                    }
                }
                if (from != null) {
                    from.items.remove(it);
                }
                if (target < 0 || target > s.items.size()) {
                    s.items.add(it);
                } else {
                    s.items.add(target, it);
                }
                store.save();
                ui.post(this::renderPages);
                return true;
            default:
                return true;
        }
    }

    /** "Drop here for a new section" box at the bottom of each page while customizing. */
    private View newSectionDrop(int p) {
        TextView t = tv("+ Drop an app here to start a new section\nor tap to add an empty one", 13, true, Color.WHITE);
        t.setTypeface(strong);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(3), 1f);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(0x33000000);
        g.setStroke(dp(2), 0xBBFFFFFF, dp(8), dp(6));
        t.setBackground(g);
        t.setPadding(dp(12), dp(18), dp(12), dp(18));
        t.setOnClickListener(v -> addSection(p, null));
        t.setOnDragListener((v, e) -> {
            switch (e.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return editing && dragging != null;
                case android.view.DragEvent.ACTION_DRAG_ENTERED:
                    v.setAlpha(0.6f);
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    v.setAlpha(1f);
                    return true;
                case android.view.DragEvent.ACTION_DROP:
                    Store.Item it = dragging;
                    dragging = null;
                    if (it != null) {
                        ui.post(() -> addSection(p, it));
                    }
                    return true;
                default:
                    return true;
            }
        });
        LinearLayout.LayoutParams l = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.bottomMargin = dp(10);
        t.setLayoutParams(l);
        return t;
    }

    private void addSection(int p, Store.Item first) {
        askText("New section name", "", t -> {
            Store.Section ns = new Store.Section();
            ns.name = t;
            ns.page = p;
            ns.color = store.sections.size() % 4;
            if (first != null) {
                Store.Section from = store.sectionOf(first);
                if (from != null) {
                    from.items.remove(first);
                }
                ns.items.add(first);
            }
            store.sections.add(ns);
            store.pages = Math.max(store.pages, p + 1);
            saveAndRender();
            if (first == null) {
                sectionApps(ns);
            }
        });
    }

    /** Buttons at the bottom of a page while customizing. */
    private View pageTools(int p) {
        boolean hasCard = false, hasClock = false;
        for (Store.Section s : store.sections) {
            hasCard |= s.card;
            hasClock |= s.clock;
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER);
        if (!hasClock) {
            r1.addView(chipButton("+ Clock", v -> addSpecial(p, true)));
        }
        if (!hasCard) {
            r1.addView(chipButton("+ Weather card", v -> addSpecial(p, false)));
        }
        r1.addView(chipButton(p == store.homePage ? "★ Home page" : "Make home page", v -> {
            store.homePage = p;
            saveAndRender();
        }));
        r1.addView(chipButton("Settings", v -> openSettings()));
        wrap.addView(r1);
        TextView n = tv(p >= store.pages ? "A spare page — drop apps here to use it" : "Page " + (p + 1), 12, false, 0xCCFFFFFF);
        n.setPadding(0, dp(10), 0, 0);
        shadow(n);
        wrap.addView(n);
        return wrap;
    }

    private void addSpecial(int p, boolean clock) {
        Store.Section c = new Store.Section();
        c.clock = clock;
        c.card = !clock;
        c.page = p;
        int at = store.sections.size();
        for (int i = 0; i < store.sections.size(); i++) {
            if (store.sections.get(i).page == p) {
                at = clock ? i : i + 1;
                break;
            }
        }
        store.sections.add(Math.min(at, store.sections.size()), c);
        store.pages = Math.max(store.pages, p + 1);
        saveAndRender();
    }

    // ---------------------------------------------------------------- icons

    private LinearLayout cellBox(View iconView, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(2), 0, dp(2));
        int s = iconSize();
        box.addView(iconView, lp(s, s));
        TextView l = tv(label, 10.5f, false, Color.WHITE);
        shadow(l);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(1), dp(4), dp(1), 0);
        box.addView(l, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setContentDescription(label);
        return box;
    }

    /** While customizing, an icon gets a small remove badge and can be dragged. */
    private View editable(View cellView, Store.Item it) {
        if (!editing) {
            return cellView;
        }
        FrameLayout f = new FrameLayout(this);
        f.addView(cellView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView x = tv("−", 15, true, Color.WHITE);
        x.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(PRESS[1]);
        g.setStroke(dp(1.5f), Color.WHITE);
        x.setBackground(g);
        x.setContentDescription("Remove " + itemName(it));
        x.setOnClickListener(v -> {
            Store.Section s = store.sectionOf(it);
            if (s != null) {
                s.items.remove(it);
                saveAndRender();
            }
        });
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
        f.addView(x, xl);
        f.setOnLongClickListener(v -> startDrag(cellView, it));
        cellView.setOnLongClickListener(v -> startDrag(cellView, it));
        cellView.setOnClickListener(v -> itemMenu(it));
        return f;
    }

    private boolean startDrag(View v, Store.Item it) {
        dragging = it;
        v.startDragAndDrop(android.content.ClipData.newPlainText("app", itemName(it)), new View.DragShadowBuilder(v), it,
                View.DRAG_FLAG_OPAQUE);
        v.setAlpha(0.3f);
        return true;
    }

    private View appView(Store.Item it, Store.App a) {
        ImageView iv = new ImageView(this);
        bindIcon(iv, a);
        LinearLayout box = cellBox(iv, a.label);
        box.setOnClickListener(v -> launch(a, v));
        box.setOnLongClickListener(v -> {
            itemMenu(it);
            return true;
        });
        return editable(box, it);
    }

    private View folderIcon(Store.Item it, int size) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(0x80FFFFFF);
        bgShape.setCornerRadius(size * 0.3f);
        g.setBackground(bgShape);
        int pad = Math.round(size * 0.13f);
        g.setPadding(pad, pad, pad, pad);
        int gap = Math.max(1, Math.round(size * 0.05f));
        int mini = Math.max(1, (size - pad * 2 - gap) / 2);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                ImageView iv = new ImageView(this);
                if (i < it.apps.size()) {
                    bindIcon(iv, store.app(it.apps.get(i)));
                }
                LinearLayout.LayoutParams l = lp(mini, mini);
                if (c == 1) {
                    l.leftMargin = gap;
                }
                row.addView(iv, l);
            }
            LinearLayout.LayoutParams rl = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r == 1) {
                rl.topMargin = gap;
            }
            g.addView(row, rl);
        }
        return g;
    }

    private View folderView(Store.Item it) {
        LinearLayout box = cellBox(folderIcon(it, iconSize()), itemName(it));
        box.setOnClickListener(v -> openFolder(it));
        box.setOnLongClickListener(v -> {
            itemMenu(it);
            return true;
        });
        return editable(box, it);
    }

    // ---------------------------------------------------------------- the clock

    private View clockView(Store.Section s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(panel());
        box.setPadding(dp(18), dp(10), dp(16), dp(14));
        if (editing) {
            box.addView(editRow(s));
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        android.widget.TextClock time = new android.widget.TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 58);
        time.setTypeface(display);
        time.setTextColor(Color.WHITE);
        time.setIncludeFontPadding(false);
        time.setLetterSpacing(-0.02f);
        time.setOnClickListener(v -> openClockApp());
        left.addView(time);
        android.widget.TextClock date = new android.widget.TextClock(this);
        date.setFormat12Hour("EEEE · MMM d");
        date.setFormat24Hour("EEEE · MMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        date.setTypeface(strong);
        date.setAllCaps(true);
        date.setLetterSpacing(0.1f);
        date.setTextColor(0xFFE6E8EE);
        date.setPadding(dp(2), dp(4), 0, 0);
        date.setOnClickListener(v -> openCalendarApp());
        left.addView(date);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(regMark(26, 0xCCFFFFFF));
        View bar = colorBar(12);
        LinearLayout.LayoutParams bl = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(12);
        right.addView(bar, bl);
        row.addView(right);
        box.addView(row);

        box.setOnClickListener(v -> openClockApp());
        box.setOnLongClickListener(v -> {
            if (editing) {
                sectionMenu(s);
            } else {
                setEditing(true);
            }
            return true;
        });
        return box;
    }

    private void openClockApp() {
        if (editing) {
            return;
        }
        Store.App a = store.match("clock", "samsung clock");
        if (a != null) {
            launch(a, null);
            return;
        }
        try {
            Intent i = new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
            // no clock app
        }
    }

    private void openCalendarApp() {
        if (editing) {
            return;
        }
        Store.App a = store.match("calendar", "samsung calendar", "google calendar");
        if (a != null) {
            launch(a, null);
        }
    }

    // ---------------------------------------------------------------- the weather + InkFlow card

    private View cardView(Store.Section s) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(panel());
        if (editing) {
            outer.setPadding(dp(6), dp(6), dp(6), 0);
            outer.addView(editRow(s));
        }
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        outer.addView(box, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView wi = icon(R.drawable.wx_cloud, 34, Color.WHITE);
        box.addView(wi);
        TextView wt = tv("--°", 32, true, Color.WHITE);
        wt.setTypeface(display);
        wt.setPadding(dp(10), 0, dp(12), 0);
        box.addView(wt);

        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView ww = tv("", 13f, false, 0xFFE6E8EE);
        ww.setSingleLine(true);
        ww.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(ww);
        TextView ink = tv("", 13f, true, Color.WHITE);
        ink.setTypeface(strong);
        ink.setSingleLine(true);
        ink.setEllipsize(TextUtils.TruncateAt.END);
        ink.setPadding(0, dp(6), 0, 0);
        GradientDrawable dot = new GradientDrawable();
        dot.setCornerRadius(dp(2));
        dot.setSize(dp(9), dp(9));
        dot.setColor(PRESS[1]);
        ink.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
        ink.setCompoundDrawablePadding(dp(7));
        ink.setOnClickListener(v -> {
            if (editing) {
                return;
            }
            if (signedIn()) {
                openInkFlow("/dashboard");
            } else {
                openSettings();
            }
        });
        lines.addView(ink, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(lines, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        box.setOnClickListener(v -> {
            if (!editing) {
                openWeatherApp();
            }
        });
        View.OnLongClickListener hold = v -> {
            if (editing) {
                sectionMenu(s);
            } else {
                setEditing(true);
            }
            return true;
        };
        box.setOnLongClickListener(hold);
        ink.setOnLongClickListener(hold);

        box.setTag(new Object[]{wi, wt, ww, ink});
        cards.add(box);
        fillCard(box);
        return outer;
    }

    private String deg(double v) {
        return Double.isNaN(v) ? "--°" : Math.round(v) + "°";
    }

    private void fillCard(View pad) {
        Object[] parts = (Object[]) pad.getTag();
        ImageView wi = (ImageView) parts[0];
        TextView wt = (TextView) parts[1];
        TextView ww = (TextView) parts[2];
        TextView ink = (TextView) parts[3];
        Weather.Now w = weather;
        if (w == null || Double.isNaN(w.temp)) {
            wt.setText("--°");
            ww.setText("Weather loading…");
            ww.setTextColor(0xFFE6E8EE);
        } else {
            wi.setImageResource(Weather.icon(w.words, w.day));
            wt.setText(deg(w.temp));
            String hl = "Tonight".equals(w.when)
                    ? (Double.isNaN(w.lo) ? "" : "Low " + deg(w.lo))
                    : ((Double.isNaN(w.hi) ? "" : "High " + deg(w.hi)) + (Double.isNaN(w.lo) ? "" : " · Low " + deg(w.lo)));
            String line = w.words + (hl.trim().isEmpty() ? "" : " · " + hl.trim());
            if (w.alert != null && !w.alert.isEmpty()) {
                line = "⚠ " + w.alert;
                ww.setTextColor(M);
            } else {
                ww.setTextColor(0xFFE6E8EE);
            }
            ww.setText(line);
        }
        if (!signedIn()) {
            ink.setText("Sign in to InkFlow");
        } else {
            String o = ordersText();
            String pr = printersText();
            ink.setText("InkFlow · " + o + (pr.isEmpty() ? "" : " · " + pr));
        }
    }

    private void refreshCards() {
        for (View v : cards) {
            fillCard(v);
        }
    }

    // ---------------------------------------------------------------- dots + dock

    /** Page dots in press colors: the current page is a bigger square. */
    private void renderDots(int current) {
        if (dots == null || pager == null) {
            return;
        }
        dots.removeAllViews();
        int count = pager.pageCount;
        for (int i = 0; i < count; i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            boolean on = i == current;
            g.setCornerRadius(dp(on ? 2 : 4));
            g.setColor(on ? PRESS[i % 3] : 0x99FFFFFF);
            if (on) {
                g.setStroke(dp(1), Color.WHITE);
            }
            d.setBackground(g);
            int size = on ? 10 : 7;
            LinearLayout.LayoutParams l = lp(dp(size), dp(size));
            l.leftMargin = dp(5);
            l.rightMargin = dp(5);
            l.gravity = Gravity.CENTER_VERTICAL;
            final int target = i;
            d.setOnClickListener(v -> pager.goTo(target, true));
            dots.addView(d, l);
        }
    }

    private void renderDock() {
        dockRow.removeAllViews();
        int s = Math.min(iconSize() + dp(6), dp(56));
        for (int i = 0; i < 5; i++) {
            final int spot = i;
            Store.App a = store.app(i < store.dock.size() ? store.dock.get(i) : "");
            View iconView;
            if (a == null) {
                ImageView plus = icon(R.drawable.ic_plus, 24, 0xCCFFFFFF);
                plus.setPadding(dp(10), dp(10), dp(10), dp(10));
                iconView = plus;
            } else {
                ImageView iv = new ImageView(this);
                bindIcon(iv, a);
                iconView = iv;
            }
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.addView(iconView, lp(s, s));
            TextView l = tv(a == null ? "" : a.label, 11f, false, Color.WHITE);
            shadow(l);
            l.setSingleLine(true);
            l.setEllipsize(TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER_HORIZONTAL);
            l.setPadding(dp(2), dp(4), dp(2), 0);
            box.addView(l, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setContentDescription(a == null ? "Empty dock spot" : a.label);
            box.setOnClickListener(v -> {
                if (a != null) {
                    launch(a, v);
                } else {
                    pickApp("Pick an app for the dock", picked -> setDock(spot, picked.key));
                }
            });
            box.setOnLongClickListener(v -> {
                dockMenu(spot);
                return true;
            });
            dockRow.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void setDock(int spot, String key) {
        while (store.dock.size() <= spot) {
            store.dock.add("");
        }
        store.dock.set(spot, key);
        store.save();
        renderDock();
    }

    // ================================================================== opening things

    private void launch(Store.App a, View from) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(a.pkg, a.cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Bundle opts = from == null || from.getWidth() == 0 ? null
                    : ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(i, opts);
            store.noteLaunch(a.key);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn’t open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn’t open that link", Toast.LENGTH_SHORT).show();
        }
    }

    private String inkUrl() {
        return prefs.getString("if_url", "https://inkflow.up.railway.app");
    }

    private void openInkFlow(String path) {
        openUrl(inkUrl() + path);
    }

    private void openWeatherApp() {
        Store.App a = store.app(prefs.getString("wx_app", ""));
        if (a == null) {
            a = store.match("weather", "the weather channel", "accuweather", "weather forecast");
        }
        if (a != null) {
            launch(a, null);
        } else {
            Weather.Place pl = Weather.place(prefs);
            openUrl(String.format(Locale.US, "https://forecast.weather.gov/MapClick.php?lat=%.4f&lon=%.4f", pl.lat, pl.lon));
        }
    }

    /** Pulls down the notification shade, like swiping down on Samsung's home screen. */
    private void openNotifications() {
        try {
            Object sbs = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbs);
        } catch (Exception ignored) {
            // not allowed on this phone: nothing happens
        }
    }

    private void appInfo(Store.App a) {
        if (a == null) {
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
            // nothing to open
        }
    }

    // ================================================================== weather + InkFlow data

    private void refreshWeather(boolean force) {
        if (wxBusy) {
            return;
        }
        if (!force && weather != null && Weather.place(prefs).name.equals(weather.place)
                && System.currentTimeMillis() - weather.at < WX_EVERY) {
            return;
        }
        wxBusy = true;
        bg.execute(() -> {
            Weather.Now n = Weather.fetch(prefs);
            ui.post(() -> {
                wxBusy = false;
                if (n != null) {
                    weather = n;
                }
                if (!gone()) {
                    refreshCards();
                }
            });
        });
    }

    private boolean signedIn() {
        return !prefs.getString("if_token", "").isEmpty();
    }

    private String ordersText() {
        if (live == null) {
            return liveError.isEmpty() ? "checking…" : liveError;
        }
        JSONObject c = live.optJSONObject("counts");
        if (c == null) {
            return "";
        }
        int due = c.optInt("dueToday"), late = c.optInt("late");
        return due + " due" + (late > 0 ? " · " + late + " late" : "");
    }

    private String printersText() {
        if (live == null) {
            return "";
        }
        JSONArray ps = live.optJSONArray("printers");
        if (ps == null) {
            return "";
        }
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && p.optBoolean("online") && p.optBoolean("busy")) {
                double pct = p.optDouble("pct", Double.NaN);
                return "printing" + (Double.isNaN(pct) ? "" : " " + Math.round(pct) + "%");
            }
        }
        return "";
    }

    private void refreshLive(boolean force) {
        if (!signedIn() || liveBusy) {
            return;
        }
        if (!force && System.currentTimeMillis() - liveAt < 60_000L) {
            return;
        }
        liveBusy = true;
        String token = prefs.getString("if_token", "");
        String base = inkUrl();
        bg.execute(() -> {
            JSONObject data = null;
            String err = "";
            boolean out = false;
            try {
                Net.Reply r = Net.get(base + "/api/mobile/launcher", token, null);
                if (r.ok()) {
                    data = r.json();
                } else if (r.code == 401) {
                    out = true;
                    err = "sign in again";
                } else {
                    err = "answered " + r.code;
                }
            } catch (Exception e) {
                err = "offline";
            }
            final JSONObject d = data;
            final String e2 = err;
            final boolean signedOut = out;
            ui.post(() -> {
                liveBusy = false;
                liveAt = System.currentTimeMillis();
                if (signedOut) {
                    prefs.edit().remove("if_token").apply();
                }
                if (d != null) {
                    live = d;
                    liveError = "";
                    prefs.edit().putString("if_live", d.toString()).apply();
                } else {
                    liveError = e2;
                }
                if (!gone()) {
                    refreshCards();
                }
            });
        });
    }

    // ================================================================== editing the home screen

    private interface AppPicked {
        void picked(Store.App a);
    }

    private void pickApp(String title, AppPicked cb) {
        if (store.apps.isEmpty()) {
            return;
        }
        String[] names = new String[store.apps.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = store.apps.get(i).label;
        }
        dialog().setTitle(title).setItems(names, (d, which) -> cb.picked(store.apps.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    private String itemName(Store.Item it) {
        if ("folder".equals(it.type)) {
            return it.name == null || it.name.isEmpty() ? "Folder" : it.name;
        }
        Store.App a = store.app(it.key);
        return a == null ? "App" : a.label;
    }

    private String sectionName(Store.Section s) {
        if (s.card) {
            return "Weather & InkFlow card";
        }
        if (s.clock) {
            return "Clock";
        }
        return s.name.isEmpty() ? "Everyday apps" : s.name;
    }

    private void saveAndRender() {
        store.save();
        renderPages();
    }

    /** Press and hold an app or folder on a page. */
    private void itemMenu(Store.Item it) {
        Store.Section s = store.sectionOf(it);
        if (s == null) {
            return;
        }
        boolean folder = "folder".equals(it.type);
        List<String> opts = new ArrayList<>();
        if (folder) {
            opts.add("Open");
            opts.add("Rename");
            opts.add("Add or remove apps");
        }
        opts.add("Move to another section…");
        opts.add("Move left");
        opts.add("Move right");
        if (!folder) {
            opts.add("Put in a folder…");
            opts.add("App info");
        }
        opts.add(folder ? "Remove folder" : "Remove from home screen");
        dialog().setTitle(itemName(it)).setItems(opts.toArray(new String[0]), (d, which) -> {
            String o = opts.get(which);
            int at = s.items.indexOf(it);
            switch (o) {
                case "Open":
                    openFolder(it);
                    break;
                case "Rename":
                    askText("Rename folder", it.name, t -> {
                        it.name = t;
                        saveAndRender();
                    });
                    break;
                case "Add or remove apps":
                    folderApps(it);
                    break;
                case "Move left":
                    if (at > 0) {
                        s.items.remove(at);
                        s.items.add(at - 1, it);
                        saveAndRender();
                    }
                    break;
                case "Move right":
                    if (at >= 0 && at < s.items.size() - 1) {
                        s.items.remove(at);
                        s.items.add(at + 1, it);
                        saveAndRender();
                    }
                    break;
                case "App info":
                    appInfo(store.app(it.key));
                    break;
                case "Put in a folder…":
                    putInFolder(it, s);
                    break;
                case "Remove folder":
                    dialog().setTitle("Remove " + itemName(it) + "?")
                            .setMessage("The apps stay on your phone and in the app list.")
                            .setPositiveButton("Remove", (d2, w2) -> {
                                s.items.remove(it);
                                saveAndRender();
                            }).setNegativeButton("Keep it", null).show();
                    break;
                case "Remove from home screen":
                    s.items.remove(it);
                    saveAndRender();
                    break;
                default:
                    pickSection("Move " + itemName(it) + " to", s, target -> {
                        s.items.remove(it);
                        target.items.add(it);
                        saveAndRender();
                    });
            }
        }).show();
    }

    private interface SectionPicked {
        void picked(Store.Section s);
    }

    /** Lists every section (with its page) plus "New section"; `skip` is left out. */
    private void pickSection(String title, Store.Section skip, SectionPicked cb) {
        List<Store.Section> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Store.Section x : store.sections) {
            if (x != skip && !x.special()) {
                list.add(x);
                names.add(sectionName(x) + "  ·  page " + (x.page + 1));
            }
        }
        names.add("+ New section");
        dialog().setTitle(title).setItems(names.toArray(new String[0]), (d, w) -> {
            if (w < list.size()) {
                cb.picked(list.get(w));
            } else {
                askText("New section name", "", t -> {
                    Store.Section ns = new Store.Section();
                    ns.name = t;
                    ns.page = pager.page;
                    store.sections.add(ns);
                    cb.picked(ns);
                });
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void putInFolder(Store.Item it, Store.Section s) {
        List<Store.Item> folders = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Store.Item f : s.items) {
            if ("folder".equals(f.type)) {
                folders.add(f);
                names.add(itemName(f));
            }
        }
        names.add("+ New folder here");
        dialog().setTitle("Put " + itemName(it) + " in").setItems(names.toArray(new String[0]), (d, w) -> {
            if (w < folders.size()) {
                Store.Item f = folders.get(w);
                if (!f.apps.contains(it.key)) {
                    f.apps.add(it.key);
                }
                s.items.remove(it);
                saveAndRender();
            } else {
                askText("New folder name", "", t -> {
                    Store.Item f = new Store.Item();
                    f.type = "folder";
                    f.name = t;
                    f.apps.add(it.key);
                    int at = Math.max(0, s.items.indexOf(it));
                    s.items.remove(it);
                    s.items.add(Math.min(at, s.items.size()), f);
                    saveAndRender();
                    folderApps(f);
                });
            }
        }).show();
    }

    /** Press and hold (or tap) a section's heading, or press and hold the weather card. */
    private void sectionMenu(Store.Section s) {
        List<String> opts = new ArrayList<>();
        if (!editing) {
            opts.add("Customize the home screen");
        }
        if (!s.special()) {
            opts.add("Add or remove apps");
            opts.add("Add a folder");
            opts.add(s.name.isEmpty() ? "Give it a heading" : "Rename");
            opts.add("Color…");
        }
        opts.add("Move up");
        opts.add("Move down");
        opts.add("Move to another page…");
        if (!s.special()) {
            opts.add("Add a new section below");
        }
        opts.add(s.clock ? "Hide the clock" : s.card ? "Hide the card" : "Remove section");
        dialog().setTitle(sectionName(s)).setItems(opts.toArray(new String[0]), (d, w) -> {
            String o = opts.get(w);
            switch (o) {
                case "Customize the home screen":
                    setEditing(true);
                    break;
                case "Color…":
                    dialog().setTitle("Color for " + sectionName(s)).setItems(PRESS_NAMES, (d2, c) -> {
                        s.color = c >= 4 ? -1 : c;
                        saveAndRender();
                    }).show();
                    break;
                case "Add or remove apps":
                    sectionApps(s);
                    break;
                case "Add a folder":
                    askText("Folder name", "", t -> {
                        Store.Item f = new Store.Item();
                        f.type = "folder";
                        f.name = t;
                        s.items.add(f);
                        saveAndRender();
                        folderApps(f);
                    });
                    break;
                case "Rename":
                case "Give it a heading":
                    askText("Section heading", s.name, t -> {
                        s.name = t;
                        saveAndRender();
                    });
                    break;
                case "Move up":
                    store.shift(s, -1);
                    renderPages();
                    break;
                case "Move down":
                    store.shift(s, 1);
                    renderPages();
                    break;
                case "Move to another page…":
                    String[] pg = new String[store.pages + 1];
                    for (int i = 0; i < store.pages; i++) {
                        pg[i] = "Page " + (i + 1) + (i == s.page ? " (here now)" : "");
                    }
                    pg[store.pages] = "A new page";
                    dialog().setTitle("Move " + sectionName(s) + " to").setItems(pg, (d2, p) -> {
                        store.movePage(s, p);
                        store.trimPages();
                        startPage = s.page;
                        renderPages();
                    }).show();
                    break;
                case "Add a new section below":
                    askText("New section name", "", t -> {
                        Store.Section ns = new Store.Section();
                        ns.name = t;
                        ns.page = s.page;
                        store.sections.add(store.sections.indexOf(s) + 1, ns);
                        saveAndRender();
                        sectionApps(ns);
                    });
                    break;
                case "Hide the card":
                case "Hide the clock":
                    store.sections.remove(s);
                    saveAndRender();
                    Toast.makeText(this, "Bring it back with Edit at the bottom of the screen", Toast.LENGTH_LONG).show();
                    break;
                default:
                    dialog().setTitle("Remove " + sectionName(s) + "?")
                            .setMessage("Its apps come off the home screen but stay on your phone and in the app list.")
                            .setPositiveButton("Remove", (d2, w2) -> {
                                store.sections.remove(s);
                                saveAndRender();
                            }).setNegativeButton("Keep it", null).show();
            }
        }).show();
    }

    /** Checklist of every app; checked ones are in this section (checking one moves it here). */
    private void sectionApps(Store.Section s) {
        if (store.apps.isEmpty()) {
            return;
        }
        String[] names = new String[store.apps.size()];
        boolean[] on = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            Store.App a = store.apps.get(i);
            boolean here = false;
            for (Store.Item it : s.items) {
                if (a.key.equals(it.key)) {
                    here = true;
                }
            }
            on[i] = here;
            names[i] = a.label + (!here && store.onHome(a.key) ? "  (elsewhere)" : "");
        }
        dialog().setTitle("Apps in " + sectionName(s)).setMultiChoiceItems(names, on, (d, w, checked) -> on[w] = checked)
                .setPositiveButton("Done", (d, w) -> {
                    for (int i = 0; i < names.length; i++) {
                        String k = store.apps.get(i).key;
                        Store.Item here = null;
                        for (Store.Item it : s.items) {
                            if (k.equals(it.key)) {
                                here = it;
                            }
                        }
                        if (on[i] && here == null) {
                            removeEverywhere(k);
                            s.items.add(Store.Item.app(k));
                        } else if (!on[i] && here != null) {
                            s.items.remove(here);
                        }
                    }
                    saveAndRender();
                }).setNegativeButton("Cancel", null).show();
    }

    /** Takes an app off every section and out of every folder (so it isn't on the home screen twice). */
    private void removeEverywhere(String key) {
        for (Store.Section x : store.sections) {
            List<Store.Item> gone = new ArrayList<>();
            for (Store.Item it : x.items) {
                if (key.equals(it.key)) {
                    gone.add(it);
                }
                it.apps.remove(key);
            }
            x.items.removeAll(gone);
        }
    }

    /** Press and hold an empty part of a page while customizing. */
    private void pageMenu(int p) {
        boolean hasCard = false, hasClock = false;
        for (Store.Section s : store.sections) {
            hasCard |= s.card;
            hasClock |= s.clock;
        }
        List<String> opts = new ArrayList<>();
        opts.add("Add a section");
        if (!hasClock) {
            opts.add("Show the clock here");
        }
        if (!hasCard) {
            opts.add("Show the weather & InkFlow card here");
        }
        opts.add("Make this my home page");
        opts.add("Settings");
        dialog().setTitle("Page " + (p + 1)).setItems(opts.toArray(new String[0]), (d, w) -> {
            String o = opts.get(w);
            if (o.startsWith("Add a section")) {
                addSection(p, null);
            } else if (o.startsWith("Show the clock")) {
                addSpecial(p, true);
            } else if (o.startsWith("Show the weather")) {
                addSpecial(p, false);
            } else if (o.startsWith("Make this")) {
                store.homePage = p;
                saveAndRender();
                Toast.makeText(this, "Home goes to page " + (p + 1) + " now", Toast.LENGTH_SHORT).show();
            } else {
                openSettings();
            }
        }).show();
    }

    private void dockMenu(int spot) {
        Store.App a = store.app(spot < store.dock.size() ? store.dock.get(spot) : "");
        List<String> opts = new ArrayList<>();
        opts.add("Choose an app");
        if (a != null) {
            opts.add("Empty this spot");
            opts.add("App info");
        }
        dialog().setTitle(a == null ? "Empty dock spot" : a.label).setItems(opts.toArray(new String[0]), (d, w) -> {
            String o = opts.get(w);
            if (o.startsWith("Choose")) {
                pickApp("Pick an app for the dock", picked -> setDock(spot, picked.key));
            } else if (o.startsWith("Empty")) {
                setDock(spot, "");
            } else {
                appInfo(a);
            }
        }).show();
    }

    // ---------------------------------------------------------------- folders

    private void openFolder(Store.Item f) {
        palette();
        folderLayer.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(round(dark ? 0xF21C1F27 : 0xF7FFFFFF, 28));
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        panel.setClickable(true);

        TextView title = tv(itemName(f), 20, true, text);
        title.setPadding(dp(4), 0, 0, dp(14));
        title.setOnClickListener(v -> askText("Rename folder", f.name, s -> {
            f.name = s;
            store.save();
            renderPages();
            openFolder(f);
        }));
        panel.addView(title);

        GridView g = new GridView(this);
        g.setNumColumns(4);
        g.setVerticalSpacing(dp(14));
        g.setSelector(new ColorDrawable(Color.TRANSPARENT));
        List<Store.App> list = new ArrayList<>();
        for (String k : f.apps) {
            Store.App a = store.app(k);
            if (a != null) {
                list.add(a);
            }
        }
        g.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return list.size();
            }

            @Override
            public Object getItem(int i) {
                return list.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                Store.App a = list.get(i);
                LinearLayout cell = drawerCell(a);
                cell.setOnClickListener(v -> {
                    closeFolder();
                    launch(a, v);
                });
                cell.setOnLongClickListener(v -> {
                    folderAppMenu(f, a);
                    return true;
                });
                return cell;
            }
        });
        int rowsShown = Math.min(4, Math.max(1, (list.size() + 3) / 4));
        panel.addView(g, lp(ViewGroup.LayoutParams.MATCH_PARENT, rowsShown * dp(90)));

        TextView add = tv("+ Add apps", 15, true, text);
        add.setGravity(Gravity.CENTER);
        add.setBackground(round(chip, 14));
        add.setPadding(0, dp(12), 0, dp(12));
        add.setOnClickListener(v -> folderApps(f));
        LinearLayout.LayoutParams alp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        panel.addView(add, alp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        plp.leftMargin = dp(22);
        plp.rightMargin = dp(22);
        folderLayer.addView(panel, plp);
        folderLayer.animate().cancel();
        folderLayer.setVisibility(View.VISIBLE);
        folderLayer.setAlpha(0f);
        folderLayer.animate().alpha(1f).setDuration(140).start();
    }

    private void closeFolder() {
        if (folderLayer == null || folderLayer.getVisibility() != View.VISIBLE) {
            return;
        }
        folderLayer.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            folderLayer.setVisibility(View.GONE);
            folderLayer.removeAllViews();
        }).start();
    }

    private void folderAppMenu(Store.Item f, Store.App a) {
        String[] opts = {"Take out of the folder", "Remove from folder", "App info"};
        dialog().setTitle(a.label).setItems(opts, (d, w) -> {
            if (w == 2) {
                appInfo(a);
                return;
            }
            f.apps.remove(a.key);
            if (w == 0) {
                Store.Section s = store.sectionOf(f);
                if (s != null) {
                    s.items.add(s.items.indexOf(f) + 1, Store.Item.app(a.key));
                }
            }
            saveAndRender();
            if (f.apps.isEmpty()) {
                closeFolder();
            } else {
                openFolder(f);
            }
        }).show();
    }

    private void folderApps(Store.Item f) {
        if (store.apps.isEmpty()) {
            return;
        }
        String[] names = new String[store.apps.size()];
        boolean[] on = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = store.apps.get(i).label;
            on[i] = f.apps.contains(store.apps.get(i).key);
        }
        dialog().setTitle("Apps in " + itemName(f)).setMultiChoiceItems(names, on, (d, w, checked) -> on[w] = checked)
                .setPositiveButton("Done", (d, w) -> {
                    for (int i = 0; i < names.length; i++) {
                        String k = store.apps.get(i).key;
                        if (on[i] && !f.apps.contains(k)) {
                            f.apps.add(k);
                        } else if (!on[i]) {
                            f.apps.remove(k);
                        }
                    }
                    store.save();
                    renderPages();
                    if (folderLayer.getVisibility() == View.VISIBLE) {
                        openFolder(f);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private interface TextDone {
        void done(String s);
    }

    private void askText(String title, String start, TextDone cb) {
        EditText e = new EditText(this);
        e.setText(start);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(e);
        dialog().setTitle(title).setView(box).setPositiveButton("Save", (d, w) -> {
            String s = e.getText().toString().trim();
            if (!s.isEmpty()) {
                cb.done(s);
            }
        }).setNegativeButton("Cancel", null).show();
    }

    // ================================================================== the app drawer (swipe up)

    private LinearLayout buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(ground);
        d.setFitsSystemWindows(true);
        d.setPadding(dp(14), dp(10), dp(14), 0);
        d.setClickable(true);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        search = new EditText(this);
        search.setHint("Search apps, InkFlow, the web");
        search.setHintTextColor(sub);
        search.setTextColor(text);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(round(card, 25));
        search.setPadding(dp(16), 0, dp(12), 0);
        Drawable mag = tinted(R.drawable.ic_search, sub);
        if (mag != null) {
            mag.setBounds(0, 0, dp(20), dp(20));
        }
        search.setCompoundDrawables(mag, null, null, null);
        search.setCompoundDrawablePadding(dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderDrawer();
            }
        });
        search.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                List<Store.App> hits = searchApps(search.getText().toString());
                if (!hits.isEmpty()) {
                    launch(hits.get(0), null);
                    closeDrawer();
                }
                return true;
            }
            return false;
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(50), 1f));
        FrameLayout gear = new FrameLayout(this);
        gear.setBackground(round(card, 25));
        gear.addView(icon(R.drawable.ic_gear, 22, text), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        gear.setContentDescription("Settings");
        gear.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams glp = lp(dp(50), dp(50));
        glp.leftMargin = dp(8);
        searchRow.addView(gear, glp);
        d.addView(searchRow);

        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        recentBox.setPadding(0, dp(14), 0, 0);
        d.addView(recentBox);

        actionsBox = new LinearLayout(this);
        actionsBox.setOrientation(LinearLayout.VERTICAL);
        actionsBox.setPadding(0, dp(12), 0, 0);
        d.addView(actionsBox);

        grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setVerticalSpacing(dp(14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setPadding(0, dp(12), dp(6), dp(24));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state != SCROLL_STATE_IDLE) {
                    hideKeyboard();
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
            }
        });
        d.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return d;
    }

    private LinearLayout drawerCell(Store.App a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        bindIcon(iv, a);
        cell.addView(iv, lp(dp(50), dp(50)));
        TextView l = tv(a.label, 11.5f, false, text);
        l.setMaxLines(2);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(2), dp(5), dp(2), 0);
        cell.addView(l, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setContentDescription(a.label);
        return cell;
    }

    private void openDrawer(boolean focusSearch) {
        if (drawer == null || gone()) {
            return;
        }
        closeFolder();
        search.setText("");
        renderDrawer();
        grid.setSelection(0);
        drawer.animate().cancel();
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationY(dp(160));
        drawer.setAlpha(0f);
        drawer.animate().translationY(0).alpha(1f).setDuration(190).setInterpolator(new DecelerateInterpolator()).start();
        setBars(ground, !dark);
        if (focusSearch) {
            search.requestFocus();
            ui.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 150);
        }
    }

    private void closeDrawer() {
        if (drawer == null || drawer.getVisibility() != View.VISIBLE) {
            return;
        }
        hideKeyboard();
        search.clearFocus();
        drawer.animate().translationY(dp(120)).alpha(0f).setDuration(150).withEndAction(() -> {
            drawer.setVisibility(View.GONE);
            search.setText("");
            homeBars();
        }).start();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && search != null) {
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private List<Store.App> searchApps(String q) {
        String n = Store.norm(q);
        List<Store.App> starts = new ArrayList<>(), contains = new ArrayList<>();
        if (n.isEmpty()) {
            return starts;
        }
        for (Store.App a : store.apps) {
            String l = Store.norm(a.label);
            if (l.startsWith(n) || l.contains(" " + n)) {
                starts.add(a);
            } else if (l.contains(n)) {
                contains.add(a);
            }
        }
        starts.addAll(contains);
        return starts;
    }

    private View actionRow(String label, String note, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(card, 16));
        r.setPadding(dp(16), 0, dp(16), 0);
        r.setMinimumHeight(dp(50));
        TextView l = tv(label, 15, true, text);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(tv(note, 12, false, sub));
        r.setOnClickListener(click);
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(8);
        r.setLayoutParams(p);
        return r;
    }

    private void renderDrawer() {
        if (drawer == null || gone()) {
            return;
        }
        String q = search.getText().toString().trim();
        boolean searching = !q.isEmpty();
        recentBox.setVisibility(searching ? View.GONE : View.VISIBLE);
        actionsBox.setVisibility(searching ? View.VISIBLE : View.GONE);

        if (searching) {
            actionsBox.removeAllViews();
            String n = Store.norm(q);
            int shown = 0;
            for (String[] p : INKFLOW_PAGES) {
                if (shown < 3 && Store.norm(p[0]).contains(n)) {
                    actionsBox.addView(actionRow(p[0], "InkFlow", v -> {
                        closeDrawer();
                        openInkFlow(p[1]);
                    }));
                    shown++;
                }
            }
            actionsBox.addView(actionRow("Search the web for “" + q + "”", "Web", v -> {
                closeDrawer();
                openUrl("https://www.google.com/search?q=" + Uri.encode(q));
            }));
            grid.setFastScrollAlwaysVisible(false);
            grid.setFastScrollEnabled(false);
            grid.setAdapter(new AppAdapter(searchApps(q), false));
            return;
        }

        recentBox.removeAllViews();
        List<Store.App> rec = new ArrayList<>();
        for (String k : store.recent) {
            Store.App a = store.app(k);
            if (a != null && rec.size() < 5) {
                rec.add(a);
            }
        }
        if (!rec.isEmpty()) {
            TextView h = tv("RECENTLY USED", 11.5f, true, sub);
            h.setLetterSpacing(0.06f);
            recentBox.addView(h);
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(10), dp(6), dp(4));
            for (Store.App a : rec) {
                LinearLayout cell = drawerCell(a);
                cell.setOnClickListener(v -> {
                    launch(a, v);
                    closeDrawer();
                });
                cell.setOnLongClickListener(v -> {
                    drawerAppMenu(a);
                    return true;
                });
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            for (int i = rec.size(); i < 5; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            recentBox.addView(row);
            TextView all = tv("ALL APPS", 11.5f, true, sub);
            all.setLetterSpacing(0.06f);
            all.setPadding(0, dp(12), 0, 0);
            recentBox.addView(all);
        }
        grid.setAdapter(new AppAdapter(new ArrayList<>(store.apps), true));
        grid.setFastScrollEnabled(true);
        grid.setFastScrollAlwaysVisible(true);
    }

    private void drawerAppMenu(Store.App a) {
        String[] opts = {store.onHome(a.key) ? "Move to a section on the home screen…" : "Add to the home screen…",
                "Put in the dock…", "App info"};
        dialog().setTitle(a.label).setItems(opts, (d, w) -> {
            if (w == 0) {
                pickSection("Put " + a.label + " in", null, target -> {
                    removeEverywhere(a.key);
                    target.items.add(Store.Item.app(a.key));
                    saveAndRender();
                    Toast.makeText(this, "Added to " + sectionName(target) + " on page " + (target.page + 1), Toast.LENGTH_SHORT).show();
                });
            } else if (w == 1) {
                String[] names = new String[5];
                for (int k = 0; k < 5; k++) {
                    Store.App f = store.app(k < store.dock.size() ? store.dock.get(k) : "");
                    names[k] = "Spot " + (k + 1) + ": " + (f == null ? "empty" : f.label);
                }
                dialog().setTitle("Put " + a.label + " in").setItems(names, (d2, k) -> setDock(k, a.key)).show();
            } else {
                appInfo(a);
            }
        }).show();
    }

    /** The drawer's grid: every app A to Z with a letter scroller, or search results. */
    private final class AppAdapter extends BaseAdapter implements SectionIndexer {
        private final List<Store.App> items;
        private final String[] sections;
        private final int[] starts;

        AppAdapter(List<Store.App> items, boolean az) {
            this.items = items;
            List<String> secs = new ArrayList<>();
            List<Integer> st = new ArrayList<>();
            if (az) {
                String last = null;
                for (int i = 0; i < items.size(); i++) {
                    String l = letter(items.get(i));
                    if (!l.equals(last)) {
                        secs.add(l);
                        st.add(i);
                        last = l;
                    }
                }
            }
            sections = secs.toArray(new String[0]);
            starts = new int[st.size()];
            for (int i = 0; i < st.size(); i++) {
                starts[i] = st.get(i);
            }
        }

        private String letter(Store.App a) {
            if (a.label.isEmpty()) {
                return "#";
            }
            char ch = Character.toUpperCase(a.label.charAt(0));
            return Character.isLetter(ch) ? String.valueOf(ch) : "#";
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Store.App a = items.get(position);
            LinearLayout cell = drawerCell(a);
            cell.setOnClickListener(v -> {
                launch(a, v);
                closeDrawer();
            });
            cell.setOnLongClickListener(v -> {
                drawerAppMenu(a);
                return true;
            });
            return cell;
        }

        @Override
        public Object[] getSections() {
            return sections;
        }

        @Override
        public int getPositionForSection(int s) {
            return starts.length == 0 ? 0 : starts[Math.max(0, Math.min(s, starts.length - 1))];
        }

        @Override
        public int getSectionForPosition(int position) {
            int s = 0;
            for (int i = 0; i < starts.length; i++) {
                if (starts[i] <= position) {
                    s = i;
                }
            }
            return s;
        }
    }

    // ================================================================== settings

    private Button button(String label, boolean on, View.OnClickListener click) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(on ? (dark ? K : Color.WHITE) : text);
        b.setBackground(round(on ? (dark ? Y : K) : chip, 14));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                p.leftMargin = dp(8);
            }
            r.addView(views[i], p);
        }
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        r.setLayoutParams(p);
        return r;
    }

    private TextView header(String s) {
        TextView t = tv(s, 17, true, text);
        t.setPadding(0, dp(18), 0, dp(2));
        return t;
    }

    private TextView note(String s) {
        TextView t = tv(s, 13, false, sub);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTextColor(text);
        e.setHintTextColor(sub);
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(6);
        e.setLayoutParams(p);
        return e;
    }

    private void openSettings() {
        palette();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(4), dp(22), dp(16));
        sv.addView(col);
        AlertDialog[] holder = new AlertDialog[1];
        Runnable reopen = () -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            buildFrame();
            ui.postDelayed(this::openSettings, 120);
        };

        col.addView(header("Icons across"));
        List<View> gb = new ArrayList<>();
        for (int n = 5; n <= 8; n++) {
            final int across = n;
            gb.add(button(String.valueOf(n), store.cols == n, v -> {
                store.cols = across;
                store.save();
                reopen.run();
            }));
        }
        col.addView(row(gb.get(0), gb.get(1), gb.get(2), gb.get(3)));
        col.addView(note("How many icons fit in each row of a section. 8 is what your Samsung used."));

        col.addView(header("Colors for the drawer and folders"));
        String th = prefs.getString("theme", "phone");
        View.OnClickListener setTheme = v -> {
            prefs.edit().putString("theme", (String) v.getTag()).apply();
            reopen.run();
        };
        Button lightB = button("Light", "light".equals(th), setTheme);
        lightB.setTag("light");
        Button darkB = button("Dark", "dark".equals(th), setTheme);
        darkB.setTag("dark");
        Button phoneB = button("Same as phone", "phone".equals(th), setTheme);
        phoneB.setTag("phone");
        col.addView(row(lightB, darkB, phoneB));

        col.addView(header("Weather"));
        EditText town = field("Town, state", Weather.place(prefs).name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        col.addView(town);
        TextView wxMsg = note("From the National Weather Service, with Open-Meteo as the backup.");
        col.addView(wxMsg);
        boolean cel = Weather.celsius(prefs);
        col.addView(row(
                button("Save town", false, v -> {
                    String t = town.getText().toString().trim();
                    wxMsg.setText("Looking up “" + t + "”…");
                    bg.execute(() -> {
                        String[] err = new String[1];
                        Weather.Place pl = Weather.findPlace(t, err);
                        ui.post(() -> {
                            if (gone()) {
                                return;
                            }
                            if (pl == null) {
                                wxMsg.setText(err[0] == null ? "Couldn’t find that town." : err[0]);
                                return;
                            }
                            Weather.savePlace(prefs, pl);
                            town.setText(pl.name);
                            wxMsg.setText("Weather is now for " + pl.name + ".");
                            weather = null;
                            refreshCards();
                            refreshWeather(true);
                        });
                    });
                }),
                button("°F", !cel, v -> {
                    prefs.edit().putString("wx_unit", "F").remove("wx_cache").apply();
                    weather = null;
                    refreshWeather(true);
                    reopen.run();
                }),
                button("°C", cel, v -> {
                    prefs.edit().putString("wx_unit", "C").remove("wx_cache").apply();
                    weather = null;
                    refreshWeather(true);
                    reopen.run();
                })));
        Store.App wxApp = store.app(prefs.getString("wx_app", ""));
        col.addView(row(button("Weather app: " + (wxApp == null ? "pick one" : wxApp.label), false,
                v -> pickApp("Open which app when you tap the weather?", a -> {
                    prefs.edit().putString("wx_app", a.key).apply();
                    reopen.run();
                }))));

        col.addView(header("InkFlow"));
        if (signedIn()) {
            col.addView(note("Signed in as " + prefs.getString("if_email", "your InkFlow login")));
            col.addView(row(button("Sign out of InkFlow", false, v -> {
                prefs.edit().remove("if_token").remove("if_live").apply();
                live = null;
                reopen.run();
            })));
        } else {
            EditText url = field("InkFlow address", inkUrl(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            EditText email = field("Email", prefs.getString("if_email", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            EditText pw = field("Password", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            TextView msg = note("Shows today’s orders on the card. The password is used once and not kept.");
            col.addView(url);
            col.addView(email);
            col.addView(pw);
            col.addView(msg);
            col.addView(row(button("Sign in", true, v -> {
                String base = url.getText().toString().trim();
                if (!base.startsWith("http")) {
                    base = "https://" + base;
                }
                while (base.endsWith("/")) {
                    base = base.substring(0, base.length() - 1);
                }
                final String b2 = base;
                final String em = email.getText().toString().trim();
                final String pass = pw.getText().toString();
                msg.setText("Signing in…");
                bg.execute(() -> {
                    String result;
                    try {
                        JSONObject body = new JSONObject().put("email", em).put("password", pass);
                        Net.Reply r = Net.post(b2 + "/api/mobile/login", null, body.toString());
                        JSONObject j = r.json();
                        if (j == null) {
                            j = new JSONObject();
                        }
                        if (r.ok() && !j.optString("token").isEmpty()) {
                            JSONObject user = j.optJSONObject("user");
                            prefs.edit().putString("if_url", b2).putString("if_email", em)
                                    .putString("if_name", user == null ? "" : user.optString("name"))
                                    .putString("if_token", j.optString("token")).apply();
                            result = null;
                        } else {
                            result = j.optString("error", "InkFlow answered " + r.code + ".");
                        }
                    } catch (Exception ex) {
                        result = "Couldn’t reach InkFlow. Check the address and your connection.";
                    }
                    final String res = result;
                    ui.post(() -> {
                        if (gone()) {
                            return;
                        }
                        if (res == null) {
                            liveAt = 0;
                            refreshLive(true);
                            reopen.run();
                        } else {
                            msg.setText(res);
                        }
                    });
                });
            })));
        }

        col.addView(header("Home screen"));
        col.addView(row(button("Customize the home screen", true, v -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            closeDrawer();
            setEditing(true);
        })));
        col.addView(note("Tap Edit (bottom right) to customize right on the screen: drag icons between sections, tap a section\u2019s color square to change it, rename it, move it up or down, and add sections, the clock or the weather card. Swipe up for every app; swipe down for notifications."));
        col.addView(row(button(isDefaultHome() ? "Default home app (Press Sheet is on)" : "Make Press Sheet my home screen", false, v -> {
            try {
                Intent i = new Intent(Settings.ACTION_HOME_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        })));
        col.addView(row(button("Add a blank page", false, v -> {
            store.pages++;
            store.save();
            Toast.makeText(this, "Page " + store.pages + " added — swipe to it, then press and hold to add apps", Toast.LENGTH_LONG).show();
            reopen.run();
        })));
        col.addView(row(button("Start the sections over", false, v -> dialog().setTitle("Start the layout over?")
                .setMessage("Sections, folders and the dock go back to the starting setup. Your InkFlow sign-in and weather stay.")
                .setPositiveButton("Start over", (d, w) -> {
                    store.reset();
                    reopen.run();
                }).setNegativeButton("Keep my layout", null).show())));
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            version = "";
        }
        TextView ver = note("Press Sheet " + version);
        ver.setPadding(0, dp(16), 0, 0);
        col.addView(ver);

        holder[0] = dialog().setTitle("Settings").setView(sv).setPositiveButton("Done", null).create();
        holder[0].show();
    }

    private boolean isDefaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================== gestures

    /** Sideways pages that snap one at a time, like Samsung's home screen. */
    private final class Pager extends HorizontalScrollView {
        int page;
        int pageCount = 1;
        private int startScroll;

        Pager(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setFillViewport(true);
        }

        void goTo(int p, boolean smooth) {
            int w = getWidth();
            page = Math.max(0, Math.min(p, pageCount - 1));
            if (w > 0) {
                if (smooth) {
                    smoothScrollTo(page * w, 0);
                } else {
                    scrollTo(page * w, 0);
                }
            }
            renderDots(page);
        }

        /** The phone's bars can change the space after the first layout: redraw the pages to fit. */
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (oldw != 0 && oldh != 0 && (w != oldw || h != oldh)) {
                startPage = page;
                post(MainActivity.this::renderAll);
            }
        }

        @Override
        public void fling(int velocityX) {
            // no free scrolling: pages snap in onTouchEvent
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startScroll = getScrollX();
            }
            return super.onInterceptTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                startScroll = getScrollX();
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                super.onTouchEvent(e);
                int w = Math.max(1, getWidth());
                int delta = getScrollX() - startScroll;
                int target = Math.round((float) startScroll / w);
                if (delta > w * 0.15f) {
                    target++;
                } else if (delta < -w * 0.15f) {
                    target--;
                }
                goTo(target, true);
                return true;
            }
            return super.onTouchEvent(e);
        }
    }

    /** Watches for a swipe up (open the drawer) or down (notifications, or close the drawer). */
    private final class SwipeRoot extends FrameLayout {
        private final int slop;
        private float downX, downY;
        private boolean tracking;
        private boolean claimed;
        private boolean up;

        SwipeRoot(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private boolean drawerOpen() {
            return drawer != null && drawer.getVisibility() == View.VISIBLE;
        }

        private boolean folderOpen() {
            return folderLayer != null && folderLayer.getVisibility() == View.VISIBLE;
        }

        private long edgeSince;
        private int edgeDir;

        /** While dragging an icon: hold it at the left or right edge to change pages, or near the top or bottom to scroll. */
        @Override
        public boolean dispatchDragEvent(android.view.DragEvent e) {
            int a = e.getAction();
            if (a == android.view.DragEvent.ACTION_DRAG_LOCATION && pager != null) {
                float x = e.getX(), y = e.getY();
                int w = getWidth(), edge = dp(28);
                int dir = x < edge ? -1 : x > w - edge ? 1 : 0;
                long now = android.os.SystemClock.uptimeMillis();
                if (dir == 0) {
                    edgeDir = 0;
                } else if (dir != edgeDir) {
                    edgeDir = dir;
                    edgeSince = now;
                } else if (now - edgeSince > 650) {
                    edgeSince = now;
                    pager.goTo(pager.page + dir, true);
                }
                ScrollView sv = pager.page < pageScrolls.size() ? pageScrolls.get(pager.page) : null;
                if (sv != null) {
                    int[] loc = new int[2];
                    int[] me = new int[2];
                    sv.getLocationOnScreen(loc);
                    getLocationOnScreen(me);
                    float top = loc[1] - me[1], bottom = top + sv.getHeight();
                    if (y < top + dp(60)) {
                        sv.scrollBy(0, -dp(14));
                    } else if (y > bottom - dp(60)) {
                        sv.scrollBy(0, dp(14));
                    }
                }
            } else if (a == android.view.DragEvent.ACTION_DRAG_ENDED) {
                edgeDir = 0;
                if (!e.getResult()) {
                    dragging = null;
                    ui.post(MainActivity.this::renderPages);
                }
            }
            return super.dispatchDragEvent(e);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    tracking = !folderOpen() && !editing;
                    claimed = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!tracking) {
                        return false;
                    }
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (Math.abs(dy) < slop * 3 || Math.abs(dy) < Math.abs(dx) * 1.5f) {
                        if (Math.abs(dx) > slop * 2) {
                            tracking = false;   // sideways: the pages take it
                        }
                        return false;
                    }
                    if (!drawerOpen()) {
                        ScrollView page = pager.page < pageScrolls.size() ? pageScrolls.get(pager.page) : null;
                        if (page != null && page.canScrollVertically(dy < 0 ? 1 : -1)) {
                            tracking = false;   // the page itself scrolls first
                            return false;
                        }
                        claimed = true;
                        up = dy < 0;
                        return true;
                    }
                    if (dy > 0 && (grid == null || !grid.canScrollVertically(-1))) {
                        claimed = true;
                        up = false;
                        return true;
                    }
                    tracking = false;
                    return false;
                default:
                    tracking = false;
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!claimed) {
                return super.onTouchEvent(e);
            }
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                claimed = false;
                if (a == MotionEvent.ACTION_UP) {
                    if (drawerOpen()) {
                        closeDrawer();
                    } else if (up) {
                        openDrawer(false);
                    } else {
                        openNotifications();
                    }
                }
            }
            return true;
        }
    }
}
