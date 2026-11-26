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
    volatile String openTab = "";
    /** The bridge of the screen that is open right now, if any. */
    static volatile Bridge current;

    Bridge(MainActivity act, WebView web) {
        this.act = act;
        this.web = web;
        current = this;
    }

    void shutdown() {
        if (current == this) current = null;
        bg.shutdownNow();
    }

    /** Tells the open screen (if any) that new prices were saved, so every tab refreshes. */
    static void notifyUpdated() {
        Bridge b = current;
        if (b != null) b.emit("updated", "");
    }

    @JavascriptInterface
    public String takeTab() {
        String t = openTab;
        openTab = "";
        return t == null ? "" : t;
    }

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
            o.put("holdings", s.holdings());
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

    /** Save one My Stocks entry (empty symbol clears it), then fetch its price and report back. */
    @JavascriptInterface
    public void saveHolding(int i, String sym, String buy, String qty, String target, String acct) {
        String s = sym == null ? "" : sym.trim().toUpperCase();
        double b = num(buy), q = num(qty), t = num(target);
        Store store = Store.get(ctx());
        store.setHolding(i, s, b, q, t, acct);
        store.resetTargetState(i);
        bg.execute(() -> {
            JSONObject r = new JSONObject();
            try {
                Checker.run(ctx(), true);
                JSONObject qt = Store.get(ctx()).quotes().optJSONObject(s);
                boolean fresh = qt != null && System.currentTimeMillis() - qt.optLong("t", 0) < 60_000L;
                r.put("i", i);
                r.put("sym", s);
                r.put("target", t);
                r.put("ok", s.isEmpty() || fresh);
                if (qt != null) r.put("price", qt.optDouble("price", 0));
            } catch (Exception e) {
                try { r.put("i", i); r.put("sym", s); r.put("ok", false); } catch (JSONException ignored) { }
            }
            emit("holding", r.toString());
        });
    }

    private static double num(String v) {
        try { double d = Double.parseDouble(v == null ? "0" : v.trim()); return d > 0 ? d : 0; }
        catch (Exception e) { return 0; }
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
            if (Store.get(ctx()).monitoring() && PriceService.running == null) {
                act.runOnUiThread(() -> PriceService.start(act));
            }
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

    /** Opens the settings page of the background-service notification so it can be switched off. */
    @JavascriptInterface
    public void hideServiceNotification() {
        act.runOnUiThread(() -> {
            Intent i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName());
            i.putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CH_MONITOR);
            try { act.startActivity(i); } catch (Exception e) { openChannelSettings(); }
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
