package com.furnituremall.englandfabriccheck;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private EditText input;
    private TextView status;
    private TextView detail;
    private TextView catalogInfo;
    private Button checkButton;
    private Button refreshButton;
    private CatalogRepository catalog;
    private FabricCompositionLookup compositionLookup;
    private String compositionCode = "";
    private String compositionText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        catalog = new CatalogRepository(this);
        compositionLookup = new FabricCompositionLookup(this);
        setContentView(buildUi());
        updateCatalogInfo();

        // Keep a current online catalog on the phone without downloading all
        // pages every time the app opens.
        if (!catalog.isLiveCatalogFresh()) {
            refreshCatalog(false);
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#EEF2EF"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(26));
        scroll.addView(root);

        TextView small = text("FURNITURE FABRIC TOOL", 12, true);
        small.setTextColor(Color.parseColor("#2C6A4F"));
        root.addView(small);

        TextView title = text("England Fabric Check", 28, true);
        title.setTextColor(Color.parseColor("#17211D"));
        title.setPadding(0, dp(4), 0, dp(18));
        root.addView(title);

        Button scan = button("SCAN FABRIC BARCODE (OR PRESS VOLUME DOWN)", true);
        scan.setOnClickListener(v -> startScan());
        root.addView(scan);

        input = new EditText(this);
        input.setHint("Example: EC8858");
        input.setSingleLine(true);
        input.setTextSize(18);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.topMargin = dp(14);
        root.addView(input, ip);

        checkButton = button("CHECK FABRIC", false);
        checkButton.setOnClickListener(v -> checkFabric(input.getText().toString()));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(8);
        root.addView(checkButton, bp);

        refreshButton = button("REFRESH ONLINE ENGLAND CATALOG", false);
        refreshButton.setOnClickListener(v -> refreshCatalog(true));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        root.addView(refreshButton, rp);

        catalogInfo = text("Catalog: checking…", 12, false);
        catalogInfo.setTextColor(Color.parseColor("#66736C"));
        catalogInfo.setPadding(0, dp(10), 0, 0);
        root.addView(catalogInfo);

        status = text("READY", 18, true);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(Color.parseColor("#66736C"));
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(22);
        root.addView(status, sp);

        detail = text("Press Volume Down, tap Scan, or enter an England fabric barcode.", 16, false);
        detail.setTextColor(Color.parseColor("#4F5E56"));
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(8), dp(16), dp(8), 0);
        root.addView(detail);

        TextView note = text(
                "Scans are checked against EnglandFurniture.com live first. Fabric composition is pulled from England's product data and saved on the phone after it is found. The Refresh button downloads the full current online fabric catalog to your phone for fast backup/offline use. If the live website cannot be checked and a fabric is missing from the saved catalog, the app shows NEEDS VERIFICATION instead of assuming it is discontinued.",
                13, false);
        note.setTextColor(Color.parseColor("#66736C"));
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note);

        return scroll;
    }

    private void startScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setCaptureActivity(PortraitCaptureActivity.class);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ONE_D_CODE_TYPES);
        integrator.setPrompt("Scan England fabric barcode");
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.getRepeatCount() == 0) {
            startScan();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                input.setText(result.getContents());
                checkFabric(result.getContents());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void checkFabric(String raw) {
        String code = normalize(raw);
        if (!code.matches("\\d{4,6}")) {
            compositionCode = "";
            compositionText = "";
            show("UNRECOGNIZED BARCODE", "Could not find an England fabric number in: " + raw, "#8A6200");
            return;
        }

        beginCompositionLookup(code);
        CatalogRepository.FabricRecord saved = catalog.find(code);
        checkButton.setEnabled(false);
        show("CHECKING ENGLAND WEBSITE…",
                withComposition("Checking fabric #" + code + " against EnglandFurniture.com…"),
                "#66736C");

        catalog.lookupOfficialWebsite(code, new CatalogRepository.WebsiteLookupCallback() {
            @Override
            public void onCurrent(CatalogRepository.FabricRecord record) {
                runOnUiThread(() -> {
                    checkButton.setEnabled(true);
                    show("CURRENT",
                            withComposition(record.name + "\nFabric #" + code + "\nVerified live on EnglandFurniture.com"),
                            "#2C6A4F");
                });
            }

            @Override
            public void onNotCurrent() {
                runOnUiThread(() -> {
                    checkButton.setEnabled(true);

                    // If the freshly downloaded full online catalog contains the
                    // code but search did not confirm it, treat that disagreement
                    // conservatively instead of calling the fabric discontinued.
                    CatalogRepository.FabricRecord currentSaved = catalog.find(code);
                    if (currentSaved != null && catalog.isLiveCatalogFresh()) {
                        show("CURRENT",
                                withComposition(currentSaved.name + "\nFabric #" + code +
                                        "\nListed in the current England online catalog"),
                                "#2C6A4F");
                        return;
                    }

                    if (catalog.isLiveCatalogFresh()) {
                        show("LIKELY DISCONTINUED / NOT CURRENT",
                                withComposition("Fabric #" + code +
                                        " was not found by England's live barcode search or in the freshly downloaded online catalog. Verify with England before a critical special order."),
                                "#9D2A2A");
                    } else {
                        show("NEEDS VERIFICATION",
                                withComposition("Fabric #" + code +
                                        " was not confirmed by the live search, but the full online catalog on this phone is not fresh enough to classify it as discontinued. Tap Refresh Online England Catalog."),
                                "#8A6200");
                    }
                });
            }

            @Override
            public void onFailure(String message) {
                runOnUiThread(() -> {
                    checkButton.setEnabled(true);
                    CatalogRepository.FabricRecord currentSaved = catalog.find(code);
                    if (currentSaved != null) {
                        String sourceText = catalog.isLiveCatalog()
                                ? "Found in the saved England online catalog; live verification is unavailable right now."
                                : "Found in the backup England catalog; live verification is unavailable right now.";
                        show("CURRENT IN SAVED CATALOG",
                                withComposition(currentSaved.name + "\nFabric #" + code + "\n" + sourceText),
                                "#8A6200");
                    } else {
                        show("NEEDS VERIFICATION",
                                withComposition("Fabric #" + code +
                                        " could not be checked on EnglandFurniture.com and is not in the saved catalog. It has NOT been classified as discontinued."),
                                "#8A6200");
                    }
                });
            }
        });
    }

    private void beginCompositionLookup(String code) {
        compositionCode = code;
        String cached = compositionLookup.getCached(code);
        compositionText = cached == null || cached.trim().isEmpty()
                ? "Checking England data…"
                : cached.trim();

        compositionLookup.lookup(code, new FabricCompositionLookup.Callback() {
            @Override
            public void onSuccess(String composition) {
                runOnUiThread(() -> updateCompositionLine(code, composition));
            }

            @Override
            public void onNotListed() {
                runOnUiThread(() -> {
                    String existing = compositionLookup.getCached(code);
                    if (existing != null && !existing.trim().isEmpty()) {
                        updateCompositionLine(code, existing.trim());
                    } else {
                        updateCompositionLine(code, "Not listed in England's product data");
                    }
                });
            }

            @Override
            public void onFailure() {
                runOnUiThread(() -> {
                    String existing = compositionLookup.getCached(code);
                    if (existing != null && !existing.trim().isEmpty()) {
                        updateCompositionLine(code, existing.trim());
                    } else {
                        updateCompositionLine(code, "Unavailable right now");
                    }
                });
            }
        });
    }

    private String withComposition(String message) {
        if (compositionCode.isEmpty()) return message;
        String madeOf = compositionText == null || compositionText.trim().isEmpty()
                ? "Checking England data…"
                : compositionText.trim();
        return message + "\nMade of: " + madeOf;
    }

    private void updateCompositionLine(String code, String composition) {
        if (!code.equals(compositionCode)) return;
        compositionText = composition == null || composition.trim().isEmpty()
                ? "Not listed in England's product data"
                : composition.trim();

        String current = detail.getText().toString();
        current = current.replaceFirst("(?s)\\nMade of:.*$", "");
        detail.setText(current + "\nMade of: " + compositionText);
    }

    private void refreshCatalog(boolean showToast) {
        refreshButton.setEnabled(false);
        catalogInfo.setText("Refreshing full online catalog from EnglandFurniture.com…");

        catalog.refresh(new CatalogRepository.RefreshCallback() {
            @Override
            public void onSuccess(int count, String source) {
                runOnUiThread(() -> {
                    refreshButton.setEnabled(true);
                    updateCatalogInfo();
                    if (showToast) {
                        Toast.makeText(MainActivity.this,
                                "Online England catalog refreshed: " + count + " current fabrics.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onFailure(String message) {
                runOnUiThread(() -> {
                    refreshButton.setEnabled(true);
                    updateCatalogInfo();
                    if (showToast) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void updateCatalogInfo() {
        int count = catalog.getCount();
        if (catalog.isLiveCatalog()) {
            catalogInfo.setText("Online catalog ready • " + count + " fabrics • updated " +
                    catalog.getLastSyncText());
        } else if (catalog.hasUsableCatalog()) {
            catalogInfo.setText("Backup catalog loaded • " + count +
                    " fabrics • tap Refresh Online England Catalog for the current website list");
        } else {
            catalogInfo.setText("No complete catalog saved • tap Refresh Online England Catalog");
        }
    }

    private String normalize(String raw) {
        String text = raw == null ? "" : raw.trim().toUpperCase(Locale.US).replace("*", "");
        Matcher ec = Pattern.compile("^EC[\\s-]*(\\d{4,6})$").matcher(text);
        if (ec.find()) return ec.group(1);
        Matcher digits = Pattern.compile("\\d{4,6}").matcher(text);
        String best = "";
        while (digits.find()) if (digits.group().length() > best.length()) best = digits.group();
        return best;
    }

    private void show(String title, String message, String color) {
        status.setText(title);
        status.setBackgroundColor(Color.parseColor(color));
        detail.setText(message);
    }

    private TextView text(String s, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.parseColor("#153A2D"));
        }
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (catalog != null) catalog.shutdown();
        if (compositionLookup != null) compositionLookup.shutdown();
    }
}
