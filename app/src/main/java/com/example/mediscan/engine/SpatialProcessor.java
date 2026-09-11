package com.example.mediscan.engine;

import java.util.Arrays;

/**
 * 2D Spatial Domain Image Processing Engine.
 * Implements intensity transformations, arbitrary NxN spatial convolution,
 * Gaussian smoothing, order-statistic non-linear median filtering,
 * Sobel gradient magnitude edge detection, and Laplacian sharpening.
 * Reference: Gonzalez & Woods, Digital Image Processing (4th Ed.), Chapter 3.
 */
public final class SpatialProcessor {

    private SpatialProcessor() {
        // Pure mathematical processor
    }

    // =========================================================================
    // MODULE 1: INTENSITY TRANSFORMATIONS & HISTOGRAM EQUALIZATION (Ch. 3)
    // =========================================================================

    /**
     * Logarithmic Transformation: s = c * log(1 + r)
     * Compresses the dynamic range of images with large intensity variations
     * (e.g., Fourier spectrums, X-ray transmission differences).
     *
     * @param inputGrayscale 1D array of input luminance [0..255]
     * @param outGrayscale   1D array of output luminance [0..255]
     * @param cFactor        Scaling constant (default ~ 1.0)
     */
    public static void applyLogTransform(int[] inputGrayscale, int[] outGrayscale, float cFactor) {
        // Pre-compute 256-element Look-Up Table (LUT) for O(1) loop speed
        int[] lut = new int[256];
        double scale = (255.0 / Math.log(256.0)) * cFactor;
        for (int r = 0; r < 256; r++) {
            int s = (int) (scale * Math.log(1.0 + r) + 0.5);
            lut[r] = Math.max(0, Math.min(255, s));
        }

        int len = inputGrayscale.length;
        for (int i = 0; i < len; i++) {
            int r = inputGrayscale[i];
            outGrayscale[i] = lut[Math.max(0, Math.min(255, r))];
        }
    }

    /**
     * Power-Law (Gamma) Transformation: s = c * r^gamma
     * Corrects non-linear display response and enhances dark or washed-out medical scans.
     *
     * @param inputGrayscale 1D array of input luminance [0..255]
     * @param outGrayscale   1D array of output luminance [0..255]
     * @param gamma          Gamma exponent (>1 darkens/enhances contrast, <1 expands dark regions)
     */
    public static void applyGammaCorrection(int[] inputGrayscale, int[] outGrayscale, float gamma) {
        int[] lut = new int[256];
        double inv255 = 1.0 / 255.0;
        for (int r = 0; r < 256; r++) {
            double normalized = r * inv255;
            double corrected = Math.pow(normalized, gamma);
            int s = (int) (corrected * 255.0 + 0.5);
            lut[r] = Math.max(0, Math.min(255, s));
        }

        int len = inputGrayscale.length;
        for (int i = 0; i < len; i++) {
            int r = inputGrayscale[i];
            outGrayscale[i] = lut[Math.max(0, Math.min(255, r))];
        }
    }

    /**
     * Global Histogram Equalization:
     * 1. Compute PDF: p(r_k) = n_k / MN
     * 2. Compute CDF: s_k = (L - 1) * sum_{j=0}^k p(r_j)
     * 3. Map pixel intensities: out = s_k
     */
    public static void applyHistogramEqualization(int[] inputGrayscale, int[] outGrayscale) {
        int N = inputGrayscale.length;
        if (N == 0) return;

        // 1. Calculate frequency distribution n_k
        int[] hist = new int[256];
        for (int val : inputGrayscale) {
            hist[Math.max(0, Math.min(255, val))]++;
        }

        // 2. Compute Cumulative Distribution Function (CDF) and build LUT
        int[] lut = new int[256];
        long cumulative = 0;
        double scale = 255.0 / (double) N;

        for (int k = 0; k < 256; k++) {
            cumulative += hist[k];
            int mapped = (int) Math.round(cumulative * scale);
            lut[k] = Math.max(0, Math.min(255, mapped));
        }

        // 3. Re-map pixels
        for (int i = 0; i < N; i++) {
            int r = inputGrayscale[i];
            outGrayscale[i] = lut[Math.max(0, Math.min(255, r))];
        }
    }

    // =========================================================================
    // MODULE 2: SPATIAL CONVOLUTION & FILTERING ENGINE (Ch. 3)
    // =========================================================================

    /**
     * Discrete 2D Spatial Convolution with zero-padding border boundary handling:
     * g(x, y) = sum_{s=-a}^a sum_{t=-b}^b w(s, t) * f(x + s, y + t)
     *
     * @param input  1D row-major grayscale array of size width * height
     * @param output 1D row-major destination array of size width * height
     * @param width  Image width
     * @param height Image height
     * @param kernel 2D float kernel matrix of odd dimensions (e.g. 3x3, 5x5)
     */
    public static void convolve2D(
            int[] input,
            int[] output,
            int width,
            int height,
            float[][] kernel) {

        int kHeight = kernel.length;
        int kWidth = kernel[0].length;
        int radiusY = kHeight / 2;
        int radiusX = kWidth / 2;

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                float sum = 0.0f;

                for (int ky = 0; ky < kHeight; ky++) {
                    int sampleY = y + ky - radiusY;
                    // Zero-padding border handling
                    if (sampleY < 0 || sampleY >= height) {
                        continue;
                    }
                    int sampleRowOffset = sampleY * width;
                    float[] kRow = kernel[ky];

                    for (int kx = 0; kx < kWidth; kx++) {
                        int sampleX = x + kx - radiusX;
                        if (sampleX < 0 || sampleX >= width) {
                            continue;
                        }

                        int pixelVal = input[sampleRowOffset + sampleX];
                        sum += pixelVal * kRow[kx];
                    }
                }

                int result = Math.round(sum);
                output[rowOffset + x] = Math.max(0, Math.min(255, result));
            }
        }
    }

    /**
     * Applies a 3x3 or 5x5 Spatial Gaussian Smoothing filter.
     * Kernel weights derived from 2D Gaussian function: G(x, y) = (1 / (2*pi*sigma^2)) * exp(-(x^2+y^2)/(2*sigma^2)).
     */
    public static void applyGaussianBlur(int[] input, int[] output, int width, int height, int kernelSize) {
        float[][] kernel;
        if (kernelSize >= 5) {
            // 5x5 Gaussian Kernel (sigma ~ 1.4)
            kernel = new float[][]{
                    {1 / 256f,  4 / 256f,  6 / 256f,  4 / 256f, 1 / 256f},
                    {4 / 256f, 16 / 256f, 24 / 256f, 16 / 256f, 4 / 256f},
                    {6 / 256f, 24 / 256f, 36 / 256f, 24 / 256f, 6 / 256f},
                    {4 / 256f, 16 / 256f, 24 / 256f, 16 / 256f, 4 / 256f},
                    {1 / 256f,  4 / 256f,  6 / 256f,  4 / 256f, 1 / 256f}
            };
        } else {
            // 3x3 Gaussian Kernel (sigma ~ 1.0)
            kernel = new float[][]{
                    {1 / 16f, 2 / 16f, 1 / 16f},
                    {2 / 16f, 4 / 16f, 2 / 16f},
                    {1 / 16f, 2 / 16f, 1 / 16f}
            };
        }
        convolve2D(input, output, width, height, kernel);
    }

    /**
     * 3x3 Order-Statistic Non-Linear Median Filter.
     * Outstanding for eliminating acoustic/speckle noise in Ultrasound scans
     * without blurring high-contrast organ boundaries.
     */
    public static void applyMedianFilter(int[] input, int[] output, int width, int height) {
        int[] neighborhood = new int[9];

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int count = 0;

                for (int dy = -1; dy <= 1; dy++) {
                    int sampleY = y + dy;
                    if (sampleY < 0 || sampleY >= height) continue;
                    int sampleRow = sampleY * width;

                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= width) continue;

                        neighborhood[count++] = input[sampleRow + sampleX];
                    }
                }

                // In-place sorting of small 9-element array
                Arrays.sort(neighborhood, 0, count);
                int median = neighborhood[count / 2];
                output[rowOffset + x] = median;
            }
        }
    }

    /**
     * Sobel Edge Gradient Operators (G_x, G_y) and Gradient Vector Magnitude:
     * G_x = [[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]]
     * G_y = [[-1, -2, -1], [ 0,  0,  0], [ 1,  2,  1]]
     * Magnitude M(x, y) = sqrt(G_x^2 + G_y^2)
     */
    public static void applySobelFilter(int[] input, int[] output, int width, int height) {
        for (int y = 1; y < height - 1; y++) {
            int rowPrev = (y - 1) * width;
            int rowCurr = y * width;
            int rowNext = (y + 1) * width;

            for (int x = 1; x < width - 1; x++) {
                int p00 = input[rowPrev + (x - 1)];
                int p01 = input[rowPrev + x];
                int p02 = input[rowPrev + (x + 1)];

                int p10 = input[rowCurr + (x - 1)];
                int p12 = input[rowCurr + (x + 1)];

                int p20 = input[rowNext + (x - 1)];
                int p21 = input[rowNext + x];
                int p22 = input[rowNext + (x + 1)];

                // Horizontal gradient operator G_x
                int gx = (-1 * p00) + (1 * p02)
                        + (-2 * p10) + (2 * p12)
                        + (-1 * p20) + (1 * p22);

                // Vertical gradient operator G_y
                int gy = (-1 * p00) + (-2 * p01) + (-1 * p02)
                        + (1 * p20) + (2 * p21) + (1 * p22);

                // Exact Euclidean magnitude
                int mag = (int) Math.sqrt((gx * gx) + (gy * gy));
                output[rowCurr + x] = Math.max(0, Math.min(255, mag));
            }
        }

        // Fill borders with zero
        for (int x = 0; x < width; x++) {
            output[x] = 0;
            output[(height - 1) * width + x] = 0;
        }
        for (int y = 0; y < height; y++) {
            output[y * width] = 0;
            output[y * width + (width - 1)] = 0;
        }
    }

    /**
     * Laplacian Spatial Sharpening:
     * Kernel = [[0, 1, 0], [1, -4, 1], [0, 1, 0]]
     * Sharpened: g(x, y) = f(x, y) - c * nabla^2 f(x, y)
     */
    public static void applyLaplacianSharpening(int[] input, int[] output, int width, int height, float strength) {
        for (int y = 1; y < height - 1; y++) {
            int rowPrev = (y - 1) * width;
            int rowCurr = y * width;
            int rowNext = (y + 1) * width;

            for (int x = 1; x < width - 1; x++) {
                int center = input[rowCurr + x];
                int up = input[rowPrev + x];
                int down = input[rowNext + x];
                int left = input[rowCurr + (x - 1)];
                int right = input[rowCurr + (x + 1)];

                // Discrete Laplacian approximation
                int laplacian = up + down + left + right - (4 * center);

                // Subtract Laplacian to boost high-frequency edges
                int sharpened = (int) (center - (strength * laplacian));
                output[rowCurr + x] = Math.max(0, Math.min(255, sharpened));
            }
        }

        // Copy borders
        for (int x = 0; x < width; x++) {
            output[x] = input[x];
            output[(height - 1) * width + x] = input[(height - 1) * width + x];
        }
        for (int y = 0; y < height; y++) {
            output[y * width] = input[y * width];
            output[y * width + (width - 1)] = input[y * width + (width - 1)];
        }
    }
}
