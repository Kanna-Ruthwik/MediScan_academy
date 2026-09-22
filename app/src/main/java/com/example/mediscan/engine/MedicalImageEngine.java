package com.example.mediscan.engine;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.example.mediscan.utils.ImageUtils;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU Dispatch Master for Medical Image & Digital Signal Processing algorithms.
 * Manages background thread pools, frame synchronization, latency calculation,
 * and UI thread dispatching.
 */
public class MedicalImageEngine {

    public enum Algorithm {
        ORIGINAL,
        BIT_DEPTH_QUANTIZATION,
        SPATIAL_SUBSAMPLING,
        BIT_PLANE_SLICING,
        MACH_BANDS_ILLUSION,
        SIMULTANEOUS_CONTRAST,
        DISTANCE_TRANSFORM_EUCLIDEAN,
        DISTANCE_TRANSFORM_D4,
        DISTANCE_TRANSFORM_D8,
        IMAGE_SUBTRACTION_DSA,
        LOG_TRANSFORM,
        GAMMA_CORRECTION,
        HISTOGRAM_EQUALIZATION,
        GAUSSIAN_BLUR,
        MEDIAN_FILTER,
        SOBEL_GRADIENTS,
        LAPLACIAN_SHARPEN,
        SPATIAL_CORRELATION,
        SPATIAL_CONVOLUTION,
        FFT_SPECTRUM,
        FFT_IDEAL_LOWPASS,
        FFT_GAUSSIAN_LOWPASS,
        // Chapter 5: Image Restoration & Noise Models
        NOISE_SALT_AND_PEPPER,
        NOISE_GAUSSIAN,
        RESTORE_ARITHMETIC_MEAN,
        RESTORE_GEOMETRIC_MEAN,
        RESTORE_HARMONIC_MEAN,
        RESTORE_CONTRAHARMONIC_MEAN,
        RESTORE_ALPHA_TRIMMED_MEAN,
        RESTORE_ADAPTIVE_WIENER,
        // Chapter 6: Color Image Processing
        PSEUDOCOLOR_RAINBOW_JET,
        PSEUDOCOLOR_THERMAL_HOT,
        PSEUDOCOLOR_PET_HOT_METAL,
        PSEUDOCOLOR_INTENSITY_SLICING,
        HSI_HUE_EXTRACTION,
        HSI_SATURATION_EXTRACTION,
        HSI_INTENSITY_EXTRACTION,
        // Chapter 10: Segmentation
        GLOBAL_THRESHOLD,
        OTSU_BINARIZATION,
        ADAPTIVE_THRESHOLD,
        // Chapter 9: Morphological Processing
        BINARY_EROSION,
        BINARY_DILATION,
        MORPH_OPENING,
        MORPH_CLOSING,
        MORPH_BOUNDARY
    }

    public static class Parameters {
        public int bitDepth = 3; // 1 to 7 bits (false contouring)
        public int spatialSubsampleFactor = 8; // 2 to 32 downsampling grid
        public int bitPlane = 7; // 0 (LSB) to 7 (MSB)
        public float dsaContrastBoost = 2.0f; // DSA contrast gain
        public FundamentalsProcessor.DistanceMetric distanceMetric =
                FundamentalsProcessor.DistanceMetric.EUCLIDEAN;

        // Chapter 5 Parameters
        public float saltProb = 0.05f;
        public float pepperProb = 0.05f;
        public float gaussianNoiseStdDev = 25.0f;
        public int meanFilterRadius = 1; // 1: 3x3, 2: 5x5
        public float contraharmonicQ = 1.5f;
        public int alphaTrimD = 4;
        public float wienerNoiseVariance = 400.0f;

        public float gamma = 1.6f;
        public float logFactor = 1.0f;
        public int gaussianKernelSize = 3;
        public float laplacianStrength = 0.8f;
        public int spatialKernelType = 0; // 0: Asymmetric (Fig 3.32), 1: Diagonal Gradient, 2: Sharpening, 3: Gaussian, 4: Horizontal Edge
        public double fftCutoffD0 = 36.0;
        public int globalThreshold = 128;
        public int adaptiveRadius = 9;
        public int adaptiveDeltaC = 6;
        public MorphologyProcessor.StructuringElementType structuringElement =
                MorphologyProcessor.StructuringElementType.SQUARE;
    }

    public interface ProcessCallback {
        void onProcessCompleted(
                @NonNull Bitmap processedBitmap,
                @NonNull int[] histogram,
                @NonNull ImageUtils.HistogramStats stats,
                long latencyMs,
                float fps
        );
    }

    private final ExecutorService executorService;
    private final Handler mainHandler;
    private final AtomicBoolean isBusy = new AtomicBoolean(false);

    private Algorithm currentAlgorithm = Algorithm.ORIGINAL;
    private final Parameters parameters = new Parameters();

    // Reusable buffers to minimize GC pressure during live camera analysis
    private int[] cachedGrayscale;
    private int[] cachedOutput;
    private int[] cachedHistogram = new int[256];
    private final ImageUtils.HistogramStats cachedStats = new ImageUtils.HistogramStats();
    private Bitmap cachedOutputBitmap;

    // FPS calculation tracking
    private long lastFrameTimestamp = 0;
    private float smoothedFps = 0.0f;

    public MedicalImageEngine() {
        int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.executorService = Executors.newFixedThreadPool(threadCount);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setAlgorithm(@NonNull Algorithm algorithm) {
        this.currentAlgorithm = algorithm;
        if (algorithm != Algorithm.NOISE_SALT_AND_PEPPER
                && algorithm != Algorithm.NOISE_GAUSSIAN
                && algorithm != Algorithm.RESTORE_ARITHMETIC_MEAN
                && algorithm != Algorithm.RESTORE_GEOMETRIC_MEAN
                && algorithm != Algorithm.RESTORE_HARMONIC_MEAN
                && algorithm != Algorithm.RESTORE_CONTRAHARMONIC_MEAN
                && algorithm != Algorithm.RESTORE_ALPHA_TRIMMED_MEAN
                && algorithm != Algorithm.RESTORE_ADAPTIVE_WIENER) {
            isDegradedUserSelected = false;
        }
    }

    public static boolean isColorAlgorithm(Algorithm algo) {
        if (algo == null) return false;
        switch (algo) {
            case PSEUDOCOLOR_RAINBOW_JET:
            case PSEUDOCOLOR_THERMAL_HOT:
            case PSEUDOCOLOR_PET_HOT_METAL:
            case PSEUDOCOLOR_INTENSITY_SLICING:
                return true;
            default:
                return false;
        }
    }

    private int[] persistentDegraded = null;
    private boolean isDegradedUserSelected = false;

    private void cacheDegraded(int[] degradedOutput) {
        if (persistentDegraded == null || persistentDegraded.length != degradedOutput.length) {
            persistentDegraded = new int[degradedOutput.length];
        }
        System.arraycopy(degradedOutput, 0, persistentDegraded, 0, degradedOutput.length);
        isDegradedUserSelected = true;
    }

    private int[] getDegradedOrInput(int[] input, int width, int height, int defaultNoiseType) {
        if (isDegradedUserSelected && persistentDegraded != null && persistentDegraded.length == input.length) {
            return persistentDegraded;
        }
        int[] synthetic = new int[input.length];
        switch (defaultNoiseType) {
            case 1: // Salt noise (Harmonic & negative Contraharmonic)
                RestorationProcessor.applySaltAndPepperNoise(input, synthetic, 0.12f, 0.0f);
                return synthetic;
            case 2: // Pepper noise (positive Contraharmonic)
                RestorationProcessor.applySaltAndPepperNoise(input, synthetic, 0.0f, 0.12f);
                return synthetic;
            case 3: // Mixed Gaussian + Salt & Pepper (Alpha-Trimmed)
                RestorationProcessor.applyGaussianNoise(input, synthetic, 0.0f, 18.0f);
                int[] mixed = new int[input.length];
                RestorationProcessor.applySaltAndPepperNoise(synthetic, mixed, 0.06f, 0.06f);
                return mixed;
            case 4: // Gaussian additive noise (Adaptive Wiener MMSE)
                float stdDev = (float) Math.sqrt(Math.max(4.0f, parameters.wienerNoiseVariance));
                RestorationProcessor.applyGaussianNoise(input, synthetic, 0.0f, stdDev);
                return synthetic;
            default:
                return input;
        }
    }

    public Algorithm getAlgorithm() {
        return currentAlgorithm;
    }

    public Parameters getParameters() {
        return parameters;
    }

    /**
     * Dispatches a live camera frame (raw Y-plane bytes) for DSP computation.
     * Skips frame if previous computation is still in-flight to prevent hardware starvation.
     */
    public boolean dispatchCameraFrame(
            @NonNull byte[] yBuffer,
            int width,
            int height,
            int rotationDegrees,
            @NonNull ProcessCallback callback) {

        if (!isBusy.compareAndSet(false, true)) {
            return false; // Throttled: CPU is currently busy
        }

        executorService.execute(() -> {
            try {
                long startTime = System.nanoTime();

                int dstWidth;
                int dstHeight;
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    dstWidth = height;
                    dstHeight = width;
                } else {
                    dstWidth = width;
                    dstHeight = height;
                }

                int totalPixels = dstWidth * dstHeight;
                ensureBuffers(totalPixels, dstWidth, dstHeight);

                // Rotate and extract luminance into cachedGrayscale
                extractRotatedY(yBuffer, width, height, rotationDegrees, dstWidth, dstHeight, cachedGrayscale);

                // Run DSP pipeline
                executePipeline(cachedGrayscale, null, cachedOutput, dstWidth, dstHeight);

                // Reconstruct ARGB bitmap
                updateOutputBitmap(cachedOutput, dstWidth, dstHeight);

                // Compute histogram & statistical metrics
                ImageUtils.computeHistogramAndStats(cachedOutput, cachedHistogram, cachedStats);

                long latencyMs = (System.nanoTime() - startTime) / 1_000_000L;
                computeFps();

                Bitmap resultBmp = cachedOutputBitmap;
                float currentFps = smoothedFps;

                mainHandler.post(() -> callback.onProcessCompleted(
                        resultBmp,
                        cachedHistogram,
                        cachedStats,
                        latencyMs,
                        currentFps
                ));

            } finally {
                isBusy.set(false);
            }
        });

        return true;
    }

    /**
     * Dispatches a static medical scan Bitmap (e.g. from gallery or sample scan).
     * Guaranteed execution (queued if busy).
     */
    public void dispatchStaticScan(@NonNull Bitmap sourceBitmap, @NonNull ProcessCallback callback) {
        executorService.execute(() -> {
            long startTime = System.nanoTime();

            int width = sourceBitmap.getWidth();
            int height = sourceBitmap.getHeight();
            int totalPixels = width * height;

            // Extract ARGB pixels if color source
            int[] rgbPixels = new int[totalPixels];
            sourceBitmap.getPixels(rgbPixels, 0, width, 0, 0, width, height);

            // Extract ITU-R BT.601 luminance
            int[] grayscale = ImageUtils.bitmapToGrayscaleIntArray(sourceBitmap);
            int[] output = new int[totalPixels];

            executePipeline(grayscale, rgbPixels, output, width, height);

            Bitmap outputBitmap;
            if (isColorAlgorithm(currentAlgorithm)) {
                outputBitmap = Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888);
            } else {
                outputBitmap = ImageUtils.grayscaleIntArrayToBitmap(output, width, height);
            }

            int[] histogram = new int[256];
            ImageUtils.HistogramStats stats = new ImageUtils.HistogramStats();
            ImageUtils.computeHistogramAndStats(output, histogram, stats);

            long latencyMs = (System.nanoTime() - startTime) / 1_000_000L;

            mainHandler.post(() -> callback.onProcessCompleted(
                    outputBitmap,
                    histogram,
                    stats,
                    latencyMs,
                    0.0f
            ));
        });
    }

    private void executePipeline(int[] input, int[] rgbInput, int[] output, int width, int height) {
        switch (currentAlgorithm) {
            case BIT_DEPTH_QUANTIZATION:
                FundamentalsProcessor.applyBitDepthQuantization(input, output, parameters.bitDepth);
                break;

            case SPATIAL_SUBSAMPLING:
                FundamentalsProcessor.applySpatialSubsampling(input, output, width, height, parameters.spatialSubsampleFactor);
                break;

            case BIT_PLANE_SLICING:
                FundamentalsProcessor.applyBitPlaneSlicing(input, output, parameters.bitPlane);
                break;

            case MACH_BANDS_ILLUSION:
                FundamentalsProcessor.generateMachBands(output, width, height);
                break;

            case SIMULTANEOUS_CONTRAST:
                FundamentalsProcessor.generateSimultaneousContrast(output, width, height);
                break;

            case DISTANCE_TRANSFORM_EUCLIDEAN:
                FundamentalsProcessor.computeDistanceTransform(
                        input, output, width, height,
                        FundamentalsProcessor.DistanceMetric.EUCLIDEAN,
                        parameters.globalThreshold
                );
                break;

            case DISTANCE_TRANSFORM_D4:
                FundamentalsProcessor.computeDistanceTransform(
                        input, output, width, height,
                        FundamentalsProcessor.DistanceMetric.CITY_BLOCK_D4,
                        parameters.globalThreshold
                );
                break;

            case DISTANCE_TRANSFORM_D8:
                FundamentalsProcessor.computeDistanceTransform(
                        input, output, width, height,
                        FundamentalsProcessor.DistanceMetric.CHESSBOARD_D8,
                        parameters.globalThreshold
                );
                break;

            case IMAGE_SUBTRACTION_DSA:
                FundamentalsProcessor.applyDigitalSubtractionAngiography(input, output, width, height, parameters.dsaContrastBoost);
                break;

            case LOG_TRANSFORM:
                SpatialProcessor.applyLogTransform(input, output, parameters.logFactor);
                break;

            case GAMMA_CORRECTION:
                SpatialProcessor.applyGammaCorrection(input, output, parameters.gamma);
                break;

            case HISTOGRAM_EQUALIZATION:
                SpatialProcessor.applyHistogramEqualization(input, output);
                break;

            case GAUSSIAN_BLUR:
                SpatialProcessor.applyGaussianBlur(input, output, width, height, parameters.gaussianKernelSize);
                break;

            case MEDIAN_FILTER:
                SpatialProcessor.applyMedianFilter(input, output, width, height);
                break;

            case SOBEL_GRADIENTS:
                SpatialProcessor.applySobelFilter(input, output, width, height);
                break;

            case LAPLACIAN_SHARPEN:
                SpatialProcessor.applyLaplacianSharpening(input, output, width, height, parameters.laplacianStrength);
                break;

            case SPATIAL_CORRELATION: {
                float[][] kernel = SpatialProcessor.getPredefinedKernel(parameters.spatialKernelType);
                SpatialProcessor.applySpatialCorrelation(input, output, width, height, kernel);
                break;
            }

            case SPATIAL_CONVOLUTION: {
                float[][] kernel = SpatialProcessor.getPredefinedKernel(parameters.spatialKernelType);
                SpatialProcessor.applySpatialConvolution(input, output, width, height, kernel);
                break;
            }

            case FFT_SPECTRUM: {
                int fftSize = FrequencyProcessor.getValidFftDimension(Math.min(width, height), 256);
                int[] fftOut = new int[fftSize * fftSize];
                FrequencyProcessor.computeMagnitudeSpectrum(input, width, height, fftOut, fftSize);
                // Resize/fill back to full output
                scaleOrCenterToOutput(fftOut, fftSize, fftSize, output, width, height);
                break;
            }

            case FFT_IDEAL_LOWPASS: {
                int fftSize = FrequencyProcessor.getValidFftDimension(Math.min(width, height), 256);
                int[] fftOut = new int[fftSize * fftSize];
                FrequencyProcessor.applyFrequencyFilter(input, width, height, fftOut, fftSize, 0, parameters.fftCutoffD0);
                scaleOrCenterToOutput(fftOut, fftSize, fftSize, output, width, height);
                break;
            }

            case FFT_GAUSSIAN_LOWPASS: {
                int fftSize = FrequencyProcessor.getValidFftDimension(Math.min(width, height), 256);
                int[] fftOut = new int[fftSize * fftSize];
                FrequencyProcessor.applyFrequencyFilter(input, width, height, fftOut, fftSize, 1, parameters.fftCutoffD0);
                scaleOrCenterToOutput(fftOut, fftSize, fftSize, output, width, height);
                break;
            }

            // Chapter 5: Image Restoration & Noise Degradation Models
            case NOISE_SALT_AND_PEPPER:
                RestorationProcessor.applySaltAndPepperNoise(input, output, parameters.saltProb, parameters.pepperProb);
                cacheDegraded(output);
                break;

            case NOISE_GAUSSIAN:
                RestorationProcessor.applyGaussianNoise(input, output, 0.0f, parameters.gaussianNoiseStdDev);
                cacheDegraded(output);
                break;

            case RESTORE_ARITHMETIC_MEAN: {
                int[] src = getDegradedOrInput(input, width, height, 0);
                RestorationProcessor.applyArithmeticMeanFilter(src, output, width, height, parameters.meanFilterRadius);
                break;
            }

            case RESTORE_GEOMETRIC_MEAN: {
                int[] src = getDegradedOrInput(input, width, height, 0);
                RestorationProcessor.applyGeometricMeanFilter(src, output, width, height, parameters.meanFilterRadius);
                break;
            }

            case RESTORE_HARMONIC_MEAN: {
                // Harmonic mean specifically eliminates salt noise
                int[] src = getDegradedOrInput(input, width, height, 1);
                RestorationProcessor.applyHarmonicMeanFilter(src, output, width, height, parameters.meanFilterRadius);
                break;
            }

            case RESTORE_CONTRAHARMONIC_MEAN: {
                // Contraharmonic: Q > 0 eliminates pepper; Q < 0 eliminates salt
                int noiseType = (parameters.contraharmonicQ >= 0.0f) ? 2 : 1;
                int[] src = getDegradedOrInput(input, width, height, noiseType);
                RestorationProcessor.applyContraharmonicMeanFilter(src, output, width, height, parameters.meanFilterRadius, parameters.contraharmonicQ);
                break;
            }

            case RESTORE_ALPHA_TRIMMED_MEAN: {
                // Alpha-trimmed restores mixed Gaussian + impulse noise
                int[] src = getDegradedOrInput(input, width, height, 3);
                RestorationProcessor.applyAlphaTrimmedMeanFilter(src, output, width, height, parameters.meanFilterRadius, parameters.alphaTrimD);
                break;
            }

            case RESTORE_ADAPTIVE_WIENER: {
                // Local adaptive Wiener filter restores Gaussian noise
                int[] src = getDegradedOrInput(input, width, height, 4);
                RestorationProcessor.applyAdaptiveWienerFilter(src, output, width, height, parameters.meanFilterRadius, parameters.wienerNoiseVariance);
                break;
            }

            // Chapter 6: Color Image Processing & Pseudocolor
            case PSEUDOCOLOR_RAINBOW_JET:
                ColorProcessingProcessor.applyPseudocolor(input, output, ColorProcessingProcessor.ColormapType.RAINBOW_JET);
                break;

            case PSEUDOCOLOR_THERMAL_HOT:
                ColorProcessingProcessor.applyPseudocolor(input, output, ColorProcessingProcessor.ColormapType.MEDICAL_THERMAL_HOT);
                break;

            case PSEUDOCOLOR_PET_HOT_METAL:
                ColorProcessingProcessor.applyPseudocolor(input, output, ColorProcessingProcessor.ColormapType.PET_HOT_METAL);
                break;

            case PSEUDOCOLOR_INTENSITY_SLICING:
                ColorProcessingProcessor.applyPseudocolor(input, output, ColorProcessingProcessor.ColormapType.INTENSITY_SLICING_ISOPHOTES);
                break;

            case HSI_HUE_EXTRACTION:
                ColorProcessingProcessor.extractHsiComponent(rgbInput != null ? rgbInput : input, output, 0);
                break;

            case HSI_SATURATION_EXTRACTION:
                ColorProcessingProcessor.extractHsiComponent(rgbInput != null ? rgbInput : input, output, 1);
                break;

            case HSI_INTENSITY_EXTRACTION:
                ColorProcessingProcessor.extractHsiComponent(rgbInput != null ? rgbInput : input, output, 2);
                break;

            case GLOBAL_THRESHOLD:
                SegmentationProcessor.applyGlobalThreshold(input, output, parameters.globalThreshold);
                break;

            case OTSU_BINARIZATION:
                SegmentationProcessor.applyOtsuBinarization(input, output);
                break;

            case ADAPTIVE_THRESHOLD:
                SegmentationProcessor.applyAdaptiveThreshold(
                        input, output, width, height,
                        parameters.adaptiveRadius, parameters.adaptiveDeltaC
                );
                break;

            case BINARY_EROSION: {
                // Ensure binary input
                int[] binary = new int[width * height];
                SegmentationProcessor.applyGlobalThreshold(input, binary, parameters.globalThreshold);
                int[][] se = MorphologyProcessor.getStructuringElement(parameters.structuringElement);
                MorphologyProcessor.applyErosion(binary, output, width, height, se);
                break;
            }

            case BINARY_DILATION: {
                int[] binary = new int[width * height];
                SegmentationProcessor.applyGlobalThreshold(input, binary, parameters.globalThreshold);
                int[][] se = MorphologyProcessor.getStructuringElement(parameters.structuringElement);
                MorphologyProcessor.applyDilation(binary, output, width, height, se);
                break;
            }

            case MORPH_OPENING: {
                int[] binary = new int[width * height];
                SegmentationProcessor.applyGlobalThreshold(input, binary, parameters.globalThreshold);
                int[][] se = MorphologyProcessor.getStructuringElement(parameters.structuringElement);
                MorphologyProcessor.applyOpening(binary, output, width, height, se);
                break;
            }

            case MORPH_CLOSING: {
                int[] binary = new int[width * height];
                SegmentationProcessor.applyGlobalThreshold(input, binary, parameters.globalThreshold);
                int[][] se = MorphologyProcessor.getStructuringElement(parameters.structuringElement);
                MorphologyProcessor.applyClosing(binary, output, width, height, se);
                break;
            }

            case MORPH_BOUNDARY: {
                int[] binary = new int[width * height];
                SegmentationProcessor.applyGlobalThreshold(input, binary, parameters.globalThreshold);
                int[][] se = MorphologyProcessor.getStructuringElement(parameters.structuringElement);
                MorphologyProcessor.applyBoundaryExtraction(binary, output, width, height, se);
                break;
            }

            case ORIGINAL:
            default:
                System.arraycopy(input, 0, output, 0, input.length);
                break;
        }
    }

    private void scaleOrCenterToOutput(
            int[] src, int srcW, int srcH,
            int[] dst, int dstW, int dstH) {
        // Nearest-neighbor scaling for high speed
        for (int y = 0; y < dstH; y++) {
            int srcY = Math.min(srcH - 1, (y * srcH) / dstH);
            int dstRow = y * dstW;
            int srcRow = srcY * srcW;

            for (int x = 0; x < dstW; x++) {
                int srcX = Math.min(srcW - 1, (x * srcW) / dstW);
                dst[dstRow + x] = src[srcRow + srcX];
            }
        }
    }

    private void ensureBuffers(int totalPixels, int width, int height) {
        if (cachedGrayscale == null || cachedGrayscale.length != totalPixels) {
            cachedGrayscale = new int[totalPixels];
            cachedOutput = new int[totalPixels];
        }
        if (cachedOutputBitmap == null ||
                cachedOutputBitmap.getWidth() != width ||
                cachedOutputBitmap.getHeight() != height) {
            cachedOutputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
    }

    private void extractRotatedY(
            byte[] yBuffer,
            int srcW,
            int srcH,
            int rotationDegrees,
            int dstW,
            int dstH,
            int[] outGrayscale) {

        switch (rotationDegrees) {
            case 90:
                for (int y = 0; y < srcH; y++) {
                    int srcRow = y * srcW;
                    for (int x = 0; x < srcW; x++) {
                        int val = yBuffer[srcRow + x] & 0xFF;
                        int destX = srcH - 1 - y;
                        int destY = x;
                        outGrayscale[destY * dstW + destX] = val;
                    }
                }
                break;

            case 180:
                int total = srcW * srcH;
                for (int i = 0; i < total; i++) {
                    outGrayscale[total - 1 - i] = yBuffer[i] & 0xFF;
                }
                break;

            case 270:
                for (int y = 0; y < srcH; y++) {
                    int srcRow = y * srcW;
                    for (int x = 0; x < srcW; x++) {
                        int val = yBuffer[srcRow + x] & 0xFF;
                        int destX = y;
                        int destY = srcW - 1 - x;
                        outGrayscale[destY * dstW + destX] = val;
                    }
                }
                break;

            case 0:
            default:
                for (int i = 0; i < srcW * srcH; i++) {
                    outGrayscale[i] = yBuffer[i] & 0xFF;
                }
                break;
        }
    }

    private void updateOutputBitmap(int[] outputBuffer, int width, int height) {
        int total = width * height;
        if (isColorAlgorithm(currentAlgorithm)) {
            cachedOutputBitmap.setPixels(outputBuffer, 0, width, 0, 0, width, height);
        } else {
            int[] argb = new int[total];
            for (int i = 0; i < total; i++) {
                int val = outputBuffer[i];
                if (val < 0) val = 0;
                else if (val > 255) val = 255;
                argb[i] = 0xFF000000 | (val << 16) | (val << 8) | val;
            }
            cachedOutputBitmap.setPixels(argb, 0, width, 0, 0, width, height);
        }
    }

    private void computeFps() {
        long now = System.currentTimeMillis();
        if (lastFrameTimestamp > 0) {
            long delta = now - lastFrameTimestamp;
            if (delta > 0) {
                float instantFps = 1000.0f / delta;
                smoothedFps = (smoothedFps == 0.0f) ? instantFps : (0.85f * smoothedFps + 0.15f * instantFps);
            }
        }
        lastFrameTimestamp = now;
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
