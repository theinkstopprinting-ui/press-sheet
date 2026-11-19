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
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
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
import android.widget.TextClock;
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
 * Press Sheet: a native Android home screen with two looks.
 *  - Glance: your wallpaper, a big clock and weather, InkFlow at a glance, 4 favorites and the dock.
 *  - Press Bed: bold CMYK bands, one per category, every app on one scrolling screen.
 * Both share one swipe-up drawer (search, recently used, category tabs, A to Z).
 */
public class MainActivity extends Activity {

    private static final int C = 0xFF4CCBF2, M = 0xFFFF7DB5, Y = 0xFFFFD60A, K = 0xFF14161C;
    private static final long WX_EVERY = 20 * 60 * 1000L;
    private static final String[][] INKFLOW_PAGES = {
            {"New order", "/orders/new"}, {"Orders board", "/orders"}, {"Dashboard", "/dashboard"}, {"Production", "/production"},
            {"Printers", "/printers"}, {"Customers", "/customers"}, {"Calendar", "/calendar"}, {"Tasks", "/tasks"},
            {"Inventory", "/inventory"}, {"Mockup studio", "/studio"}, {"Scan a work order", "/scan"}};

    private SharedPreferences prefs;
    private Store store;
    private final ExecutorService bg = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean appsReady;
    private boolean resumed;
    private boolean dark;
    private Typeface tBold, tBody, tBodyMed, tBodyBold, tSerif, tTight, tTightSemi;

    // colors for the current light/dark setting
    private int ground, card, text, sub, chip;

    private SwipeRoot root;
    private View content;
    private ScrollView bedScroll;
    private LinearLayout drawer;
    private EditText search;
    private GridView grid;
    private LinearLayout recentBox, tabsRow, actionsBox;
    private HorizontalScrollView tabsScroll;
    private String drawerTab = "az";

    // views that update in place
    private ImageView wxIcon;
    private TextView wxTemp, wxLine, alertView;
    private LinearLayout inkBox;

    private Weather.Now weather;
    private boolean wxBusy;
    private JSONObject live;
    private String liveError = "";
    private long liveAt;
    private boolean liveBusy;

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
        tBold = font("BricolageGrotesque_800ExtraBold.ttf");
        tBody = font("DMSans_400Regular.ttf");
        tBodyMed = font("DMSans_500Medium.ttf");
        tBodyBold = font("DMSans_700Bold.ttf");
        tSerif = font("Fraunces_600SemiBold.ttf");
        tTight = font("InterTight_500Medium.ttf");
        tTightSemi = font("InterTight_600SemiBold.ttf");
        weather = Weather.cached(prefs);
        try {
            String s = prefs.getString("if_live", null);
            if (s != null) {
                live = new JSONObject(s);
            }
        } catch (Exception ignored) {
            live = null;
        }
        applyTestExtras(getIntent());

        root = new SwipeRoot(this);
        setContentView(root);
        build();
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
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(tick);
        super.onPause();
    }

    /** Pressing Home while on the home screen: close the drawer and go back to the top. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (applyTestExtras(intent)) {
            build();
        }
        if (intent != null && "drawer".equals(intent.getStringExtra("open"))) {
            ui.postDelayed(() -> openDrawer("az", false), 300);
            return;
        }
        closeDrawer();
        if (bedScroll != null) {
            bedScroll.smoothScrollTo(0, 0);
        }
    }

    @Override
    public void onBackPressed() {
        closeDrawer();   // a home screen never closes itself
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        build();   // dark mode switched on or off
    }

    /** For the automatic emulator test: --es look glance|bed, --es theme light|dark|phone. */
    private boolean applyTestExtras(Intent i) {
        if (i == null) {
            return false;
        }
        boolean changed = false;
        String look = i.getStringExtra("look");
        if ("glance".equals(look) || "bed".equals(look)) {
            prefs.edit().putString("look", look).apply();
            changed = true;
        }
        String theme = i.getStringExtra("theme");
        if ("light".equals(theme) || "dark".equals(theme) || "phone".equals(theme)) {
            prefs.edit().putString("theme", theme).apply();
            changed = true;
        }
        return changed;
    }

    private void reloadApps() {
        bg.execute(() -> {
            List<Store.App> list = store.queryApps();
            ui.post(() -> {
                store.setApps(list);
                store.forgetIcons();
                if (!appsReady) {
                    store.load();      // first run fills the categories from the apps on this phone
                    appsReady = true;
                }
                if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
                    renderDrawer();
                } else {
                    build();
                }
                if (getIntent() != null && "drawer".equals(getIntent().getStringExtra("open"))) {
                    getIntent().removeExtra("open");
                    ui.postDelayed(() -> openDrawer("az", false), 400);
                }
            });
        });
    }

    // ================================================================== look and colors

    private boolean isGlance() {
        return "glance".equals(prefs.getString("look", "bed"));
    }

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
        ground = dark ? 0xFF0E1015 : 0xFFEEF1F6;
        card = dark ? 0xFF1A1D25 : 0xFFFFFFFF;
        text = dark ? 0xFFF2F3F6 : K;
        sub = dark ? 0xFFA7ADBA : 0xFF4A5160;
        chip = dark ? 0xFF262A34 : 0xFFE2E6ED;
    }

    private int bandBg(String color) {
        switch (color) {
            case "cyan":
                return C;
            case "yellow":
                return Y;
            case "magenta":
                return M;
            case "black":
                return dark ? 0xFF2A2F3A : K;
            default:
                return card;
        }
    }

    private int bandFg(String color) {
        if ("black".equals(color)) {
            return Color.WHITE;
        }
        if ("white".equals(color)) {
            return text;
        }
        return K;
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
        } else if (isGlance()) {
            setBars(Color.TRANSPARENT, false);
        } else {
            setBars(ground, !dark);
        }
    }

    // ================================================================== small view helpers

    private Typeface font(String file) {
        try {
            return Typeface.createFromAsset(getAssets(), "fonts/" + file);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tv(String s, float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void shadow(TextView t) {
        t.setShadowLayer(dp(4), 0, dp(1), 0xB3000000);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams weightLp(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    private ImageView icon(int res, int sizeDp, int tint) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** Puts an app's icon in an ImageView, drawing it in the background the first time. */
    private void bindIcon(ImageView iv, Store.App a) {
        iv.setTag(a == null ? null : a.key);
        if (a == null) {
            iv.setImageDrawable(round(dark ? 0x33FFFFFF : 0x22000000, 16));
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
                if (a.key.equals(iv.getTag()) && loaded != null) {
                    iv.setImageBitmap(loaded);
                }
            });
        });
    }

    /** An app icon with its name under it. */
    private LinearLayout appCell(Store.App a, int iconDp, int labelColor, boolean labelShadow) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(lp(dp(iconDp), dp(iconDp)));
        if (a == null) {
            ImageView plus = icon(R.drawable.ic_plus, iconDp, labelColor);
            plus.setPadding(dp(iconDp / 4f), dp(iconDp / 4f), dp(iconDp / 4f), dp(iconDp / 4f));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(iconDp * 0.3f));
            g.setStroke(dp(2), labelColor, dp(5), dp(4));
            plus.setBackground(g);
            iv = plus;
        } else {
            bindIcon(iv, a);
        }
        cell.addView(iv);
        TextView l = tv(a == null ? "Add" : a.label, 11.5f, tBodyMed, labelColor);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(0, dp(5), 0, 0);
        if (labelShadow) {
            shadow(l);
        }
        cell.addView(l, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setContentDescription(a == null ? "Empty spot" : a.label);
        return cell;
    }

    // ================================================================== build the screen

    private void build() {
        palette();
        root.removeAllViews();
        bedScroll = null;
        root.setBackgroundColor(isGlance() ? Color.TRANSPARENT : ground);
        content = isGlance() ? buildGlance() : buildBed();
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        drawer = buildDrawer();
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        homeBars();
        showWeather();
        showLive();
    }

    private TextClock clock(String fmt12, String fmt24, float sp, Typeface tf, int color) {
        TextClock c = new TextClock(this);
        c.setFormat12Hour(fmt12);
        c.setFormat24Hour(fmt24);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        c.setTypeface(tf);
        c.setTextColor(color);
        c.setIncludeFontPadding(false);
        return c;
    }

    // ---------------------------------------------------------------- Glance

    private View buildGlance() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(36), dp(20), dp(12));
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextClock time = clock("h:mm", "H:mm", 92, tSerif, Color.WHITE);
        shadow(time);
        time.setGravity(Gravity.CENTER);
        time.setOnClickListener(v -> openClockApp());
        col.addView(time);

        TextClock date = clock("EEEE, MMMM d", "EEEE, MMMM d", 16, tTight, Color.WHITE);
        shadow(date);
        date.setPadding(0, dp(6), 0, dp(6));
        col.addView(date);

        LinearLayout wx = new LinearLayout(this);
        wx.setGravity(Gravity.CENTER_VERTICAL);
        wxIcon = icon(R.drawable.wx_cloud, 22, Color.WHITE);
        wx.addView(wxIcon);
        wxTemp = null;
        wxLine = tv("Weather loading…", 15, tTightSemi, Color.WHITE);
        shadow(wxLine);
        wxLine.setPadding(dp(6), 0, 0, 0);
        wx.addView(wxLine);
        wx.setOnClickListener(v -> openWeatherApp());
        wx.setOnLongClickListener(v -> {
            openSettings();
            return true;
        });
        wx.setMinimumHeight(dp(44));
        col.addView(wx);

        alertView = tv("", 13, tTightSemi, K);
        alertView.setBackground(round(M, 16));
        alertView.setPadding(dp(14), dp(8), dp(14), dp(8));
        alertView.setVisibility(View.GONE);
        alertView.setOnClickListener(v -> openWeatherApp());
        LinearLayout.LayoutParams alp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(4);
        col.addView(alertView, alp);

        inkBox = new LinearLayout(this);
        inkBox.setOrientation(LinearLayout.VERTICAL);
        inkBox.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams ilp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(14);
        col.addView(inkBox, ilp);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout favRow = new LinearLayout(this);
        for (int i = 0; i < 4; i++) {
            final int spot = i;
            String key = i < store.favs.size() ? store.favs.get(i) : "";
            Store.App a = store.app(key);
            LinearLayout cell = appCell(a, 58, Color.WHITE, true);
            cell.setOnClickListener(v -> {
                if (a != null) {
                    launch(a, v);
                } else {
                    pickApp("Pick a favorite", picked -> setSpot(store.favs, spot, picked.key));
                }
            });
            cell.setOnLongClickListener(v -> {
                spotMenu(store.favs, spot, "favorite");
                return true;
            });
            favRow.addView(cell, weightLp(1));
        }
        LinearLayout.LayoutParams flp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.bottomMargin = dp(14);
        col.addView(favRow, flp);

        LinearLayout dock = dockRow(0x38FFFFFF, false);
        col.addView(dock, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = tv("Swipe up for everything", 12, tTightSemi, Color.WHITE);
        shadow(hint);
        hint.setCompoundDrawablesWithIntrinsicBounds(null, getDrawable(R.drawable.ic_up), null, null);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(8), dp(16), dp(2));
        hint.setOnClickListener(v -> openDrawer(null, false));
        col.addView(hint);
        return col;
    }

    private LinearLayout dockRow(int bgColor, boolean labels) {
        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(10), dp(8), dp(10), dp(8));
        dock.setBackground(round(bgColor, 32));
        for (int i = 0; i < 5; i++) {
            final int spot = i;
            String key = i < store.dock.size() ? store.dock.get(i) : "";
            Store.App a = store.app(key);
            FrameLayout cell = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            if (a == null) {
                iv = icon(R.drawable.ic_plus, 48, isGlance() ? Color.WHITE : sub);
                iv.setPadding(dp(12), dp(12), dp(12), dp(12));
            } else {
                bindIcon(iv, a);
            }
            cell.addView(iv, new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER));
            cell.setContentDescription(a == null ? "Empty dock spot" : a.label);
            cell.setOnClickListener(v -> {
                if (a != null) {
                    launch(a, v);
                } else {
                    pickApp("Pick an app for the dock", picked -> setSpot(store.dock, spot, picked.key));
                }
            });
            cell.setOnLongClickListener(v -> {
                spotMenu(store.dock, spot, "dock spot");
                return true;
            });
            dock.addView(cell, new LinearLayout.LayoutParams(0, dp(54), 1f));
        }
        return dock;
    }

    // ---------------------------------------------------------------- Press Bed

    private View buildBed() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ground);

        bedScroll = new ScrollView(this);
        bedScroll.setVerticalScrollBarEnabled(false);
        bedScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(10));
        bedScroll.addView(col);

        // clock + weather
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        head.setPadding(dp(4), 0, dp(4), dp(12));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock time = clock("h:mm", "H:mm", 58, tBold, text);
        time.setOnClickListener(v -> openClockApp());
        left.addView(time);
        TextClock date = clock("EEE, MMM d", "EEE, MMM d", 14, tBody, sub);
        date.setPadding(0, dp(4), 0, 0);
        left.addView(date);
        head.addView(left, weightLp(1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setGravity(Gravity.CENTER_VERTICAL);
        wxIcon = icon(R.drawable.wx_cloud, 30, text);
        tempRow.addView(wxIcon);
        wxTemp = tv("--°", 32, tBold, text);
        wxTemp.setPadding(dp(6), 0, 0, 0);
        tempRow.addView(wxTemp);
        right.addView(tempRow);
        wxLine = tv("Weather loading…", 12.5f, tBody, sub);
        wxLine.setGravity(Gravity.END);
        wxLine.setPadding(0, dp(4), 0, 0);
        right.addView(wxLine);
        right.setOnClickListener(v -> openWeatherApp());
        right.setOnLongClickListener(v -> {
            openSettings();
            return true;
        });
        head.addView(right);
        col.addView(head);

        alertView = tv("", 13.5f, tBodyBold, K);
        alertView.setBackground(round(M, 16));
        alertView.setPadding(dp(14), dp(10), dp(14), dp(10));
        alertView.setVisibility(View.GONE);
        alertView.setOnClickListener(v -> openWeatherApp());
        LinearLayout.LayoutParams alp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = dp(8);
        col.addView(alertView, alp);

        inkBox = new LinearLayout(this);
        inkBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.bottomMargin = dp(8);
        col.addView(inkBox, ilp);

        for (Store.Cat c : store.cats) {
            LinearLayout.LayoutParams blp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.bottomMargin = dp(8);
            col.addView(band(c), blp);
        }
        TextView add = tv("+ Add a category", 14, tBodyBold, sub);
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, dp(12), 0, dp(12));
        add.setOnClickListener(v -> newCategory());
        col.addView(add, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        outer.addView(bedScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(14), dp(6), dp(14), dp(12));
        bottom.addView(dockRow(card, false), weightLp(1));
        FrameLayout searchBtn = new FrameLayout(this);
        searchBtn.setBackground(round(dark ? Y : K, 32));
        searchBtn.addView(icon(R.drawable.ic_search, 26, dark ? K : Color.WHITE),
                new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        searchBtn.setContentDescription("Search all apps");
        searchBtn.setOnClickListener(v -> openDrawer(null, true));
        LinearLayout.LayoutParams slp = lp(dp(64), dp(64));
        slp.leftMargin = dp(8);
        bottom.addView(searchBtn, slp);
        outer.addView(bottom);
        return outer;
    }

    private View band(Store.Cat c) {
        int bgc = bandBg(c.color), fg = bandFg(c.color);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(round(bgc, 22));
        b.setPadding(dp(14), dp(10), 0, dp(10));

        LinearLayout title = new LinearLayout(this);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, dp(12), dp(8));
        TextView name = tv(c.name, 18, tBold, fg);
        title.addView(name, weightLp(1));
        int count = 0;
        for (String k : c.apps) {
            if (store.app(k) != null) {
                count++;
            }
        }
        TextView more = tv(count + (count == 1 ? " app ›" : " apps ›"), 12.5f, tBodyBold, fg);
        more.setAlpha(0.85f);
        title.addView(more);
        title.setMinimumHeight(dp(36));
        title.setOnClickListener(v -> openDrawer(c.id, false));
        title.setOnLongClickListener(v -> {
            catMenu(c);
            return true;
        });
        b.addView(title);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(this);
        boolean any = false;
        for (String k : c.apps) {
            Store.App a = store.app(k);
            if (a == null) {
                continue;
            }
            any = true;
            LinearLayout cell = appCell(a, 50, fg, false);
            cell.setOnClickListener(v -> launch(a, v));
            cell.setOnLongClickListener(v -> {
                appMenu(a, c);
                return true;
            });
            LinearLayout.LayoutParams clp = lp(dp(66), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(cell, clp);
        }
        if (!any) {
            TextView empty = tv(appsReady ? "Press and hold “" + c.name + "” to add apps" : "Loading apps…", 13, tBody, fg);
            empty.setPadding(0, dp(8), dp(12), dp(8));
            row.addView(empty);
        }
        hs.addView(row);
        b.addView(hs);
        return b;
    }

    // ================================================================== weather + InkFlow on screen

    private String deg(double v) {
        return Double.isNaN(v) ? "--°" : Math.round(v) + "°";
    }

    private void showWeather() {
        if (wxLine == null) {
            return;
        }
        Weather.Now w = weather;
        if (w == null || Double.isNaN(w.temp)) {
            wxLine.setText("Weather loading…");
            if (wxTemp != null) {
                wxTemp.setText("--°");
            }
        } else {
            String hl;
            if ("Tonight".equals(w.when)) {
                hl = Double.isNaN(w.lo) ? "" : "Tonight L " + deg(w.lo);
            } else {
                hl = (Double.isNaN(w.hi) ? "" : "H " + deg(w.hi)) + (Double.isNaN(w.lo) ? "" : " L " + deg(w.lo));
            }
            String words = w.words == null ? "" : w.words;
            if (wxTemp != null) {
                wxTemp.setText(deg(w.temp));
                wxLine.setText(words + (hl.isEmpty() ? "" : "\n" + hl.trim()));
            } else {
                wxLine.setText(deg(w.temp) + "  " + words + (hl.isEmpty() ? "" : " · " + hl.trim()));
            }
            wxIcon.setImageResource(Weather.icon(words, w.day));
            boolean old = System.currentTimeMillis() - w.at > 3 * 3600_000L;
            if (old) {
                wxLine.append(" · old");
            }
        }
        if (alertView != null) {
            boolean has = w != null && w.alert != null && !w.alert.isEmpty();
            alertView.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) {
                alertView.setText("⚠ " + w.alert);
            }
        }
    }

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
                showWeather();
            });
        });
    }

    private boolean signedIn() {
        return !prefs.getString("if_token", "").isEmpty();
    }

    private String inkUrl() {
        return prefs.getString("if_url", "https://inkflow.up.railway.app");
    }

    private String ordersText() {
        if (live == null) {
            return liveError.isEmpty() ? "Checking InkFlow…" : liveError;
        }
        JSONObject c = live.optJSONObject("counts");
        if (c == null) {
            return "InkFlow";
        }
        int due = c.optInt("dueToday"), late = c.optInt("late");
        return due + " due today" + (late > 0 ? " · " + late + " late" : "");
    }

    private String printersText() {
        if (live == null) {
            return "";
        }
        JSONArray ps = live.optJSONArray("printers");
        if (ps == null || ps.length() == 0) {
            return "No printers reporting";
        }
        int offline = 0;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) {
                continue;
            }
            if (p.optBoolean("online") && p.optBoolean("busy")) {
                String name = p.optString("name").replaceFirst("(?i)^EZ-JET PRO ", "EZ-JET ");
                double pct = p.optDouble("pct", Double.NaN);
                return name + " printing" + (Double.isNaN(pct) ? "" : " " + Math.round(pct) + "%");
            }
            if (!p.optBoolean("online")) {
                offline++;
            }
        }
        return offline > 0 ? offline + " printer" + (offline == 1 ? "" : "s") + " offline" : "Printers idle";
    }

    private TextView pill(String s, int bgColor, int fg, int dotColor) {
        TextView t = tv(s, 13, isGlance() ? tTightSemi : tBodyBold, fg);
        t.setBackground(round(bgColor, 20));
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (dotColor != 0) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(dotColor);
            dot.setSize(dp(8), dp(8));
            t.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
            t.setCompoundDrawablePadding(dp(8));
        }
        return t;
    }

    private void showLive() {
        if (inkBox == null) {
            return;
        }
        inkBox.removeAllViews();
        boolean in = signedIn();
        if (isGlance()) {
            LinearLayout r1 = new LinearLayout(this);
            r1.setGravity(Gravity.CENTER_HORIZONTAL);
            if (!in) {
                TextView s = pill("Sign in to InkFlow", 0xE6FFFFFF, K, Y);
                s.setOnClickListener(v -> openSettings());
                r1.addView(s);
                inkBox.addView(r1);
                return;
            }
            TextView o = pill(ordersText(), 0xE6FFFFFF, K, M);
            o.setOnClickListener(v -> openInkFlow("/dashboard"));
            r1.addView(o, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
            TextView n = pill("+ New order", Y, K, 0);
            n.setOnClickListener(v -> openInkFlow("/orders/new"));
            LinearLayout.LayoutParams nlp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.leftMargin = dp(8);
            r1.addView(n, nlp);
            inkBox.addView(r1);
            String pt = printersText();
            if (!pt.isEmpty()) {
                TextView p = pill(pt, 0xE6FFFFFF, K, C);
                p.setOnClickListener(v -> openInkFlow("/printers"));
                LinearLayout.LayoutParams plp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.topMargin = dp(8);
                plp.gravity = Gravity.CENTER_HORIZONTAL;
                inkBox.addView(p, plp);
            }
            return;
        }
        // Press Bed: one yellow strip
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBackground(round(Y, 18));
        strip.setPadding(dp(14), dp(10), dp(10), dp(10));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = tv(in ? "InkFlow · " + ordersText() : "InkFlow", 14.5f, tBodyBold, K);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(t1);
        String l2 = in ? printersText() : "Tap to sign in and see today’s orders";
        if (!l2.isEmpty()) {
            TextView t2 = tv(l2, 12.5f, tBody, K);
            t2.setSingleLine(true);
            t2.setEllipsize(TextUtils.TruncateAt.END);
            t2.setPadding(0, dp(3), 0, 0);
            txt.addView(t2);
        }
        strip.addView(txt, weightLp(1));
        TextView go = tv("New order", 13, tBodyBold, Color.WHITE);
        go.setBackground(round(K, 18));
        go.setPadding(dp(14), dp(10), dp(14), dp(10));
        go.setOnClickListener(v -> {
            if (in) {
                openInkFlow("/orders/new");
            } else {
                openSettings();
            }
        });
        strip.addView(go);
        strip.setOnClickListener(v -> {
            if (in) {
                openInkFlow("/dashboard");
            } else {
                openSettings();
            }
        });
        inkBox.addView(strip);
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
            boolean signedOut = false;
            try {
                Net.Reply r = Net.get(base + "/api/mobile/launcher", token, null);
                if (r.ok()) {
                    data = r.json();
                } else if (r.code == 401) {
                    signedOut = true;
                    err = "Sign in to InkFlow again";
                } else {
                    err = "InkFlow answered " + r.code;
                }
            } catch (Exception e) {
                err = "Couldn’t reach InkFlow";
            }
            final JSONObject d = data;
            final String e2 = err;
            final boolean out = signedOut;
            ui.post(() -> {
                liveBusy = false;
                liveAt = System.currentTimeMillis();
                if (out) {
                    prefs.edit().remove("if_token").apply();
                }
                if (d != null) {
                    live = d;
                    liveError = "";
                    prefs.edit().putString("if_live", d.toString()).apply();
                } else {
                    liveError = e2;
                }
                showLive();
            });
        });
    }

    // ================================================================== opening things

    private void launch(Store.App a, View from) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(a.pkg, a.cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Bundle opts = from == null ? null
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

    private void openInkFlow(String path) {
        openUrl(inkUrl() + path);
    }

    private void openClockApp() {
        Store.App a = store.match("clock");
        if (a != null) {
            launch(a, null);
        }
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

    // ================================================================== the drawer

    private LinearLayout buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(ground);
        d.setPadding(dp(16), dp(8), dp(16), 0);
        d.setClickable(true);

        View handle = new View(this);
        handle.setBackground(round(dark ? 0xFF3A404D : 0xFFC9CED8, 3));
        LinearLayout.LayoutParams hl = lp(dp(40), dp(5));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = dp(10);
        d.addView(handle, hl);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        search = new EditText(this);
        search.setHint("Search apps, InkFlow, the web");
        search.setHintTextColor(sub);
        search.setTextColor(text);
        search.setTypeface(tBody);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(round(card, 25));
        search.setPadding(dp(18), 0, dp(12), 0);
        search.setCompoundDrawablesWithIntrinsicBounds(tinted(R.drawable.ic_search, sub), null, null, null);
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

        tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsRow = new LinearLayout(this);
        tabsScroll.addView(tabsRow);
        LinearLayout.LayoutParams tlp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(14);
        d.addView(tabsScroll, tlp);

        actionsBox = new LinearLayout(this);
        actionsBox.setOrientation(LinearLayout.VERTICAL);
        actionsBox.setPadding(0, dp(12), 0, 0);
        d.addView(actionsBox);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(16));
        grid.setHorizontalSpacing(dp(6));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setPadding(0, dp(14), 0, dp(24));
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

    private android.graphics.drawable.Drawable tinted(int res, int color) {
        android.graphics.drawable.Drawable dr = getDrawable(res);
        if (dr != null) {
            dr = dr.mutate();
            dr.setTint(color);
            dr.setBounds(0, 0, dp(20), dp(20));
        }
        return dr;
    }

    private void openDrawer(String tab, boolean focusSearch) {
        if (drawer == null) {
            return;
        }
        if (tab != null) {
            drawerTab = tab;
        }
        search.setText("");
        renderDrawer();
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(1f);
        float h = root.getHeight() > 0 ? root.getHeight() : dp(800);
        drawer.setTranslationY(h * 0.35f);
        drawer.setAlpha(0f);
        drawer.animate().translationY(0).alpha(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
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
        drawer.animate().translationY(dp(120)).alpha(0f).setDuration(160).withEndAction(() -> {
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

    private void renderDrawer() {
        if (drawer == null) {
            return;
        }
        String q = search.getText().toString().trim();
        boolean searching = !q.isEmpty();
        recentBox.setVisibility(searching ? View.GONE : View.VISIBLE);
        tabsScroll.setVisibility(searching ? View.GONE : View.VISIBLE);
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
            grid.setAdapter(new AppAdapter(new ArrayList<>(searchApps(q)), null, false));
            grid.setFastScrollEnabled(false);
            return;
        }

        // recently used
        recentBox.removeAllViews();
        List<Store.App> rec = new ArrayList<>();
        for (String k : store.recent) {
            Store.App a = store.app(k);
            if (a != null && rec.size() < 6) {
                rec.add(a);
            }
        }
        if (!rec.isEmpty()) {
            TextView h = tv("RECENTLY USED", 11.5f, tBodyBold, sub);
            h.setLetterSpacing(0.06f);
            recentBox.addView(h);
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(8), 0, 0);
            for (Store.App a : rec) {
                ImageView iv = new ImageView(this);
                bindIcon(iv, a);
                iv.setContentDescription(a.label);
                iv.setOnClickListener(v -> {
                    launch(a, v);
                    closeDrawer();
                });
                iv.setOnLongClickListener(v -> {
                    appMenu(a, null);
                    return true;
                });
                FrameLayout cell = new FrameLayout(this);
                cell.addView(iv, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
                row.addView(cell, new LinearLayout.LayoutParams(0, dp(50), 1f));
            }
            for (int i = rec.size(); i < 6; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(50), 1f));
            }
            recentBox.addView(row);
        }

        // tabs: A to Z, then each category
        tabsRow.removeAllViews();
        tabsRow.addView(tab("All A–Z", "az"));
        Store.Cat current = null;
        for (Store.Cat c : store.cats) {
            tabsRow.addView(tab(c.name, c.id));
            if (c.id.equals(drawerTab)) {
                current = c;
            }
        }
        if (current == null) {
            drawerTab = "az";
        }
        final Store.Cat cat = current;
        ui.post(() -> {
            for (int i = 0; i < tabsRow.getChildCount(); i++) {
                View t = tabsRow.getChildAt(i);
                if (drawerTab.equals(t.getTag())) {
                    tabsScroll.smoothScrollTo(Math.max(0, t.getLeft() - dp(16)), 0);
                }
            }
        });

        if (cat == null) {
            grid.setAdapter(new AppAdapter(new ArrayList<>(store.apps), null, true));
            grid.setFastScrollEnabled(true);
            grid.setFastScrollAlwaysVisible(true);
        } else {
            List<Store.App> list = new ArrayList<>();
            for (String k : cat.apps) {
                Store.App a = store.app(k);
                if (a != null) {
                    list.add(a);
                }
            }
            list.add(null);  // the "Add" cell
            grid.setFastScrollAlwaysVisible(false);
            grid.setFastScrollEnabled(false);
            grid.setAdapter(new AppAdapter(list, cat, false));
        }
    }

    private TextView tab(String label, String id) {
        boolean on = id.equals(drawerTab);
        TextView t = tv(label, 14, tBodyBold, on ? (dark ? K : Color.WHITE) : text);
        t.setTag(id);
        t.setBackground(round(on ? (dark ? Y : K) : chip, 19));
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        p.rightMargin = dp(6);
        t.setLayoutParams(p);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> {
            drawerTab = id;
            renderDrawer();
        });
        if (!"az".equals(id)) {
            t.setOnLongClickListener(v -> {
                for (Store.Cat c : store.cats) {
                    if (c.id.equals(id)) {
                        catMenu(c);
                    }
                }
                return true;
            });
        }
        return t;
    }

    private View actionRow(String label, String note, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(card, 16));
        r.setPadding(dp(16), 0, dp(16), 0);
        r.setMinimumHeight(dp(52));
        TextView l = tv(label, 15, tBodyBold, text);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(l, weightLp(1));
        r.addView(tv(note, 12, tBody, sub));
        r.setOnClickListener(click);
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(8);
        r.setLayoutParams(p);
        return r;
    }

    /** The drawer's grid of apps. A null item is the "Add apps" cell for a category. */
    private final class AppAdapter extends BaseAdapter implements SectionIndexer {
        private final List<Store.App> items;
        private final Store.Cat cat;
        private final String[] sections;
        private final int[] sectionStart;

        AppAdapter(List<Store.App> items, Store.Cat cat, boolean az) {
            this.items = items;
            this.cat = cat;
            List<String> secs = new ArrayList<>();
            List<Integer> starts = new ArrayList<>();
            if (az) {
                String last = null;
                for (int i = 0; i < items.size(); i++) {
                    String l = letter(items.get(i));
                    if (!l.equals(last)) {
                        secs.add(l);
                        starts.add(i);
                        last = l;
                    }
                }
            }
            sections = secs.toArray(new String[0]);
            sectionStart = new int[starts.size()];
            for (int i = 0; i < starts.size(); i++) {
                sectionStart[i] = starts.get(i);
            }
        }

        private String letter(Store.App a) {
            if (a == null || a.label.isEmpty()) {
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
            LinearLayout cell = appCell(a, 52, text, false);
            if (a == null) {
                ((TextView) cell.getChildAt(1)).setText("Add apps");
                cell.setOnClickListener(v -> addAppsTo(cat));
            } else {
                cell.setOnClickListener(v -> {
                    launch(a, v);
                    closeDrawer();
                });
                cell.setOnLongClickListener(v -> {
                    appMenu(a, cat);
                    return true;
                });
            }
            return cell;
        }

        @Override
        public Object[] getSections() {
            return sections;
        }

        @Override
        public int getPositionForSection(int s) {
            if (sectionStart.length == 0) {
                return 0;
            }
            return sectionStart[Math.max(0, Math.min(s, sectionStart.length - 1))];
        }

        @Override
        public int getSectionForPosition(int position) {
            int s = 0;
            for (int i = 0; i < sectionStart.length; i++) {
                if (sectionStart[i] <= position) {
                    s = i;
                }
            }
            return s;
        }
    }

    // ================================================================== editing

    private interface AppPicked {
        void picked(Store.App a);
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
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

    private void setSpot(List<String> list, int spot, String key) {
        while (list.size() <= spot) {
            list.add("");
        }
        list.set(spot, key);
        store.save();
        build();
    }

    private void spotMenu(List<String> list, int spot, String what) {
        Store.App a = store.app(spot < list.size() ? list.get(spot) : "");
        List<String> opts = new ArrayList<>();
        opts.add("Choose an app");
        if (a != null) {
            opts.add("Empty this " + what);
            opts.add("App info");
        }
        dialog().setTitle(a == null ? "Empty " + what : a.label).setItems(opts.toArray(new String[0]), (d, which) -> {
            String o = opts.get(which);
            if (o.startsWith("Choose")) {
                pickApp("Pick an app", picked -> setSpot(list, spot, picked.key));
            } else if (o.startsWith("Empty")) {
                setSpot(list, spot, "");
            } else {
                appInfo(a);
            }
        }).show();
    }

    private void appInfo(Store.App a) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
            // nothing to open
        }
    }

    private void appMenu(Store.App a, Store.Cat inCat) {
        List<String> opts = new ArrayList<>();
        Store.Cat home = store.catOf(a.key);
        opts.add(home == null ? "Put in a category…" : "Move to another category…");
        if (inCat != null) {
            opts.add("Move left");
            opts.add("Move right");
            opts.add("Take out of " + inCat.name);
        }
        opts.add(store.favs.contains(a.key) ? "Remove from favorites" : "Add to favorites");
        opts.add("Put in the dock…");
        opts.add("App info");
        dialog().setTitle(a.label).setItems(opts.toArray(new String[0]), (d, which) -> {
            String o = opts.get(which);
            if (o.startsWith("Put in a") || o.startsWith("Move to")) {
                String[] names = new String[store.cats.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = store.cats.get(i).name;
                }
                dialog().setTitle("Move " + a.label + " to").setItems(names, (d2, w) -> {
                    store.moveTo(a.key, store.cats.get(w));
                    refreshAfterEdit();
                }).show();
            } else if (o.equals("Move left") || o.equals("Move right")) {
                int at = inCat.apps.indexOf(a.key);
                int to = at + (o.equals("Move left") ? -1 : 1);
                if (at >= 0 && to >= 0 && to < inCat.apps.size()) {
                    inCat.apps.remove(at);
                    inCat.apps.add(to, a.key);
                    store.save();
                    refreshAfterEdit();
                }
            } else if (o.startsWith("Take out")) {
                inCat.apps.remove(a.key);
                store.save();
                refreshAfterEdit();
            } else if (o.startsWith("Remove from fav")) {
                int i = store.favs.indexOf(a.key);
                setSpot(store.favs, i, "");
            } else if (o.startsWith("Add to fav")) {
                int i = store.favs.indexOf("");
                if (i < 0) {
                    String[] names = new String[store.favs.size()];
                    for (int k = 0; k < names.length; k++) {
                        Store.App f = store.app(store.favs.get(k));
                        names[k] = "Replace " + (f == null ? "empty spot" : f.label);
                    }
                    dialog().setTitle("Favorites are full").setItems(names, (d2, w) -> setSpot(store.favs, w, a.key)).show();
                } else {
                    setSpot(store.favs, i, a.key);
                    Toast.makeText(this, "Added to favorites", Toast.LENGTH_SHORT).show();
                }
            } else if (o.startsWith("Put in the dock")) {
                String[] names = new String[5];
                for (int k = 0; k < 5; k++) {
                    Store.App f = store.app(k < store.dock.size() ? store.dock.get(k) : "");
                    names[k] = "Spot " + (k + 1) + ": " + (f == null ? "empty" : f.label);
                }
                dialog().setTitle("Put " + a.label + " in").setItems(names, (d2, w) -> setSpot(store.dock, w, a.key)).show();
            } else {
                appInfo(a);
            }
        }).show();
    }

    private void refreshAfterEdit() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
            renderDrawer();
            // rebuild the home screen underneath without closing the drawer
            int index = root.indexOfChild(content);
            if (index >= 0) {
                root.removeView(content);
                inkBox = null;
                content = isGlance() ? buildGlance() : buildBed();
                root.addView(content, index, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                showWeather();
                showLive();
            }
        } else {
            build();
        }
    }

    private void addAppsTo(Store.Cat c) {
        if (c == null || store.apps.isEmpty()) {
            return;
        }
        String[] names = new String[store.apps.size()];
        boolean[] on = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            Store.App a = store.apps.get(i);
            Store.Cat now = store.catOf(a.key);
            names[i] = a.label + (now != null && now != c ? "  (" + now.name + ")" : "");
            on[i] = c.apps.contains(a.key);
        }
        dialog().setTitle("Apps in " + c.name).setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Done", (d, w) -> {
                    for (int i = 0; i < names.length; i++) {
                        String k = store.apps.get(i).key;
                        if (on[i] && !c.apps.contains(k)) {
                            store.moveTo(k, c);
                        } else if (!on[i] && c.apps.contains(k)) {
                            c.apps.remove(k);
                        }
                    }
                    store.save();
                    refreshAfterEdit();
                }).setNegativeButton("Cancel", null).show();
    }

    private void catMenu(Store.Cat c) {
        String[] opts = {"Add or remove apps", "Rename", "Change color", "Move up", "Move down", "Delete this category"};
        dialog().setTitle(c.name).setItems(opts, (d, which) -> {
            int at = store.cats.indexOf(c);
            switch (which) {
                case 0:
                    addAppsTo(c);
                    break;
                case 1:
                    askText("Rename " + c.name, c.name, name -> {
                        c.name = name;
                        store.save();
                        refreshAfterEdit();
                    });
                    break;
                case 2:
                    String[] colors = {"Cyan", "Yellow", "Magenta", "Black", "White"};
                    dialog().setTitle("Color for " + c.name).setItems(colors, (d2, w) -> {
                        c.color = Store.COLORS[w];
                        store.save();
                        refreshAfterEdit();
                    }).show();
                    break;
                case 3:
                    if (at > 0) {
                        store.cats.remove(at);
                        store.cats.add(at - 1, c);
                        store.save();
                        refreshAfterEdit();
                    }
                    break;
                case 4:
                    if (at >= 0 && at < store.cats.size() - 1) {
                        store.cats.remove(at);
                        store.cats.add(at + 1, c);
                        store.save();
                        refreshAfterEdit();
                    }
                    break;
                default:
                    dialog().setTitle("Delete " + c.name + "?")
                            .setMessage("The apps stay on your phone and in All A–Z.")
                            .setPositiveButton("Delete", (d2, w) -> {
                                store.cats.remove(c);
                                store.save();
                                refreshAfterEdit();
                            }).setNegativeButton("Keep it", null).show();
            }
        }).show();
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

    private void newCategory() {
        askText("New category", "", name -> {
            Store.Cat c = store.addCategory(name);
            refreshAfterEdit();
            addAppsTo(c);
        });
    }

    // ================================================================== settings

    private Button button(String label, boolean on, View.OnClickListener click) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTypeface(tBodyBold);
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
        TextView t = tv(s, 18, tBold, text);
        t.setPadding(0, dp(18), 0, dp(2));
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
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(4), dp(22), dp(16));
        sv.addView(col);
        AlertDialog[] holder = new AlertDialog[1];
        Runnable rebuild = () -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            build();
            ui.postDelayed(this::openSettings, 120);
        };

        col.addView(header("Look"));
        String look = prefs.getString("look", "bed");
        col.addView(row(
                button("Glance", "glance".equals(look), v -> {
                    prefs.edit().putString("look", "glance").apply();
                    rebuild.run();
                }),
                button("Press Bed", "bed".equals(look), v -> {
                    prefs.edit().putString("look", "bed").apply();
                    rebuild.run();
                })));
        TextView lookNote = tv("Glance: your wallpaper, clock, weather and favorites, everything else a swipe up. "
                + "Press Bed: every category as a colored band on one screen.", 13, tBody, sub);
        lookNote.setPadding(0, dp(8), 0, 0);
        col.addView(lookNote);

        col.addView(header("Colors"));
        String th = prefs.getString("theme", "phone");
        View.OnClickListener setTheme = v -> {
            prefs.edit().putString("theme", (String) v.getTag()).apply();
            rebuild.run();
        };
        Button l = button("Light", "light".equals(th), setTheme);
        l.setTag("light");
        Button dk = button("Dark", "dark".equals(th), setTheme);
        dk.setTag("dark");
        Button ph = button("Same as phone", "phone".equals(th), setTheme);
        ph.setTag("phone");
        col.addView(row(l, dk));
        col.addView(row(ph));

        col.addView(header("Weather"));
        EditText town = field("Town, state", Weather.place(prefs).name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        col.addView(town);
        TextView wxMsg = tv("From the National Weather Service" + (weather != null && !weather.source.isEmpty() && !weather.source.startsWith("National")
                ? " (last reading came from " + weather.source + ")" : "") + ".", 13, tBody, sub);
        wxMsg.setPadding(0, dp(6), 0, 0);
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
                            if (pl == null) {
                                wxMsg.setText(err[0]);
                                return;
                            }
                            Weather.savePlace(prefs, pl);
                            town.setText(pl.name);
                            wxMsg.setText("Weather is now for " + pl.name + ".");
                            weather = null;
                            showWeather();
                            refreshWeather(true);
                        });
                    });
                }),
                button("°F", !cel, v -> {
                    prefs.edit().putString("wx_unit", "F").remove("wx_cache").apply();
                    weather = null;
                    refreshWeather(true);
                    rebuild.run();
                }),
                button("°C", cel, v -> {
                    prefs.edit().putString("wx_unit", "C").remove("wx_cache").apply();
                    weather = null;
                    refreshWeather(true);
                    rebuild.run();
                })));
        Store.App wxApp = store.app(prefs.getString("wx_app", ""));
        col.addView(row(button("Weather app: " + (wxApp == null ? "pick one" : wxApp.label), false,
                v -> pickApp("Open which app when you tap the weather?", a -> {
                    prefs.edit().putString("wx_app", a.key).apply();
                    rebuild.run();
                }))));

        col.addView(header("InkFlow"));
        if (signedIn()) {
            TextView who = tv("Signed in as " + prefs.getString("if_email", "your InkFlow login"), 14, tBodyBold, text);
            who.setPadding(0, dp(6), 0, 0);
            col.addView(who);
            col.addView(row(button("Sign out of InkFlow", false, v -> {
                prefs.edit().remove("if_token").remove("if_live").apply();
                live = null;
                rebuild.run();
            })));
        } else {
            EditText url = field("InkFlow address", inkUrl(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            EditText email = field("Email", prefs.getString("if_email", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            EditText pw = field("Password", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            TextView msg = tv("Shows today’s orders and your printers. The password is used once and not kept.", 13, tBody, sub);
            msg.setPadding(0, dp(6), 0, 0);
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
                final String p = pw.getText().toString();
                msg.setText("Signing in…");
                bg.execute(() -> {
                    String result;
                    try {
                        JSONObject body = new JSONObject().put("email", em).put("password", p);
                        Net.Reply r = Net.post(b2 + "/api/mobile/login", null, body.toString());
                        JSONObject j = r.json();
                        if (r.ok() && !j.optString("token").isEmpty()) {
                            JSONObject user = j.optJSONObject("user");
                            prefs.edit().putString("if_url", b2).putString("if_email", em)
                                    .putString("if_name", user == null ? "" : user.optString("name"))
                                    .putString("if_token", j.optString("token")).apply();
                            result = null;
                        } else {
                            result = j.optString("error", "InkFlow answered " + r.code + ".");
                        }
                    } catch (Exception e) {
                        result = "Couldn’t reach InkFlow. Check the address and your connection.";
                    }
                    final String res = result;
                    ui.post(() -> {
                        if (res == null) {
                            liveAt = 0;
                            refreshLive(true);
                            rebuild.run();
                        } else {
                            msg.setText(res);
                        }
                    });
                });
            })));
        }

        col.addView(header("Categories"));
        col.addView(row(button("+ Add a category", false, v -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            newCategory();
        })));
        TextView catNote = tv("Press and hold a category or an app to move, rename or recolor it.", 13, tBody, sub);
        catNote.setPadding(0, dp(8), 0, 0);
        col.addView(catNote);

        col.addView(header("Home screen"));
        col.addView(row(button(isDefaultHome() ? "Default home app (Press Sheet is on)" : "Make Press Sheet my home screen", false, v -> {
            try {
                Intent i = new Intent(Settings.ACTION_HOME_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        })));
        col.addView(row(button("Start my layout over", false, v -> dialog().setTitle("Start the layout over?")
                .setMessage("Categories, favorites and the dock go back to the starting setup. Your InkFlow sign-in and weather stay.")
                .setPositiveButton("Start over", (d, w) -> {
                    store.reset();
                    rebuild.run();
                }).setNegativeButton("Keep my layout", null).show())));
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            version = "";
        }
        TextView ver = tv("Press Sheet " + version, 12, tBody, sub);
        ver.setPadding(0, dp(16), 0, 0);
        col.addView(ver);

        holder[0] = dialog().setTitle("Settings").setView(sv).setPositiveButton("Done", null).create();
        holder[0].show();
    }

    private boolean isDefaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================== swipe up / down

    /** The window's root: watches for a swipe up (open the drawer) or down (close it). */
    private final class SwipeRoot extends FrameLayout {
        private final int slop;
        private float downX, downY;
        private boolean tracking;
        private boolean claimed;

        SwipeRoot(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private boolean drawerOpen() {
            return drawer != null && drawer.getVisibility() == View.VISIBLE;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    tracking = true;
                    claimed = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!tracking) {
                        return false;
                    }
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (Math.abs(dy) < slop * 3 || Math.abs(dy) < Math.abs(dx) * 1.5f) {
                        return false;
                    }
                    if (!drawerOpen() && dy < 0) {
                        boolean canScroll = bedScroll != null && bedScroll.canScrollVertically(1);
                        if (!canScroll) {
                            claimed = true;
                            return true;
                        }
                    } else if (drawerOpen() && dy > 0 && (grid == null || !grid.canScrollVertically(-1))) {
                        claimed = true;
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
                    } else {
                        openDrawer(null, false);
                    }
                }
            }
            return true;
        }
    }
}
