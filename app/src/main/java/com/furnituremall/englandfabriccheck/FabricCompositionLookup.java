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
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Looks up an England fabric's fiber/fabric composition from the same public
 * Matrix product data used by EnglandFurniture.com. Successful values are
 * cached on the phone so they can still be displayed later if the website is
 * temporarily unavailable.
 */
public class FabricCompositionLookup {

    public interface Callback {
        void onSuccess(String composition);
        void onNotListed();
        void onFailure();
    }

    private static final String PREFS = "england_fabric_composition_v1";
    private static final String ENGLAND_PRODUCTS_API =
            "https://www.englandfurniture.com/api/matrix/v2/england/products/";
    private static final String ENGLAND_SEARCH_API =
            "https://www.englandfurniture.com/api/matrix/v2/england/search/?q=";
    private static final String USER_AGENT = "EnglandFabricCheck-Android/1.7";

    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public FabricCompositionLookup(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getCached(String code) {
        return prefs.getString(code, "");
    }

    public void lookup(String code, Callback callback) {
        executor.execute(() -> {
            try {
                String encodedCode = URLEncoder.encode(code, StandardCharsets.UTF_8.toString());
                JSONObject searchRoot = new JSONObject(downloadJson(ENGLAND_SEARCH_API + encodedCode));
                JSONObject products = searchRoot.optJSONObject("products");
                JSONArray results = products == null ? null : products.optJSONArray("results");

                if (results == null || results.length() == 0) {
                    callback.onNotListed();
                    return;
                }

                for (int i = 0; i < results.length(); i++) {
                    JSONObject result = results.optJSONObject(i);
                    if (result == null || !isFabricResult(result)) continue;

                    String sku = result.optString("sku", "").trim();
                    if (sku.isEmpty()) continue;

                    String encodedSku = URLEncoder.encode(sku, StandardCharsets.UTF_8.toString());
                    JSONObject fullRoot = new JSONObject(downloadJson(
                            ENGLAND_PRODUCTS_API + "?sku=" + encodedSku + "&include=full"));
                    JSONArray fullResults = fullRoot.optJSONArray("results");
                    if (fullResults == null) continue;

                    for (int j = 0; j < fullResults.length(); j++) {
                        JSONObject full = fullResults.optJSONObject(j);
                        if (full == null || !code.equals(extractBarcode(full))) continue;

                        String composition = extractComposition(full);
                        if (!composition.isEmpty()) {
                            prefs.edit().putString(code, composition).apply();
                            callback.onSuccess(composition);
                        } else {
                            callback.onNotListed();
                        }
                        return;
                    }
                }

                callback.onNotListed();
            } catch (Exception ignored) {
                callback.onFailure();
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
                return "fabric".equalsIgnoreCase(new JSONObject(text).optString("idx", ""));
            } catch (Exception ignored) {
                String lower = text.toLowerCase(Locale.US);
                return lower.contains("\"idx\"") && lower.contains("fabric");
            }
        }
        return false;
    }

    private String extractBarcode(JSONObject product) {
        String value = extractFeatureValue(product.opt("visual_assets"), "barcode");
        if (!value.isEmpty()) return value;
        return extractFeatureValue(product.opt("attributes"), "barcode");
    }

    private String extractComposition(JSONObject product) {
        String[] directKeys = {
                "fiber_content", "fibre_content", "fabric_content",
                "fiber_composition", "fibre_composition", "fabric_composition"
        };
        for (String key : directKeys) {
            String value = cleanComposition(valueToText(product.opt(key)));
            if (looksLikeComposition(value)) return value;
        }

        String value = extractCompositionFromFeatureList(product.opt("attributes"));
        if (!value.isEmpty()) return value;
        value = extractCompositionFromFeatureList(product.opt("visual_assets"));
        if (!value.isEmpty()) return value;

        value = findCompositionRecursive(product, 0);
        return cleanComposition(value);
    }

    private String extractCompositionFromFeatureList(Object raw) {
        JSONArray array = asArray(raw);
        if (array == null) return "";

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;

            String descriptor = firstNonEmpty(
                    item.optString("feature_idx", ""),
                    item.optString("code", ""),
                    item.optString("idx", ""),
                    item.optString("feature", ""),
                    item.optString("label", ""),
                    item.optString("title", ""),
                    item.optString("name", "")
            );

            if (!isCompositionKey(descriptor)) continue;

            String value = firstNonEmpty(
                    valueToText(item.opt("value")),
                    valueToText(item.opt("attribute_value")),
                    valueToText(item.opt("display_value")),
                    valueToText(item.opt("feature_value"))
            );
            value = cleanComposition(value);
            if (looksLikeComposition(value)) return value;
        }
        return "";
    }

    private String findCompositionRecursive(Object node, int depth) {
        if (node == null || depth > 7) return "";

        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (isCompositionKey(key)) {
                    String text = cleanComposition(valueToText(value));
                    if (looksLikeComposition(text)) return text;
                }
            }

            keys = object.keys();
            while (keys.hasNext()) {
                String found = findCompositionRecursive(object.opt(keys.next()), depth + 1);
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findCompositionRecursive(array.opt(i), depth + 1);
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof String) {
            String text = ((String) node).trim();
            if ((text.startsWith("{") && text.endsWith("}")) ||
                    (text.startsWith("[") && text.endsWith("]"))) {
                try {
                    Object parsed = text.startsWith("{")
                            ? new JSONObject(text)
                            : new JSONArray(text);
                    return findCompositionRecursive(parsed, depth + 1);
                } catch (Exception ignored) {}
            }
        }
        return "";
    }

    private String extractFeatureValue(Object raw, String requestedKey) {
        JSONArray array = asArray(raw);
        if (array == null) return "";
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String key = item.optString("feature_idx", item.optString("code", ""));
            if (!requestedKey.equalsIgnoreCase(key)) continue;

            String value = firstNonEmpty(
                    valueToText(item.opt("value")),
                    valueToText(item.opt("attribute_value")),
                    valueToText(item.opt("display_value"))
            );
            if (!value.isEmpty()) return value.trim();
        }
        return "";
    }

    private JSONArray asArray(Object raw) {
        if (raw instanceof JSONArray) return (JSONArray) raw;
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (!text.isEmpty()) {
                try {
                    return new JSONArray(text);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private boolean isCompositionKey(String raw) {
        if (raw == null) return false;
        String key = raw.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
        return key.equals("fibercontent") ||
                key.equals("fibrecontent") ||
                key.equals("fabriccontent") ||
                key.equals("fibercomposition") ||
                key.equals("fibrecomposition") ||
                key.equals("fabriccomposition") ||
                key.equals("content");
    }

    private boolean looksLikeComposition(String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("%") ||
                lower.contains("polyester") ||
                lower.contains("polypropylene") ||
                lower.contains("olefin") ||
                lower.contains("cotton") ||
                lower.contains("rayon") ||
                lower.contains("acrylic") ||
                lower.contains("nylon") ||
                lower.contains("linen") ||
                lower.contains("wool") ||
                lower.contains("polyurethane") ||
                lower.contains("viscose") ||
                lower.contains("acetate");
    }

    private String cleanComposition(String value) {
        if (value == null) return "";
        String cleaned = value
                .replace("\n", " ")
                .replace("\r", " ")
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = cleaned.replaceAll("(?i)^\\s*(fiber|fibre|fabric)\\s*(content|composition)\\s*[:\\-]\\s*", "");
        return cleaned;
    }

    private String valueToText(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return ((String) value).trim();
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String text = firstNonEmpty(
                    object.optString("value", ""),
                    object.optString("name", ""),
                    object.optString("label", ""),
                    object.optString("title", "")
            );
            if (!text.isEmpty()) return text;
        }

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String part = valueToText(array.opt(i));
                if (part.isEmpty()) continue;
                if (out.length() > 0) out.append(", ");
                out.append(part);
            }
            return out.toString();
        }
        return "";
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private String downloadJson(String urlText) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        conn.setRequestProperty("Referer", "https://www.englandfurniture.com/");

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            conn.disconnect();
            throw new Exception("HTTP " + status);
        }

        try (InputStream in = conn.getInputStream()) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            return out.toString();
        } finally {
            conn.disconnect();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
