package com.pricealert.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Home-screen widget: NIFTY 50 and BANK NIFTY with today's change. */
public class MarketWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Widgets.updateAll(c);
        WatchJob.schedule(c);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        m.updateAppWidget(id, Widgets.marketViews(c, id));     // side by side when wide, stacked when narrow
    }
}
