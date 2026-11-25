package com.pricealert.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Foreground service that checks prices on a timer while monitoring is switched on. */
public class PriceService extends Service {
    public static final String ACTION_STOP = "com.pricealert.app.STOP";
    public static volatile PriceService running;

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private final Runnable tick = this::tick;

    public static void start(Context c) {
        Intent i = new Intent(c, PriceService.class);
        try { c.startForegroundService(i); } catch (Exception ignored) { }
    }

    public static void stop(Context c) {
        PriceService s = running;
        if (s != null) s.stopSelf();
        else c.stopService(new Intent(c, PriceService.class));
    }

    /** Re-check now (for example after settings changed). */
    public static void kick() {
        PriceService s = running;
        if (s != null && s.handler != null) {
            s.handler.removeCallbacks(s.tick);
            s.handler.post(s.tick);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.ensureChannels(this);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.MONITOR_ID, Notifier.monitor(this, "Starting…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(Notifier.MONITOR_ID, Notifier.monitor(this, "Starting…"));
        }
        thread = new HandlerThread("price-check");
        thread.start();
        handler = new Handler(thread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PriceAlert:check");
        wakeLock.setReferenceCounted(false);
        running = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Store.get(this).monitoring()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private static String time(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(Market.IST);
        return z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
    }

    private static String day(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(Market.IST);
        return z.format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH));
    }

    private void tick() {
        Store store = Store.get(this);
        if (!store.monitoring()) {
            stopSelf();
            return;
        }
        long now = System.currentTimeMillis();
        boolean marketOnly = store.settings().optBoolean("marketOnly", true);
        long pollMs = Math.max(1, store.settings().optInt("pollMin", 1)) * 60_000L;

        if (!marketOnly || Market.isOpen(now)) {
            wakeLock.acquire(pollMs * 3);
            try {
                Checker.Result r = Checker.run(this, true);
                String text = r.asked == 0
                        ? "No stocks in your list yet"
                        : "Checked " + r.got + " of " + r.asked + " stocks at " + time(now)
                          + (r.got < r.asked ? " · some prices unavailable" : "");
                Notifier.updateMonitor(this, text);
            } catch (Exception e) {
                Notifier.updateMonitor(this, "Last check failed: " + e.getMessage());
            }
            handler.postDelayed(tick, pollMs);
        } else {
            if (wakeLock.isHeld()) wakeLock.release();
            long open = Market.nextOpen(now);
            Notifier.updateMonitor(this, "Market closed · resumes " + day(open));
            scheduleWake(open);
            handler.postDelayed(tick, Math.min(15 * 60_000L, Math.max(60_000L, open - now)));
        }
    }

    private void scheduleWake(long at) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(this, ActionReceiver.class);
        i.setAction(ActionReceiver.ACTION_KICK);
        PendingIntent pi = PendingIntent.getBroadcast(this, 7, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    @Override
    public void onDestroy() {
        running = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
