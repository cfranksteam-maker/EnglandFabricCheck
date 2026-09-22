package com.furnituremall.paymentscanner;

import android.content.Intent;
import android.graphics.Color;
import android.media.Image;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScannerActivity extends AppCompatActivity {
    public static final String EXTRA_PRICE = "scanned_price";

    private PreviewView previewView;
    private TextView statusView;
    private LinearLayout candidateContainer;
    private ExecutorService cameraExecutor;
    private TextRecognizer recognizer;
    private volatile boolean processing = false;
    private volatile long lastAnalyzeAt = 0L;

    private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);

    private static final Pattern DOLLAR_PRICE = Pattern.compile(
            "[\\$S]\\s*([0-9]{1,3}(?:[, ]?[0-9]{3})+|[0-9]{1,6})(?:[\\.,](\\d{2}))?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPACED_CENTS = Pattern.compile(
            "[\\$S]\\s*([0-9]{1,6})\\s+(\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DECIMAL_PRICE = Pattern.compile(
            "(?<!\\d)([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,6})[\\.,](\\d{2})(?!\\d)"
    );
    private static final Pattern KEYWORD_WHOLE_PRICE = Pattern.compile(
            "(?<!\\d)([0-9]{2,6})(?!\\d)"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Scan Price");
        buildUi();

        cameraExecutor = Executors.newSingleThreadExecutor();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        startCamera();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(16), dp(14), dp(16), dp(14));
        top.setBackgroundColor(0xCC000000);

        TextView title = new TextView(this);
        title.setText("Point the camera at the PRICE");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Move close enough that the price fills much of the screen. The app reads continuously.");
        hint.setTextSize(14);
        hint.setTextColor(Color.WHITE);
        top.addView(hint);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        topParams.gravity = Gravity.TOP;
        root.addView(top, topParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), dp(12), dp(14), dp(16));
        bottom.setBackgroundColor(0xEFFFFFFF);

        statusView = new TextView(this);
        statusView.setText("Looking for a price...");
        statusView.setTextSize(17);
        statusView.setTextColor(Color.BLACK);
        statusView.setTypeface(statusView.getTypeface(), android.graphics.Typeface.BOLD);
        bottom.addView(statusView);

        candidateContainer = new LinearLayout(this);
        candidateContainer.setOrientation(LinearLayout.VERTICAL);
        candidateContainer.setPadding(0, dp(8), 0, 0);
        bottom.addView(candidateContainer);

        TextView note = new TextView(this);
        note.setText("If more than one price appears, tap the correct one.");
        note.setTextSize(13);
        note.setTextColor(Color.DKGRAY);
        note.setPadding(0, dp(8), 0, 0);
        bottom.addView(note);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bottomParams.gravity = Gravity.BOTTOM;
        root.addView(bottom, bottomParams);

        setContentView(root);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                );
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Could not start camera."));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy imageProxy) {
        long now = System.currentTimeMillis();
        if (processing || now - lastAnalyzeAt < 250) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        processing = true;
        lastAnalyzeAt = now;

        InputImage input = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.getImageInfo().getRotationDegrees()
        );

        recognizer.process(input)
                .addOnSuccessListener(result -> {
                    List<Double> candidates = extractCandidates(result);
                    runOnUiThread(() -> showCandidates(candidates));
                })
                .addOnFailureListener(e ->
                        runOnUiThread(() -> statusView.setText("Still scanning... move closer and hold steady."))
                )
                .addOnCompleteListener(task -> {
                    processing = false;
                    imageProxy.close();
                });
    }

    private List<Double> extractCandidates(Text result) {
        Set<Double> unique = new LinkedHashSet<>();

        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText();
                addDollarCandidates(value, unique);
                addDecimalCandidates(value, unique);

                String lower = value.toLowerCase(Locale.US);
                if (lower.contains("price") || lower.contains("sale") || lower.contains("now")
                        || lower.contains("today") || lower.contains("special") || lower.contains("our")) {
                    addKeywordWholeCandidates(value, unique);
                }
            }
        }

        if (unique.isEmpty()) {
            addDollarCandidates(result.getText(), unique);
            addDecimalCandidates(result.getText(), unique);
        }

        List<Double> values = new ArrayList<>(unique);
        if (values.size() > 6) {
            values = new ArrayList<>(values.subList(0, 6));
        }
        return values;
    }

    private void addDollarCandidates(String text, Set<Double> out) {
        Matcher spaced = SPACED_CENTS.matcher(text);
        while (spaced.find()) {
            addValue(spaced.group(1), spaced.group(2), out);
        }

        Matcher matcher = DOLLAR_PRICE.matcher(text);
        while (matcher.find()) {
            addValue(matcher.group(1), matcher.group(2), out);
        }
    }

    private void addDecimalCandidates(String text, Set<Double> out) {
        Matcher matcher = DECIMAL_PRICE.matcher(text);
        while (matcher.find()) {
            addValue(matcher.group(1), matcher.group(2), out);
        }
    }

    private void addKeywordWholeCandidates(String text, Set<Double> out) {
        Matcher matcher = KEYWORD_WHOLE_PRICE.matcher(text);
        while (matcher.find()) {
            addValue(matcher.group(1), null, out);
        }
    }

    private void addValue(String whole, String cents, Set<Double> out) {
        try {
            String cleanWhole = whole.replace(",", "").replace(" ", "");
            double value = Double.parseDouble(cleanWhole);
            if (cents != null && !cents.isEmpty()) {
                value += Integer.parseInt(cents) / 100.0;
            }
            value = Math.round(value * 100.0) / 100.0;

            if (value >= 1.0 && value <= 100000.0) {
                out.add(value);
            }
        } catch (Exception ignored) {}
    }

    private void showCandidates(List<Double> prices) {
        candidateContainer.removeAllViews();

        if (prices.isEmpty()) {
            statusView.setText("Looking for a price...");
            return;
        }

        statusView.setText("Price found — tap the correct amount");

        for (double price : prices) {
            Button button = new Button(this);
            button.setText(money.format(price));
            button.setTextSize(20);
            button.setAllCaps(false);
            button.setMinHeight(dp(52));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, dp(4), 0, dp(4));
            button.setLayoutParams(params);

            button.setOnClickListener(v -> returnPrice(price));
            candidateContainer.addView(button);
        }
    }

    private void returnPrice(double price) {
        Intent result = new Intent();
        result.putExtra(EXTRA_PRICE, price);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.getRepeatCount() == 0) {
            statusView.setText("Scanning... hold steady on the price.");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (recognizer != null) {
            recognizer.close();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
