package com.furnituremall.englandfabriccheck;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    public interface WebsiteLookupCallback {
        void onCurrent(FabricRecord record);
        void onNotCurrent();
        void onFailure(String message);
    }

    private static final String PREFS = "england_fabric_catalog_v4";
    private static final String KEY_JSON = "catalog_json";
    private static final String KEY_SYNC = "last_sync";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_GENERATED = "generated_at";
    private static final int MIN_VALID_RECORDS = 450;

    // Offline snapshot. This is generated from England's own in-store catalog.
    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/cfranksteam-maker/EnglandFabricCheck/main/catalog/catalog.json";

    // England's current public website API. The current England website itself
    // uses this Matrix API for its fabric/product search. We use it only when a
    // barcode is missing from the offline snapshot, so new fabrics are not
    // incorrectly called discontinued.
    private static final String ENGLAND_SEARCH_API =
            "https://www.englandfurniture.com/api/matrix/v2/england/search/?q=";
    private static final String ENGLAND_PRODUCT_API =
            "https://www.englandfurniture.com/api/matrix/v2/england/products/?sku=";

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, FabricRecord> catalog = new HashMap<>();

    private String source = "Catalog unavailable";
    private String generatedAt = "";
    private long lastSync = 0L;

    public CatalogRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (!loadSavedCatalog()) {
            loadBundledCatalog();
        }
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
        return getCount() >= MIN_VALID_RECORDS && source.startsWith("England Furniture official");
    }

    public String getSource() {
        return source;
    }

    public String getLastSyncText() {
        if (lastSync > 0L) {
            return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(lastSync));
        }
        if (generatedAt != null && !generatedAt.isEmpty()) {
            return generatedAt.replace("T", " ").replace("Z", " UTC");
        }
        return "included with app";
    }

    public void refresh(RefreshCallback callback) {
        executor.execute(() -> {
            try {
                String json = downloadJson(UPDATE_URL, "EnglandFabricCheck-Android/1.5", false);
                ParsedCatalog parsed = parseAndValidate(json);
                saveCatalog(json, parsed);

                synchronized (catalog) {
                    catalog.clear();
                    catalog.putAll(parsed.records);
                }
                source = parsed.source;
                generatedAt = parsed.generatedAt;
                lastSync = System.currentTimeMillis();
                callback.onSuccess(parsed.records.size(), parsed.source);
            } catch (Exception e) {
                callback.onFailure(e.getMessage() == null
                        ? "Could not download the official England catalog update. The last verified catalog was kept."
                        : e.getMessage());
            }
        });
    }

    /**
     * Checks EnglandFurniture.com itself for a barcode that is absent from the
     * offline snapshot. The website's search can return a fabric by its barcode,
     * but the barcode is stored in the full product data, so we verify the exact
     * barcode before calling it current.
     */
    public void lookupOfficialWebsite(String code, WebsiteLookupCallback callback) {
        executor.execute(() -> {
            try {
                String encodedCode = URLEncoder.encode(code, StandardCharsets.UTF_8.toString());
                String searchJson = downloadJson(
                        ENGLAND_SEARCH_API + encodedCode,
                        "EnglandFabricCheck-Android/1.5",
                        true
                );

                JSONObject searchRoot = new JSONObject(searchJson);
                JSONObject products = searchRoot.optJSONObject("products");
                JSONArray results = products == null ? null : products.optJSONArray("results");

                if (results == null || results.length() == 0) {
                    callback.onNotCurrent();
                    return;
                }

                for (int i = 0; i < results.length(); i++) {
                    JSONObject result = results.optJSONObject(i);
                    if (result == null || !isFabricResult(result)) continue;

                    String sku = result.optString("sku", "").trim();
                    String searchName = result.optString("name", "").trim();
                    if (sku.isEmpty()) continue;

                    String encodedSku = URLEncoder.encode(sku, StandardCharsets.UTF_8.toString());
                    String fullJson = downloadJson(
                            ENGLAND_PRODUCT_API + encodedSku + "&include=full",
                            "EnglandFabricCheck-Android/1.5",
                            true
                    );

                    JSONObject fullRoot = new JSONObject(fullJson);
                    JSONArray fullResults = fullRoot.optJSONArray("results");
                    if (fullResults == null) continue;

                    for (int j = 0; j < fullResults.length(); j++) {
                        JSONObject full = fullResults.optJSONObject(j);
                        if (full == null) continue;

                        String barcode = extractBarcode(full);
                        if (code.equals(barcode)) {
                            String name = full.optString("name", searchName).trim();
                            if (name.isEmpty()) name = searchName;
                            if (name.isEmpty()) name = "England Fabric " + code;
                            callback.onCurrent(new FabricRecord(code, name.toUpperCase()));
                            return;
                        }
                    }
                }

                // The official search completed normally but did not return a
                // fabric whose actual barcode exactly matches this code.
                callback.onNotCurrent();
            } catch (Exception e) {
                callback.onFailure(e.getMessage() == null
                        ? "Could not check EnglandFurniture.com."
                        : e.getMessage());
            }
        });
    }

    private boolean isFabricResult(JSONObject result) {
        Object raw = result.opt("feature_set");
        if (raw instanceof JSONObject) {
            return "fabric".equalsIgnoreCase(((JSONObject) raw).optString("idx", ""));
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            try {
                JSONObject obj = new JSONObject(text);
                return "fabric".equalsIgnoreCase(obj.optString("idx", ""));
            } catch (Exception ignored) {
                return text.toLowerCase().contains("\"idx\"") &&
                        text.toLowerCase().contains("fabric");
            }
        }
        return false;
    }

    private String extractBarcode(JSONObject product) {
        String fromAssets = extractBarcodeFromValue(product.opt("visual_assets"));
        if (!fromAssets.isEmpty()) return fromAssets;
        return extractBarcodeFromValue(product.opt("attributes"));
    }

    private String extractBarcodeFromValue(Object raw) {
        JSONArray array = null;
        if (raw instanceof JSONArray) {
            array = (JSONArray) raw;
        } else if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (!text.isEmpty()) {
                try {
                    array = new JSONArray(text);
                } catch (Exception ignored) {}
            }
        }

        if (array == null) return "";

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String key = item.optString("feature_idx", item.optString("code", ""));
            if (!"barcode".equalsIgnoreCase(key)) continue;

            String value = item.optString("value", "").trim();
            if (value.isEmpty()) value = item.optString("attribute_value", "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private boolean loadSavedCatalog() {
        String json = prefs.getString(KEY_JSON, "");
        if (json == null || json.isEmpty()) return false;

        try {
            ParsedCatalog parsed = parseAndValidate(json);
            synchronized (catalog) {
                catalog.clear();
                catalog.putAll(parsed.records);
            }
            source = prefs.getString(KEY_SOURCE, parsed.source);
            generatedAt = prefs.getString(KEY_GENERATED, parsed.generatedAt);
            lastSync = prefs.getLong(KEY_SYNC, 0L);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void loadBundledCatalog() {
        try (InputStream in = context.getAssets().open("catalog.json")) {
            String json = readAll(in);
            ParsedCatalog parsed = parseAndValidate(json);
            synchronized (catalog) {
                catalog.clear();
                catalog.putAll(parsed.records);
            }
            source = parsed.source;
            generatedAt = parsed.generatedAt;
            lastSync = 0L;
        } catch (Exception ignored) {
            synchronized (catalog) {
                catalog.clear();
            }
            source = "Catalog unavailable";
            generatedAt = "";
            lastSync = 0L;
        }
    }

    private ParsedCatalog parseAndValidate(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String parsedSource = root.optString("source", "").trim();
        if (!parsedSource.startsWith("England Furniture official")) {
            throw new Exception("Catalog source was not verified as England Furniture official data.");
        }

        JSONObject recordsObject = root.optJSONObject("records");
        if (recordsObject == null) {
            throw new Exception("Catalog update did not contain fabric records.");
        }

        Map<String, FabricRecord> records = new HashMap<>();
        Iterator<String> keys = recordsObject.keys();
        while (keys.hasNext()) {
            String code = keys.next();
            String name = recordsObject.optString(code, "").trim();
            if (code.matches("\\d{4,6}") && !name.isEmpty()) {
                records.put(code, new FabricRecord(code, name));
            }
        }

        int declaredCount = root.optInt("count", 0);
        if (records.size() < MIN_VALID_RECORDS) {
            throw new Exception("Official England catalog update was incomplete (" +
                    records.size() + " fabrics). The existing catalog was kept.");
        }
        if (declaredCount > 0 && Math.abs(declaredCount - records.size()) > 5) {
            throw new Exception("Official England catalog update failed its completeness check.");
        }

        String parsedGeneratedAt = root.optString("generated_at", "").trim();
        return new ParsedCatalog(records, parsedSource, parsedGeneratedAt);
    }

    private void saveCatalog(String json, ParsedCatalog parsed) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putString(KEY_JSON, json)
                .putLong(KEY_SYNC, now)
                .putString(KEY_SOURCE, parsed.source)
                .putString(KEY_GENERATED, parsed.generatedAt)
                .apply();
    }

    private String downloadJson(String urlText, String userAgent, boolean englandWebsite) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        if (englandWebsite) {
            conn.setRequestProperty("Referer", "https://www.englandfurniture.com/");
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            conn.disconnect();
            throw new Exception("England website returned HTTP " + status + ".");
        }

        try (InputStream in = conn.getInputStream()) {
            return readAll(in);
        } finally {
            conn.disconnect();
        }
    }

    private String readAll(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static class ParsedCatalog {
        final Map<String, FabricRecord> records;
        final String source;
        final String generatedAt;

        ParsedCatalog(Map<String, FabricRecord> records, String source, String generatedAt) {
            this.records = records;
            this.source = source;
            this.generatedAt = generatedAt;
        }
    }
}
