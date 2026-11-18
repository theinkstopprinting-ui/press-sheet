package com.theinkstop.presssheet;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Size;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Press Sheet is a home screen. Its whole interface lives in assets/www (HTML + JavaScript) and is
 * shown in a web view that only ever loads those bundled files.
 *
 * This class is the bridge to the phone: it lists the installed apps, draws their icons, opens
 * them, keeps the layout you set up, and asks your InkFlow for the Shop tiles' live numbers
 * (the only time the app touches the network).
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/www/index.html";
    private static final String PREFS = "presssheet";
    private static final int ICON_PX = 144;
    private static final int PHOTO_REQUEST = 41;
    private static final String PARTIAL_PHOTOS = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private boolean pageReady;

    /** Tells the page when an app is installed, removed or updated so its lists stay right. */
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            tellPage("apps", "null");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0x00000000);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // stay inside the app's own pages
                return !HOST.equals(request.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
            }
        });
        web.loadUrl(START_URL);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageChanges, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageChanges, filter);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(packageChanges);
        } catch (IllegalArgumentException ignored) {
            // was never registered
        }
        background.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tellPage("resume", "null");
    }

    /** Pressing Home while already on the home screen goes back to the tiles. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        tellPage("home", "null");
    }

    /** Back never leaves a home screen; it just closes whatever is open on it. */
    @Override
    public void onBackPressed() {
        tellPage("back", "null");
    }

    /** Hands an event to the page: window.onNative(name, data). */
    private void tellPage(final String name, final String json) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null && pageReady) {
                    web.evaluateJavascript("window.onNative&&window.onNative(" + JSONObject.quote(name) + "," + json + ")", null);
                }
            }
        });
    }

    // ---------------------------------------------------------------- bundled files and icons

    private WebResourceResponse serve(Uri url) {
        if (url != null && HOST.equals(url.getHost())) {
            String path = url.getPath();
            if (path != null && !path.contains("..")) {
                while (path.startsWith("/")) {
                    path = path.substring(1);
                }
                if (path.startsWith("thumb/")) {
                    byte[] jpg = thumbJpeg(path.substring(6));
                    if (jpg != null) {
                        Map<String, String> headers = new HashMap<String, String>();
                        headers.put("Cache-Control", "max-age=3600");
                        return new WebResourceResponse("image/jpeg", null, 200, "OK", headers, new ByteArrayInputStream(jpg));
                    }
                } else if (path.startsWith("icon/")) {
                    byte[] png = iconPng(path.substring(5), url.getQueryParameter("a"));
                    if (png != null) {
                        Map<String, String> headers = new HashMap<String, String>();
                        headers.put("Cache-Control", "max-age=86400");
                        return new WebResourceResponse("image/png", null, 200, "OK", headers, new ByteArrayInputStream(png));
                    }
                } else {
                    try {
                        InputStream in = getAssets().open(path);
                        String mime = mimeOf(path);
                        String encoding = mime.startsWith("text/") || mime.contains("javascript") ? "utf-8" : null;
                        return new WebResourceResponse(mime, encoding, in);
                    } catch (IOException ignored) {
                        // fall through to "not found"
                    }
                }
            }
        }
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeOf(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "text/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "text/plain";
    }

    /** An app's own icon as a PNG, for the page's <img src="/icon/package?a=activity">. */
    private byte[] iconPng(String pkg, String activity) {
        try {
            PackageManager pm = getPackageManager();
            Drawable d = null;
            if (activity != null && activity.length() > 0) {
                try {
                    d = pm.getActivityIcon(new ComponentName(pkg, activity));
                } catch (PackageManager.NameNotFoundException ignored) {
                    // fall back to the app's icon
                }
            }
            if (d == null) {
                d = pm.getApplicationIcon(pkg);
            }
            Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, ICON_PX, ICON_PX);
            d.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- your photos (Home Base look)

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private String photoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** "granted", "partial" (only photos you picked), "ask" or "blocked" (turned off in Settings). */
    private String photoAccess() {
        if (granted(photoPermission())) {
            return "granted";
        }
        if (Build.VERSION.SDK_INT >= 34 && granted(PARTIAL_PHOTOS)) {
            return "partial";
        }
        boolean asked = prefs.getBoolean("photos_asked", false);
        if (asked && !shouldShowRequestPermissionRationale(photoPermission())) {
            return "blocked";
        }
        return "ask";
    }

    private String recentPhotoIds(int max) {
        JSONArray out = new JSONArray();
        if ("ask".equals(photoAccess()) || "blocked".equals(photoAccess())) {
            return out.toString();
        }
        Cursor c = null;
        try {
            c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID}, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c != null) {
                while (c.moveToNext() && out.length() < max) {
                    out.put(String.valueOf(c.getLong(0)));
                }
            }
        } catch (Exception ignored) {
            // an empty list just shows the camera button
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out.toString();
    }

    private byte[] thumbJpeg(String id) {
        if (id == null || !id.matches("[0-9]{1,19}")) {
            return null;
        }
        try {
            long n = Long.parseLong(id);
            Bitmap bmp;
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, n);
                bmp = getContentResolver().loadThumbnail(uri, new Size(256, 256), null);
            } else {
                bmp = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), n, MediaStore.Images.Thumbnails.MINI_KIND, null);
            }
            if (bmp == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            bmp.recycle();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PHOTO_REQUEST) {
            tellPage("photos", "null");
        }
    }

    // ---------------------------------------------------------------- what the page can ask for

    private class Bridge {

        /** Every app that has an icon in the normal app list: [{label, pkg, act}], A to Z. */
        @JavascriptInterface
        public String getApps() {
            JSONArray out = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
                List<String[]> rows = new ArrayList<String[]>();
                for (ResolveInfo r : found) {
                    if (r.activityInfo == null || getPackageName().equals(r.activityInfo.packageName)) {
                        continue;
                    }
                    CharSequence label = r.loadLabel(pm);
                    rows.add(new String[]{label == null ? r.activityInfo.packageName : label.toString(),
                            r.activityInfo.packageName, r.activityInfo.name});
                }
                Collections.sort(rows, new Comparator<String[]>() {
                    @Override
                    public int compare(String[] a, String[] b) {
                        return a[0].compareToIgnoreCase(b[0]);
                    }
                });
                for (String[] row : rows) {
                    JSONObject o = new JSONObject();
                    o.put("label", row[0]);
                    o.put("pkg", row[1]);
                    o.put("act", row[2]);
                    out.put(o);
                }
            } catch (Exception ignored) {
                // an empty list is shown as "no apps found"
            }
            return out.toString();
        }

        @JavascriptInterface
        public boolean launch(String pkg, String activity) {
            try {
                Intent intent;
                if (activity != null && activity.length() > 0) {
                    intent = new Intent(Intent.ACTION_MAIN);
                    intent.addCategory(Intent.CATEGORY_LAUNCHER);
                    intent.setComponent(new ComponentName(pkg, activity));
                } else {
                    intent = getPackageManager().getLaunchIntentForPackage(pkg);
                }
                if (intent == null) {
                    return false;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Opens a web address in the phone's browser (or the app that owns that address). */
        @JavascriptInterface
        public boolean openUrl(String url) {
            try {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                if (!"https".equals(scheme) && !"http".equals(scheme)) {
                    return false;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void appInfo(String pkg) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
                // nothing to open
            }
        }

        /** Colours the status and navigation bars to match the current look. dark = dark icons. */
        @JavascriptInterface
        public void setBars(final String color, final boolean darkIcons) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int c = Color.parseColor(color);
                        getWindow().setStatusBarColor(c);
                        getWindow().setNavigationBarColor(c);
                        if (web != null) {
                            web.setBackgroundColor(c);
                        }
                        View decor = getWindow().getDecorView();
                        int flags = decor.getSystemUiVisibility();
                        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                        decor.setSystemUiVisibility(darkIcons ? (flags | light) : (flags & ~light));
                    } catch (Exception ignored) {
                        // a bad colour leaves the bars as they were
                    }
                }
            });
        }

        @JavascriptInterface
        public String photoAccessState() {
            return photoAccess();
        }

        /** Asks Android for photo access; the answer arrives as onNative("photos"). */
        @JavascriptInterface
        public void requestPhotoAccess() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    prefs.edit().putBoolean("photos_asked", true).apply();
                    if (Build.VERSION.SDK_INT >= 34) {
                        requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES, PARTIAL_PHOTOS}, PHOTO_REQUEST);
                    } else {
                        requestPermissions(new String[]{photoPermission()}, PHOTO_REQUEST);
                    }
                }
            });
        }

        /** Newest photos first, as a JSON list of ids (thumbnails are at /thumb/<id>). */
        @JavascriptInterface
        public String recentPhotos(int max) {
            return recentPhotoIds(Math.max(1, Math.min(max, 60)));
        }

        @JavascriptInterface
        public boolean openPhoto(String id) {
            try {
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.parseLong(id));
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "image/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** The phone's camera, ready for a still photo. */
        @JavascriptInterface
        public boolean openCamera() {
            try {
                Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Press Sheet's own page in Settings, for turning photo access back on. */
        @JavascriptInterface
        public void openOwnSettings() {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
                // nothing to open
            }
        }

        /** Android's "Default home app" screen, to switch to or away from Press Sheet. */
        @JavascriptInterface
        public void homeSettings() {
            try {
                Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {
                    // nothing to open
                }
            }
        }

        @JavascriptInterface
        public boolean isDefaultHome() {
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                ResolveInfo r = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
                return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
            } catch (Exception e) {
                return false;
            }
        }

        /** The saved layout (tiles, dock, mode), as the JSON text the page stored. */
        @JavascriptInterface
        public String getState() {
            return prefs.getString("state", "");
        }

        @JavascriptInterface
        public void setState(String json) {
            prefs.edit().putString("state", json).apply();
        }

        /** {url, email, name, signedIn}: never the token itself. */
        @JavascriptInterface
        public String inkflowAccount() {
            JSONObject o = new JSONObject();
            try {
                o.put("url", prefs.getString("if_url", "https://inkflow.up.railway.app"));
                o.put("email", prefs.getString("if_email", ""));
                o.put("name", prefs.getString("if_name", ""));
                o.put("signedIn", prefs.getString("if_token", "").length() > 0);
            } catch (Exception ignored) {
                // returns what it has
            }
            return o.toString();
        }

        /** Signs in with an InkFlow staff login. The password is sent once and not kept. */
        @JavascriptInterface
        public void inkflowSignIn(final String url, final String email, final String password) {
            background.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject result = new JSONObject();
                    try {
                        String base = cleanBase(url);
                        JSONObject body = new JSONObject();
                        body.put("email", email);
                        body.put("password", password);
                        String[] res = http("POST", base + "/api/mobile/login", null, body.toString());
                        JSONObject json = parse(res[1]);
                        if ("200".equals(res[0]) && json.optString("token").length() > 0) {
                            JSONObject user = json.optJSONObject("user");
                            prefs.edit()
                                    .putString("if_url", base)
                                    .putString("if_email", email)
                                    .putString("if_name", user == null ? "" : user.optString("name"))
                                    .putString("if_token", json.optString("token"))
                                    .apply();
                            result.put("ok", true);
                        } else {
                            result.put("ok", false);
                            result.put("error", json.optString("error", "InkFlow answered " + res[0] + "."));
                        }
                    } catch (Exception e) {
                        try {
                            result.put("ok", false);
                            result.put("error", "Couldn't reach InkFlow. Check the address and your connection.");
                        } catch (Exception ignored) {
                            // result stays empty
                        }
                    }
                    tellPage("signin", result.toString());
                }
            });
        }

        @JavascriptInterface
        public void inkflowSignOut() {
            prefs.edit().remove("if_token").remove("if_name").apply();
        }

        /** Fetches the Shop tiles' live numbers; the answer arrives as onNative("live", {...}). */
        @JavascriptInterface
        public void inkflowRefresh() {
            final String token = prefs.getString("if_token", "");
            final String base = prefs.getString("if_url", "");
            if (token.length() == 0 || base.length() == 0) {
                tellPage("live", "{\"ok\":false,\"signedOut\":true}");
                return;
            }
            background.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject result = new JSONObject();
                    try {
                        String[] res = http("GET", base + "/api/mobile/launcher", token, null);
                        JSONObject json = parse(res[1]);
                        if ("200".equals(res[0])) {
                            result.put("ok", true);
                            result.put("data", json);
                        } else if ("401".equals(res[0])) {
                            prefs.edit().remove("if_token").apply();
                            result.put("ok", false);
                            result.put("signedOut", true);
                            result.put("error", json.optString("error", "Sign in to InkFlow again."));
                        } else {
                            result.put("ok", false);
                            result.put("error", "InkFlow answered " + res[0] + ".");
                        }
                    } catch (Exception e) {
                        try {
                            result.put("ok", false);
                            result.put("error", "Couldn't reach InkFlow.");
                        } catch (Exception ignored) {
                            // result stays empty
                        }
                    }
                    tellPage("live", result.toString());
                }
            });
        }
    }

    // ---------------------------------------------------------------- talking to InkFlow

    private static String cleanBase(String url) {
        String base = url == null ? "" : url.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static JSONObject parse(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Returns {status code, body}. */
    private static String[] http(String method, String address, String token, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "PressSheet-Android");
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 2 * 1024 * 1024) {
                        break;
                    }
                }
                in.close();
            }
            return new String[]{String.valueOf(code), new String(buf.toByteArray(), StandardCharsets.UTF_8)};
        } finally {
            conn.disconnect();
        }
    }
}
