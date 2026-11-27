package com.pricealert.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private WebView web;
    Bridge bridge;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifier.ensureChannels(this);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0A0F1C);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "Android");

        // Android 15+ draws apps edge to edge: keep the page clear of the status bar,
        // navigation bar and keyboard.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0F1C);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets b = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(b.left, b.top, b.right, b.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        web.loadUrl("file:///android_asset/index.html");

        handleIntent(getIntent());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (Store.get(this).monitoring() && PriceService.running == null) PriceService.start(this);
        WatchJob.schedule(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        int slot = i.getIntExtra("slot", -1);
        if (slot >= 0) {
            bridge.focusSlot = slot;
            i.removeExtra("slot");
        }
        String tab = i.getStringExtra("tab");
        if (tab != null) {
            bridge.openTab = tab;
            i.removeExtra("tab");
        }
    }

    // ---------- backup & restore ----------
    private static final int REQ_SAVE = 41, REQ_OPEN = 42;

    void pickBackupDestination() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, Backup.fileName());
        try { startActivityForResult(i, REQ_SAVE); }
        catch (Exception e) { bridge.emit("backup", "{\"ok\":false,\"err\":\"No file manager found on this phone\"}"); }
    }

    void pickBackupFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "application/octet-stream", "text/plain"});
        try { startActivityForResult(i, REQ_OPEN); }
        catch (Exception e) { bridge.emit("restorePreview", "{\"ok\":false,\"err\":\"No file manager found on this phone\"}"); }
    }

    void shareBackup() {
        try {
            java.io.File f = Backup.cacheFile(this);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/json");
            send.putExtra(Intent.EXTRA_STREAM, BackupProvider.uriFor(f));
            send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Send Wealthora backup to"));
            Backup.saved(this, "Shared · " + f.getName());
            bridge.emit("backup", "{\"ok\":false}");     // refresh "last backup" without a success toast
        } catch (Exception e) {
            bridge.emit("backup", "{\"ok\":false,\"err\":" + org.json.JSONObject.quote("Couldn't share: " + e.getMessage()) + "}");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE && requestCode != REQ_OPEN) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;   // user cancelled
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                if (requestCode == REQ_SAVE) {
                    Backup.write(this, uri);
                    String where = Store.get(this).backupInfo().optString("where", "");
                    String place = where.startsWith("Google Drive") ? "Google Drive" : where.startsWith("Phone") ? "your phone" : "";
                    bridge.emit("backup", "{\"ok\":true,\"where\":" + org.json.JSONObject.quote(place) + "}");
                } else {
                    org.json.JSONObject o = Backup.read(this, uri);
                    String name = Backup.where(this, uri).replaceFirst("^[^·]*· ", "");
                    bridge.pendingRestore = o;
                    org.json.JSONObject pv = Backup.preview(o, name);
                    pv.put("token", "r" + System.currentTimeMillis());
                    bridge.emit("restorePreview", pv.toString());
                }
            } catch (Exception e) {
                String ev = requestCode == REQ_SAVE ? "backup" : "restorePreview";
                bridge.emit(ev, "{\"ok\":false,\"err\":" + org.json.JSONObject.quote(String.valueOf(e.getMessage())) + "}");
            }
        }, "backup").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Store.get(this).monitoring() && PriceService.running == null) PriceService.start(this);
        bridge.emit("resume", "");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        bridge.emit("resume", "");
    }

    /** Back goes to the Alerts tab first, then closes the app. */
    private void handleBack() {
        web.evaluateJavascript("window.goBack ? window.goBack() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onDestroy() {
        bridge.shutdown();
        web.destroy();
        super.onDestroy();
    }
}
