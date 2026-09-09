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
import java.time.Instant;
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

    // v6 starts with a clean saved catalog so older incomplete snapshots cannot
    // override the new EnglandFurniture.com catalog.
    private static final String PREFS = "england_fabric_catalog_v6";
    private static final String KEY_JSON = "catalog_json";
    private static final String KEY_SYNC = "last_sync";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_GENERATED = "generated_at";
    private static final int MIN_VALID_RECORDS = 450;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final long ONLINE_FRESH_MS = 24L * 60L * 60L * 1000L;

    private static final String LIVE_SOURCE = "EnglandFurniture.com live catalog";

    // These are the current public Matrix endpoints used by EnglandFurniture.com.
    private static final String ENGLAND_PRODUCTS_API =
            "https://www.englandfurniture.com/api/matrix/v2/england/products/";
    private static final String ENGLAND_SEARCH_API =
            "https://www.englandfurniture.com/api/matrix/v2/england/search/?q=";

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
            // The bundled catalog is only a backup until the phone completes its
            // first refresh from EnglandFurniture.com.
            loadBundledCatalog();
        }
    }

    public FabricRecord find(String code) {
        synchronized (catalog) {
            return catalog.get(code);
        }
    }

    public void rememberCurrent(FabricRecord record) {
        if (record == null) return;
        synchronized (catalog) {
            catalog.put(record.code, record);
        }
    }

    public int getCount() {
        synchronized (catalog) {
            return catalog.size();
        }
    }

    public boolean hasUsableCatalog() {
        return getCount() >= MIN_VALID_RECORDS;
    }

    public boolean isLiveCatalog() {
        return LIVE_SOURCE.equals(source);
    }

    public boolean isLiveCatalogFresh() {
        return isLiveCatalog() && lastSync > 0L &&
                System.currentTimeMillis() - lastSync < ONLINE_FRESH_MS;
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

    /**
     * Refreshes the phone's saved catalog directly from the current
     * EnglandFurniture.com Matrix product API. The API contains furniture and
     * fabrics together, so we page through every product and keep only records
     * whose feature set is fabric and whose exact barcode is present.
     */
    public void refresh(RefreshCallback callback) {
        executor.execute(() -> {
            try {
                Map<String, FabricRecord> records = downloadCurrentOnlineCatalog();
                if (records.size() < MIN_VALID_RECORDS) {
                    throw new Exception("England's online catalog returned only " +
                            records.size() + " fabrics. The existing saved catalog was kept.");
                }

                String generated = Instant.now().toString();
                String json = buildCatalogJson(records, LIVE_SOURCE, generated);
                saveCatalog(json, LIVE_SOURCE, generated);

                synchronized (catalog) {
                    catalog.clear();
                    catalog.putAll(records);
                }
                source = LIVE_SOURCE;
                generatedAt = generated;
                lastSync = System.currentTimeMillis();
                callback.onSuccess(records.size(), LIVE_SOURCE);
            } catch (Exception e) {
                callback.onFailure(e.getMessage() == null
                        ? "Could not refresh EnglandFurniture.com's online fabric catalog. The last saved catalog was kept."
                        : e.getMessage());
            }
        });
    }

    private Map<String, FabricRecord> downloadCurrentOnlineCatalog() throws Exception {
        Map<String, FabricRecord> records = new HashMap<>();
        boolean reachedEnd = false;

        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = ENGLAND_PRODUCTS_API +
                    "?include=full&page_size=" + PAGE_SIZE + "&page=" + page;
            String json = downloadJson(url, "EnglandFabricCheck-Android/1.6", true);
            JSONObject root = new JSONObject(json);
            JSONArray results = root.optJSONArray("results");
            if (results == null) {
                throw new Exception("England's online catalog response was missing product records.");
            }

            if (results.length() == 0) {
                reachedEnd = true;
                break;
            }

            for (int i = 0; i < results.length(); i++) {
                JSONObject product = results.optJSONObject(i);
                if (product == null || !isFabricResult(product)) continue;

                String barcode = extractBarcode(product).trim();
                if (!barcode.matches("\\d{4,6}")) continue;

                String name = product.optString("name", "").trim();
                if (name.isEmpty()) name = product.optString("sku", "").trim();
                if (name.isEmpty()) name = "England Fabric " + barcode;

                records.put(barcode, new FabricRecord(barcode, name.toUpperCase()));
            }

            // England's API currently reports has_next_page=false even while
            // later numbered pages exist, so the actual page length is used to
            // detect the end instead.
            if (results.length() < PAGE_SIZE) {
                reachedEnd = true;
                break;
            }
        }

        if (!reachedEnd) {
            throw new Exception("England's online catalog exceeded the expected page limit; refusing an incomplete refresh.");
        }
        return records;
    }

    /**
     * Performs an exact live lookup against EnglandFurniture.com for one fabric
     * barcode. This is used for scans so a stale local cache cannot be the sole
     * reason a fabric is called current or not current.
     */
    public void lookupOfficialWebsite(String code, WebsiteLookupCallback callback) {
        executor.execute(() -> {
            try {
                String encodedCode = URLEncoder.encode(code, StandardCharsets.UTF_8.toString());
                String searchJson = downloadJson(
                        ENGLAND_SEARCH_API + encodedCode,
                        "EnglandFabricCheck-Android/1.6",
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
                            ENGLAND_PRODUCTS_API + "?sku=" + encodedSku + "&include=full",
                            "EnglandFabricCheck-Android/1.6",
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
                            FabricRecord record = new FabricRecord(code, name.toUpperCase());
                            rememberCurrent(record);
                            callback.onCurrent(record);
                            return;
                        }
                    }
                }

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
                String lower = text.toLowerCase();
                return lower.contains("\"idx\"") && lower.contains("fabric");
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
        boolean acceptedSource = LIVE_SOURCE.equals(parsedSource) ||
                parsedSource.startsWith("England Furniture official");
        if (!acceptedSource) {
            throw new Exception("Catalog source was not recognized as England Furniture data.");
        }

        JSONObject recordsObject = root.optJSONObject("records");
        if (recordsObject == null) {
            throw new Exception("Catalog did not contain fabric records.");
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

        if (records.size() < MIN_VALID_RECORDS) {
            throw new Exception("Catalog was incomplete (" + records.size() + " fabrics).");
        }
        int declaredCount = root.optInt("count", 0);
        if (declaredCount > 0 && Math.abs(declaredCount - records.size()) > 5) {
            throw new Exception("Catalog failed its completeness check.");
        }

        return new ParsedCatalog(
                records,
                parsedSource,
                root.optString("generated_at", "").trim()
        );
    }

    private String buildCatalogJson(Map<String, FabricRecord> records, String sourceName, String generated) throws Exception {
        JSONObject recordObject = new JSONObject();
        for (Map.Entry<String, FabricRecord> entry : records.entrySet()) {
            recordObject.put(entry.getKey(), entry.getValue().name);
        }
        JSONObject root = new JSONObject();
        root.put("source", sourceName);
        root.put("source_url", "https://www.englandfurniture.com/api/matrix/v2/england/products/");
        root.put("generated_at", generated);
        root.put("count", records.size());
        root.put("records", recordObject);
        return root.toString();
    }

    private void saveCatalog(String json, String sourceName, String generated) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putString(KEY_JSON, json)
                .putLong(KEY_SYNC, now)
                .putString(KEY_SOURCE, sourceName)
                .putString(KEY_GENERATED, generated)
                .apply();
    }

    private String downloadJson(String urlText, String userAgent, boolean englandWebsite) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        if (englandWebsite) {
            conn.setRequestProperty("Referer", "https://www.englandfurniture.com/");
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            conn.disconnect();
            throw new Exception("EnglandFurniture.com returned HTTP " + status + ".");
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
