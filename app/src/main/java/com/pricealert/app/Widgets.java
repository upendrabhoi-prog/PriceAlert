package com.pricealert.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
        boolean fno;         // F&O position
    }

    static final int ORANGE = 0xFFFB923C;

    static boolean isFno(JSONObject h) {
        if (h == null) return false;
        Object f = h.opt("fno");
        return Boolean.TRUE.equals(f) || (f instanceof String && ((String) f).toLowerCase(Locale.ROOT).startsWith("y"));
    }

    /** "SBIN" followed by a small orange "F&O" tag when it is an F&O position. */
    static CharSequence symText(String sym, boolean fno) {
        if (!fno) return sym;
        SpannableStringBuilder b = new SpannableStringBuilder(sym).append("  ");
        int st = b.length();
        b.append("\u00A0F&O\u00A0");
        b.setSpan(new BackgroundColorSpan(ORANGE), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(0xFF0A0F1C), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new RelativeSizeSpan(0.7f), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
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
        int[] d = m.getAppWidgetIds(new ComponentName(c, DistWidget.class));
        if (d != null && d.length > 0) {
            for (int id : d) m.updateAppWidget(id, listViews(c, id, "dist"));
            m.notifyAppWidgetViewDataChanged(d, R.id.w_list);
        }
        int[] mk = m.getAppWidgetIds(new ComponentName(c, MarketWidget.class));
        if (mk != null) for (int id : mk) m.updateAppWidget(id, marketViews(c, id));
        int[] t = m.getAppWidgetIds(new ComponentName(c, TargetWidget.class));
        if (t != null && t.length > 0) {
            for (int id : t) m.updateAppWidget(id, listViews(c, id, "target"));
            m.notifyAppWidgetViewDataChanged(t, R.id.w_list);
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
        double[] fInv = new double[2], fCur = new double[2];
        int[] fN = new int[2];
        int n = 0, priced = 0;
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h == null) continue;
            String sym = h.optString("sym", "");
            double buy = h.optDouble("buy", 0), qty = h.optDouble("qty", 0);
            if (sym.isEmpty() || buy <= 0 || qty <= 0) continue;
            n++;
            int f = isFno(h) ? 1 : 0;
            fN[f]++;
            JSONObject q = quotes.optJSONObject(sym);
            if (q == null) continue;
            priced++;
            inv += buy * qty;
            cur += q.optDouble("price", 0) * qty;
            fInv[f] += buy * qty;
            fCur[f] += q.optDouble("price", 0) * qty;
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
        if (fN[1] > 0) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            String[] lab = {"Stocks", "F&O"};
            int[] col = {0xFF38BDF8, ORANGE};
            for (int f = 0; f < 2; f++) {
                if (f == 1) sb.append("\n");
                int a = sb.length();
                sb.append("■ ");
                sb.setSpan(new ForegroundColorSpan(col[f]), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(lab[f]).append(" · ").append(String.valueOf(fN[f])).append("   ").append(Notifier.rupees(fCur[f])).append("   ");
                double fp = fInv[f] > 0 ? (fCur[f] - fInv[f]) / fInv[f] * 100 : 0;
                int b0 = sb.length();
                sb.append(fInv[f] > 0 ? pct(fp) : "—");
                sb.setSpan(new ForegroundColorSpan(fp >= 0 ? GREEN : RED), b0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            v.setViewVisibility(R.id.w_split, android.view.View.VISIBLE);
            v.setTextViewText(R.id.w_split, sb);
        } else v.setViewVisibility(R.id.w_split, android.view.View.GONE);
        v.setTextViewText(R.id.w_count, n + " holding" + (n == 1 ? "" : "s")
                + (priced < n ? " · " + (n - priced) + " waiting for price" : ""));
        return v;
    }

    // ---------------- Market (NIFTY 50 / BANK NIFTY) ----------------
    static RemoteViews marketViews(Context c, int widgetId) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int minW = 0, minH = 0;
        try {
            android.os.Bundle o = m.getAppWidgetOptions(widgetId);
            minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        } catch (Exception ignored) { }
        int layout = minW == 0 || minW >= 230 ? R.layout.w_market_h          // side by side
                : minH > 0 && minH < 100 ? R.layout.w_market_t                // tiny: one line each
                : R.layout.w_market_v;                                        // stacked
        Store s = Store.get(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        v.setOnClickPendingIntent(R.id.w_root, openApp(c, "dashboard", 64, false));
        JSONObject quotes = s.quotes();
        marketTile(v, quotes.optJSONObject("NIFTY"), R.id.m_n, R.id.m_n_v, R.id.m_n_c, R.id.m_n_ar);
        marketTile(v, quotes.optJSONObject("BANKNIFTY"), R.id.m_b, R.id.m_b_v, R.id.m_b_c, R.id.m_b_ar);
        return v;
    }

    private static void marketTile(RemoteViews v, JSONObject q, int box, int val, int pts, int pill) {
        double price = q == null ? 0 : q.optDouble("price", 0);
        v.setTextViewText(val, price > 0 ? String.format(Locale.ENGLISH, "%,.2f", price) : "—");
        if (price <= 0 || !q.optBoolean("hasChg", false)) {
            v.setInt(box, "setBackgroundResource", R.drawable.w_row_n);
            v.setInt(pill, "setBackgroundResource", R.drawable.w_chip_n);
            v.setTextViewText(pill, "—");
            v.setTextViewText(pts, price > 0 ? "" : "waiting…");
            v.setTextColor(pts, MUTED);
            return;
        }
        double ch = q.optDouble("chg", 0), pc = q.optDouble("pchg", 0);
        boolean up = ch >= 0;
        v.setInt(box, "setBackgroundResource", up ? R.drawable.w_row_g : R.drawable.w_row_r);
        v.setInt(pill, "setBackgroundResource", up ? R.drawable.w_chip_g : R.drawable.w_chip_r);
        v.setTextViewText(pill, String.format(Locale.ENGLISH, "%s %.2f%%", up ? "▲" : "▼", Math.abs(pc)));
        v.setTextViewText(pts, String.format(Locale.ENGLISH, "%s%,.2f", up ? "+" : "−", Math.abs(ch)));
        v.setTextColor(pts, up ? GREEN : RED);
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

    // ---------------- Distance from buy price / % Target left ----------------
    /** One row of the distance or target widget. */
    static final class Item {
        String sym, tag = "", sub, pct, note = "";
        double sortKey;
        int color;       // 'r', 'y', 'b' or 'g' bar
        int progress;    // 0-100
        boolean fno;     // F&O position
        boolean good;    // green row (target reached)
        char bg = 'n';   // row background: 'n' normal, 'g' green (less than 5% away)
    }

    static RemoteViews listViews(Context c, int widgetId, String kind) {
        boolean dist = "dist".equals(kind);
        List<Item> items = dist ? distItems(c) : targetItems(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.w_watch);
        String t = time(Store.get(c).lastFetch().optLong("t", 0));
        v.setTextViewText(R.id.w_title, dist ? "Distance from buy price" : "% Target left");
        v.setTextViewText(R.id.w_sub, (dist ? "Top 10 closest · " : "From current price · smallest first · ") + t);
        v.setTextViewText(R.id.w_empty, dist ? "Set alert (buy) prices in Wealthora's Alerts tab to see them here."
                : "Add target prices to your stocks in Wealthora's My Stocks tab to see them here.");
        String tab = dist ? "dashboard" : "port";
        v.setOnClickPendingIntent(R.id.w_head, openApp(c, tab, dist ? 64 : 65, false));
        v.setOnClickPendingIntent(R.id.w_refresh, refresh(c));
        Intent svc = new Intent(c, WatchService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        svc.putExtra("kind", kind);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.w_list, svc);
        v.setEmptyView(R.id.w_list, R.id.w_empty);
        v.setPendingIntentTemplate(R.id.w_list, openApp(c, tab, dist ? 66 : 67, true));
        return v;
    }

    /** Alert stocks sorted like the Dashboard's "Closest" view (at/below buy price first), top 10. */
    static List<Item> distItems(Context c) {
        Store s = Store.get(c);
        JSONArray sl = s.slots();
        JSONObject quotes = s.quotes();
        double nearPct = s.settings().optDouble("nearPct", 2);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < sl.length(); i++) {
            JSONObject o = sl.optJSONObject(i);
            if (o == null) continue;
            String sym = o.optString("sym", "");
            double al = o.optDouble("alert", 0);
            JSONObject q = quotes.optJSONObject(sym);
            if (sym.isEmpty() || al <= 0 || q == null) continue;
            double price = q.optDouble("price", 0);
            double d = (price - al) / al * 100;
            Item it = new Item();
            it.sym = sym;
            it.sub = "CMP " + Notifier.rupees(price) + " · Buy " + Notifier.rupees(al);
            it.pct = pct(d);
            it.sortKey = d;
            it.color = d < 5 ? 'g' : 'b';
            if (d < 5) it.bg = 'g';      // less than 5% from buy price: green
            out.add(it);
        }
        out.sort((a, b) -> Double.compare(a.sortKey, b.sortKey));
        List<Item> top = out.size() > 10 ? new ArrayList<>(out.subList(0, 10)) : out;
        closeness(top);
        return top;
    }

    /** My Stocks with a target: % the price still has to rise, smallest first (reached targets on top). */
    static List<Item> targetItems(Context c) {
        Store s = Store.get(c);
        JSONArray hs = s.holdings();
        JSONObject quotes = s.quotes();
        JSONArray accts = s.settings().optJSONArray("accounts");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h == null) continue;
            String sym = h.optString("sym", "");
            double tg = h.optDouble("target", 0), buy = h.optDouble("buy", 0);
            JSONObject q = quotes.optJSONObject(sym);
            if (sym.isEmpty() || tg <= 0 || q == null) continue;
            double price = q.optDouble("price", 0);
            if (price <= 0) continue;
            double left = (tg - price) / price * 100;
            boolean hit = price >= tg;
            Item it = new Item();
            it.sym = sym;
            it.tag = accountName(accts, h.optString("acct", ""));
            it.fno = isFno(h);
            it.sub = "CMP " + Notifier.rupees(price) + " · Target " + Notifier.rupees(tg);
            it.pct = hit ? "✓ Hit" : String.format(Locale.US, "%.2f%%", left);
            it.note = hit ? "reached" : "to go";
            it.sortKey = left;
            it.good = hit;
            it.color = left < 5 ? 'g' : 'y';
            if (left < 5) it.bg = 'g';   // less than 5% left (or reached): green
            out.add(it);
        }
        out.sort((a, b) -> Double.compare(a.sortKey, b.sortKey));
        closeness(out);
        return out;
    }

    /** Bar length = closeness on one shared scale: full at the target / buy price, shorter the further away. */
    static void closeness(List<Item> list) {
        double m = 0;
        for (Item it : list) m = Math.max(m, it.sortKey);
        double sc = 50;
        for (double st : new double[]{5, 10, 15, 20, 25, 30, 40, 50}) if (m <= st) { sc = st; break; }
        for (Item it : list) {
            double v = it.sortKey;
            it.progress = v <= 0 ? 100 : (int) Math.round(Math.max(4, (1 - Math.min(v, sc) / sc) * 100));
        }
    }

    /** Account name for a holding, shown only when the user has more than one account. */
    private static String accountName(JSONArray accts, String id) {
        if (accts == null || accts.length() < 2) return "";
        for (int k = 0; k < accts.length(); k++) {
            JSONObject a = accts.optJSONObject(k);
            if (a != null && id.equals(a.optString("id"))) return a.optString("name", "");
        }
        JSONObject first = accts.optJSONObject(0);
        return first == null ? "" : first.optString("name", "");
    }

    static RemoteViews itemViews(Context c, Item it) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.w_row2);
        v.setTextViewText(R.id.r2_sym, symText(it.sym, it.fno));
        v.setTextViewText(R.id.r2_sub, it.sub);
        v.setTextViewText(R.id.r2_pct, it.pct);
        int col = it.color == 'r' ? RED : it.color == 'y' ? 0xFFFCD34D : it.color == 'g' ? GREEN : 0xFF38BDF8;
        v.setTextColor(R.id.r2_pct, col);
        if (it.tag.isEmpty()) v.setViewVisibility(R.id.r2_tag, android.view.View.GONE);
        else { v.setViewVisibility(R.id.r2_tag, android.view.View.VISIBLE); v.setTextViewText(R.id.r2_tag, it.tag); }
        if (it.note.isEmpty()) v.setViewVisibility(R.id.r2_note, android.view.View.GONE);
        else { v.setViewVisibility(R.id.r2_note, android.view.View.VISIBLE); v.setTextViewText(R.id.r2_note, it.note); }
        int[] bars = {R.id.r2_pb_r, R.id.r2_pb_y, R.id.r2_pb_b, R.id.r2_pb_g};
        char[] keys = {'r', 'y', 'b', 'g'};
        for (int k = 0; k < 4; k++) {
            boolean on = keys[k] == it.color;
            v.setViewVisibility(bars[k], on ? android.view.View.VISIBLE : android.view.View.GONE);
            if (on) v.setProgressBar(bars[k], 100, it.progress, false);
        }
        v.setInt(R.id.row2, "setBackgroundResource",
                it.bg == 'g' ? R.drawable.w_row_g : R.drawable.w_row_n);
        v.setOnClickFillInIntent(R.id.row2, new Intent());
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
                String key = isFno(h) ? sym + "|F" : sym;
                double[] a = agg.get(key);
                if (a == null) agg.put(key, new double[]{buy * qty, qty});
                else { a[0] += buy * qty; a[1] += qty; }
            }
            for (Map.Entry<String, double[]> e : agg.entrySet()) {
                boolean f = e.getKey().endsWith("|F");
                Row r = row(f ? e.getKey().substring(0, e.getKey().length() - 2) : e.getKey(), quotes);
                r.fno = f;
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
        v.setTextViewText(R.id.r_sym, symText(r.sym, r.fno));
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
