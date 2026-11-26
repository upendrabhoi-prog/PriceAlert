package com.pricealert.app;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/** Supplies the rows of the scrollable Watchlist widget. */
public class WatchService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static final class Factory implements RemoteViewsFactory {
        private final Context c;
        private List<Widgets.Row> rows = new ArrayList<>();
        private boolean mine;

        Factory(Context c) { this.c = c; }

        @Override public void onCreate() { onDataSetChanged(); }

        @Override public void onDataSetChanged() {
            mine = Widgets.hasHoldings(c);
            rows = Widgets.rows(c);
        }

        @Override public void onDestroy() { rows.clear(); }

        @Override public int getCount() { return rows.size(); }

        @Override public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= rows.size()) return new RemoteViews(c.getPackageName(), R.layout.w_row);
            return Widgets.rowViews(c, rows.get(position), mine);
        }

        @Override public RemoteViews getLoadingView() { return null; }

        @Override public int getViewTypeCount() { return 1; }

        @Override public long getItemId(int position) { return position; }

        @Override public boolean hasStableIds() { return false; }
    }
}
