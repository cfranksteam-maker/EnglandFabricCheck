package com.furnituremall.paymentscanner;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
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

import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScannerActivity extends AppCompatActivity {
    public static final String EXTRA_PRICE = "scanned_price";

    private PreviewView previewView;
    private TextView statusView;
    private TextView debugView;
    private LinearLayout candidateContainer;
    private ExecutorService cameraExecutor;
    private TextRecognizer recognizer;
    private ImageCapture imageCapture;

    private volatile boolean processing = false;
    private volatile boolean takingStill = false;
    private volatile long lastAnalyzeAt = 0L;

    private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);

    private static final Pattern MONEY_WITH_SYMBOL = Pattern.compile(
            "[\\$S]\\s*([0-9OIl|]{1,6}(?:[, ]?[0-9OIl|]{3})?)(?:[\\.,\\s]([0-9OIl|]{2}))?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DECIMAL_PRICE = Pattern.compile(
            "(?<![0-9])([0-9OIl|]{1,6})[\\.,]([0-9OIl|]{2})(?![0-9])",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WHOLE_PRICE = Pattern.compile(
            "(?<![0-9A-Z])([0-9OIl|]{2,6})(?![0-9A-Z])",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SPLIT_CENTS = Pattern.compile(
            "(?<![0-9])([0-9OIl|]{2,6})\\s+([0-9OIl|]{2})(?![0-9])",
            Pattern.CASE_INSENSITIVE
    );

    private static class Candidate {
        final double value;
        int score;

        Candidate(double value, int score) {
            this.value = value;
            this.score = score;
        }
    }

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
        title.setText("Fill the screen with the PRICE");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Then tap READ PRICE or press Volume Down. A full-resolution photo will be read.");
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
        bottom.setBackgroundColor(0xEEFFFFFF);

        statusView = new TextView(this);
        statusView.setText("Ready — aim at the price.");
        statusView.setTextSize(17);
        statusView.setTextColor(Color.BLACK);
        statusView.setTypeface(statusView.getTypeface(), android.graphics.Typeface.BOLD);
        bottom.addView(statusView);

        Button readButton = new Button(this);
        readButton.setText("READ PRICE");
        readButton.setTextSize(20);
        readButton.setMinHeight(dp(58));
        readButton.setOnClickListener(v -> captureAndRead());
        bottom.addView(readButton);

        candidateContainer = new LinearLayout(this);
        candidateContainer.setOrientation(LinearLayout.VERTICAL);
        candidateContainer.setPadding(0, dp(8), 0, 0);
        bottom.addView(candidateContainer);

        debugView = new TextView(this);
        debugView.setText("");
        debugView.setTextSize(11);
        debugView.setTextColor(Color.DKGRAY);
        debugView.setMaxLines(4);
        debugView.setPadding(0, dp(8), 0, 0);
        bottom.addView(debugView);

        TextView note = new TextView(this);
        note.setText("If several numbers are found, tap the actual merchandise price.");
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

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1920, 1080))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                        analysis
                );

                runOnUiThread(() -> statusView.setText("Ready — aim at the price, then press READ PRICE."));
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Could not start camera: " + e.getClass().getSimpleName()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureAndRead() {
        if (takingStill || imageCapture == null) return;

        takingStill = true;
        statusView.setText("Capturing full-resolution tag...");
        candidateContainer.removeAllViews();
        debugView.setText("");

        File photo = new File(getCacheDir(), "price_scan_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(photo).build();

        imageCapture.takePicture(
                options,
                cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        runOnUiThread(() -> statusView.setText("Reading price..."));
                        try {
                            InputImage input = InputImage.fromFilePath(
                                    ScannerActivity.this,
                                    Uri.fromFile(photo)
                            );

                            recognizer.process(input)
                                    .addOnSuccessListener(result -> {
                                        List<Candidate> candidates = extractCandidates(result);
                                        runOnUiThread(() -> {
                                            showCandidates(candidates);
                                            showRecognizedText(result.getText());
                                        });
                                    })
                                    .addOnFailureListener(e ->
                                            runOnUiThread(() -> {
                                                statusView.setText("Could not read text. Move closer and try again.");
                                                debugView.setText(e.getClass().getSimpleName());
                                            })
                                    )
                                    .addOnCompleteListener(task -> {
                                        takingStill = false;
                                        photo.delete();
                                    });
                        } catch (Exception e) {
                            takingStill = false;
                            photo.delete();
                            runOnUiThread(() -> {
                                statusView.setText("Could not read captured image.");
                                debugView.setText(e.getClass().getSimpleName());
                            });
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        takingStill = false;
                        runOnUiThread(() -> {
                            statusView.setText("Camera capture failed. Try again.");
                            debugView.setText(exception.getMessage());
                        });
                    }
                }
        );
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy imageProxy) {
        long now = System.currentTimeMillis();

        if (takingStill || processing || now - lastAnalyzeAt < 700) {
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
                    List<Candidate> candidates = extractCandidates(result);
                    if (!candidates.isEmpty()) {
                        runOnUiThread(() -> showCandidates(candidates));
                    }
                })
                .addOnCompleteListener(task -> {
                    processing = false;
                    imageProxy.close();
                });
    }

    private List<Candidate> extractCandidates(Text result) {
        Map<Long, Candidate> unique = new LinkedHashMap<>();

        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String raw = line.getText();
                int height = lineHeight(line);
                String lower = raw.toLowerCase(Locale.US);

                int base = Math.min(80, Math.max(0, height / 2));
                if (lower.contains("price") || lower.contains("sale")
                        || lower.contains("now") || lower.contains("today")
                        || lower.contains("special") || lower.contains("our price")) {
                    base += 80;
                }

                addPatternCandidates(raw, MONEY_WITH_SYMBOL, true, base + 100, unique);
                addPatternCandidates(raw, DECIMAL_PRICE, true, base + 45, unique);
                addPatternCandidates(raw, SPLIT_CENTS, true, base + 35, unique);
                addWholeCandidates(raw, base + 15, unique);
            }
        }

        if (unique.isEmpty()) {
            String all = result.getText();
            addPatternCandidates(all, MONEY_WITH_SYMBOL, true, 80, unique);
            addPatternCandidates(all, DECIMAL_PRICE, true, 40, unique);
            addPatternCandidates(all, SPLIT_CENTS, true, 35, unique);
            addWholeCandidates(all, 10, unique);
        }

        List<Candidate> values = new ArrayList<>(unique.values());
        Collections.sort(values, (a, b) -> {
            int byScore = Integer.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            return Double.compare(b.value, a.value);
        });

        if (values.size() > 8) {
            values = new ArrayList<>(values.subList(0, 8));
        }

        return values;
    }

    private void addPatternCandidates(
            String text,
            Pattern pattern,
            boolean hasOptionalCents,
            int score,
            Map<Long, Candidate> out
    ) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String whole = matcher.group(1);
            String cents = null;
            if (hasOptionalCents && matcher.groupCount() >= 2) {
                cents = matcher.group(2);
            }
            addValue(whole, cents, score, out);
        }
    }

    private void addWholeCandidates(String text, int score, Map<Long, Candidate> out) {
        Matcher matcher = WHOLE_PRICE.matcher(text);
        while (matcher.find()) {
            addValue(matcher.group(1), null, score, out);
        }
    }

    private void addValue(
            String whole,
            String cents,
            int score,
            Map<Long, Candidate> out
    ) {
        try {
            String cleanWhole = normalizeDigits(whole);
            String cleanCents = cents == null ? null : normalizeDigits(cents);

            if (cleanWhole.isEmpty()) return;

            double value = Double.parseDouble(cleanWhole);

            if (cleanCents != null && cleanCents.length() == 2) {
                value += Integer.parseInt(cleanCents) / 100.0;
            }

            value = Math.round(value * 100.0) / 100.0;

            if (value < 10.0 || value > 100000.0) return;

            long key = Math.round(value * 100.0);
            Candidate existing = out.get(key);

            if (existing == null) {
                out.put(key, new Candidate(value, score));
            } else if (score > existing.score) {
                existing.score = score;
            }
        } catch (Exception ignored) {}
    }

    private String normalizeDigits(String value) {
        if (value == null) return "";
        return value
                .replace("O", "0")
                .replace("o", "0")
                .replace("I", "1")
                .replace("l", "1")
                .replace("|", "1")
                .replace(",", "")
                .replace(".", "")
                .replace(" ", "")
                .replace("$", "")
                .replace("S", "");
    }

    private int lineHeight(Text.Line line) {
        Rect box = line.getBoundingBox();
        return box == null ? 0 : box.height();
    }

    private void showCandidates(List<Candidate> prices) {
        candidateContainer.removeAllViews();

        if (prices.isEmpty()) {
            statusView.setText("No price found — move closer so the price fills the screen, then try again.");
            return;
        }

        statusView.setText("Possible price found — tap the correct amount");

        for (Candidate candidate : prices) {
            Button button = new Button(this);
            button.setText(money.format(candidate.value));
            button.setTextSize(20);
            button.setAllCaps(false);
            button.setMinHeight(dp(52));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, dp(4), 0, dp(4));
            button.setLayoutParams(params);

            button.setOnClickListener(v -> returnPrice(candidate.value));
            candidateContainer.addView(button);
        }
    }

    private void showRecognizedText(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            debugView.setText("OCR saw no text.");
            return;
        }

        String compact = raw.replace("\n", " • ").trim();
        if (compact.length() > 240) {
            compact = compact.substring(0, 240) + "...";
        }

        debugView.setText("OCR saw: " + compact);
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
            captureAndRead();
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
