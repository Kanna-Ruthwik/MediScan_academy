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
        LOG_TRANSFORM,
        GAMMA_CORRECTION,
        HISTOGRAM_EQUALIZATION,
        GAUSSIAN_BLUR,
        MEDIAN_FILTER,
        SOBEL_GRADIENTS,
        LAPLACIAN_SHARPEN,
        FFT_SPECTRUM,
        FFT_IDEAL_LOWPASS,
        FFT_GAUSSIAN_LOWPASS,
        GLOBAL_THRESHOLD,
        OTSU_BINARIZATION,
        ADAPTIVE_THRESHOLD,
        BINARY_EROSION,
        BINARY_DILATION,
        MORPH_OPENING,
        MORPH_CLOSING,
        MORPH_BOUNDARY
    }

    public static class Parameters {
        public float gamma = 1.6f;
        public float logFactor = 1.0f;
        public int gaussianKernelSize = 3;
        public float laplacianStrength = 0.8f;
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
                executePipeline(cachedGrayscale, cachedOutput, dstWidth, dstHeight);

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

            // Extract ITU-R BT.601 luminance
            int[] grayscale = ImageUtils.bitmapToGrayscaleIntArray(sourceBitmap);
            int[] output = new int[totalPixels];

            executePipeline(grayscale, output, width, height);

            Bitmap outputBitmap = ImageUtils.grayscaleIntArrayToBitmap(output, width, height);

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

    private void executePipeline(int[] input, int[] output, int width, int height) {
        switch (currentAlgorithm) {
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

    private void updateOutputBitmap(int[] grayscale, int width, int height) {
        int total = width * height;
        int[] argb = new int[total];
        for (int i = 0; i < total; i++) {
            int val = grayscale[i];
            if (val < 0) val = 0;
            else if (val > 255) val = 255;
            argb[i] = 0xFF000000 | (val << 16) | (val << 8) | val;
        }
        cachedOutputBitmap.setPixels(argb, 0, width, 0, 0, width, height);
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
