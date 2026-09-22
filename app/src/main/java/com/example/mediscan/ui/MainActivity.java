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

    // Version 2.0 Feature Action Bar & Controls
    private Button btnAction3dSlicer;
    private Button btnActionDecisionGuide;
    private Button btnActionWalkthrough;
    private LinearLayout layoutSpatialKernel;
    private MaterialButtonToggleGroup toggleSpatialKernel;

    // Parameters UI
    private TextView tvParamLabel;
    private TextView tvParamValue;
    private SeekBar seekbarParam;
    private TextView tvParamHint;
    private LinearLayout layoutStructuringElement;
    private MaterialButtonToggleGroup toggleSe;

    // State
    private Bitmap currentStaticBitmap;
    private Bitmap lastProcessedBitmap;
    private int currentSampleIndex = 1;
    private final String[] sampleNames = {
            "1.3.1 Gamma-Ray (PET Scan)",
            "1.3.2 X-Ray (Chest Radiograph)",
            "1.3.3 UV Band (Fluorescence)",
            "1.3.4 Visible Band (Histology)",
            "1.3.4 Infrared Band (Thermography)",
            "1.3.5 Microwave Band (Radar)",
            "1.3.6 Radio Band (Brain MRI)",
            "1.3.7 Acoustic (Ultrasound)",
            "1.3.7 Electron Microscopy (SEM)"
    };
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

        btnAction3dSlicer = findViewById(R.id.btn_action_3d_slicer);
        btnActionDecisionGuide = findViewById(R.id.btn_action_decision_guide);
        btnActionWalkthrough = findViewById(R.id.btn_action_walkthrough);
        layoutSpatialKernel = findViewById(R.id.layout_spatial_kernel);
        toggleSpatialKernel = findViewById(R.id.toggle_spatial_kernel);

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

        // Version 2.0 Feature Action Buttons
        if (btnAction3dSlicer != null) {
            btnAction3dSlicer.setOnClickListener(v -> open3dIntensitySlicer());
        }
        if (btnActionDecisionGuide != null) {
            btnActionDecisionGuide.setOnClickListener(v -> openDiagnosticGuide());
        }
        if (btnActionWalkthrough != null) {
            btnActionWalkthrough.setOnClickListener(v -> openWalkthroughTour());
        }

        // Auto-show walkthrough on initial launch of Version 2.0
        android.content.SharedPreferences prefs = getSharedPreferences("mediscan_prefs", MODE_PRIVATE);
        if (!prefs.getBoolean("has_seen_v2_walkthrough", false)) {
            prefs.edit().putBoolean("has_seen_v2_walkthrough", true).apply();
            openWalkthroughTour();
        }

        // Chapter 2: Digital Image Fundamentals bindings
        bindAlgorithmButton(R.id.btn_algo_quantize, MedicalImageEngine.Algorithm.BIT_DEPTH_QUANTIZATION, "Bit-Depth Quantization");
        bindAlgorithmButton(R.id.btn_algo_subsampling, MedicalImageEngine.Algorithm.SPATIAL_SUBSAMPLING, "Spatial Subsampling");
        bindAlgorithmButton(R.id.btn_algo_bitplane, MedicalImageEngine.Algorithm.BIT_PLANE_SLICING, "Bit-Plane Slicing");
        bindAlgorithmButton(R.id.btn_algo_mach_bands, MedicalImageEngine.Algorithm.MACH_BANDS_ILLUSION, "Mach Bands Perception");
        bindAlgorithmButton(R.id.btn_algo_contrast, MedicalImageEngine.Algorithm.SIMULTANEOUS_CONTRAST, "Simultaneous Contrast");
        bindAlgorithmButton(R.id.btn_algo_dist_euclidean, MedicalImageEngine.Algorithm.DISTANCE_TRANSFORM_EUCLIDEAN, "Euclidean Distance (De)");
        bindAlgorithmButton(R.id.btn_algo_dist_d4, MedicalImageEngine.Algorithm.DISTANCE_TRANSFORM_D4, "City-Block Distance (D4)");
        bindAlgorithmButton(R.id.btn_algo_dist_d8, MedicalImageEngine.Algorithm.DISTANCE_TRANSFORM_D8, "Chessboard Distance (D8)");
        bindAlgorithmButton(R.id.btn_algo_dsa, MedicalImageEngine.Algorithm.IMAGE_SUBTRACTION_DSA, "DSA Difference Imaging");

        // Algorithm button bindings (Chapter 3, 4, 10, 9)
        bindAlgorithmButton(R.id.btn_algo_original, MedicalImageEngine.Algorithm.ORIGINAL, "Original (BT.601)");
        bindAlgorithmButton(R.id.btn_algo_log, MedicalImageEngine.Algorithm.LOG_TRANSFORM, "Logarithmic Transform");
        bindAlgorithmButton(R.id.btn_algo_gamma, MedicalImageEngine.Algorithm.GAMMA_CORRECTION, "Power-Law (Gamma)");
        bindAlgorithmButton(R.id.btn_algo_hist_eq, MedicalImageEngine.Algorithm.HISTOGRAM_EQUALIZATION, "Histogram Equalization");

        bindAlgorithmButton(R.id.btn_algo_gaussian, MedicalImageEngine.Algorithm.GAUSSIAN_BLUR, "Gaussian Blur (Spatial)");
        bindAlgorithmButton(R.id.btn_algo_median, MedicalImageEngine.Algorithm.MEDIAN_FILTER, "Median Filter (Speckle)");
        bindAlgorithmButton(R.id.btn_algo_sobel, MedicalImageEngine.Algorithm.SOBEL_GRADIENTS, "Sobel Edge Gradients");
        bindAlgorithmButton(R.id.btn_algo_laplacian, MedicalImageEngine.Algorithm.LAPLACIAN_SHARPEN, "Laplacian Sharpening");
        bindAlgorithmButton(R.id.btn_algo_correlation, MedicalImageEngine.Algorithm.SPATIAL_CORRELATION, "Spatial Correlation (w ★ f)");
        bindAlgorithmButton(R.id.btn_algo_convolution, MedicalImageEngine.Algorithm.SPATIAL_CONVOLUTION, "Spatial Convolution (w * f)");

        bindAlgorithmButton(R.id.btn_algo_fft_spectrum, MedicalImageEngine.Algorithm.FFT_SPECTRUM, "2D FFT Magnitude Spectrum");
        bindAlgorithmButton(R.id.btn_algo_ilpf, MedicalImageEngine.Algorithm.FFT_IDEAL_LOWPASS, "Ideal Lowpass (ILPF)");
        bindAlgorithmButton(R.id.btn_algo_glpf, MedicalImageEngine.Algorithm.FFT_GAUSSIAN_LOWPASS, "Gaussian Lowpass (GLPF)");

        // Chapter 5: Image Restoration & Noise Degradation
        bindAlgorithmButton(R.id.btn_algo_noise_sp, MedicalImageEngine.Algorithm.NOISE_SALT_AND_PEPPER, "Salt & Pepper Noise");
        bindAlgorithmButton(R.id.btn_algo_noise_gaussian, MedicalImageEngine.Algorithm.NOISE_GAUSSIAN, "Gaussian Noise");
        bindAlgorithmButton(R.id.btn_algo_restore_arithmetic, MedicalImageEngine.Algorithm.RESTORE_ARITHMETIC_MEAN, "Arithmetic Mean Filter");
        bindAlgorithmButton(R.id.btn_algo_restore_geometric, MedicalImageEngine.Algorithm.RESTORE_GEOMETRIC_MEAN, "Geometric Mean Filter");
        bindAlgorithmButton(R.id.btn_algo_restore_harmonic, MedicalImageEngine.Algorithm.RESTORE_HARMONIC_MEAN, "Harmonic Mean Filter");
        bindAlgorithmButton(R.id.btn_algo_restore_contraharmonic, MedicalImageEngine.Algorithm.RESTORE_CONTRAHARMONIC_MEAN, "Contraharmonic Filter (Q)");
        bindAlgorithmButton(R.id.btn_algo_restore_alpha_trimmed, MedicalImageEngine.Algorithm.RESTORE_ALPHA_TRIMMED_MEAN, "Alpha-Trimmed Mean");
        bindAlgorithmButton(R.id.btn_algo_restore_wiener, MedicalImageEngine.Algorithm.RESTORE_ADAPTIVE_WIENER, "Adaptive Wiener Filter");

        // Chapter 6: Color Image Processing & Pseudocolor
        bindAlgorithmButton(R.id.btn_algo_pseudocolor_rainbow, MedicalImageEngine.Algorithm.PSEUDOCOLOR_RAINBOW_JET, "Rainbow / Jet Colormap");
        bindAlgorithmButton(R.id.btn_algo_pseudocolor_thermal, MedicalImageEngine.Algorithm.PSEUDOCOLOR_THERMAL_HOT, "Medical Thermal Hot");
        bindAlgorithmButton(R.id.btn_algo_pseudocolor_pet, MedicalImageEngine.Algorithm.PSEUDOCOLOR_PET_HOT_METAL, "PET Hot Metal Uptake");
        bindAlgorithmButton(R.id.btn_algo_pseudocolor_slicing, MedicalImageEngine.Algorithm.PSEUDOCOLOR_INTENSITY_SLICING, "Intensity Slicing (Isophotes)");
        bindAlgorithmButton(R.id.btn_algo_hsi_hue, MedicalImageEngine.Algorithm.HSI_HUE_EXTRACTION, "HSI: Hue Component");
        bindAlgorithmButton(R.id.btn_algo_hsi_saturation, MedicalImageEngine.Algorithm.HSI_SATURATION_EXTRACTION, "HSI: Saturation Component");
        bindAlgorithmButton(R.id.btn_algo_hsi_intensity, MedicalImageEngine.Algorithm.HSI_INTENSITY_EXTRACTION, "HSI: Intensity Component");

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

        // Spatial kernel selector for Correlation & Convolution
        if (toggleSpatialKernel != null) {
            toggleSpatialKernel.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (isChecked) {
                    if (checkedId == R.id.btn_kernel_asym) {
                        imageEngine.getParameters().spatialKernelType = 0;
                    } else if (checkedId == R.id.btn_kernel_gradient) {
                        imageEngine.getParameters().spatialKernelType = 1;
                    } else if (checkedId == R.id.btn_kernel_sharpen) {
                        imageEngine.getParameters().spatialKernelType = 2;
                    } else if (checkedId == R.id.btn_kernel_gaussian) {
                        imageEngine.getParameters().spatialKernelType = 3;
                    } else if (checkedId == R.id.btn_kernel_edge) {
                        imageEngine.getParameters().spatialKernelType = 4;
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
        if (layoutSpatialKernel != null) {
            layoutSpatialKernel.setVisibility(View.GONE);
        }

        switch (algorithm) {
            case SPATIAL_CORRELATION:
                if (layoutSpatialKernel != null) layoutSpatialKernel.setVisibility(View.VISIBLE);
                tvParamLabel.setText("Spatial Correlation w(x,y) ★ f(x,y):");
                tvParamValue.setText("Predefined Kernel");
                tvParamHint.setText("Gonzalez & Woods 3.4.1: Slides unrotated kernel w(s,t) directly across f(x,y). Use 'Asymmetric Wedge' to contrast with Convolution!");
                break;

            case SPATIAL_CONVOLUTION:
                if (layoutSpatialKernel != null) layoutSpatialKernel.setVisibility(View.VISIBLE);
                tvParamLabel.setText("Spatial Convolution w(x,y) ∗ f(x,y):");
                tvParamValue.setText("Rotated 180° Kernel");
                tvParamHint.setText("Gonzalez & Woods 3.4.1: Rotates kernel 180° w(-s,-t) before sliding. Notice opposite directional weighting vs Correlation!");
                break;

            case BIT_DEPTH_QUANTIZATION:
                tvParamLabel.setText("Quantization Bit Depth (k):");
                seekbarParam.setMax(7); // 1 to 7 bits
                seekbarParam.setProgress(imageEngine.getParameters().bitDepth);
                tvParamValue.setText(String.format(Locale.US, "%d bits (%d gray levels)",
                        imageEngine.getParameters().bitDepth, 1 << imageEngine.getParameters().bitDepth));
                tvParamHint.setText("Gonzalez & Woods 2.4.2: As bit depth decreases, false contouring (isophote banding) becomes prominent.");
                break;

            case SPATIAL_SUBSAMPLING:
                tvParamLabel.setText("Subsampling Grid Factor (N):");
                seekbarParam.setMax(5); // 0:2x, 1:4x, 2:8x, 3:16x, 4:32x, 5:64x
                int subIdx = 2; // default 8x
                if (imageEngine.getParameters().spatialSubsampleFactor <= 2) subIdx = 0;
                else if (imageEngine.getParameters().spatialSubsampleFactor <= 4) subIdx = 1;
                else if (imageEngine.getParameters().spatialSubsampleFactor <= 8) subIdx = 2;
                else if (imageEngine.getParameters().spatialSubsampleFactor <= 16) subIdx = 3;
                else if (imageEngine.getParameters().spatialSubsampleFactor <= 32) subIdx = 4;
                else subIdx = 5;
                seekbarParam.setProgress(subIdx);
                tvParamValue.setText(String.format(Locale.US, "%dx%d pixel grid",
                        imageEngine.getParameters().spatialSubsampleFactor, imageEngine.getParameters().spatialSubsampleFactor));
                tvParamHint.setText("Gonzalez & Woods 2.4.1: Decreasing spatial sampling resolution causes pixelation and checkerboard artifacts.");
                break;

            case BIT_PLANE_SLICING:
                tvParamLabel.setText("Target Bit Plane (n):");
                seekbarParam.setMax(7); // 0 to 7
                seekbarParam.setProgress(imageEngine.getParameters().bitPlane);
                tvParamValue.setText(String.format(Locale.US, "Bit %d (%s)",
                        imageEngine.getParameters().bitPlane,
                        imageEngine.getParameters().bitPlane == 7 ? "MSB - High Energy" :
                                (imageEngine.getParameters().bitPlane == 0 ? "LSB - Noise" : "Intermediate")));
                tvParamHint.setText("Gonzalez & Woods 2.6.2 & 3.2.4: Bit 7 contains the dominant structural information; bit 0 contains subtle noise.");
                break;

            case DISTANCE_TRANSFORM_EUCLIDEAN:
            case DISTANCE_TRANSFORM_D4:
            case DISTANCE_TRANSFORM_D8:
                tvParamLabel.setText("Seed Feature Threshold (T):");
                seekbarParam.setMax(255);
                seekbarParam.setProgress(imageEngine.getParameters().globalThreshold);
                tvParamValue.setText(String.format(Locale.US, "T = %d", imageEngine.getParameters().globalThreshold));
                tvParamHint.setText("Gonzalez & Woods 2.5: Distance transform creates isodistance contours (Circles for De, Diamonds for D4, Squares for D8).");
                break;

            case IMAGE_SUBTRACTION_DSA:
                tvParamLabel.setText("DSA Subtraction Gain (c):");
                seekbarParam.setMax(400);
                seekbarParam.setProgress((int) (imageEngine.getParameters().dsaContrastBoost * 100));
                tvParamValue.setText(String.format(Locale.US, "Gain = %.2fx", imageEngine.getParameters().dsaContrastBoost));
                tvParamHint.setText("Gonzalez & Woods 2.6.3: Digital Subtraction Angiography eliminates static bone mask, isolating arterial contrast.");
                break;

            case MACH_BANDS_ILLUSION:
                tvParamLabel.setText("Perceptual Visual Stimulus:");
                seekbarParam.setMax(100);
                seekbarParam.setProgress(50);
                tvParamValue.setText("8 Uniform Bands");
                tvParamHint.setText("Gonzalez & Woods 2.1.3: Retinal lateral inhibition produces illusory scalloped peaks at step transitions.");
                break;

            case SIMULTANEOUS_CONTRAST:
                tvParamLabel.setText("Perceptual Visual Stimulus:");
                seekbarParam.setMax(100);
                seekbarParam.setProgress(50);
                tvParamValue.setText("Gray = 128 in all 3");
                tvParamHint.setText("Gonzalez & Woods 2.1.3: Identical central square intensity is perceived darker on light bg and lighter on dark bg.");
                break;

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

            case NOISE_SALT_AND_PEPPER:
                tvParamLabel.setText("Impulse Noise Density (Pa + Pb):");
                seekbarParam.setMax(40);
                int spProg = (int) ((imageEngine.getParameters().saltProb + imageEngine.getParameters().pepperProb) * 100);
                seekbarParam.setProgress(Math.max(2, spProg));
                tvParamValue.setText(String.format(Locale.US, "%.0f%% Noise Density", (imageEngine.getParameters().saltProb + imageEngine.getParameters().pepperProb) * 100));
                tvParamHint.setText("Gonzalez & Woods 5.2.4: Bipolar impulse noise replacing pixels with 0 (pepper) and 255 (salt).");
                break;

            case NOISE_GAUSSIAN:
                tvParamLabel.setText("Gaussian Noise Std Dev (σ):");
                seekbarParam.setMax(80);
                seekbarParam.setProgress((int) imageEngine.getParameters().gaussianNoiseStdDev);
                tvParamValue.setText(String.format(Locale.US, "σ = %.0f", imageEngine.getParameters().gaussianNoiseStdDev));
                tvParamHint.setText("Gonzalez & Woods 5.2.1: Additive zero-mean Gaussian electronic noise due to sensor thermal agitation.");
                break;

            case RESTORE_ARITHMETIC_MEAN:
            case RESTORE_GEOMETRIC_MEAN:
            case RESTORE_HARMONIC_MEAN:
                tvParamLabel.setText("Neighborhood Window Radius (r):");
                seekbarParam.setMax(3);
                seekbarParam.setProgress(imageEngine.getParameters().meanFilterRadius);
                int win = 2 * imageEngine.getParameters().meanFilterRadius + 1;
                tvParamValue.setText(String.format(Locale.US, "%dx%d Window", win, win));
                tvParamHint.setText("Gonzalez & Woods 5.3.1: Geometric mean preserves details better than arithmetic mean; harmonic mean excels for salt noise.");
                break;

            case RESTORE_CONTRAHARMONIC_MEAN:
                tvParamLabel.setText("Contraharmonic Order (Q):");
                seekbarParam.setMax(60);
                seekbarParam.setProgress((int) ((imageEngine.getParameters().contraharmonicQ + 3.0f) * 10));
                tvParamValue.setText(String.format(Locale.US, "Q = %.1f (%s)",
                        imageEngine.getParameters().contraharmonicQ,
                        imageEngine.getParameters().contraharmonicQ > 0 ? "Filters Pepper" : "Filters Salt"));
                tvParamHint.setText("Gonzalez & Woods 5.3.1: Q > 0 eliminates pepper noise; Q < 0 eliminates salt noise; Q=0 is arithmetic mean.");
                break;

            case RESTORE_ALPHA_TRIMMED_MEAN:
                tvParamLabel.setText("Alpha-Trimmed Count (d):");
                seekbarParam.setMax(8);
                seekbarParam.setProgress(imageEngine.getParameters().alphaTrimD);
                tvParamValue.setText(String.format(Locale.US, "d = %d trimmed", imageEngine.getParameters().alphaTrimD));
                tvParamHint.setText("Gonzalez & Woods 5.3.2: Deletes d/2 highest and d/2 lowest pixel values; ideal for mixed Gaussian + salt/pepper noise.");
                break;

            case RESTORE_ADAPTIVE_WIENER:
                tvParamLabel.setText("Noise Variance Estimate (σ_η²):");
                seekbarParam.setMax(1000);
                seekbarParam.setProgress((int) imageEngine.getParameters().wienerNoiseVariance);
                tvParamValue.setText(String.format(Locale.US, "σ_η² = %.0f", imageEngine.getParameters().wienerNoiseVariance));
                tvParamHint.setText("Gonzalez & Woods 5.8: Minimum Mean Square Error restoration based on local image statistics and noise variance.");
                break;

            case PSEUDOCOLOR_RAINBOW_JET:
            case PSEUDOCOLOR_THERMAL_HOT:
            case PSEUDOCOLOR_PET_HOT_METAL:
            case PSEUDOCOLOR_INTENSITY_SLICING:
                tvParamLabel.setText("Pseudocolor Diagnostic Palette:");
                seekbarParam.setMax(100);
                seekbarParam.setProgress(50);
                tvParamValue.setText("Standard Map");
                tvParamHint.setText("Gonzalez & Woods 6.3: False-color transformation assigning distinct perceptual colors to enhance radiologic feature discriminability.");
                break;

            case HSI_HUE_EXTRACTION:
            case HSI_SATURATION_EXTRACTION:
            case HSI_INTENSITY_EXTRACTION:
                tvParamLabel.setText("HSI Color Space Component:");
                seekbarParam.setMax(100);
                seekbarParam.setProgress(50);
                tvParamValue.setText("Decoupled Channel");
                tvParamHint.setText("Gonzalez & Woods 6.2: HSI space decouples intensity from chrominance. Switch to Histopathology (Biopsy) scan or Gallery for color decomposition.");
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
            case BIT_DEPTH_QUANTIZATION:
                params.bitDepth = Math.max(1, progress);
                tvParamValue.setText(String.format(Locale.US, "%d bits (%d gray levels)",
                        params.bitDepth, 1 << params.bitDepth));
                break;

            case SPATIAL_SUBSAMPLING:
                int[] gridFactors = {2, 4, 8, 16, 32, 64};
                int factorIdx = Math.max(0, Math.min(gridFactors.length - 1, progress));
                params.spatialSubsampleFactor = gridFactors[factorIdx];
                tvParamValue.setText(String.format(Locale.US, "%dx%d pixel grid",
                        params.spatialSubsampleFactor, params.spatialSubsampleFactor));
                break;

            case BIT_PLANE_SLICING:
                params.bitPlane = Math.max(0, Math.min(7, progress));
                tvParamValue.setText(String.format(Locale.US, "Bit %d (%s)",
                        params.bitPlane,
                        params.bitPlane == 7 ? "MSB - High Energy" :
                                (params.bitPlane == 0 ? "LSB - Noise" : "Intermediate")));
                break;

            case DISTANCE_TRANSFORM_EUCLIDEAN:
            case DISTANCE_TRANSFORM_D4:
            case DISTANCE_TRANSFORM_D8:
                params.globalThreshold = progress;
                tvParamValue.setText(String.format(Locale.US, "T = %d", params.globalThreshold));
                break;

            case IMAGE_SUBTRACTION_DSA:
                params.dsaContrastBoost = Math.max(0.5f, progress / 100.0f);
                tvParamValue.setText(String.format(Locale.US, "Gain = %.2fx", params.dsaContrastBoost));
                break;

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

            case NOISE_SALT_AND_PEPPER: {
                float totalNoise = Math.max(0.01f, progress / 100.0f);
                params.saltProb = totalNoise / 2.0f;
                params.pepperProb = totalNoise / 2.0f;
                tvParamValue.setText(String.format(Locale.US, "%.0f%% Noise Density", totalNoise * 100));
                break;
            }

            case NOISE_GAUSSIAN:
                params.gaussianNoiseStdDev = Math.max(1.0f, (float) progress);
                tvParamValue.setText(String.format(Locale.US, "σ = %.0f", params.gaussianNoiseStdDev));
                break;

            case RESTORE_ARITHMETIC_MEAN:
            case RESTORE_GEOMETRIC_MEAN:
            case RESTORE_HARMONIC_MEAN:
                params.meanFilterRadius = Math.max(1, progress);
                int win = 2 * params.meanFilterRadius + 1;
                tvParamValue.setText(String.format(Locale.US, "%dx%d Window", win, win));
                break;

            case RESTORE_CONTRAHARMONIC_MEAN:
                params.contraharmonicQ = (progress / 10.0f) - 3.0f;
                String qRole = params.contraharmonicQ > 0.05f ? "Filters Pepper" : (params.contraharmonicQ < -0.05f ? "Filters Salt" : "Arithmetic Mean (Q=0)");
                tvParamValue.setText(String.format(Locale.US, "Q = %.1f (%s)", params.contraharmonicQ, qRole));
                break;

            case RESTORE_ALPHA_TRIMMED_MEAN:
                params.alphaTrimD = progress;
                tvParamValue.setText(String.format(Locale.US, "d = %d trimmed", params.alphaTrimD));
                break;

            case RESTORE_ADAPTIVE_WIENER:
                params.wienerNoiseVariance = Math.max(1.0f, (float) progress);
                tvParamValue.setText(String.format(Locale.US, "σ_η² = %.0f", params.wienerNoiseVariance));
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
                        lastProcessedBitmap = processedBmp;
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

    private void open3dIntensitySlicer() {
        Bitmap source = lastProcessedBitmap != null ? lastProcessedBitmap : currentStaticBitmap;
        if (source == null) {
            Toast.makeText(this, "No image buffer available for 3D reconstruction.", Toast.LENGTH_SHORT).show();
            return;
        }
        int[] grayscale = ImageUtils.bitmapToGrayscaleIntArray(source);
        Intensity3DDialog dialog = Intensity3DDialog.newInstance(grayscale, source.getWidth(), source.getHeight());
        dialog.show(getSupportFragmentManager(), "intensity_3d");
    }

    private void openDiagnosticGuide() {
        DiagnosticGuideDialog guide = DiagnosticGuideDialog.newInstance();
        guide.setOnApplySolutionListener((algorithm, sampleScanIndex, customParam) -> {
            switchToStaticMode();
            loadSampleScan(sampleScanIndex);
            selectAlgorithm(algorithm, algorithm.name());

            if (customParam > 0.001f) {
                applyGuideCustomParam(algorithm, customParam);
            }
        });
        guide.show(getSupportFragmentManager(), "diagnostic_guide");
    }

    private void applyGuideCustomParam(MedicalImageEngine.Algorithm algo, float param) {
        MedicalImageEngine.Parameters p = imageEngine.getParameters();
        if (algo == MedicalImageEngine.Algorithm.GAMMA_CORRECTION) {
            p.gamma = param;
            seekbarParam.setProgress((int) (param * 100));
        } else if (algo == MedicalImageEngine.Algorithm.RESTORE_ADAPTIVE_WIENER) {
            p.wienerNoiseVariance = param;
            seekbarParam.setProgress((int) param);
        } else if (algo == MedicalImageEngine.Algorithm.RESTORE_CONTRAHARMONIC_MEAN) {
            p.contraharmonicQ = param;
            seekbarParam.setProgress((int) ((param + 3.0f) * 10));
        }
        reprocessStaticScan();
    }

    private void openWalkthroughTour() {
        WalkthroughDialog tour = WalkthroughDialog.newInstance();
        tour.show(getSupportFragmentManager(), "walkthrough");
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
            lastProcessedBitmap = processedBmp;
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
