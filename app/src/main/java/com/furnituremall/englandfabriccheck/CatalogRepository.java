package com.furnituremall.englandfabriccheck;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CatalogRepository {

    public static class FabricRecord {
        public final String code;
        public final String name;

        public FabricRecord(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    public interface RefreshCallback {
        void onSuccess(int count, String source);
        void onFailure(String message);
    }

    private static final String PREFS = "england_fabric_catalog_v2";
    private static final String KEY_CATALOG = "catalog";
    private static final String KEY_SYNC = "last_sync";
    private static final String KEY_SOURCE = "source";

    private static final long FRESH_MS = 24L * 60L * 60L * 1000L;
    private static final int MIN_EXPECTED_ENGLAND_FABRICS = 350;

    private static final String OFFICIAL_PAGE =
            "https://www.englandfurniture.com/fabric/cover-type.aspx?page=%d";
    private static final String MIRROR_PAGE =
            "https://www.frazierandsonfurniture.com/fabric/cover-type.aspx?page=%d";

    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, FabricRecord> catalog = new HashMap<>();

    public CatalogRepository(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadSaved();
    }

    public FabricRecord find(String code) {
        synchronized (catalog) {
            return catalog.get(code);
        }
    }

    public int getCount() {
        synchronized (catalog) {
            return catalog.size();
        }
    }

    public boolean hasUsableCatalog() {
        return getCount() >= MIN_EXPECTED_ENGLAND_FABRICS && prefs.getLong(KEY_SYNC, 0L) > 0L;
    }

    public boolean isFresh() {
        long last = prefs.getLong(KEY_SYNC, 0L);
        return hasUsableCatalog() && last > 0L && System.currentTimeMillis() - last < FRESH_MS;
    }

    public String getSource() {
        return prefs.getString(KEY_SOURCE, "none");
    }

    public String getLastSyncText() {
        long t = prefs.getLong(KEY_SYNC, 0L);
        if (t <= 0L) return "never";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(t));
    }

    public void refresh(RefreshCallback callback) {
        executor.execute(() -> {
            try {
                CatalogDownload official = downloadCatalog(OFFICIAL_PAGE, true);
                if (official.records.size() >= MIN_EXPECTED_ENGLAND_FABRICS) {
                    save(official.records, "England Furniture");
                    callback.onSuccess(official.records.size(), "England Furniture");
                    return;
                }
            } catch (Exception ignored) {}

            try {
                CatalogDownload mirror = downloadCatalog(MIRROR_PAGE, false);
                if (mirror.records.size() < MIN_EXPECTED_ENGLAND_FABRICS) {
                    throw new Exception("Only " + mirror.records.size() + " England fabrics were found; refusing to classify missing fabrics.");
                }
                save(mirror.records, "England catalog mirror");
                callback.onSuccess(mirror.records.size(), "England catalog mirror");
            } catch (Exception e) {
                callback.onFailure(e.getMessage() == null ? "Could not refresh the fabric catalog." : e.getMessage());
            }
        });
    }

    private CatalogDownload downloadCatalog(String template, boolean official) throws Exception {
        Map<String, FabricRecord> found = new HashMap<>();
        Document first = fetch(String.format(Locale.US, template, 1));
        Map<String, FabricRecord> firstRecords = parseRecords(first);

        if (official && !looksLikeFabricCatalog(first, firstRecords)) {
            throw new Exception("England's legacy fabric-list page is no longer serving a catalog.");
        }

        found.putAll(firstRecords);
        int maxPage = detectMaxPage(first);
        if (maxPage < 1) maxPage = 1;
        if (maxPage > 40) maxPage = 40;

        for (int page = 2; page <= maxPage; page++) {
            Document doc = fetch(String.format(Locale.US, template, page));
            found.putAll(parseRecords(doc));
        }
        return new CatalogDownload(found);
    }

    private Document fetch(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 EnglandFabricCheck/1.1")
                .referrer("https://www.google.com/")
                .timeout(20000)
                .followRedirects(true)
                .get();
    }

    private boolean looksLikeFabricCatalog(Document doc, Map<String, FabricRecord> parsed) {
        String body = doc.body() == null ? "" : doc.body().text();
        return parsed.size() >= 10 && body.contains("View Item") &&
                body.toLowerCase(Locale.US).contains("fabric");
    }

    private int detectMaxPage(Document doc) {
        int max = 1;
        for (Element a : doc.select("a[href]")) {
            String href = a.attr("href");
            Matcher m = Pattern.compile("(?:[?&]|&amp;)page=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(href);
            while (m.find()) {
                try {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {}
            }
        }
        return max;
    }

    private Map<String, FabricRecord> parseRecords(Document doc) {
        Map<String, FabricRecord> out = new HashMap<>();
        if (doc.body() == null) return out;

        String text = doc.body().text().replace('\u00A0', ' ').replaceAll("\\s+", " ");
        Pattern cardPattern = Pattern.compile(
                "(?i)\\b(\\d{4,6})\\b\\s+([A-Z][A-Z0-9 &'./()\\-]{2,70}?)\\s+View Item\\b");
        Matcher matcher = cardPattern.matcher(text);
        while (matcher.find()) {
            String code = matcher.group(1);
            String name = matcher.group(2).replaceAll("\\s+", " ").trim().toUpperCase(Locale.US);
            if (isPlausibleFabricName(name)) {
                out.put(code, new FabricRecord(code, name));
            }
        }

        if (out.size() < 5) {
            Map<String, List<String>> textsByHref = new HashMap<>();
            for (Element a : doc.select("a[href*='iteminformation.aspx']")) {
                String href = a.absUrl("href");
                if (href.isEmpty()) href = a.attr("href");
                String own = a.text().replaceAll("\\s+", " ").trim();
                if (own.isEmpty()) continue;
                textsByHref.computeIfAbsent(href, k -> new ArrayList<>()).add(own);
            }

            for (List<String> parts : textsByHref.values()) {
                String code = null;
                String name = null;
                for (String part : parts) {
                    if (part.matches("\\d{4,6}")) code = part;
                }
                if (code == null) continue;
                for (String part : parts) {
                    String candidate = part.toUpperCase(Locale.US);
                    if (!part.equals(code) && isPlausibleFabricName(candidate)) {
                        name = candidate;
                        break;
                    }
                }
                if (name != null) out.put(code, new FabricRecord(code, name));
            }
        }

        return out;
    }

    private boolean isPlausibleFabricName(String name) {
        if (name == null || name.length() < 3 || name.length() > 80) return false;
        if (!name.matches(".*[A-Z].*")) return false;
        return !name.matches("(?i)^(VIEW ITEM|COMPARE|ENGLAND|IMPORT|DOMESTIC|CRYPTON|REVOLUTION|LIVESMART|SUNBELIEVABLE|LEATHER)$");
    }

    private void save(Map<String, FabricRecord> records, String source) throws Exception {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, FabricRecord> entry : records.entrySet()) {
            obj.put(entry.getKey(), entry.getValue().name);
        }

        synchronized (catalog) {
            catalog.clear();
            catalog.putAll(records);
        }

        prefs.edit()
                .putString(KEY_CATALOG, obj.toString())
                .putLong(KEY_SYNC, System.currentTimeMillis())
                .putString(KEY_SOURCE, source)
                .apply();
    }

    private void loadSaved() {
        String json = prefs.getString(KEY_CATALOG, "");
        if (json.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(json);
            synchronized (catalog) {
                catalog.clear();
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String code = keys.next();
                    catalog.put(code, new FabricRecord(code, obj.optString(code, "ENGLAND FABRIC")));
                }
            }
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static class CatalogDownload {
        final Map<String, FabricRecord> records;
        CatalogDownload(Map<String, FabricRecord> records) {
            this.records = records;
        }
    }
}
