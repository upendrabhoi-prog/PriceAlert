package com.pricealert.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fetches prices for every stock in the list and sends any alerts that are due. */
public final class Checker {

    private Checker() { }

    public static final class Result {
        public int asked;
        public int got;
        public String error = "";
    }

    public static synchronized Result run(Context ctx, boolean evaluate) {
        Store store = Store.get(ctx);
        JSONArray slots = store.slots();
        Set<String> syms = new LinkedHashSet<>();
        for (int i = 0; i < slots.length(); i++) {
            JSONObject s = slots.optJSONObject(i);
            if (s == null) continue;
            String sym = s.optString("sym", "").trim();
            if (!sym.isEmpty()) syms.add(sym);
        }
        JSONArray holds = store.holdings();
        for (int i = 0; i < holds.length(); i++) {
            JSONObject h = holds.optJSONObject(i);
            if (h == null) continue;
            String sym = h.optString("sym", "").trim();
            if (!sym.isEmpty()) syms.add(sym);
        }

        Result r = new Result();
        r.asked = syms.size();
        Map<String, PriceFetcher.Quote> got = PriceFetcher.fetchAll(syms);
        r.got = got.size();
        r.error = PriceFetcher.lastError;
        long now = System.currentTimeMillis();

        JSONObject quotes = store.quotes();
        List<String> sources = new ArrayList<>();
        for (PriceFetcher.Quote q : got.values()) {
            JSONObject o = new JSONObject();
            try {
                o.put("price", q.price);
                o.put("chg", q.chg);
                o.put("pchg", q.pchg);
                o.put("hasChg", q.hasChange);
                o.put("name", q.name);
                o.put("src", q.src);
                o.put("t", now);
                quotes.put(q.sym, o);
            } catch (JSONException ignored) { }
            if (!sources.contains(q.src)) sources.add(q.src);
        }
        store.saveQuotes(quotes);

        JSONObject lf = new JSONObject();
        try {
            lf.put("t", now);
            lf.put("asked", r.asked);
            lf.put("got", r.got);
            lf.put("src", sources.isEmpty() ? "—" : String.join(" + ", sources));
            lf.put("err", r.got < r.asked ? r.error : "");
        } catch (JSONException ignored) { }
        store.saveLastFetch(lf);

        if (evaluate) {
            evaluate(ctx, store, slots, got, now);
            evaluateTargets(ctx, store, holds, got, now);
        }
        return r;
    }

    private static void evaluate(Context ctx, Store store, JSONArray slots,
                                 Map<String, PriceFetcher.Quote> got, long now) {
        JSONObject settings = store.settings();
        long repeatMs = Math.max(1, settings.optInt("repeatMin", 5)) * 60_000L;
        boolean nearWarn = settings.optBoolean("nearWarn", true);
        double nearPct = settings.optDouble("nearPct", 2);
        JSONObject state = store.alertState();

        for (int i = 0; i < slots.length(); i++) {
            JSONObject s = slots.optJSONObject(i);
            if (s == null) continue;
            String sym = s.optString("sym", "").trim();
            double alert = s.optDouble("alert", 0);
            if (sym.isEmpty() || alert <= 0) continue;
            PriceFetcher.Quote q = got.get(sym);
            if (q == null) continue;

            JSONObject st = state.optJSONObject(sym);
            if (st == null) st = new JSONObject();
            try {
                if (st.optLong("pausedUntil", 0) > now) {
                    state.put(sym, st);
                    continue;
                }
                if (q.price <= alert) {
                    long last = st.optLong("lastHit", 0);
                    if (last == 0 || now - last >= repeatMs - 5_000L) {
                        int count = st.optInt("count", 0) + 1;
                        st.put("lastHit", now);
                        st.put("count", count);
                        Notifier.alertHit(ctx, i, q, alert, count);
                        store.addHistory(hist(sym, q, alert, now, "hit", count));
                    }
                    st.put("nearSent", true);
                } else {
                    st.put("lastHit", 0);
                    st.put("count", 0);
                    boolean near = q.price <= alert * (1 + nearPct / 100.0);
                    if (nearWarn && near && !st.optBoolean("nearSent", false)) {
                        st.put("nearSent", true);
                        Notifier.alertNear(ctx, i, q, alert);
                        store.addHistory(hist(sym, q, alert, now, "near", 0));
                    } else if (!near) {
                        st.put("nearSent", false);
                    }
                }
                state.put(sym, st);
            } catch (JSONException ignored) { }
        }
        store.saveAlertState(state);
    }

    /** My Stocks: notify when price is at or above the target, repeating at the user's interval. */
    private static void evaluateTargets(Context ctx, Store store, JSONArray holds,
                                        Map<String, PriceFetcher.Quote> got, long now) {
        long repeatMs = Math.max(1, store.settings().optInt("repeatMin", 5)) * 60_000L;
        JSONObject paused = store.alertState();
        JSONObject state = store.targetState();
        for (int i = 0; i < holds.length(); i++) {
            JSONObject h = holds.optJSONObject(i);
            if (h == null) continue;
            String sym = h.optString("sym", "").trim();
            double target = h.optDouble("target", 0);
            if (sym.isEmpty() || target <= 0) continue;
            PriceFetcher.Quote q = got.get(sym);
            if (q == null) continue;
            String key = String.valueOf(i);
            JSONObject st = state.optJSONObject(key);
            if (st == null) st = new JSONObject();
            try {
                JSONObject ps = paused.optJSONObject(sym);
                if (ps != null && ps.optLong("pausedUntil", 0) > now) { state.put(key, st); continue; }
                if (q.price >= target) {
                    long last = st.optLong("lastHit", 0);
                    if (last == 0 || now - last >= repeatMs - 5_000L) {
                        int count = st.optInt("count", 0) + 1;
                        st.put("lastHit", now);
                        st.put("count", count);
                        Notifier.targetHit(ctx, i, q, target, count);
                        store.addHistory(hist(sym, q, target, now, "target", count));
                    }
                } else {
                    st.put("lastHit", 0);
                    st.put("count", 0);
                }
                state.put(key, st);
            } catch (JSONException ignored) { }
        }
        store.saveTargetState(state);
    }

    private static JSONObject hist(String sym, PriceFetcher.Quote q, double alert, long t, String kind, int n) {
        JSONObject h = new JSONObject();
        try {
            h.put("sym", sym);
            h.put("price", q.price);
            h.put("alert", alert);
            h.put("t", t);
            h.put("kind", kind);
            h.put("n", n);
            h.put("src", q.src);
        } catch (JSONException ignored) { }
        return h;
    }
}
