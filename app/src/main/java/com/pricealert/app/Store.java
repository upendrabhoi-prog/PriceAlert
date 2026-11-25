package com.pricealert.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** All app data, kept as JSON strings in SharedPreferences. */
public final class Store {
    public static final int SLOTS = 50;
    private static final int HISTORY_MAX = 300;
    private static final String[] DEFAULT_SYMBOLS = {
            "RELIANCE", "TCS", "INFY", "HDFCBANK", "ICICIBANK",
            "SBIN", "ITC", "LT", "TATAMOTORS", "BHARTIARTL"
    };

    private static Store instance;
    private final SharedPreferences sp;

    public static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    private Store(Context c) {
        sp = c.getSharedPreferences("pricealert", Context.MODE_PRIVATE);
        if (!sp.contains("slots")) {
            JSONArray a = new JSONArray();
            for (int i = 0; i < SLOTS; i++) {
                a.put(slot(i < DEFAULT_SYMBOLS.length ? DEFAULT_SYMBOLS[i] : "", 0));
            }
            sp.edit().putString("slots", a.toString()).apply();
        }
    }

    private static JSONObject slot(String sym, double alert) {
        JSONObject o = new JSONObject();
        try {
            o.put("sym", sym);
            o.put("alert", alert);
            o.put("setAt", alert > 0 ? System.currentTimeMillis() : 0);
        } catch (JSONException ignored) { }
        return o;
    }

    // ---------- generic helpers ----------
    private JSONObject obj(String key) {
        try { return new JSONObject(sp.getString(key, "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    private JSONArray arr(String key) {
        try { return new JSONArray(sp.getString(key, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    private void put(String key, Object json) {
        sp.edit().putString(key, json.toString()).apply();
    }

    // ---------- slots ----------
    public synchronized JSONArray slots() {
        JSONArray a = arr("slots");
        while (a.length() < SLOTS) a.put(slot("", 0));
        return a;
    }

    public synchronized void setSlot(int i, String sym, double alert) {
        if (i < 0 || i >= SLOTS) return;
        JSONArray a = slots();
        try { a.put(i, slot(sym, alert)); } catch (JSONException ignored) { }
        put("slots", a);
    }

    // ---------- settings ----------
    public synchronized JSONObject settings() {
        JSONObject s = obj("settings");
        try {
            if (!s.has("repeatMin")) s.put("repeatMin", 5);
            if (!s.has("pollMin")) s.put("pollMin", 1);
            if (!s.has("marketOnly")) s.put("marketOnly", true);
            if (!s.has("sound")) s.put("sound", true);
            if (!s.has("vibrate")) s.put("vibrate", true);
            if (!s.has("nearWarn")) s.put("nearWarn", true);
            if (!s.has("nearPct")) s.put("nearPct", 2);
            if (!s.has("monitoring")) s.put("monitoring", false);
        } catch (JSONException ignored) { }
        return s;
    }

    public synchronized void mergeSettings(JSONObject changes) {
        JSONObject s = settings();
        JSONArray names = changes.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String k = names.optString(i);
                try { s.put(k, changes.get(k)); } catch (JSONException ignored) { }
            }
        }
        put("settings", s);
    }

    public synchronized boolean monitoring() { return settings().optBoolean("monitoring", false); }

    public synchronized void setMonitoring(boolean on) {
        JSONObject o = new JSONObject();
        try { o.put("monitoring", on); } catch (JSONException ignored) { }
        mergeSettings(o);
    }

    // ---------- quotes ----------
    public synchronized JSONObject quotes() { return obj("quotes"); }

    public synchronized void saveQuotes(JSONObject q) { put("quotes", q); }

    // ---------- per-symbol alert state ----------
    public synchronized JSONObject alertState() { return obj("alertState"); }

    public synchronized void saveAlertState(JSONObject o) { put("alertState", o); }

    public synchronized void resetAlertState(String sym) {
        JSONObject o = alertState();
        o.remove(sym);
        put("alertState", o);
    }

    public synchronized void pauseToday(String sym, long until) {
        JSONObject o = alertState();
        JSONObject st = o.optJSONObject(sym);
        if (st == null) st = new JSONObject();
        try {
            st.put("pausedUntil", until);
            o.put(sym, st);
        } catch (JSONException ignored) { }
        put("alertState", o);
    }

    // ---------- history ----------
    public synchronized JSONArray history() { return arr("history"); }

    public synchronized void addHistory(JSONObject h) {
        JSONArray old = history();
        JSONArray a = new JSONArray();
        a.put(h);
        for (int i = 0; i < old.length() && a.length() < HISTORY_MAX; i++) a.put(old.opt(i));
        put("history", a);
    }

    public synchronized void clearHistory() { put("history", new JSONArray()); }

    // ---------- last fetch ----------
    public synchronized JSONObject lastFetch() { return obj("lastFetch"); }

    public synchronized void saveLastFetch(JSONObject o) { put("lastFetch", o); }
}
