package com.pricealert.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Draws the two home-screen widgets (Portfolio and Watchlist) from the saved prices. */
public final class Widgets {
    static final int GREEN = 0xFF34D399;
    static final int RED = 0xFFF87171;
    static final int MUTED = 0xFF94A3B8;
    static final int WHITE = 0xFFE6EAF2;

    private Widgets() { }

    /** One row of the watchlist widget. */
    static final class Row {
        String sym;
        String name = "";
        double price;
        double pchg;
        boolean hasPrice;
        boolean hasChg;
        double ref;          // buy price (My stocks) or alert price (Alert stocks)
    }

    /** Redraws every widget the user has placed. Safe to call from any thread. */
    public static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        int[] p = m.getAppWidgetIds(new ComponentName(c, PortfolioWidget.class));
        if (p != null && p.length > 0) {
            RemoteViews v = portfolioViews(c);
            for (int id : p) m.updateAppWidget(id, v);
        }
        int[] w = m.getAppWidgetIds(new ComponentName(c, WatchWidget.class));
        if (w != null && w.length > 0) {
            for (int id : w) m.updateAppWidget(id, watchViews(c, id));
            m.notifyAppWidgetViewDataChanged(w, R.id.w_list);
        }
    }

    static String time(long ms) {
        if (ms <= 0) return "not updated yet";
        return Instant.ofEpochMilli(ms).atZone(Market.IST)
                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
    }

    static String signed(double v) {
        return (v >= 0 ? "+" : "−") + Notifier.rupees(Math.abs(v));
    }

    static String pct(double v) {
        return (v >= 0 ? "+" : "") + String.format(Locale.US, "%.2f%%", v);
    }

    private static int mutable() {
        return Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
    }

    static PendingIntent openApp(Context c, String tab, int req, boolean mutable) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("tab", tab);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT
                | (mutable ? mutable() : PendingIntent.FLAG_IMMUTABLE));
    }

    static PendingIntent refresh(Context c) {
        Intent i = new Intent(c, ActionReceiver.class);
        i.setAction(ActionReceiver.ACTION_REFRESH);
        return PendingIntent.getBroadcast(c, 88, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ---------------- Portfolio ----------------
    static RemoteViews portfolioViews(Context c) {
        Store s = Store.get(c);
        JSONArray hs = s.holdings();
        JSONObject quotes = s.quotes();
        double inv = 0, cur = 0, day = 0;
        int n = 0, priced = 0;
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h == null) continue;
            String sym = h.optString("sym", "");
            double buy = h.optDouble("buy", 0), qty = h.optDouble("qty", 0);
            if (sym.isEmpty() || buy <= 0 || qty <= 0) continue;
            n++;
            JSONObject q = quotes.optJSONObject(sym);
            if (q == null) continue;
            priced++;
            inv += buy * qty;
            cur += q.optDouble("price", 0) * qty;
            if (q.optBoolean("hasChg", false)) day += q.optDouble("chg", 0) * qty;
        }
        double pl = cur - inv, plp = inv > 0 ? pl / inv * 100 : 0;
        double base = cur - day, dayp = base > 0 ? day / base * 100 : 0;

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.w_portfolio);
        v.setOnClickPendingIntent(R.id.w_root, openApp(c, "port", 61, false));
        v.setOnClickPendingIntent(R.id.w_refresh, refresh(c));
        v.setTextViewText(R.id.w_time, time(s.lastFetch().optLong("t", 0)));
        if (n == 0) {
            v.setInt(R.id.w_root, "setBackgroundResource", R.drawable.w_bg);
            v.setTextViewText(R.id.w_value, "₹0.00");
            v.setTextViewText(R.id.w_pl, "Add your stocks in the app");
            v.setTextColor(R.id.w_pl, MUTED);
            v.setTextViewText(R.id.w_inv, "—");
            v.setTextViewText(R.id.w_day, "—");
            v.setTextColor(R.id.w_day, WHITE);
            v.setTextViewText(R.id.w_count, "No holdings yet");
            return v;
        }
        v.setInt(R.id.w_root, "setBackgroundResource",
                priced == 0 ? R.drawable.w_bg : pl >= 0 ? R.drawable.w_bg_g : R.drawable.w_bg_r);
        v.setTextViewText(R.id.w_value, Notifier.rupees(cur));
        v.setTextViewText(R.id.w_pl, signed(pl) + "  (" + pct(plp) + ") overall");
        v.setTextColor(R.id.w_pl, pl >= 0 ? GREEN : RED);
        v.setTextViewText(R.id.w_inv, Notifier.rupees(inv));
        v.setTextViewText(R.id.w_day, signed(day) + " (" + pct(dayp) + ")");
        v.setTextColor(R.id.w_day, day >= 0 ? GREEN : RED);
        v.setTextViewText(R.id.w_count, n + " holding" + (n == 1 ? "" : "s")
                + (priced < n ? " · " + (n - priced) + " waiting for price" : ""));
        return v;
    }

    // ---------------- Watchlist ----------------
    static RemoteViews watchViews(Context c, int widgetId) {
        List<Row> rows = rows(c);
        int up = 0, dn = 0;
        for (Row r : rows) { if (r.hasChg && r.pchg > 0) up++; else if (r.hasChg && r.pchg < 0) dn++; }
        boolean mine = hasHoldings(c);

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.w_watch);
        v.setTextViewText(R.id.w_title, mine ? "Watchlist · My stocks" : "Watchlist · Alert stocks");
        v.setTextViewText(R.id.w_sub, "▲ " + up + "   ▼ " + dn + "   · " + time(Store.get(c).lastFetch().optLong("t", 0)));
        v.setOnClickPendingIntent(R.id.w_head, openApp(c, "watch", 62, false));
        v.setOnClickPendingIntent(R.id.w_refresh, refresh(c));

        Intent svc = new Intent(c, WatchService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.w_list, svc);
        v.setEmptyView(R.id.w_list, R.id.w_empty);
        v.setPendingIntentTemplate(R.id.w_list, openApp(c, "watch", 63, true));
        return v;
    }

    static boolean hasHoldings(Context c) {
        JSONArray hs = Store.get(c).holdings();
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h != null && !h.optString("sym", "").isEmpty() && h.optDouble("qty", 0) > 0) return true;
        }
        return false;
    }

    /** My stocks (or alert stocks when there are none), sorted by today's % change, gainers first. */
    static List<Row> rows(Context c) {
        Store s = Store.get(c);
        JSONObject quotes = s.quotes();
        Map<String, Row> map = new LinkedHashMap<>();
        if (hasHoldings(c)) {
            JSONArray hs = s.holdings();
            Map<String, double[]> agg = new LinkedHashMap<>();
            for (int i = 0; i < hs.length(); i++) {
                JSONObject h = hs.optJSONObject(i);
                if (h == null) continue;
                String sym = h.optString("sym", "");
                double qty = h.optDouble("qty", 0), buy = h.optDouble("buy", 0);
                if (sym.isEmpty() || qty <= 0) continue;
                double[] a = agg.get(sym);
                if (a == null) agg.put(sym, new double[]{buy * qty, qty});
                else { a[0] += buy * qty; a[1] += qty; }
            }
            for (Map.Entry<String, double[]> e : agg.entrySet()) {
                Row r = row(e.getKey(), quotes);
                r.ref = e.getValue()[1] > 0 ? e.getValue()[0] / e.getValue()[1] : 0;
                map.put(e.getKey(), r);
            }
        } else {
            JSONArray sl = s.slots();
            for (int i = 0; i < sl.length(); i++) {
                JSONObject o = sl.optJSONObject(i);
                if (o == null) continue;
                String sym = o.optString("sym", "");
                double al = o.optDouble("alert", 0);
                if (sym.isEmpty() || al <= 0 || map.containsKey(sym)) continue;
                Row r = row(sym, quotes);
                r.ref = al;
                map.put(sym, r);
            }
        }
        List<Row> list = new ArrayList<>(map.values());
        list.sort((a, b) -> {
            double x = a.hasChg ? a.pchg : -1e9, y = b.hasChg ? b.pchg : -1e9;
            return Double.compare(y, x);
        });
        return list;
    }

    private static Row row(String sym, JSONObject quotes) {
        Row r = new Row();
        r.sym = sym;
        JSONObject q = quotes.optJSONObject(sym);
        if (q != null) {
            r.hasPrice = true;
            r.price = q.optDouble("price", 0);
            r.name = q.optString("name", "");
            r.hasChg = q.optBoolean("hasChg", false);
            r.pchg = q.optDouble("pchg", 0);
        }
        return r;
    }

    static RemoteViews rowViews(Context c, Row r, boolean mine) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.w_row);
        v.setTextViewText(R.id.r_sym, r.sym);
        v.setTextViewText(R.id.r_name, (mine ? "Buy " : "Alert ") + Notifier.rupees(r.ref));
        v.setTextViewText(R.id.r_price, r.hasPrice ? Notifier.rupees(r.price) : "—");
        v.setTextViewText(R.id.r_pct, r.hasChg ? pct(r.pchg) : "—");
        int chip = !r.hasChg || r.pchg == 0 ? R.drawable.w_chip_n : r.pchg > 0 ? R.drawable.w_chip_g : R.drawable.w_chip_r;
        v.setInt(R.id.r_pct, "setBackgroundResource", chip);
        int bg = !r.hasChg || r.pchg == 0 ? R.drawable.w_row_n : r.pchg > 0 ? R.drawable.w_row_g : R.drawable.w_row_r;
        v.setInt(R.id.row, "setBackgroundResource", bg);
        v.setOnClickFillInIntent(R.id.row, new Intent());
        return v;
    }
}
