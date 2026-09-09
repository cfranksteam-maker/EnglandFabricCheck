package com.furnituremall.englandfabriccheck;

import android.app.Activity;
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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private EditText input;
    private TextView status;
    private TextView detail;
    private Button checkButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
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

        TextView note = text("This app runs on your Android phone. It only uses the internet to check England Furniture's current public fabric catalog.", 13, false);
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

        checkButton.setEnabled(false);
        show("CHECKING…", "Looking for fabric #" + code + " in England's current catalog.", "#66736C");

        executor.execute(() -> {
            try {
                LookupResult r = lookupCurrentCatalog(code);
                runOnUiThread(() -> {
                    checkButton.setEnabled(true);
                    if (r.found) {
                        show("CURRENT", (r.name == null ? "England fabric" : r.name) + "\nFabric #" + code, "#2C6A4F");
                    } else {
                        show("LIKELY DISCONTINUED / NOT CURRENT", "Fabric #" + code + " was not found in England Furniture's current online fabric catalog.", "#9D2A2A");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    checkButton.setEnabled(true);
                    show("NEEDS VERIFICATION", "Could not reach or safely read England's catalog. Try again with internet access.", "#8A6200");
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private LookupResult lookupCurrentCatalog(String code) throws Exception {
        String previousSignature = "";
        int repeated = 0;

        for (int page = 1; page <= 40; page++) {
            Document doc = Jsoup.connect("https://www.englandfurniture.com/fabric/cover-type.aspx?page=" + page)
                    .userAgent("Mozilla/5.0 Android EnglandFabricCheck/1.0")
                    .timeout(18000)
                    .get();

            String text = doc.body().text();
            String signature = text.length() + ":" + text.substring(0, Math.min(120, text.length()));
            if (signature.equals(previousSignature)) repeated++; else repeated = 0;
            previousSignature = signature;

            Pattern p = Pattern.compile("(?i)(.{0,120})\\b" + Pattern.quote(code) + "\\b(.{0,180})");
            Matcher m = p.matcher(text);
            if (m.find()) {
                String around = (m.group(1) + " " + m.group(2)).replaceAll("\\s+", " ").trim();
                String name = extractName(around, code);
                return new LookupResult(true, name);
            }

            if (repeated >= 2) break;
        }
        return new LookupResult(false, null);
    }

    private String extractName(String around, String code) {
        String cleaned = around.replace(code, " ").replaceAll("(?i)View Item|Compare|New", " ").replaceAll("\\s+", " ").trim();
        Matcher caps = Pattern.compile("([A-Z][A-Z0-9 '&/-]{3,50})").matcher(cleaned.toUpperCase(Locale.US));
        String best = null;
        while (caps.find()) {
            String candidate = caps.group(1).trim();
            if (!candidate.matches(".*(IMPORT|CRYPTON|DOMESTIC|REVOLUTION|FABRIC|COVER TYPE).*")) best = candidate;
        }
        return best;
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
        executor.shutdownNow();
    }

    private static class LookupResult {
        final boolean found;
        final String name;
        LookupResult(boolean found, String name) { this.found = found; this.name = name; }
    }
}
