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
 * Gets live prices. Order: NSE NIFTY 500 list (one request for many stocks),
 * then NSE single-stock quote, then Google Finance for anything still missing.
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
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final String NSE = "https://www.nseindia.com";
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
        c.setConnectTimeout(12000);
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

    /** NSE only answers its API after the home page has set its cookies. */
    private static synchronized void warmNse(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - cookieTime < 4 * 60 * 1000L) return;
        if (force) COOKIES.getCookieStore().removeAll();
        try {
            get(NSE + "/", null, false);
            get(NSE + "/market-data/live-equity-market", NSE + "/", false);
        } catch (IOException ignored) {
            // the API call will report the real problem
        }
        cookieTime = System.currentTimeMillis();
    }

    private static String nseApi(String path, String referer) throws IOException {
        warmNse(false);
        try {
            return get(NSE + path, referer, true);
        } catch (HttpError e) {
            if (e.code == 401 || e.code == 403) {
                warmNse(true);
                return get(NSE + path, referer, true);
            }
            throw e;
        }
    }

    /** One request returns about 500 large companies. */
    private static void nseBulk(Set<String> want, Map<String, Quote> out) throws Exception {
        String body = nseApi("/api/equity-stockIndices?index=" + enc("NIFTY 500"),
                NSE + "/market-data/live-equity-market");
        JSONArray data = new JSONObject(body).optJSONArray("data");
        if (data == null) return;
        for (int i = 0; i < data.length(); i++) {
            JSONObject d = data.optJSONObject(i);
            if (d == null) continue;
            String sym = d.optString("symbol");
            if (!want.contains(sym) || out.containsKey(sym)) continue;
            double p = d.optDouble("lastPrice", Double.NaN);
            if (Double.isNaN(p) || p <= 0) continue;
            Quote q = new Quote();
            q.sym = sym;
            q.price = p;
            q.chg = d.optDouble("change", 0);
            q.pchg = d.optDouble("pChange", 0);
            q.hasChange = true;
            JSONObject meta = d.optJSONObject("meta");
            if (meta != null) q.name = meta.optString("companyName", "");
            q.src = "NSE";
            out.put(sym, q);
        }
    }

    private static Quote nseSingle(String sym) throws Exception {
        String body = nseApi("/api/quote-equity?symbol=" + enc(sym),
                NSE + "/get-quotes/equity?symbol=" + enc(sym));
        JSONObject o = new JSONObject(body);
        JSONObject pi = o.optJSONObject("priceInfo");
        if (pi == null) return null;
        double p = pi.optDouble("lastPrice", Double.NaN);
        if (Double.isNaN(p) || p <= 0) return null;
        Quote q = new Quote();
        q.sym = sym;
        q.price = p;
        q.chg = pi.optDouble("change", 0);
        q.pchg = pi.optDouble("pChange", 0);
        q.hasChange = true;
        JSONObject info = o.optJSONObject("info");
        if (info != null) q.name = info.optString("companyName", "");
        q.src = "NSE";
        return q;
    }

    private static final Pattern G_PRICE = Pattern.compile("data-last-price=\"([0-9.]+)\"");
    private static final Pattern G_PRICE2 = Pattern.compile("class=\"YMlKec fxKbKc\">[^0-9]*([0-9,]+(?:\\.[0-9]+)?)<");
    private static final Pattern G_PREV = Pattern.compile("Previous close.{0,400}?class=\"P6K39c\">[^0-9]*([0-9,]+(?:\\.[0-9]+)?)<", Pattern.DOTALL);
    private static final Pattern G_NAME = Pattern.compile("class=\"zzDege\">([^<]+)<");

    private static double num(String s) { return Double.parseDouble(s.replace(",", "")); }

    private static Quote google(String sym) throws Exception {
        String html = get("https://www.google.com/finance/quote/" + enc(sym) + ":NSE?hl=en", null, false);
        Matcher m = G_PRICE.matcher(html);
        double p;
        if (m.find()) {
            p = num(m.group(1));
        } else {
            Matcher m2 = G_PRICE2.matcher(html);
            if (!m2.find()) return null;
            p = num(m2.group(1));
        }
        if (p <= 0) return null;
        Quote q = new Quote();
        q.sym = sym;
        q.price = p;
        q.src = "Google Finance";
        Matcher pm = G_PREV.matcher(html);
        if (pm.find()) {
            double prev = num(pm.group(1));
            if (prev > 0) {
                q.chg = p - prev;
                q.pchg = q.chg / prev * 100.0;
                q.hasChange = true;
            }
        }
        Matcher nm = G_NAME.matcher(html);
        if (nm.find()) q.name = nm.group(1).replace("&amp;", "&").replace("&#39;", "'").trim();
        return q;
    }

    /** Fetch every symbol it can; symbols it can't price are left out of the map. */
    public static Map<String, Quote> fetchAll(Collection<String> symbols) {
        Set<String> want = new LinkedHashSet<>(symbols);
        Map<String, Quote> out = new HashMap<>();
        List<String> errors = new ArrayList<>();
        if (want.isEmpty()) { lastError = ""; return out; }

        if (want.size() > 2) {
            try { nseBulk(want, out); } catch (Exception e) { errors.add("NSE list: " + e.getMessage()); }
        }
        for (String s : want) {
            if (out.containsKey(s)) continue;
            try {
                Quote q = nseSingle(s);
                if (q != null) out.put(s, q);
            } catch (Exception e) {
                errors.add("NSE " + s + ": " + e.getMessage());
            }
        }
        for (String s : want) {
            if (out.containsKey(s)) continue;
            try {
                Quote q = google(s);
                if (q != null) out.put(s, q);
            } catch (Exception e) {
                errors.add("Google " + s + ": " + e.getMessage());
            }
        }
        lastError = errors.isEmpty() ? "" : errors.get(0);
        return out;
    }
}
