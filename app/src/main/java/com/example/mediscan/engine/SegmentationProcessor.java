package com.example.mediscan.engine;

import com.example.mediscan.utils.ImageUtils;

/**
 * Image Segmentation Engine implementing Gonzalez & Woods (4th Ed.) Chapter 10 algorithms:
 * Global Manual Thresholding, Otsu's Optimum Inter-Class Variance Binarization,
 * and Adaptive Local Area Thresholding with Integral Images.
 */
public final class SegmentationProcessor {

    private SegmentationProcessor() {
        // Pure mathematical processor
    }

    /**
     * Manual Global Thresholding:
     * g(x, y) = 255 if f(x, y) >= threshold else 0
     */
    public static void applyGlobalThreshold(int[] input, int[] output, int threshold) {
        int len = input.length;
        int t = Math.max(0, Math.min(255, threshold));
        for (int i = 0; i < len; i++) {
            output[i] = (input[i] >= t) ? 255 : 0;
        }
    }

    /**
     * Otsu's Automated Optimum Global Binarization (Ch. 10.3).
     * Calculates the statistical threshold k* that maximizes the between-class variance:
     * sigma_B^2(k) = [mu_T * P_1(k) - m(k)]^2 / (P_1(k) * [1 - P_1(k)])
     *
     * @return The computed optimal threshold k*
     */
    public static int applyOtsuBinarization(int[] input, int[] output) {
        int[] histogram = new int[256];
        int N = input.length;
        for (int val : input) {
            histogram[Math.max(0, Math.min(255, val))]++;
        }

        int optimalK = ImageUtils.computeOtsuThreshold(histogram, N);
        applyGlobalThreshold(input, output, optimalK);
        return optimalK;
    }

    /**
     * Adaptive Local Thresholding using 2D Integral Image (Summed-Area Table) for O(1) box queries.
     * Computes the local neighborhood mean in a (2*radius+1) x (2*radius+1) window,
     * thresholding f(x, y) >= (mean - delta).
     */
    public static void applyAdaptiveThreshold(
            int[] input,
            int[] output,
            int width,
            int height,
            int radius,
            int deltaC) {

        // 1. Build 2D Integral Image (Summed Area Table)
        int integralWidth = width + 1;
        int integralHeight = height + 1;
        long[] integral = new long[integralWidth * integralHeight];

        for (int y = 0; y < height; y++) {
            long rowSum = 0;
            int srcRowOffset = y * width;
            int intRowCurr = (y + 1) * integralWidth;
            int intRowPrev = y * integralWidth;

            for (int x = 0; x < width; x++) {
                rowSum += input[srcRowOffset + x];
                integral[intRowCurr + (x + 1)] = integral[intRowPrev + (x + 1)] + rowSum;
            }
        }

        // 2. Perform fast O(1) local mean queries
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(height - 1, y + radius);

            int srcOffset = y * width;

            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width - 1, x + radius);

                int count = (x1 - x0 + 1) * (y1 - y0 + 1);

                // Integral box query: D - B - C + A
                long A = integral[y0 * integralWidth + x0];
                long B = integral[y0 * integralWidth + (x1 + 1)];
                long C = integral[(y1 + 1) * integralWidth + x0];
                long D = integral[(y1 + 1) * integralWidth + (x1 + 1)];

                long sum = D - B - C + A;
                int mean = (int) (sum / count);

                int localThreshold = mean - deltaC;
                output[srcOffset + x] = (input[srcOffset + x] >= localThreshold) ? 255 : 0;
            }
        }
    }
}
