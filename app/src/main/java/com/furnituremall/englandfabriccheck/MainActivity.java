package com.furnituremall.paymentscanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.NumberFormat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final int SCANNER_REQUEST = 501;
    private static final int CAMERA_PERMISSION_REQUEST = 502;
    private static final double PROTECTION_RATE = 0.15;
    private static final double STANDARD_DELIVERY = 199.99;
    private static final double PROTECTION_DELIVERY = 99.99;

    private EditText priceInput;
    private EditText taxRateInput;
    private Switch protectionSwitch;
    private RadioGroup fulfillmentGroup;
    private TextView summaryView;
    private TextView termsView;
    private TextView scanStatus;

    private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Furniture Payment Scanner");
        buildUi();
        recalculate();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("Furniture Payment Scanner", 26, true);
        root.addView(title);

        TextView subtitle = text("Scan a price tag or enter a merchandise price. Payment options update automatically.", 15, false);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        Button scanButton = new Button(this);
        scanButton.setText("SCAN PRICE");
        scanButton.setTextSize(19);
        scanButton.setMinHeight(dp(58));
        scanButton.setOnClickListener(v -> beginScan());
        root.addView(scanButton);

        scanStatus = text("Live scanner ready • Volume Down also opens scanner", 13, false);
        scanStatus.setPadding(0, dp(6), 0, dp(14));
        root.addView(scanStatus);

        root.addView(label("Merchandise price"));
        priceInput = new EditText(this);
        priceInput.setHint("0.00");
        priceInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        priceInput.setTextSize(24);
        root.addView(priceInput);

        protectionSwitch = new Switch(this);
        protectionSwitch.setText("Add Protection Plan (15%)");
        protectionSwitch.setTextSize(17);
        protectionSwitch.setPadding(0, dp(14), 0, dp(8));
        root.addView(protectionSwitch);

        root.addView(label("Fulfillment"));
        fulfillmentGroup = new RadioGroup(this);
        fulfillmentGroup.setOrientation(RadioGroup.HORIZONTAL);

        RadioButton delivery = new RadioButton(this);
        delivery.setId(View.generateViewId());
        delivery.setText("Delivery");
        delivery.setTextSize(16);

        RadioButton pickup = new RadioButton(this);
        pickup.setId(View.generateViewId());
        pickup.setText("Pickup");
        pickup.setTextSize(16);

        fulfillmentGroup.addView(delivery);
        fulfillmentGroup.addView(pickup);
        fulfillmentGroup.check(delivery.getId());
        root.addView(fulfillmentGroup);

        TextView deliveryNote = text("Delivery: $199.99, or $99.99 with Protection. Delivery is tax-free.", 13, false);
        deliveryNote.setPadding(0, dp(3), 0, dp(12));
        root.addView(deliveryNote);

        root.addView(label("Sales tax %"));
        taxRateInput = new EditText(this);
        taxRateInput.setText("9.475");
        taxRateInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        taxRateInput.setTextSize(18);
        root.addView(taxRateInput);

        TextView rule = text("24/36/48/72 months require Protection OR 15% down. Minimum purchase is checked against the pre-tax purchase amount: merchandise + protection + delivery.", 14, true);
        rule.setPadding(0, dp(16), 0, dp(12));
        root.addView(rule);

        summaryView = text("", 17, false);
        summaryView.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(summaryView);

        TextView paymentHeader = text("PAYMENT TERMS", 20, true);
        paymentHeader.setPadding(0, dp(18), 0, dp(8));
        root.addView(paymentHeader);

        termsView = text("", 17, false);
        termsView.setLineSpacing(0, 1.25f);
        root.addView(termsView);

        TextView disclaimer = text("Estimated payments assume equal monthly payments and 0% promotional financing. Final approval and lender terms control.", 12, false);
        disclaimer.setPadding(0, dp(18), 0, 0);
        root.addView(disclaimer);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { recalculate(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        priceInput.addTextChangedListener(watcher);
        taxRateInput.addTextChangedListener(watcher);
        protectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> recalculate());
        fulfillmentGroup.setOnCheckedChangeListener((group, checkedId) -> recalculate());

        setContentView(scroll);
    }

    private void beginScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST
            );
        }
    }

    private void launchScanner() {
        try {
            scanStatus.setText("Opening live price scanner...");
            Intent intent = new Intent(this, ScannerActivity.class);
            startActivityForResult(intent, SCANNER_REQUEST);
        } catch (Exception e) {
            scanStatus.setText("Could not open scanner. You can still enter the price manually.");
            Toast.makeText(this, "Could not open scanner", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchScanner();
            } else {
                scanStatus.setText("Camera permission is needed for scanning.");
                Toast.makeText(this, "Enable Camera permission to scan prices.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.getRepeatCount() == 0) {
            beginScan();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCANNER_REQUEST && resultCode == RESULT_OK && data != null) {
            double price = data.getDoubleExtra(ScannerActivity.EXTRA_PRICE, -1);
            if (price > 0) {
                setPrice(price);
            }
        }
    }

    private TextView label(String value) {
        TextView v = text(value, 14, true);
        v.setPadding(0, dp(12), 0, dp(3));
        return v;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private void setPrice(double value) {
        priceInput.setText(String.format(Locale.US, "%.2f", value));
        priceInput.setSelection(priceInput.getText().length());
        scanStatus.setText("Scanned " + money.format(value));
    }

    private void recalculate() {
        if (summaryView == null || termsView == null || priceInput == null) return;

        double merchandise = parse(priceInput.getText().toString());
        double taxRate = parse(taxRateInput.getText().toString()) / 100.0;
        boolean protection = protectionSwitch.isChecked();
        boolean delivery = isDeliverySelected();

        double protectionCost = protection ? round(merchandise * PROTECTION_RATE) : 0.0;
        double taxableSubtotal = merchandise + protectionCost;
        double tax = round(taxableSubtotal * taxRate);
        double deliveryCost = delivery ? (protection ? PROTECTION_DELIVERY : STANDARD_DELIVERY) : 0.0;
        double qualifyingPreTax = round(merchandise + protectionCost + deliveryCost);
        double total = round(taxableSubtotal + tax + deliveryCost);

        StringBuilder summary = new StringBuilder();
        summary.append("Merchandise: ").append(money.format(merchandise)).append("\n");
        summary.append("Protection: ").append(protection ? money.format(protectionCost) : "Not selected").append("\n");
        summary.append("Tax: ").append(money.format(tax)).append("\n");
        summary.append(delivery ? "Delivery: " : "Pickup: ").append(money.format(deliveryCost)).append("\n");
        summary.append("Qualifying pre-tax purchase: ").append(money.format(qualifyingPreTax)).append("\n");
        summary.append("TOTAL: ").append(money.format(total));
        summaryView.setText(summary.toString());

        if (merchandise <= 0) {
            termsView.setText("Scan or enter a merchandise price to see payment terms.");
            return;
        }

        StringBuilder terms = new StringBuilder();
        appendSimpleTerm(terms, 6, total);
        appendSimpleTerm(terms, 12, total);
        appendLongTerm(terms, 24, 2000, qualifyingPreTax, merchandise, total, protection);
        appendLongTerm(terms, 36, 3000, qualifyingPreTax, merchandise, total, protection);
        appendLongTerm(terms, 48, 4000, qualifyingPreTax, merchandise, total, protection);
        appendLongTerm(terms, 72, 5000, qualifyingPreTax, merchandise, total, protection);
        termsView.setText(terms.toString().trim());
    }

    private void appendSimpleTerm(StringBuilder b, int months, double total) {
        b.append(months).append(" MONTHS  •  ")
                .append(money.format(round(total / months))).append("/mo")
                .append("\nNo minimum • No required down/protection\n\n");
    }

    private void appendLongTerm(StringBuilder b, int months, double minimum, double qualifyingPreTax, double merchandise, double total, boolean protection) {
        b.append(months).append(" MONTHS  •  ");
        if (qualifyingPreTax < minimum) {
            b.append("NOT AVAILABLE\nMinimum pre-tax purchase: ").append(money.format(minimum)).append("\n")
                    .append("Current qualifying amount: ").append(money.format(qualifyingPreTax)).append("\n\n");
            return;
        }

        if (protection) {
            b.append(money.format(round(total / months))).append("/mo\n")
                    .append("Protection satisfies financing requirement\n\n");
        } else {
            double down = round(merchandise * 0.15);
            double financed = Math.max(0, round(total - down));
            b.append(money.format(round(financed / months))).append("/mo\n")
                    .append("15% down today: ").append(money.format(down)).append("\n")
                    .append("Estimated financed balance: ").append(money.format(financed)).append("\n\n");
        }
    }

    private boolean isDeliverySelected() {
        RadioButton selected = findViewById(fulfillmentGroup.getCheckedRadioButtonId());
        return selected != null && "Delivery".contentEquals(selected.getText());
    }

    private double parse(String value) {
        try {
            return Double.parseDouble(value.replace(",", "").replace("$", "").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
