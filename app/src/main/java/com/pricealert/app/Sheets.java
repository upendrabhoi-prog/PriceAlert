package com.pricealert.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the "Realised trades" tab of the user's Google Sheet (shared as "anyone with the
 * link can view") as CSV, and keeps a parsed copy for the Realised tab.
 */
public final class Sheets {
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    private static final Pattern ID = Pattern.compile("/spreadsheets/d/([a-zA-Z0-9_-]{20,})");
    private static final Pattern GID = Pattern.compile("[#&?]gid=(\\d+)");
    private static final String[] MON = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    private Sheets() { }

    // ---------------- settings & state ----------------
    static String url(Store s) { return s.settings().optString("sheetUrl", "").trim(); }
    static String tab(Store s) {
        String t = s.settings().optString("sheetTab", "").trim();
        return t.isEmpty() ? "Realised Trades" : t;
    }
    /** The Apps Script web-app link that lets the app add rows ("" = not set up). */
    static String writeUrl(Store s) { return s.settings().optString("sheetWrite", "").trim(); }

    private static JSONObject script(String base, String query) throws Exception {
        if (!base.startsWith("https://script.google.com/")) throw new Exception("Paste the Web app URL (https://script.google.com/macros/s/…/exec)");
        String body;
        try { body = get(base + (base.contains("?") ? "&" : "?") + query); }
        catch (HttpErr e) {
            if (e.code == 403 || e.code == 401) throw new Exception("Google refused access. In Apps Script deploy again with \"Who has access: Anyone\"");
            if (e.code == 404) throw new Exception("That Web app link wasn't found. Copy the URL from Deploy → Manage deployments");
            throw new Exception("The helper didn't answer (" + e.getMessage() + ")");
        } catch (IOException e) { throw new Exception("No internet connection"); }
        String t = body.trim();
        if (!t.startsWith("{")) throw new Exception(t.startsWith("<") ? "The helper isn't set up right. Paste the helper again, Save, and deploy as a Web app" : "Unexpected answer from the helper");
        JSONObject o = new JSONObject(t);
        if (!o.optBoolean("ok")) throw new Exception(o.optString("err", "The sheet refused the change"));
        return o;
    }

    /** Checks the helper link; returns {ok, last, rows} or {ok:false, err}. */
    public static JSONObject testWrite(Context c, String url) {
        JSONObject r = new JSONObject();
        try {
            JSONObject o = script(url.trim(), "action=test&tab=" + URLEncoder.encode(tab(Store.get(c)), "UTF-8"));
            r.put("ok", true);
            r.put("last", o.optInt("last"));
            r.put("rows", o.optInt("rows"));
        } catch (Exception e) {
            try { r.put("ok", false); r.put("err", String.valueOf(e.getMessage())); } catch (JSONException ignored) { }
        }
        return r;
    }

    /** Adds one sold trade below the last entry of the sheet, then re-reads the sheet. */
    public static JSONObject append(Context c, JSONObject t) {
        Store s = Store.get(c);
        JSONObject r = new JSONObject();
        try {
            String w = writeUrl(s);
            if (w.isEmpty()) throw new Exception("Finish the one-time sheet setup in Settings first");
            String q = "action=add&tab=" + enc(tab(s)) + "&stock=" + enc(t.optString("stock")) + "&acct=" + enc(t.optString("acct"))
                    + "&date=" + enc(t.optString("date")) + "&buy=" + enc(t.optString("buy")) + "&sell=" + enc(t.optString("sell"))
                    + "&qty=" + enc(t.optString("qty")) + "&fno=" + enc(t.optString("fno", "No"));
            JSONObject o = script(w, q);
            r.put("ok", true);
            r.put("sr", o.optInt("sr"));
            r.put("row", o.optInt("row"));
        } catch (Exception e) {
            try { r.put("ok", false); r.put("err", String.valueOf(e.getMessage())); } catch (JSONException ignored) { }
        }
        if (r.optBoolean("ok")) {
            try { s.saveRealised(s.realised().put("tried", 0)); } catch (JSONException ignored) { }
        }
        return r;
    }

    private static String enc(String v) throws java.io.UnsupportedEncodingException { return URLEncoder.encode(v == null ? "" : v, "UTF-8"); }

    /** Minutes between sheet checks: 30 min, 1 hour (default), 6 hours or 10 hours. */
    static int every(Store s) {
        int m = s.settings().optInt("sheetEvery", 60);
        return m < 30 ? 60 : m;
    }

    /** What the Realised tab needs: settings plus the last parsed rows. */
    public static JSONObject state(Context c) {
        Store s = Store.get(c);
        JSONObject r = s.realised();
        try {
            r.put("url", url(s));
            r.put("tab", tab(s));
            r.put("every", every(s));
            r.put("writeUrl", writeUrl(s));
        } catch (JSONException ignored) { }
        return r;
    }

    /** Called from the background loop: syncs when the chosen interval has passed. */
    public static void maybeSync(Context c) {
        Store s = Store.get(c);
        if (url(s).isEmpty()) return;
        long last = s.realised().optLong("tried", 0);
        if (System.currentTimeMillis() - last >= every(s) * 60_000L - 5_000L) sync(c);
    }

    /** Downloads and parses the sheet now. Returns a short result for the screen. */
    public static synchronized JSONObject sync(Context c) {
        Store s = Store.get(c);
        JSONObject res = new JSONObject();
        String link = url(s);
        JSONObject old = s.realised();
        long now = System.currentTimeMillis();
        try {
            if (link.isEmpty()) throw new Exception("Paste your Google Sheet link in Settings");
            Matcher m = ID.matcher(link);
            if (!m.find()) throw new Exception("That doesn't look like a Google Sheets link");
            String id = m.group(1);
            Matcher g = GID.matcher(link);
            String gid = g.find() ? g.group(1) : null;
            String base = "https://docs.google.com/spreadsheets/d/" + id;

            List<String> tries = new ArrayList<>();
            tries.add(base + "/gviz/tq?tqx=out:csv&sheet=" + URLEncoder.encode(tab(s), "UTF-8"));
            if (gid != null) tries.add(0, base + "/export?format=csv&gid=" + gid);
            tries.add(base + "/export?format=csv");

            JSONArray rows = null;
            String why = "";
            boolean denied = false;
            for (String u : tries) {
                try {
                    String body = get(u);
                    String head = body.trim();
                    if (head.startsWith("<")) { denied = true; continue; }
                    JSONArray parsed = parse(body);
                    if (parsed != null) { rows = parsed; break; }
                    why = "headings";
                } catch (HttpErr e) {
                    if (e.code == 401 || e.code == 403 || e.code == 404) denied = true;
                    else why = "HTTP " + e.code;
                } catch (IOException e) {
                    why = "network";
                }
            }
            if (rows == null) {
                if ("network".equals(why)) throw new Exception("No internet connection, will try again");
                if ("headings".equals(why)) throw new Exception("Couldn't find the headings (Stock Name, Date of Selling, Profit/Loss) in tab \"" + tab(s) + "\"");
                if (denied) throw new Exception("Can't open the sheet. In Google Sheets tap Share → Anyone with the link → Viewer");
                throw new Exception("Couldn't read the sheet" + (why.isEmpty() ? "" : " (" + why + ")"));
            }
            boolean changed = !rows.toString().equals(String.valueOf(old.optJSONArray("rows")));
            JSONObject r = new JSONObject();
            r.put("rows", rows);
            r.put("t", now);
            r.put("tried", now);
            r.put("err", "");
            s.saveRealised(r);
            res.put("ok", true);
            res.put("n", rows.length());
            res.put("changed", changed);
        } catch (Exception e) {
            try {
                old.put("tried", now);
                old.put("err", String.valueOf(e.getMessage()));
                s.saveRealised(old);
                res.put("ok", false);
                res.put("err", String.valueOf(e.getMessage()));
            } catch (JSONException ignored) { }
        }
        Bridge b = Bridge.current;
        if (b != null) b.emit("sheet", res.toString());
        return res;
    }

    // ---------------- parsing ----------------
    /** Returns the trades, or null when no heading row with the needed columns was found. */
    static JSONArray parse(String csv) throws JSONException {
        List<List<String>> t = csv(csv);
        int hr = -1, cStock = -1, cAcct = -1, cDate = -1, cBuy = -1, cSell = -1, cQty = -1, cPl = -1, cSr = -1, cFno = -1;
        for (int i = 0; i < Math.min(t.size(), 15) && hr < 0; i++) {
            List<String> row = t.get(i);
            int st = -1, ac = -1, dt = -1, by = -1, sl = -1, qt = -1, pl = -1, sr = -1, fo = -1;
            for (int k = 0; k < row.size(); k++) {
                String h = row.get(k).toLowerCase(Locale.ROOT).replace('\n', ' ').trim();
                if (h.isEmpty()) continue;
                if (h.contains("f&o") || h.contains("f & o") || h.equals("fno") || h.contains("f&amp;o") || h.contains("futures")) { if (fo < 0) fo = k; }
                else if (h.contains("date")) { if (dt < 0) dt = k; }
                else if (h.contains("profit") || h.contains("p&l") || h.contains("p/l") || h.contains("loss")) { if (pl < 0) pl = k; }
                else if (h.contains("stock") || h.contains("symbol") || h.contains("scrip") || h.contains("company")) { if (st < 0) st = k; }
                else if (h.contains("account") || h.contains("broker") || h.contains("demat")) { if (ac < 0) ac = k; }
                else if (h.contains("buy")) { if (by < 0) by = k; }
                else if (h.contains("sell") || h.contains("sale")) { if (sl < 0) sl = k; }
                else if (h.contains("qty") || h.contains("quantity") || h.contains("shares")) { if (qt < 0) qt = k; }
                else if (h.matches(".*\\bsr\\b.*") || h.contains("serial") || h.matches(".*\\bs\\.? ?no\\b.*")) { if (sr < 0) sr = k; }
            }
            if (st >= 0 && dt >= 0 && (pl >= 0 || (by >= 0 && sl >= 0 && qt >= 0))) {
                hr = i; cStock = st; cAcct = ac; cDate = dt; cBuy = by; cSell = sl; cQty = qt; cPl = pl; cSr = sr; cFno = fo;
            }
        }
        if (hr < 0) return null;
        JSONArray out = new JSONArray();
        for (int i = hr + 1; i < t.size(); i++) {
            List<String> r = t.get(i);
            String stock = cell(r, cStock).toUpperCase(Locale.ROOT);
            if (stock.isEmpty()) continue;
            String date = date(cell(r, cDate));
            if (date == null) continue;
            double buy = num(cell(r, cBuy)), sell = num(cell(r, cSell)), qty = num(cell(r, cQty));
            double pl = num(cell(r, cPl));
            if (Double.isNaN(pl)) {
                if (Double.isNaN(buy) || Double.isNaN(sell) || Double.isNaN(qty)) continue;
                pl = (sell - buy) * qty;
            }
            String acct = cell(r, cAcct);
            JSONObject o = new JSONObject();
            o.put("stock", stock);
            o.put("acct", acct.isEmpty() ? "Main" : acct);
            o.put("fno", cell(r, cFno).trim().toLowerCase(Locale.ROOT).startsWith("y"));
            o.put("date", date);
            o.put("buy", Double.isNaN(buy) ? 0 : buy);
            o.put("sell", Double.isNaN(sell) ? 0 : sell);
            o.put("qty", Double.isNaN(qty) ? 0 : qty);
            o.put("pl", Math.round(pl * 100) / 100.0);
            double srn = num(cell(r, cSr));
            if (!Double.isNaN(srn)) o.put("sr", (int) srn);
            out.put(o);
        }
        return out;
    }

    private static String cell(List<String> r, int k) {
        return k >= 0 && k < r.size() ? r.get(k).trim() : "";
    }

    static double num(String s) {
        if (s == null) return Double.NaN;
        String v = s.replace("₹", "").replace("Rs.", "").replace("Rs", "").replace(",", "")
                .replace("−", "-").replace(" ", "").trim();
        boolean neg = v.startsWith("(") && v.endsWith(")");
        if (neg) v = v.substring(1, v.length() - 1);
        if (v.isEmpty() || v.equals("-")) return Double.NaN;
        try { double d = Double.parseDouble(v); return neg ? -d : d; } catch (Exception e) { return Double.NaN; }
    }

    /** Accepts DD-MM-YYYY (also / or .), YYYY-MM-DD, 18-Nov-2025, and M/D/YYYY when the day is over 12. */
    static String date(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        String[] p = v.split("[-/. ]+");
        if (p.length < 3) return null;
        try {
            int a, b, y;
            if (p[0].length() == 4) { y = Integer.parseInt(p[0]); b = month(p[1]); a = Integer.parseInt(p[2].replaceAll("\\D.*", "")); }
            else {
                y = Integer.parseInt(p[2].replaceAll("\\D.*", ""));
                int m2 = month(p[1]);
                if (m2 < 0) return null;
                int d1 = month(p[0]);
                if (!p[0].matches("\\d+") && d1 > 0) { a = Integer.parseInt(p[1]); b = d1; }   // Nov 18 2025
                else {
                    a = Integer.parseInt(p[0]); b = m2;
                    if (b > 12 && a <= 12) { int x = a; a = b; b = x; }                       // 11/18/2025
                }
            }
            if (y < 100) y += 2000;
            if (b < 1 || b > 12 || a < 1 || a > 31 || y < 1990 || y > 2100) return null;
            return String.format(Locale.US, "%04d-%02d-%02d", y, b, a);
        } catch (Exception e) {
            return null;
        }
    }

    private static int month(String s) {
        if (s.matches("\\d+")) return Integer.parseInt(s);
        for (int i = 0; i < 12; i++) if (s.startsWith(MON[i])) return i + 1;
        return -1;
    }

    /** Minimal CSV reader (quotes, doubled quotes, newlines inside quotes). */
    static List<List<String>> csv(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { f.append('"'); i++; }
                    else q = false;
                } else f.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { row.add(f.toString()); f.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(f.toString()); f.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else f.append(ch);
        }
        if (f.length() > 0 || !row.isEmpty()) { row.add(f.toString()); rows.add(row); }
        return rows;
    }

    // ---------------- network ----------------
    static final class HttpErr extends IOException {
        final int code;
        HttpErr(int code) { super("HTTP " + code); this.code = code; }
    }

    private static String get(String url) throws IOException {
        String u = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/csv,text/plain,*/*");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new HttpErr(code);
                    if (loc.contains("accounts.google.com") || loc.contains("ServiceLogin")) throw new HttpErr(403);
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) throw new HttpErr(code);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally {
                c.disconnect();
            }
        }
        throw new HttpErr(310);
    }
}
