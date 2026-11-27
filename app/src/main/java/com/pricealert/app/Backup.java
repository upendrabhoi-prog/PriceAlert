package com.pricealert.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Backup file of alerts, My Stocks, accounts, sectors and settings; and restoring it. */
public final class Backup {
    static final String[] KEEP = {"accounts", "sectors", "repeatMin", "pollSec", "pollMin", "nearPct", "nearWarn",
            "sound", "vibrate", "sheetUrl", "sheetTab", "sheetEvery", "backupEvery"};
    private static final long DAY = 86_400_000L;

    private Backup() { }

    static String fileName() {
        return "Wealthora-backup-" + Instant.now().atZone(Market.IST)
                .format(DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)) + ".json";
    }

    /** The whole backup as JSON text. */
    static String build(Context c) throws JSONException {
        Store s = Store.get(c);
        JSONObject o = new JSONObject();
        o.put("app", "wealthora");
        o.put("version", 1);
        o.put("made", System.currentTimeMillis());
        o.put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim());
        o.put("slots", s.slots());
        o.put("holdings", s.holdings());
        JSONObject all = s.settings(), keep = new JSONObject();
        for (String k : KEEP) if (all.has(k)) keep.put(k, all.get(k));
        o.put("settings", keep);
        return o.toString(1);
    }

    static void write(Context c, Uri uri) throws Exception {
        OutputStream o;
        try { o = c.getContentResolver().openOutputStream(uri, "wt"); }
        catch (Exception e) { o = c.getContentResolver().openOutputStream(uri, "w"); }   // Drive may not support "wt"
        try (OutputStream out = o) {
            if (out == null) throw new Exception("Couldn't open the file");
            out.write(build(c).getBytes(StandardCharsets.UTF_8));
        }
        saved(c, where(c, uri));
    }

    /** Writes the backup to the app's cache for sharing; returns the file. */
    static File cacheFile(Context c) throws Exception {
        File dir = new File(c.getCacheDir(), "share");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Couldn't prepare the file");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File f = new File(dir, fileName());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(build(c).getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    static void saved(Context c, String where) {
        Store s = Store.get(c);
        JSONObject b = s.backupInfo();
        try {
            b.put("last", System.currentTimeMillis());
            b.put("where", where);
        } catch (JSONException ignored) { }
        s.saveBackupInfo(b);
    }

    static String where(Context c, Uri uri) {
        String name = "";
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) { }
        String auth = uri.getAuthority() == null ? "" : uri.getAuthority();
        String place = auth.contains("google.android.apps.docs") ? "Google Drive"
                : auth.contains("downloads") || auth.contains("externalstorage") || auth.contains("media") ? "Phone" : "Saved file";
        return name == null || name.isEmpty() ? place : place + " · " + name;
    }

    /** Reads and checks a backup file. Returns its JSON, or throws with a message for the user. */
    static JSONObject read(Context c, Uri uri) throws Exception {
        byte[] data;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Couldn't open the file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 5_000_000) throw new Exception("That file is too big to be a Wealthora backup");
            }
            data = out.toByteArray();
        }
        JSONObject o;
        try { o = new JSONObject(new String(data, StandardCharsets.UTF_8)); }
        catch (JSONException e) { throw new Exception("That file is not a Wealthora backup"); }
        if (!"wealthora".equals(o.optString("app")) || o.optJSONArray("slots") == null)
            throw new Exception("That file is not a Wealthora backup");
        return o;
    }

    /** Short summary shown before restoring. */
    static JSONObject preview(JSONObject o, String name) throws JSONException {
        JSONArray sl = o.optJSONArray("slots"), hs = o.optJSONArray("holdings");
        JSONObject st = o.optJSONObject("settings");
        if (st == null) st = new JSONObject();
        int alerts = 0, stocks = 0, targets = 0;
        if (sl != null) for (int i = 0; i < sl.length(); i++) {
            JSONObject x = sl.optJSONObject(i);
            if (x != null && !x.optString("sym").isEmpty()) alerts++;
        }
        if (hs != null) for (int i = 0; i < hs.length(); i++) {
            JSONObject x = hs.optJSONObject(i);
            if (x != null && !x.optString("sym").isEmpty()) { stocks++; if (x.optDouble("target", 0) > 0) targets++; }
        }
        JSONArray ac = st.optJSONArray("accounts"), se = st.optJSONArray("sectors");
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("name", name);
        r.put("made", o.optLong("made", 0));
        r.put("device", o.optString("device", ""));
        r.put("alerts", alerts);
        r.put("stocks", stocks);
        r.put("targets", targets);
        r.put("accounts", ac == null || ac.length() == 0 ? 1 : ac.length());
        r.put("sectors", se == null || se.length() == 0 ? 4 : se.length());
        r.put("sheet", !st.optString("sheetUrl", "").isEmpty());
        return r;
    }

    /** Replaces alerts, My Stocks and the kept settings with the backup's. */
    static void apply(Context c, JSONObject o) throws JSONException {
        Store s = Store.get(c);
        JSONArray sl = o.optJSONArray("slots"), hs = o.optJSONArray("holdings");
        s.replaceAll(sl == null ? new JSONArray() : sl, hs == null ? new JSONArray() : hs);
        JSONObject st = o.optJSONObject("settings");
        if (st != null) {
            JSONObject keep = new JSONObject();
            for (String k : KEEP) if (st.has(k)) keep.put(k, st.get(k));
            s.mergeSettings(keep);
        }
        s.saveAlertState(new JSONObject());
        s.saveTargetState(new JSONObject());
        JSONObject r = s.realised();
        r.put("tried", 0);
        s.saveRealised(r);
    }

    // ---------------- reminder ----------------
    static int everyDays(Store s) {
        String e = s.settings().optString("backupEvery", "month");
        return "off".equals(e) ? 0 : "week".equals(e) ? 7 : "3m".equals(e) ? 91 : 30;
    }

    static boolean hasData(Store s) {
        JSONArray a = s.slots(), h = s.holdings();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null && !x.optString("sym").isEmpty()) return true; }
        for (int i = 0; i < h.length(); i++) { JSONObject x = h.optJSONObject(i); if (x != null && !x.optString("sym").isEmpty()) return true; }
        return false;
    }

    /** Shows the "Time to back up" notification when due, at most once a day, between 10 AM and 8 PM. */
    static void maybeRemind(Context c) {
        Store s = Store.get(c);
        int days = everyDays(s);
        if (days == 0 || !hasData(s)) return;
        long now = System.currentTimeMillis();
        int hour = Instant.ofEpochMilli(now).atZone(Market.IST).getHour();
        if (hour < 10 || hour >= 20) return;
        JSONObject b = s.backupInfo();
        long last = b.optLong("last", 0);
        if (last > 0 && now - last < days * DAY) return;
        if (now - b.optLong("reminded", 0) < DAY) return;
        if (last == 0) {                              // first reminder comes a few days after first use
            long first = b.optLong("firstSeen", 0);
            if (first == 0) { try { b.put("firstSeen", now); } catch (JSONException ignored) { } s.saveBackupInfo(b); return; }
            if (now - first < 3 * DAY) return;
        }
        try { b.put("reminded", now); } catch (JSONException ignored) { }
        s.saveBackupInfo(b);
        Notifier.backupReminder(c, last);
    }
}
