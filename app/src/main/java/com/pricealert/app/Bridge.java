package com.pricealert.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Methods the screen (index.html) calls as window.Android.*. */
public class Bridge {
    private final MainActivity act;
    private final WebView web;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    volatile int focusSlot = -1;

    Bridge(MainActivity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    void shutdown() { bg.shutdownNow(); }

    /** Calls window.onNative(event, data) in the page. */
    void emit(String event, String data) {
        String js = "window.onNative && window.onNative(" + JSONObject.quote(event) + ","
                + JSONObject.quote(data == null ? "" : data) + ")";
        act.runOnUiThread(() -> {
            try { web.evaluateJavascript(js, null); } catch (Exception ignored) { }
        });
    }

    private Context ctx() { return act.getApplicationContext(); }

    @JavascriptInterface
    public String getState() {
        Store s = Store.get(ctx());
        JSONObject o = new JSONObject();
        try {
            o.put("slots", s.slots());
            o.put("settings", s.settings());
            o.put("quotes", s.quotes());
            o.put("history", s.history());
            o.put("alertState", s.alertState());
            o.put("lastFetch", s.lastFetch());
            o.put("running", PriceService.running != null);
            o.put("marketOpen", Market.isOpen(System.currentTimeMillis()));
            o.put("nextOpen", Market.nextOpen(System.currentTimeMillis()));
            o.put("notifAllowed", notificationsAllowed());
            o.put("batteryFree", batteryUnrestricted());
            o.put("now", System.currentTimeMillis());
        } catch (JSONException ignored) { }
        return o.toString();
    }

    @JavascriptInterface
    public int takeFocusSlot() {
        int f = focusSlot;
        focusSlot = -1;
        return f;
    }

    /** Save one slot, then check its price right away and report back. */
    @JavascriptInterface
    public void saveSlot(int i, String sym, String alert) {
        String s = sym == null ? "" : sym.trim().toUpperCase();
        double a;
        try { a = Double.parseDouble(alert == null ? "0" : alert.trim()); } catch (Exception e) { a = 0; }
        if (a < 0) a = 0;
        Store store = Store.get(ctx());
        store.setSlot(i, s, a);
        if (!s.isEmpty()) store.resetAlertState(s);
        final double alertVal = a;
        bg.execute(() -> {
            JSONObject r = new JSONObject();
            try {
                Checker.run(ctx(), true);
                JSONObject q = Store.get(ctx()).quotes().optJSONObject(s);
                boolean fresh = q != null && System.currentTimeMillis() - q.optLong("t", 0) < 60_000L;
                r.put("i", i);
                r.put("sym", s);
                r.put("alert", alertVal);
                r.put("ok", s.isEmpty() || fresh);
                if (q != null) {
                    r.put("price", q.optDouble("price", 0));
                    r.put("name", q.optString("name", ""));
                    r.put("src", q.optString("src", ""));
                }
            } catch (Exception e) {
                try { r.put("i", i); r.put("ok", false); } catch (JSONException ignored) { }
            }
            emit("slot", r.toString());
        });
    }

    @JavascriptInterface
    public void resume(String sym) {
        if (sym != null) Store.get(ctx()).resetAlertState(sym);
    }

    @JavascriptInterface
    public void saveSettings(String json) {
        try {
            JSONObject changes = new JSONObject(json);
            Store store = Store.get(ctx());
            store.mergeSettings(changes);
            if (changes.has("monitoring")) {
                if (store.monitoring()) PriceService.start(act);
                else PriceService.stop(act);
            } else {
                PriceService.kick();
            }
        } catch (JSONException ignored) { }
    }

    @JavascriptInterface
    public void refresh() {
        bg.execute(() -> {
            JSONObject r = new JSONObject();
            try {
                Checker.Result res = Checker.run(ctx(), true);
                r.put("asked", res.asked);
                r.put("got", res.got);
                r.put("err", res.error);
            } catch (Exception e) {
                try { r.put("err", String.valueOf(e.getMessage())); } catch (JSONException ignored) { }
            }
            emit("refreshed", r.toString());
        });
    }

    @JavascriptInterface
    public void testNotification() { Notifier.test(ctx()); }

    @JavascriptInterface
    public void clearHistory() { Store.get(ctx()).clearHistory(); }

    @JavascriptInterface
    public boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= 33) {
            return act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @JavascriptInterface
    public void askNotificationPermission() {
        act.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 33) {
                act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            if (!notificationsAllowed() || Build.VERSION.SDK_INT < 33) {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName());
                try { act.startActivity(i); } catch (Exception ignored) { }
            }
        });
    }

    @JavascriptInterface
    public boolean batteryUnrestricted() {
        PowerManager pm = (PowerManager) act.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(act.getPackageName());
    }

    @JavascriptInterface
    public void openBatterySettings() {
        act.runOnUiThread(() -> {
            try {
                act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + act.getPackageName()));
                    act.startActivity(i);
                } catch (Exception ignored) { }
            }
        });
    }

    @JavascriptInterface
    public void openChannelSettings() {
        act.runOnUiThread(() -> {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName());
            try { act.startActivity(i); } catch (Exception ignored) { }
        });
    }
}
