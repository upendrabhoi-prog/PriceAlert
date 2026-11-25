package com.pricealert.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles notification buttons and the market-open wake-up alarm. */
public class ActionReceiver extends BroadcastReceiver {
    public static final String ACTION_PAUSE = "com.pricealert.app.PAUSE";
    public static final String ACTION_STOP = "com.pricealert.app.STOP_MONITOR";
    public static final String ACTION_KICK = "com.pricealert.app.KICK";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (ACTION_PAUSE.equals(a)) {
            String sym = intent.getStringExtra("sym");
            if (sym != null && !sym.equals("TEST")) {
                Store.get(c).pauseToday(sym, Market.endOfToday(System.currentTimeMillis()));
            }
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            int slot = slotOf(c, sym);
            if (slot >= 0) {
                nm.cancel(1000 + slot);
                nm.cancel(2000 + slot);
            }
            nm.cancel(3000);
        } else if (ACTION_STOP.equals(a)) {
            Store.get(c).setMonitoring(false);
            PriceService.stop(c);
        } else if (ACTION_KICK.equals(a)) {
            if (!Store.get(c).monitoring()) return;
            if (PriceService.running != null) PriceService.kick();
            else PriceService.start(c);
        }
    }

    private static int slotOf(Context c, String sym) {
        if (sym == null) return -1;
        org.json.JSONArray s = Store.get(c).slots();
        for (int i = 0; i < s.length(); i++) {
            org.json.JSONObject o = s.optJSONObject(i);
            if (o != null && sym.equals(o.optString("sym"))) return i;
        }
        return -1;
    }
}
