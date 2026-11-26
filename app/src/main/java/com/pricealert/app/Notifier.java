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
                {"al_sv", "Price alerts (sound + vibrate)"},
                {"al_s", "Price alerts (sound only)"},
                {"al_v", "Price alerts (vibrate only)"},
                {"al_n", "Price alerts (silent)"}
        };
        for (String[] d : defs) {
            NotificationChannel ch = new NotificationChannel(d[0], d[1], NotificationManager.IMPORTANCE_HIGH);
            boolean sound = d[0].equals("al_sv") || d[0].equals("al_s");
            boolean vib = d[0].equals("al_sv") || d[0].equals("al_v");
            if (!sound) ch.setSound(null, null);
            ch.enableVibration(vib);
            if (vib) ch.setVibrationPattern(new long[]{0, 400, 200, 400});
            ch.enableLights(true);
            ch.setLightColor(COLOR);
            m.createNotificationChannel(ch);
        }
    }

    private static String alertChannel(Context c) {
        JSONObject s = Store.get(c).settings();
        boolean snd = s.optBoolean("sound", true);
        boolean vib = s.optBoolean("vibrate", true);
        if (snd && vib) return "al_sv";
        if (snd) return "al_s";
        if (vib) return "al_v";
        return "al_n";
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

    private static void post(Context c, int id, int slot, String sym, String title, String text) {
        Notification n = new Notification.Builder(c, alertChannel(c))
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setColor(COLOR)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(openApp(c, slot, id))
                .addAction(action(c, "Edit price", openApp(c, slot, id + 500)))
                .addAction(action(c, "Pause today", pause(c, sym, id + 900)))
                .build();
        try { nm(c).notify(id, n); } catch (SecurityException ignored) { }
    }

    public static void alertHit(Context c, int slot, PriceFetcher.Quote q, double alert, int count) {
        double below = (q.price - alert) / alert * 100.0;
        String title = q.sym + " hit your alert price";
        String text = "Now " + rupees(q.price) + " — at or below your alert of " + rupees(alert)
                + String.format(Locale.US, " (%.2f%%).", below)
                + (count > 1 ? " Reminder #" + count + "." : "")
                + " Source: " + q.src + ".";
        post(c, 1000 + slot, slot, q.sym, title, text);
    }

    public static void alertNear(Context c, int slot, PriceFetcher.Quote q, double alert) {
        double above = (q.price - alert) / alert * 100.0;
        String title = q.sym + " is close to your alert";
        String text = "Now " + rupees(q.price) + String.format(Locale.US, ", %.2f%% above", above)
                + " your alert of " + rupees(alert) + ". Source: " + q.src + ".";
        post(c, 2000 + slot, slot, q.sym, title, text);
    }

    public static void targetHit(Context c, int holdingSlot, PriceFetcher.Quote q, double target, int count) {
        double above = (q.price - target) / target * 100.0;
        String title = q.sym + " reached your target";
        String text = "Now " + rupees(q.price) + " — at or above your target of " + rupees(target)
                + String.format(Locale.US, " (+%.2f%%).", above)
                + (count > 1 ? " Reminder #" + count + "." : "")
                + " Source: " + q.src + ".";
        int id = 4000 + holdingSlot;
        Notification n = new Notification.Builder(c, alertChannel(c))
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setColor(0xFF34D399)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(openApp(c, -1, id))
                .addAction(action(c, "Pause today", pause(c, q.sym, id + 900)))
                .build();
        try { nm(c).notify(id, n); } catch (SecurityException ignored) { }
    }

    public static void test(Context c) {
        post(c, 3000, -1, "TEST", "Wealthora test",
                "Notifications are working. You'll get alerts like this when a stock reaches your price.");
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
