package com.pocketplayer.app;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays internet radio and podcast streams through the app (https://localhost/_pp/stream?u=...).
 * This lets http-only stations play inside the secure app page, and keeps the equalizer working.
 */
public class StreamProxy {

    static final String UA = "Mozilla/5.0 (Linux; Android) PocketPlayer/1.0";

    private static String header(Map<String, String> h, String name) {
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static WebResourceResponse fail(int code, String reason) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse("text/plain", "utf-8", code, reason, h, new ByteArrayInputStream(new byte[0]));
    }

    /** Opens a connection and follows redirects, including http to https. */
    static HttpURLConnection connect(String target, String range, int readTimeout) throws Exception {
        URL url = new URL(target);
        HttpURLConnection c = null;
        for (int i = 0; i < 7; i++) {
            c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(readTimeout);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Icy-MetaData", "0");
            if (range != null) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("Redirect without a location");
                url = new URL(url, loc);
                continue;
            }
            return c;
        }
        throw new Exception("Too many redirects");
    }

    static WebResourceResponse open(String target, Map<String, String> requestHeaders) {
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) return fail(400, "Bad Request");
        try {
            HttpURLConnection c = connect(target, header(requestHeaders, "Range"), 30000);
            int code = c.getResponseCode();
            String type = c.getContentType();
            String mime = type == null ? "application/octet-stream" : type.split(";")[0].trim();
            Map<String, String> h = new HashMap<>();
            for (String k : new String[]{"Content-Length", "Content-Range", "Accept-Ranges", "Cache-Control"}) {
                String v = c.getHeaderField(k);
                if (v != null) h.put(k, v);
            }
            h.put("Access-Control-Allow-Origin", "*");
            if (code < 100 || (code >= 300 && code < 400) || code > 599) code = 502;
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String reason = c.getResponseMessage();
            if (reason == null || reason.trim().isEmpty()) reason = code < 400 ? "OK" : "Error";
            return new WebResourceResponse(mime, null, code, reason, h, in == null ? new ByteArrayInputStream(new byte[0]) : in);
        } catch (Exception e) {
            return fail(502, "Bad Gateway");
        }
    }
}
