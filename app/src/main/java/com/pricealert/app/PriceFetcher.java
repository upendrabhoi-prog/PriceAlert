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

    private static Quote google(String sym) throws Exception {
        String html = get("https://www.google.com/finance/quote/" + enc(sym) + ":NSE?hl=en", null, false);
        Quote q = new Quote();
        q.sym = sym;
        q.src = "Google Finance";
        // Page data block: ["SBIN","NSE"],"State Bank of India",0,"INR",[price,change,changePct,...]
        Pattern data = Pattern.compile("\\[\"" + Pattern.quote(sym) + "\",\"NSE\"\\],\"([^\"]*)\",\\d+,\"[A-Z]{3}\",\\[([0-9.]+),(-?[0-9.Ee-]+),(-?[0-9.Ee-]+)");
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

    private static Quote yahoo(String sym) throws Exception {
        String body;
        try {
            body = get("https://query1.finance.yahoo.com/v8/finance/chart/" + enc(sym + ".NS") + "?range=1d&interval=1d", null, true);
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

    /** Fetch every symbol it can; symbols it can't price are left out of the map. */
    public static Map<String, Quote> fetchAll(Collection<String> symbols) {
        Set<String> want = new LinkedHashSet<>(symbols);
        Map<String, Quote> out = new HashMap<>();
        List<String> errors = new ArrayList<>();
        for (String s : want) {
            Quote q = null;
            try { q = nse(s); } catch (Exception e) { errors.add("NSE " + s + ": " + e.getMessage()); }
            if (q == null) {
                try { q = google(s); } catch (Exception e) { errors.add("Google " + s + ": " + e.getMessage()); }
            }
            if (q == null) {
                try { q = yahoo(s); } catch (Exception e) { errors.add("Yahoo " + s + ": " + e.getMessage()); }
            }
            if (q != null) out.put(s, q);
            else if (errors.isEmpty()) errors.add(s + ": symbol not found");
        }
        lastError = errors.isEmpty() ? "" : errors.get(0);
        return out;
    }
}
