package com.pricealert.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts monitoring after the phone reboots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        WatchJob.schedule(c);
        if (Store.get(c).monitoring()) PriceService.start(c);
    }
}
