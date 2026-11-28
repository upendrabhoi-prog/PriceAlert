package com.pricealert.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** All app data, kept as JSON strings in SharedPreferences. */
public final class Store {
    public static final int SLOTS = 50;
    public static final int HSLOTS = 50;
    public static final int MAX_SLOTS = 500;
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
        if (i < 0 || i >= MAX_SLOTS) return;
        JSONArray a = slots();
        while (a.length() <= i) a.put(slot("", 0));
        try { a.put(i, slot(sym, alert)); } catch (JSONException ignored) { }
        put("slots", a);
    }

    /** Sector id of an alert slot ("" = no sector). Kept separately so a price change doesn't lose it. */
    public synchronized void setSlotSector(int i, String sector) {
        if (i < 0 || i >= MAX_SLOTS) return;
        JSONArray a = slots();
        JSONObject o = a.optJSONObject(i);
        if (o == null) return;
        try { o.put("sector", sector == null ? "" : sector); } catch (JSONException ignored) { }
        put("slots", a);
    }

    /** Marks a My Stocks entry as an F&O position (true) or a normal stock (false). */
    public synchronized void setHoldingFno(int i, boolean fno) {
        JSONArray a = holdings();
        JSONObject o = a.optJSONObject(i);
        if (o == null) return;
        try { o.put("fno", fno); } catch (JSONException ignored) { }
        put("holdings", a);
    }

    /** Replaces all alerts and My Stocks entries (used by restore). */
    public synchronized void replaceAll(JSONArray newSlots, JSONArray newHoldings) {
        put("slots", newSlots);
        put("holdings", newHoldings);
    }

    // ---------- backup info ----------
    public synchronized JSONObject backupInfo() { return obj("backupInfo"); }

    public synchronized void saveBackupInfo(JSONObject o) { put("backupInfo", o); }

    // ---------- my stocks (holdings) ----------
    private static JSONObject holding(String sym, double buy, double qty, double target, String acct) {
        JSONObject o = new JSONObject();
        try {
            o.put("sym", sym);
            o.put("buy", buy);
            o.put("qty", qty);
            o.put("target", target);
            o.put("acct", acct == null ? "" : acct);
        } catch (JSONException ignored) { }
        return o;
    }

    public synchronized JSONArray holdings() {
        JSONArray a = arr("holdings");
        while (a.length() < HSLOTS) a.put(holding("", 0, 0, 0, ""));
        return a;
    }

    public synchronized void setHolding(int i, String sym, double buy, double qty, double target, String acct) {
        if (i < 0 || i >= MAX_SLOTS) return;
        JSONArray a = holdings();
        while (a.length() <= i) a.put(holding("", 0, 0, 0, ""));
        try { a.put(i, holding(sym, buy, qty, target, acct)); } catch (JSONException ignored) { }
        put("holdings", a);
    }

    public synchronized JSONObject targetState() { return obj("targetState"); }

    public synchronized void saveTargetState(JSONObject o) { put("targetState", o); }

    public synchronized void resetTargetState(int i) {
        JSONObject o = targetState();
        o.remove(String.valueOf(i));
        put("targetState", o);
    }

    // ---------- settings ----------
    public synchronized JSONObject settings() {
        JSONObject s = obj("settings");
        try {
            if (!s.has("repeatMin")) s.put("repeatMin", 5);
            if (!s.has("pollMin")) s.put("pollMin", 1);
            if (!s.has("pollSec")) s.put("pollSec", Math.max(15, s.optInt("pollMin", 1) * 60));
            s.put("marketOnly", true);   // prices are only checked automatically during market hours
            if (!s.has("sound")) s.put("sound", true);
            if (!s.has("vibrate")) s.put("vibrate", true);
            if (!s.has("nearWarn")) s.put("nearWarn", true);
            if (!s.has("nearPct")) s.put("nearPct", 2);
            if (!s.has("monitoring")) s.put("monitoring", true);
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

    /** How often prices are checked, in milliseconds (15 seconds or more). */
    public synchronized long pollMs() { return Math.max(15, settings().optInt("pollSec", 60)) * 1000L; }

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

    // ---------- realised trades from Google Sheet ----------
    public synchronized JSONObject realised() { return obj("realised"); }

    public synchronized void saveRealised(JSONObject o) { put("realised", o); }

    // ---------- last fetch ----------
    public synchronized JSONObject lastFetch() { return obj("lastFetch"); }

    public synchronized void saveLastFetch(JSONObject o) { put("lastFetch", o); }
}
