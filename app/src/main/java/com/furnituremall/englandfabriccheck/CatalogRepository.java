package com.furnituremall.englandfabriccheck;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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

    // v4 intentionally ignores catalogs saved by earlier dealer-mirror builds.
    private static final String PREFS = "england_fabric_catalog_v4";
    private static final String KEY_JSON = "catalog_json";
    private static final String KEY_SYNC = "last_sync";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_GENERATED = "generated_at";
    private static final int MIN_VALID_RECORDS = 450;

    // This JSON is regenerated every day from England Furniture's own in-store
    // catalog system. The Android phone downloads only this static snapshot,
    // avoiding the 403 blocks that occur when Android scrapes furniture sites.
    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/cfranksteam-maker/EnglandFabricCheck/main/catalog/catalog.json";

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
                String json = downloadJson(UPDATE_URL);
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

    private String downloadJson(String urlText) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "EnglandFabricCheck-Android/1.3");
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            conn.disconnect();
            throw new Exception("Official catalog update returned HTTP " + status +
                    ". The saved England catalog is still available.");
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
