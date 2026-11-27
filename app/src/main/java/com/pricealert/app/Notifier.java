package com.pricealert.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

public final class Notifier {
    public static final String CH_MONITOR = "monitor_quiet";
    public static final int MONITOR_ID = 1;
    private static final int COLOR = 0xFF38BDF8;

    private Notifier() { }

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public static void ensureChannels(Context c) {
        NotificationManager m = nm(c);
        try { m.deleteNotificationChannel("monitor"); } catch (Exception ignored) { }
        NotificationChannel mon = new NotificationChannel(CH_MONITOR, "Background service (can be turned off)",
                NotificationManager.IMPORTANCE_MIN);
        mon.setDescription("Android needs this while Wealthora checks prices in the background. "
                + "You can turn it off; price checks and alerts keep working.");
        mon.setShowBadge(false);
        mon.setSound(null, null);
        mon.enableVibration(false);
        m.createNotificationChannel(mon);

        String[][] defs = {
                {"al_sv", "Buy alerts (sound + vibrate)"},
                {"al_s", "Buy alerts (sound only)"},
                {"al_v", "Buy alerts (vibrate only)"},
                {"al_n", "Buy alerts (silent)"},
                {"tg_sv", "Target hits · sell (sound + vibrate)"},
                {"tg_s", "Target hits · sell (sound only)"},
                {"tg_v", "Target hits · sell (vibrate only)"},
                {"tg_n", "Target hits · sell (silent)"}
        };
        for (String[] d : defs) {
            NotificationChannel ch = new NotificationChannel(d[0], d[1], NotificationManager.IMPORTANCE_HIGH);
            boolean sound = d[0].endsWith("_sv") || d[0].endsWith("_s");
            boolean vib = d[0].endsWith("_sv") || d[0].endsWith("_v");
            if (!sound) ch.setSound(null, null);
            ch.enableVibration(vib);
            if (vib) ch.setVibrationPattern(new long[]{0, 400, 200, 400});
            ch.enableLights(true);
            ch.setLightColor(d[0].startsWith("tg") ? 0xFF22C55E : 0xFFF59E0B);
            if (d[0].startsWith("tg")) ch.setVibrationPattern(new long[]{0, 250, 120, 250, 120, 500});
            m.createNotificationChannel(ch);
        }
    }

    private static String alertChannel(Context c) { return channel(c, "al"); }

    private static String channel(Context c, String kind) {
        JSONObject s = Store.get(c).settings();
        boolean snd = s.optBoolean("sound", true);
        boolean vib = s.optBoolean("vibrate", true);
        if (snd && vib) return kind + "_sv";
        if (snd) return kind + "_s";
        if (vib) return kind + "_v";
        return kind + "_n";
    }

    private static PendingIntent openTab(Context c, String tab, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("tab", tab);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static String rupees(double v) {
        NumberFormat f = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return "₹" + f.format(v);
    }

    private static PendingIntent openApp(Context c, int slot, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (slot >= 0) i.putExtra("slot", slot);
        return PendingIntent.getActivity(c, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent pause(Context c, String sym, int req) {
        Intent i = new Intent(c, ActionReceiver.class);
        i.setAction(ActionReceiver.ACTION_PAUSE);
        i.putExtra("sym", sym);
        return PendingIntent.getBroadcast(c, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification.Action action(Context c, String title, PendingIntent pi) {
        return new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_stat_alert), title, pi).build();
    }

    private static void post(Context c, int id, int slot, String sym, String title, String text, int color, String sub) {
        Notification n = new Notification.Builder(c, alertChannel(c))
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setColor(color)
                .setColorized(false)
                .setSubText(sub)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(openApp(c, slot, id))
                .addAction(action(c, "Edit alert", openApp(c, slot, id + 500)))
                .addAction(action(c, "Pause today", pause(c, sym, id + 900)))
                .build();
        try { nm(c).notify(id, n); } catch (SecurityException ignored) { }
    }

    public static void alertHit(Context c, int slot, PriceFetcher.Quote q, double alert, int count) {
        double below = (q.price - alert) / alert * 100.0;
        String title = "🔔 BUY ALERT — " + q.sym + " · price dropped to your level";
        String text = "Now " + rupees(q.price) + ", at or below your buy alert " + rupees(alert)
                + String.format(Locale.US, " (%.2f%%).", below)
                + (count > 1 ? " Reminder #" + count + "." : "");
        post(c, 1000 + slot, slot, q.sym, title, text, 0xFFF59E0B, "Buy alert");
    }

    public static void alertNear(Context c, int slot, PriceFetcher.Quote q, double alert) {
        double above = (q.price - alert) / alert * 100.0;
        String title = "⏳ NEAR BUY ALERT — " + q.sym + String.format(Locale.US, " · %.1f%% away", above);
        String text = "Now " + rupees(q.price) + String.format(Locale.US, ", just %.1f%% above", above)
                + " your buy alert " + rupees(alert) + ".";
        post(c, 2000 + slot, slot, q.sym, title, text, 0xFF38BDF8, "Near buy alert");
    }

    public static void targetHit(Context c, int holdingSlot, PriceFetcher.Quote q, double target, int count) {
        Store s = Store.get(c);
        JSONObject h = s.holdings().optJSONObject(holdingSlot);
        double buy = h == null ? 0 : h.optDouble("buy", 0), qty = h == null ? 0 : h.optDouble("qty", 0);
        String acct = h == null ? "" : acctName(s, h.optString("acct", ""));
        String title = "🎯 TARGET HIT — " + q.sym + " · time to sell";
        StringBuilder text = new StringBuilder("Now " + rupees(q.price) + ", at or above your target " + rupees(target) + ".");
        if (buy > 0 && qty > 0) {
            double pl = (q.price - buy) * qty, pc = (q.price - buy) / buy * 100.0;
            text.append("\n").append(acct.isEmpty() ? "" : acct + " · ").append(fmtQty(qty)).append(" shares bought at ").append(rupees(buy))
                    .append(" → profit ").append(pl >= 0 ? "+" : "−").append(rupees(Math.abs(pl)))
                    .append(String.format(Locale.US, " (%+.2f%%)", pc));
        }
        if (count > 1) text.append(" Reminder #").append(count).append(".");
        int id = 4000 + holdingSlot;
        Notification n = new Notification.Builder(c, channel(c, "tg"))
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setColor(0xFF22C55E)
                .setSubText("Target hit · SELL")
                .setContentTitle(title)
                .setContentText(text.toString().replace("\n", " "))
                .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(openTab(c, "my", id))
                .addAction(action(c, "Record sale", openTab(c, "sale:" + holdingSlot, id + 300)))
                .addAction(action(c, "Edit target", openTab(c, "my", id + 600)))
                .addAction(action(c, "Pause today", pause(c, q.sym, id + 900)))
                .build();
        try { nm(c).notify(id, n); } catch (SecurityException ignored) { }
    }

    private static String fmtQty(double q) { return q == Math.rint(q) ? String.valueOf((long) q) : String.valueOf(q); }

    private static String acctName(Store s, String id) {
        org.json.JSONArray a = s.settings().optJSONArray("accounts");
        if (a == null || a.length() == 0) return "";
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && id.equals(o.optString("id"))) return o.optString("name");
        }
        JSONObject f = a.optJSONObject(0);
        return f == null ? "" : f.optString("name");
    }

    /** "Time to back up" reminder; tapping it opens Settings and starts a backup. */
    public static void backupReminder(Context c, long last) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("tab", "backup");
        PendingIntent pi = PendingIntent.getActivity(c, 5001, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String when = last > 0
                ? "Last backup was " + java.time.Instant.ofEpochMilli(last).atZone(Market.IST)
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + "."
                : "You haven't backed up your alerts and stocks yet.";
        Notification n = new Notification.Builder(c, "al_n")
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setColor(COLOR)
                .setContentTitle("Time to back up Wealthora")
                .setContentText(when + " Tap to save a fresh copy.")
                .setStyle(new Notification.BigTextStyle().bigText(when + " Tap to save a fresh copy to your phone or Google Drive."))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(action(c, "Back up now", pi))
                .build();
        try { nm(c).notify(5000, n); } catch (SecurityException ignored) { }
    }

    public static void test(Context c) {
        post(c, 3000, -1, "TEST", "Wealthora test",
                "Notifications are working. You'll get alerts like this when a stock reaches your price.", COLOR, "Test");
    }

    public static Notification monitor(Context c, String text) {
        Intent stop = new Intent(c, ActionReceiver.class);
        stop.setAction(ActionReceiver.ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getBroadcast(c, 77, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(c, CH_MONITOR)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setColor(COLOR)
                .setContentTitle("Wealthora price alerts are active")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(c, -1, 1))
                .addAction(action(c, "Stop", stopPi))
                .build();
    }

    public static void updateMonitor(Context c, String text) {
        // Intentionally does nothing: the background notification stays quiet and never changes.
    }
}
