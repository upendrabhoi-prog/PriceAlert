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
        String kind = intent == null ? null : intent.getStringExtra("kind");
        return new Factory(getApplicationContext(), kind == null ? "watch" : kind);
    }

    static final class Factory implements RemoteViewsFactory {
        private final Context c;
        private final String kind;
        private List<Widgets.Row> rows = new ArrayList<>();
        private List<Widgets.Item> items = new ArrayList<>();
        private boolean mine;

        Factory(Context c, String kind) { this.c = c; this.kind = kind; }

        @Override public void onCreate() { onDataSetChanged(); }

        @Override public void onDataSetChanged() {
            if ("dist".equals(kind)) items = Widgets.distItems(c);
            else if ("target".equals(kind)) items = Widgets.targetItems(c);
            else {
                mine = Widgets.hasHoldings(c);
                rows = Widgets.rows(c);
            }
        }

        private boolean isList() { return "dist".equals(kind) || "target".equals(kind); }

        @Override public void onDestroy() { rows.clear(); items.clear(); }

        @Override public int getCount() { return isList() ? items.size() : rows.size(); }

        @Override public RemoteViews getViewAt(int position) {
            if (isList()) {
                if (position < 0 || position >= items.size()) return new RemoteViews(c.getPackageName(), R.layout.w_row2);
                return Widgets.itemViews(c, items.get(position));
            }
            if (position < 0 || position >= rows.size()) return new RemoteViews(c.getPackageName(), R.layout.w_row);
            return Widgets.rowViews(c, rows.get(position), mine);
        }

        @Override public RemoteViews getLoadingView() { return null; }

        @Override public int getViewTypeCount() { return 2; }

        @Override public long getItemId(int position) { return position; }

        @Override public boolean hasStableIds() { return false; }
    }
}
