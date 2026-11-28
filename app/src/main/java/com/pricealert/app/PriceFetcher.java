package com.pricealert.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gets live prices. For each stock: NSE India first, then Google Finance,
 * then Yahoo Finance. All three also return the last price after market hours.
 */
public final class PriceFetcher {

    public static final class Quote {
        public String sym;
        public String name = "";
        public String src;
        public double price;
        public double chg;
        public double pchg;
        public boolean hasChange;
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String NSE = "https://www.nseindia.in";
    private static final CookieManager COOKIES = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static long cookieTime = 0;
    public static volatile String lastError = "";

    static {
        CookieHandler.setDefault(COOKIES);
    }

    private static final class HttpError extends IOException {
        final int code;
        HttpError(int code) { super("HTTP " + code); this.code = code; }
    }

    private PriceFetcher() { }

    private static String get(String url, String referer, boolean json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
        c.setRequestProperty("Accept", json ? "application/json, text/plain, */*"
                : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        if (referer != null) c.setRequestProperty("Referer", referer);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpError(code);
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

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static double num(String s) { return Double.parseDouble(s.replace(",", "")); }

    // ---------------- Indices ----------------

    /** Typed name -> {Google Finance code, Yahoo Finance code, display name}. */
    private static final Map<String, String[]> INDICES = new HashMap<>();
    static {
        String[] nifty = {"NIFTY_50:INDEXNSE", "^NSEI", "NIFTY 50"};
        INDICES.put("NIFTY", nifty);
        INDICES.put("NIFTY50", nifty);
        String[] bank = {"NIFTY_BANK:INDEXNSE", "^NSEBANK", "NIFTY BANK"};
        INDICES.put("BANKNIFTY", bank);
        INDICES.put("NIFTYBANK", bank);
        String[] fin = {"NIFTY_FIN_SERVICE:INDEXNSE", "NIFTY_FIN_SERVICE.NS", "NIFTY FINANCIAL SERVICES"};
        INDICES.put("FINNIFTY", fin);
        INDICES.put("NIFTYFIN", fin);
        INDICES.put("NIFTYIT", new String[]{"NIFTY_IT:INDEXNSE", "^CNXIT", "NIFTY IT"});
        INDICES.put("SENSEX", new String[]{"SENSEX:INDEXBOM", "^BSESN", "BSE SENSEX"});
        String[] mid100 = {null, "NIFTY_MIDCAP_100.NS", "NIFTY MIDCAP 100"};
        INDICES.put("MIDCAP100", mid100);
        INDICES.put("NIFTYMIDCAP100", mid100);
        String[] mid50 = {null, "^NSEMDCP50", "NIFTY MIDCAP 50"};
        INDICES.put("MIDCAP50", mid50);
        INDICES.put("NIFTYMIDCAP50", mid50);
    }

    public static boolean isIndex(String sym) { return INDICES.containsKey(sym); }

    // ---------------- NSE India ----------------

    /** Visit the home page once in a while so NSE's cookies are set. */
    private static synchronized void warmNse(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - cookieTime < 5 * 60 * 1000L) return;
        if (force) COOKIES.getCookieStore().removeAll();
        try { get(NSE + "/", null, false); } catch (IOException ignored) { }
        cookieTime = System.currentTimeMillis();
    }

    private static Quote nse(String sym) throws Exception {
        warmNse(false);
        String url = NSE + "/api/NextApi/apiClient/GetQuoteApi?functionName=getSymbolData&marketType=N&series=EQ&symbol=" + enc(sym);
        String ref = NSE + "/get-quote/equity/" + enc(sym);
        String body;
        try {
            body = get(url, ref, true);
        } catch (HttpError e) {
            if (e.code == 404) return null;          // unknown symbol
            if (e.code != 401 && e.code != 403) throw e;
            warmNse(true);
            body = get(url, ref, true);
        }
        JSONArray arr = new JSONObject(body).optJSONArray("equityResponse");
        JSONObject r = arr == null ? null : arr.optJSONObject(0);
        if (r == null) return null;
        JSONObject meta = r.optJSONObject("metaData");
        JSONObject trade = r.optJSONObject("tradeInfo");
        JSONObject book = r.optJSONObject("orderBook");
        double p = Double.NaN;
        if (book != null) p = book.optDouble("lastPrice", Double.NaN);
        if ((Double.isNaN(p) || p <= 0) && trade != null) p = trade.optDouble("lastPrice", Double.NaN);
        if ((Double.isNaN(p) || p <= 0) && meta != null) p = meta.optDouble("closePrice", Double.NaN);
        if (Double.isNaN(p) || p <= 0) return null;
        Quote q = new Quote();
        q.sym = sym;
        q.price = p;
        q.src = "NSE";
        if (meta != null) {
            q.name = meta.optString("companyName", "");
            double prev = meta.optDouble("previousClose", 0);
            if (prev > 0) {
                q.chg = p - prev;
                q.pchg = q.chg / prev * 100.0;
                q.hasChange = true;
            }
        }
        return q;
    }

    // ---------------- Google Finance ----------------

    private static final Pattern G_SPAN = Pattern.compile("jsname=\"Pdsbrc\"[^>]*><span>[^0-9<]*([0-9][0-9,]*(?:\\.[0-9]+)?)<");
    private static final Pattern G_PREV = Pattern.compile("Prev(?:ious|\\.) close.{0,400}?[^0-9]([0-9][0-9,]*\\.[0-9]+)", Pattern.DOTALL);
    private static final Pattern G_OLD = Pattern.compile("data-last-price=\"([0-9.]+)\"");

    /** code is like "SBIN:NSE" for a stock or "NIFTY_50:INDEXNSE" for an index. */
    private static Quote google(String code, String sym) throws Exception {
        String[] parts = code.split(":");
        String html = get("https://www.google.com/finance/quote/" + enc(parts[0]) + ":" + parts[1] + "?hl=en", null, false);
        Quote q = new Quote();
        q.sym = sym;
        q.src = "Google Finance";
        // Page data block: ["SBIN","NSE"],"State Bank of India",0,"INR",[price,change,changePct,...]
        // (for an index the currency is null)
        Pattern data = Pattern.compile("\\[\"" + Pattern.quote(parts[0]) + "\",\"" + Pattern.quote(parts[1])
                + "\"\\],\"([^\"]*)\",\\d+,(?:\"[A-Z]{3}\"|null),\\[([0-9.]+),(-?[0-9.Ee-]+),(-?[0-9.Ee-]+)");
        Matcher m = data.matcher(html);
        if (m.find()) {
            q.name = m.group(1);
            q.price = Double.parseDouble(m.group(2));
            q.chg = Double.parseDouble(m.group(3));
            q.pchg = Double.parseDouble(m.group(4));
            q.hasChange = true;
        } else {
            Matcher s = G_SPAN.matcher(html);
            Matcher o = G_OLD.matcher(html);
            if (s.find()) q.price = num(s.group(1));
            else if (o.find()) q.price = num(o.group(1));
            else return null;
            Matcher pm = G_PREV.matcher(html);
            if (pm.find()) {
                double prev = num(pm.group(1));
                if (prev > 0) {
                    q.chg = q.price - prev;
                    q.pchg = q.chg / prev * 100.0;
                    q.hasChange = true;
                }
            }
        }
        return q.price > 0 ? q : null;
    }

    // ---------------- Yahoo Finance ----------------

    /** code is like "SBIN.NS" for a stock or "^NSEI" for an index. */
    private static Quote yahoo(String code, String sym) throws Exception {
        String body;
        try {
            body = get("https://query1.finance.yahoo.com/v8/finance/chart/" + enc(code) + "?range=1d&interval=1d", null, true);
        } catch (HttpError e) {
            if (e.code == 404) return null;
            throw e;
        }
        JSONObject chart = new JSONObject(body).optJSONObject("chart");
        JSONArray res = chart == null ? null : chart.optJSONArray("result");
        JSONObject meta = res == null || res.optJSONObject(0) == null ? null : res.optJSONObject(0).optJSONObject("meta");
        if (meta == null) return null;
        double p = meta.optDouble("regularMarketPrice", Double.NaN);
        if (Double.isNaN(p) || p <= 0) return null;
        Quote q = new Quote();
        q.sym = sym;
        q.price = p;
        q.src = "Yahoo Finance";
        q.name = meta.optString("longName", meta.optString("shortName", ""));
        double prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", 0));
        if (prev > 0) {
            q.chg = p - prev;
            q.pchg = q.chg / prev * 100.0;
            q.hasChange = true;
        }
        return q;
    }

    /** Candles for the chart screen (Yahoo Finance). range: 1d, 5d, 1mo, 6mo, 1y, 5y. */
    public static JSONObject candles(String sym, String range) throws Exception {
        String s = sym == null ? "" : sym.trim().toUpperCase(java.util.Locale.ROOT);
        String[] idx = INDICES.get(s);
        String code = idx != null && idx[1] != null ? idx[1] : s + ".NS";
        String interval;
        switch (range) {
            case "1d": interval = "5m"; break;
            case "5d": interval = "15m"; break;
            case "1mo": case "6mo": case "1y": interval = "1d"; break;
            case "5y": interval = "1mo"; break;
            default: range = "1d"; interval = "5m";
        }
        String body = get("https://query1.finance.yahoo.com/v8/finance/chart/" + enc(code)
                + "?range=" + range + "&interval=" + interval + "&includePrePost=false", null, true);
        JSONObject chart = new JSONObject(body).optJSONObject("chart");
        JSONArray res = chart == null ? null : chart.optJSONArray("result");
        JSONObject r0 = res == null ? null : res.optJSONObject(0);
        if (r0 == null) throw new Exception("No chart data for " + s);
        JSONObject meta = r0.optJSONObject("meta");
        JSONArray ts = r0.optJSONArray("timestamp");
        JSONObject ind = r0.optJSONObject("indicators");
        JSONArray qa = ind == null ? null : ind.optJSONArray("quote");
        JSONObject q = qa == null ? null : qa.optJSONObject(0);
        if (ts == null || q == null) throw new Exception("No chart data for " + s);
        JSONArray o = q.optJSONArray("open"), h = q.optJSONArray("high"), l = q.optJSONArray("low"),
                c = q.optJSONArray("close"), v = q.optJSONArray("volume");
        JSONArray T = new JSONArray(), O = new JSONArray(), Hh = new JSONArray(), L = new JSONArray(), C = new JSONArray(), V = new JSONArray();
        for (int i = 0; i < ts.length(); i++) {
            double oo = o == null ? Double.NaN : o.optDouble(i), hh = h == null ? Double.NaN : h.optDouble(i),
                    ll = l == null ? Double.NaN : l.optDouble(i), cc = c == null ? Double.NaN : c.optDouble(i);
            if (Double.isNaN(oo) || Double.isNaN(hh) || Double.isNaN(ll) || Double.isNaN(cc)) continue;   // empty candle
            T.put(ts.optLong(i));
            O.put(oo); Hh.put(hh); L.put(ll); C.put(cc);
            V.put(v == null ? 0 : v.optLong(i, 0));
        }
        if (T.length() == 0) throw new Exception("No trades in this period yet");
        JSONObject out = new JSONObject();
        out.put("t", T); out.put("o", O); out.put("h", Hh); out.put("l", L); out.put("c", C); out.put("v", V);
        if (meta != null) {
            out.put("price", meta.optDouble("regularMarketPrice", 0));
            out.put("prev", meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", 0)));
            out.put("name", meta.optString("longName", meta.optString("shortName", "")));
        }
        return out;
    }

    /** Fetch every symbol it can; symbols it can't price are left out of the map. */
    /** Fetches several stocks at once so a full list finishes within a 15-second check. */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "price-fetch");
                t.setDaemon(true);
                return t;
            });

    private static Quote one(String s, List<String> errors) {
        Quote q = null;
        String[] idx = INDICES.get(s);
        if (idx == null) {
            try { q = nse(s); } catch (Exception e) { errors.add("NSE " + s + ": " + e.getMessage()); }
        }
        String gCode = idx == null ? s + ":NSE" : idx[0];
        if (q == null && gCode != null) {
            try { q = google(gCode, s); } catch (Exception e) { errors.add("Google " + s + ": " + e.getMessage()); }
        }
        if (q == null) {
            try { q = yahoo(idx == null ? s + ".NS" : idx[1], s); } catch (Exception e) { errors.add("Yahoo " + s + ": " + e.getMessage()); }
        }
        if (q != null && idx != null) q.name = idx[2];
        if (q == null) errors.add(s + ": symbol not found");
        return q;
    }

    public static Map<String, Quote> fetchAll(Collection<String> symbols) {
        Set<String> want = new LinkedHashSet<>(symbols);
        Map<String, Quote> out = new HashMap<>();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        if (want.isEmpty()) { lastError = ""; return out; }
        warmNse(false);
        Map<String, java.util.concurrent.Future<Quote>> jobs = new HashMap<>();
        for (String s : want) jobs.put(s, POOL.submit(() -> one(s, errors)));
        for (Map.Entry<String, java.util.concurrent.Future<Quote>> e : jobs.entrySet()) {
            try {
                Quote q = e.getValue().get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (q != null) out.put(e.getKey(), q);
            } catch (Exception ex) {
                errors.add(e.getKey() + ": timed out");
            }
        }
        synchronized (errors) { lastError = errors.isEmpty() ? "" : errors.get(0); }
        return out;
    }
}
