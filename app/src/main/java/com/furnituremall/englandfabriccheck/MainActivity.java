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
        refreshCatalog(false);
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

        refreshButton = button("REFRESH OFFLINE ENGLAND CATALOG", false);
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
                "The app checks its saved England catalog first. If a fabric is missing there, it checks EnglandFurniture.com live before calling it not current. If the live check cannot be completed, the result is NEEDS VERIFICATION instead of discontinued.",
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

        checkButton.setEnabled(false);
        show("CHECKING ENGLAND WEBSITE…", "Fabric #" + code + " was not in the offline list. Checking EnglandFurniture.com now…", "#66736C");

        catalog.lookupOfficialWebsite(code, new CatalogRepository.WebsiteLookupCallback() {
            @Override
            public void onCurrent(CatalogRepository.FabricRecord record) {
                runOnUiThread(() -> {
                    checkButton.setEnabled(true);
                    show("CURRENT", record.name + "\nFabric #" + code + "\nVerified live on EnglandFurniture.com", "#2C6A4F");
                });
            }

            @Override
            public void onNotCurrent() {
                runOnUiThread(() -> {
                    checkButton.setEnabled(true);
                    show(
                            "LIKELY DISCONTINUED / NOT CURRENT",
                            "Fabric #" + code + " was not found as an exact current fabric barcode on EnglandFurniture.com. Verify with England before a critical special order.",
                            "#9D2A2A"
                    );
                });
            }

            @Override
            public void onFailure(String message) {
                runOnUiThread(() -> {
                    checkButton.setEnabled(true);
                    show(
                            "NEEDS VERIFICATION",
                            "Fabric #" + code + " is not in the offline list, and EnglandFurniture.com could not be checked right now. It has NOT been classified as discontinued.",
                            "#8A6200"
                    );
                });
            }
        });
    }

    private void refreshCatalog(boolean showToast) {
        refreshButton.setEnabled(false);
        catalogInfo.setText("Refreshing offline England catalog…");

        catalog.refresh(new CatalogRepository.RefreshCallback() {
            @Override
            public void onSuccess(int count, String source) {
                runOnUiThread(() -> {
                    refreshButton.setEnabled(true);
                    updateCatalogInfo();
                    if (showToast) {
                        Toast.makeText(MainActivity.this,
                                "Offline England catalog refreshed: " + count + " fabrics.",
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
        if (catalog.hasUsableCatalog()) {
            catalogInfo.setText("Offline catalog ready • " + count + " fabrics • " +
                    catalog.getSource() + " • updated " + catalog.getLastSyncText() +
                    " • missing codes are checked live");
        } else {
            catalogInfo.setText("Offline catalog unavailable • missing codes will be checked live");
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
