package com.furnituremall.englandfabriccheck;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        catalog = new CatalogRepository(this);
        setContentView(buildUi());
        updateCatalogInfo();

        // If there is no verified full catalog saved on this phone, get one now.
        if (!catalog.hasUsableCatalog()) {
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

        Button scan = button("SCAN FABRIC BARCODE", true);
        scan.setOnClickListener(v -> startScan());
        root.addView(scan);

        input = new EditText(this);
        input.setHint("Example: EC9528");
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

        refreshButton = button("REFRESH ENGLAND CATALOG", false);
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

        detail = text("Scan or enter an England fabric barcode.", 16, false);
        detail.setTextColor(Color.parseColor("#4F5E56"));
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(8), dp(16), dp(8), 0);
        root.addView(detail);

        TextView note = text(
                "Important: this version will not call a fabric discontinued unless a complete verified England catalog is saved on the phone. If the catalog cannot refresh safely, it shows NEEDS VERIFICATION instead.",
                13, false);
        note.setTextColor(Color.parseColor("#66736C"));
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note);

        return scroll;
    }

    private void startScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ONE_D_CODE_TYPES);
        integrator.setPrompt("Scan England fabric barcode");
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
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
            show("UNRECOGNIZED BARCODE", "Could not find an England fabric number in: " + raw, "#8A6200");
            return;
        }

        CatalogRepository.FabricRecord rec = catalog.find(code);
        if (rec != null) {
            show("CURRENT", rec.name + "\nFabric #" + code, "#2C6A4F");
            return;
        }

        if (!catalog.hasUsableCatalog()) {
            show(
                    "NEEDS VERIFICATION",
                    "Fabric #" + code + " is not in the small local data currently available. Refresh the England catalog before deciding its status.",
                    "#8A6200"
            );
            return;
        }

        show(
                "LIKELY DISCONTINUED / NOT CURRENT",
                "Fabric #" + code + " was not found in the complete England catalog saved on this phone. Verify with England before a critical order.",
                "#9D2A2A"
        );
    }

    private void refreshCatalog(boolean showToast) {
        refreshButton.setEnabled(false);
        checkButton.setEnabled(false);
        catalogInfo.setText("Refreshing England catalog…");

        catalog.refresh(new CatalogRepository.RefreshCallback() {
            @Override
            public void onSuccess(int count, String source) {
                runOnUiThread(() -> {
                    refreshButton.setEnabled(true);
                    checkButton.setEnabled(true);
                    updateCatalogInfo();
                    if (showToast) {
                        Toast.makeText(MainActivity.this,
                                "Catalog refreshed: " + count + " fabrics.", Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onFailure(String message) {
                runOnUiThread(() -> {
                    refreshButton.setEnabled(true);
                    checkButton.setEnabled(true);
                    updateCatalogInfo();
                    show("NEEDS VERIFICATION",
                            "The England catalog could not be refreshed safely. Existing saved data was kept; no fabric will be called discontinued from an incomplete refresh.",
                            "#8A6200");
                    if (showToast) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void updateCatalogInfo() {
        int count = catalog.getCount();
        if (catalog.hasUsableCatalog()) {
            catalogInfo.setText("Catalog ready • " + count + " fabrics • " + catalog.getSource() + " • updated " + catalog.getLastSyncText());
        } else {
            catalogInfo.setText("No complete verified catalog saved yet • tap Refresh England Catalog");
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
    }
}
