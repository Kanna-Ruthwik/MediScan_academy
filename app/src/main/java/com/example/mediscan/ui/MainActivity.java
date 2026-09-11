package com.example.mediscan.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.R;
import com.example.mediscan.engine.MedicalImageEngine;
import com.example.mediscan.engine.MorphologyProcessor;
import com.example.mediscan.ui.views.HistogramView;
import com.example.mediscan.utils.ImageUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * Main Workstation Activity for MediScan Academy.
 * Integrates CameraX zero-copy Y-plane streaming, interactive static medical scan loading,
 * Gonzalez & Woods algorithmic control surface, dynamic parameter tuning,
 * and live 256-bin intensity histogram visualization.
 */
public class MainActivity extends AppCompatActivity {

    private enum InputMode {
        LIVE_CAMERA,
        STATIC_SCAN
    }

    private InputMode currentInputMode = InputMode.STATIC_SCAN;
    private MedicalImageEngine imageEngine;

    // View References
    private PreviewView cameraPreviewView;
    private ImageView imageViewport;
    private TextView tvModeBadge;
    private TextView tvAlgorithmBadge;
    private TextView tvFpsHud;
    private TextView tvHistogramStats;
    private HistogramView histogramView;
    private ProgressBar pbProcessing;

    private MaterialButton btnModeToggle;
    private ImageButton btnSampleScans;
    private ImageButton btnOpenGallery;
    private ImageButton btnTheoryInfo;

    // Parameters UI
    private TextView tvParamLabel;
    private TextView tvParamValue;
    private SeekBar seekbarParam;
    private TextView tvParamHint;
    private LinearLayout layoutStructuringElement;
    private MaterialButtonToggleGroup toggleSe;

    // State
    private Bitmap currentStaticBitmap;
    private int currentSampleIndex = 0;
    private final String[] sampleNames = {"Chest X-Ray", "Brain MRI", "CT Bone Scan", "Ultrasound"};
    private ProcessCameraProvider cameraProvider;
    private byte[] cameraYBuffer;

    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<String> galleryLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageEngine = new MedicalImageEngine();

        initViews();
        setupLaunchers();
        setupListeners();

        // Start in static medical scan mode with synthetic Chest X-Ray
        loadSampleScan(0);
    }

    private void initViews() {
        cameraPreviewView = findViewById(R.id.camera_preview_view);
        imageViewport = findViewById(R.id.image_viewport);
        tvModeBadge = findViewById(R.id.tv_mode_badge);
        tvAlgorithmBadge = findViewById(R.id.tv_algorithm_active_badge);
        tvFpsHud = findViewById(R.id.tv_fps_hud);
        tvHistogramStats = findViewById(R.id.tv_histogram_stats);
        histogramView = findViewById(R.id.histogram_view);
        pbProcessing = findViewById(R.id.pb_processing);

        btnModeToggle = findViewById(R.id.btn_mode_toggle);
        btnSampleScans = findViewById(R.id.btn_sample_scans);
        btnOpenGallery = findViewById(R.id.btn_open_gallery);
        btnTheoryInfo = findViewById(R.id.btn_theory_info);

        tvParamLabel = findViewById(R.id.tv_param_label);
        tvParamValue = findViewById(R.id.tv_param_value);
        seekbarParam = findViewById(R.id.seekbar_param);
        tvParamHint = findViewById(R.id.tv_param_hint);
        layoutStructuringElement = findViewById(R.id.layout_structuring_element);
        toggleSe = findViewById(R.id.toggle_se);

        // Configure default algorithm
        selectAlgorithm(MedicalImageEngine.Algorithm.HISTOGRAM_EQUALIZATION, "Histogram Equalization");
    }

    private void setupLaunchers() {
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        switchToCameraMode();
                    } else {
                        Toast.makeText(this, "Camera permission denied. Staying in Scan mode.", Toast.LENGTH_SHORT).show();
                        switchToStaticMode();
                    }
                }
        );

        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        loadBitmapFromUri(uri);
                    }
                }
        );
    }

    private void setupListeners() {
        btnModeToggle.setOnClickListener(v -> {
            if (currentInputMode == InputMode.STATIC_SCAN) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    switchToCameraMode();
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
                }
            } else {
                switchToStaticMode();
            }
        });

        btnSampleScans.setOnClickListener(v -> showSampleScanDialog());

        btnOpenGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));

        btnTheoryInfo.setOnClickListener(v -> {
            TheoryBottomSheetFragment sheet = TheoryBottomSheetFragment.newInstance();
            sheet.show(getSupportFragmentManager(), TheoryBottomSheetFragment.TAG);
        });

        // Algorithm button bindings
        bindAlgorithmButton(R.id.btn_algo_original, MedicalImageEngine.Algorithm.ORIGINAL, "Original (BT.601)");
        bindAlgorithmButton(R.id.btn_algo_log, MedicalImageEngine.Algorithm.LOG_TRANSFORM, "Logarithmic Transform");
        bindAlgorithmButton(R.id.btn_algo_gamma, MedicalImageEngine.Algorithm.GAMMA_CORRECTION, "Power-Law (Gamma)");
        bindAlgorithmButton(R.id.btn_algo_hist_eq, MedicalImageEngine.Algorithm.HISTOGRAM_EQUALIZATION, "Histogram Equalization");

        bindAlgorithmButton(R.id.btn_algo_gaussian, MedicalImageEngine.Algorithm.GAUSSIAN_BLUR, "Gaussian Blur (Spatial)");
        bindAlgorithmButton(R.id.btn_algo_median, MedicalImageEngine.Algorithm.MEDIAN_FILTER, "Median Filter (Speckle)");
        bindAlgorithmButton(R.id.btn_algo_sobel, MedicalImageEngine.Algorithm.SOBEL_GRADIENTS, "Sobel Edge Gradients");
        bindAlgorithmButton(R.id.btn_algo_laplacian, MedicalImageEngine.Algorithm.LAPLACIAN_SHARPEN, "Laplacian Sharpening");

        bindAlgorithmButton(R.id.btn_algo_fft_spectrum, MedicalImageEngine.Algorithm.FFT_SPECTRUM, "2D FFT Magnitude Spectrum");
        bindAlgorithmButton(R.id.btn_algo_ilpf, MedicalImageEngine.Algorithm.FFT_IDEAL_LOWPASS, "Ideal Lowpass (ILPF)");
        bindAlgorithmButton(R.id.btn_algo_glpf, MedicalImageEngine.Algorithm.FFT_GAUSSIAN_LOWPASS, "Gaussian Lowpass (GLPF)");

        bindAlgorithmButton(R.id.btn_algo_global_thresh, MedicalImageEngine.Algorithm.GLOBAL_THRESHOLD, "Global Threshold");
        bindAlgorithmButton(R.id.btn_algo_otsu, MedicalImageEngine.Algorithm.OTSU_BINARIZATION, "Otsu's Binarization");
        bindAlgorithmButton(R.id.btn_algo_adaptive_thresh, MedicalImageEngine.Algorithm.ADAPTIVE_THRESHOLD, "Adaptive Threshold");

        bindAlgorithmButton(R.id.btn_algo_erosion, MedicalImageEngine.Algorithm.BINARY_EROSION, "Binary Erosion (A ⊖ B)");
        bindAlgorithmButton(R.id.btn_algo_dilation, MedicalImageEngine.Algorithm.BINARY_DILATION, "Binary Dilation (A ⊕ B)");
        bindAlgorithmButton(R.id.btn_algo_opening, MedicalImageEngine.Algorithm.MORPH_OPENING, "Morphological Opening");
        bindAlgorithmButton(R.id.btn_algo_closing, MedicalImageEngine.Algorithm.MORPH_CLOSING, "Morphological Closing");
        bindAlgorithmButton(R.id.btn_algo_boundary, MedicalImageEngine.Algorithm.MORPH_BOUNDARY, "Boundary Extraction");

        // Dynamic Parameter SeekBar Listener
        seekbarParam.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updateParameterFromProgress(progress);
                    if (currentInputMode == InputMode.STATIC_SCAN) {
                        reprocessStaticScan();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Structuring element toggle group
        if (toggleSe != null) {
            toggleSe.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (isChecked) {
                    if (checkedId == R.id.btn_se_cross) {
                        imageEngine.getParameters().structuringElement =
                                MorphologyProcessor.StructuringElementType.CROSS;
                    } else if (checkedId == R.id.btn_se_disk) {
                        imageEngine.getParameters().structuringElement =
                                MorphologyProcessor.StructuringElementType.DISK;
                    } else {
                        imageEngine.getParameters().structuringElement =
                                MorphologyProcessor.StructuringElementType.SQUARE;
                    }
                    if (currentInputMode == InputMode.STATIC_SCAN) {
                        reprocessStaticScan();
                    }
                }
            });
        }
    }

    private void bindAlgorithmButton(int viewId, MedicalImageEngine.Algorithm algorithm, String displayName) {
        Button btn = findViewById(viewId);
        if (btn != null) {
            btn.setOnClickListener(v -> selectAlgorithm(algorithm, displayName));
        }
    }

    private void selectAlgorithm(MedicalImageEngine.Algorithm algorithm, String displayName) {
        imageEngine.setAlgorithm(algorithm);
        tvAlgorithmBadge.setText(displayName.toUpperCase(Locale.US));

        configureParameterControls(algorithm);

        if (currentInputMode == InputMode.STATIC_SCAN) {
            reprocessStaticScan();
        }
    }

    private void configureParameterControls(MedicalImageEngine.Algorithm algorithm) {
        layoutStructuringElement.setVisibility(View.GONE);

        switch (algorithm) {
            case GAMMA_CORRECTION:
                tvParamLabel.setText("Power-Law Exponent (γ):");
                seekbarParam.setMax(300); // 0.10 to 3.00
                int gammaProg = (int) (imageEngine.getParameters().gamma * 100);
                seekbarParam.setProgress(Math.max(10, Math.min(300, gammaProg)));
                tvParamValue.setText(String.format(Locale.US, "γ = %.2f", imageEngine.getParameters().gamma));
                tvParamHint.setText("γ < 1 expands dark details; γ > 1 expands bright details and compresses shadows.");
                break;

            case LOG_TRANSFORM:
                tvParamLabel.setText("Log Scaling Constant (c):");
                seekbarParam.setMax(200);
                seekbarParam.setProgress((int) (imageEngine.getParameters().logFactor * 100));
                tvParamValue.setText(String.format(Locale.US, "c = %.2f", imageEngine.getParameters().logFactor));
                tvParamHint.setText("Compresses broad dynamic range; essential for high dynamic range radiology.");
                break;

            case GAUSSIAN_BLUR:
                tvParamLabel.setText("Gaussian Filter Mask Size:");
                seekbarParam.setMax(1); // 0: 3x3, 1: 5x5
                seekbarParam.setProgress(imageEngine.getParameters().gaussianKernelSize >= 5 ? 1 : 0);
                tvParamValue.setText(imageEngine.getParameters().gaussianKernelSize >= 5 ? "5 x 5 Kernel" : "3 x 3 Kernel");
                tvParamHint.setText("Standard deviation isotropic Gaussian spatial kernel.");
                break;

            case LAPLACIAN_SHARPEN:
                tvParamLabel.setText("Laplacian Edge Boost (c):");
                seekbarParam.setMax(200);
                seekbarParam.setProgress((int) (imageEngine.getParameters().laplacianStrength * 100));
                tvParamValue.setText(String.format(Locale.US, "Boost = %.2f", imageEngine.getParameters().laplacianStrength));
                tvParamHint.setText("High-boost 2D second derivative edge sharpening: g(x,y) = f(x,y) - c·∇²f(x,y).");
                break;

            case FFT_IDEAL_LOWPASS:
            case FFT_GAUSSIAN_LOWPASS:
                tvParamLabel.setText("Cutoff Frequency Radius (D₀):");
                seekbarParam.setMax(100);
                seekbarParam.setProgress((int) imageEngine.getParameters().fftCutoffD0);
                tvParamValue.setText(String.format(Locale.US, "D₀ = %.0f px", imageEngine.getParameters().fftCutoffD0));
                tvParamHint.setText("Radial cutoff frequency in centered Fourier frequency domain.");
                break;

            case GLOBAL_THRESHOLD:
                tvParamLabel.setText("Global Binarization Threshold (T):");
                seekbarParam.setMax(255);
                seekbarParam.setProgress(imageEngine.getParameters().globalThreshold);
                tvParamValue.setText(String.format(Locale.US, "T = %d", imageEngine.getParameters().globalThreshold));
                tvParamHint.setText("Manual intensity partition: g(x,y) = 255 if f(x,y) ≥ T else 0.");
                break;

            case ADAPTIVE_THRESHOLD:
                tvParamLabel.setText("Integral Box Radius (W):");
                seekbarParam.setMax(25);
                seekbarParam.setProgress(imageEngine.getParameters().adaptiveRadius);
                tvParamValue.setText(String.format(Locale.US, "Radius = %d px", imageEngine.getParameters().adaptiveRadius));
                tvParamHint.setText("O(1) 2D Summed-Area Table local neighborhood average segmentation.");
                break;

            case BINARY_EROSION:
            case BINARY_DILATION:
            case MORPH_OPENING:
            case MORPH_CLOSING:
            case MORPH_BOUNDARY:
                layoutStructuringElement.setVisibility(View.VISIBLE);
                tvParamLabel.setText("Binary Threshold Pre-pass (T):");
                seekbarParam.setMax(255);
                seekbarParam.setProgress(imageEngine.getParameters().globalThreshold);
                tvParamValue.setText(String.format(Locale.US, "T = %d", imageEngine.getParameters().globalThreshold));
                tvParamHint.setText("Structuring Element (SE) scans binary foreground to extract geometry.");
                break;

            case ORIGINAL:
            case HISTOGRAM_EQUALIZATION:
            case MEDIAN_FILTER:
            case SOBEL_GRADIENTS:
            case FFT_SPECTRUM:
            case OTSU_BINARIZATION:
            default:
                tvParamLabel.setText("Auto-Tuned Parameter:");
                seekbarParam.setMax(100);
                seekbarParam.setProgress(50);
                tvParamValue.setText("Optimal");
                tvParamHint.setText("Fully automated mathematical transformation per Gonzalez & Woods specification.");
                break;
        }
    }

    private void updateParameterFromProgress(int progress) {
        MedicalImageEngine.Algorithm algo = imageEngine.getAlgorithm();
        MedicalImageEngine.Parameters params = imageEngine.getParameters();

        switch (algo) {
            case GAMMA_CORRECTION:
                params.gamma = Math.max(0.1f, progress / 100.0f);
                tvParamValue.setText(String.format(Locale.US, "γ = %.2f", params.gamma));
                break;

            case LOG_TRANSFORM:
                params.logFactor = Math.max(0.1f, progress / 100.0f);
                tvParamValue.setText(String.format(Locale.US, "c = %.2f", params.logFactor));
                break;

            case GAUSSIAN_BLUR:
                params.gaussianKernelSize = progress >= 1 ? 5 : 3;
                tvParamValue.setText(params.gaussianKernelSize == 5 ? "5 x 5 Kernel" : "3 x 3 Kernel");
                break;

            case LAPLACIAN_SHARPEN:
                params.laplacianStrength = progress / 100.0f;
                tvParamValue.setText(String.format(Locale.US, "Boost = %.2f", params.laplacianStrength));
                break;

            case FFT_IDEAL_LOWPASS:
            case FFT_GAUSSIAN_LOWPASS:
                params.fftCutoffD0 = Math.max(5.0, progress);
                tvParamValue.setText(String.format(Locale.US, "D₀ = %.0f px", params.fftCutoffD0));
                break;

            case GLOBAL_THRESHOLD:
            case BINARY_EROSION:
            case BINARY_DILATION:
            case MORPH_OPENING:
            case MORPH_CLOSING:
            case MORPH_BOUNDARY:
                params.globalThreshold = progress;
                tvParamValue.setText(String.format(Locale.US, "T = %d", params.globalThreshold));
                break;

            case ADAPTIVE_THRESHOLD:
                params.adaptiveRadius = Math.max(2, progress);
                tvParamValue.setText(String.format(Locale.US, "Radius = %d px", params.adaptiveRadius));
                break;

            default:
                break;
        }
    }

    private void switchToCameraMode() {
        currentInputMode = InputMode.LIVE_CAMERA;
        btnModeToggle.setText("Gallery");
        btnModeToggle.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_gallery));
        tvModeBadge.setText("LIVE CAMERA ANALYSIS");

        cameraPreviewView.setVisibility(View.GONE); // We render the processed DSP bitmap directly on imageViewport
        imageViewport.setVisibility(View.VISIBLE);

        startCameraX();
    }

    private void switchToStaticMode() {
        currentInputMode = InputMode.STATIC_SCAN;
        btnModeToggle.setText("Live Cam");
        btnModeToggle.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_camera));
        tvModeBadge.setText(String.format("STATIC SCAN: %s", sampleNames[currentSampleIndex].toUpperCase(Locale.US)));

        stopCameraX();
        reprocessStaticScan();
    }

    private void startCameraX() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                // Build ImageAnalysis pipeline
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::analyzeCameraFrame);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Failed to start camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCameraX() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    /**
     * CameraX ImageAnalysis.Analyzer zero-copy consumer.
     * Extracts raw YUV_420_888 Y-plane bytes and sends to CPU engine.
     */
    private void analyzeCameraFrame(@NonNull ImageProxy imageProxy) {
        try {
            if (currentInputMode != InputMode.LIVE_CAMERA) return;

            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

            int requiredBufferSize = width * height;
            if (cameraYBuffer == null || cameraYBuffer.length != requiredBufferSize) {
                cameraYBuffer = new byte[requiredBufferSize];
            }

            // Zero-copy stride-aware Y-plane extraction
            ImageUtils.extractYPlaneFromImageProxy(imageProxy, cameraYBuffer);

            // Dispatch to DSP worker threads
            imageEngine.dispatchCameraFrame(
                    cameraYBuffer,
                    width,
                    height,
                    rotationDegrees,
                    (processedBmp, histogram, stats, latencyMs, fps) -> {
                        if (currentInputMode == InputMode.LIVE_CAMERA) {
                            imageViewport.setImageBitmap(processedBmp);
                            histogramView.updateHistogram(histogram, stats.otsuThreshold, stats.meanIntensity);
                            updateHud(latencyMs, fps, stats, processedBmp.getWidth(), processedBmp.getHeight());
                        }
                    }
            );

        } finally {
            // CRITICAL: Always release hardware buffer
            imageProxy.close();
        }
    }

    private void loadSampleScan(int index) {
        currentSampleIndex = index;
        currentInputMode = InputMode.STATIC_SCAN;
        tvModeBadge.setText(String.format("STATIC SCAN: %s", sampleNames[index].toUpperCase(Locale.US)));

        // Generate synthetic scan with 400x400 resolution
        currentStaticBitmap = ImageUtils.generateSyntheticMedicalScan(index, 400, 400);
        reprocessStaticScan();
    }

    private void showSampleScanDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Select Medical Modality Scan")
                .setItems(sampleNames, (dialog, which) -> {
                    switchToStaticMode();
                    loadSampleScan(which);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadBitmapFromUri(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, options);
            if (is != null) is.close();

            // Downscale to max 600x600 to prevent OOM
            int maxDimension = Math.max(options.outWidth, options.outHeight);
            int sampleSize = 1;
            while ((maxDimension / sampleSize) > 600) {
                sampleSize *= 2;
            }

            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;

            is = getContentResolver().openInputStream(uri);
            Bitmap decoded = BitmapFactory.decodeStream(is, null, options);
            if (is != null) is.close();

            if (decoded != null) {
                currentStaticBitmap = decoded;
                switchToStaticMode();
                tvModeBadge.setText("STATIC SCAN: GALLERY FILE");
                reprocessStaticScan();
            } else {
                Toast.makeText(this, "Could not decode selected image.", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Toast.makeText(this, "Error opening image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void reprocessStaticScan() {
        if (currentStaticBitmap == null) return;

        pbProcessing.setVisibility(View.VISIBLE);

        imageEngine.dispatchStaticScan(currentStaticBitmap, (processedBmp, histogram, stats, latencyMs, fps) -> {
            pbProcessing.setVisibility(View.GONE);
            imageViewport.setImageBitmap(processedBmp);
            histogramView.updateHistogram(histogram, stats.otsuThreshold, stats.meanIntensity);
            updateHud(latencyMs, 0.0f, stats, processedBmp.getWidth(), processedBmp.getHeight());
        });
    }

    private void updateHud(long latencyMs, float fps, ImageUtils.HistogramStats stats, int width, int height) {
        if (currentInputMode == InputMode.LIVE_CAMERA) {
            tvFpsHud.setText(String.format(
                    Locale.US,
                    "%dx%d | DSP: %d ms | %.1f FPS | Buffer: int[]",
                    width, height, latencyMs, fps
            ));
        } else {
            tvFpsHud.setText(String.format(
                    Locale.US,
                    "%dx%d | CPU DSP Latency: %d ms | Buffer: int[]",
                    width, height, latencyMs
            ));
        }

        tvHistogramStats.setText(String.format(
                Locale.US,
                "μ=%.1f  σ=%.1f  k*=%d",
                stats.meanIntensity, stats.standardDeviation, stats.otsuThreshold
        ));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCameraX();
        if (imageEngine != null) {
            imageEngine.shutdown();
        }
    }
}
