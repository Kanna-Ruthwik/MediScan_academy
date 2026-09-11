package com.example.mediscan.engine;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Random;

/**
 * Gonzalez & Woods (4th Edition) Chapter 5: Image Restoration and Reconstruction.
 *
 * Implements pure unassisted Java mathematical transformations operating directly on
 * int[] grayscale buffers (values [0..255]):
 * - 5.2 Noise Models (Gaussian, Salt & Pepper / Impulse)
 * - 5.3 Restoration Mean Filters (Arithmetic, Geometric, Harmonic, Contraharmonic, Alpha-Trimmed)
 * - 5.8 Wiener Minimum Mean Square Error (MMSE) Restoration
 */
public final class RestorationProcessor {

    private static final Random dynamicRandom = new Random();

    private RestorationProcessor() {
        // Prevent instantiation
    }

    /**
     * 5.2.4 Impulse (Salt-and-Pepper) Noise Generator.
     * Replaces pixels with 0 (pepper) or 255 (salt) with probability pa and pb.
     *
     * @param input      Input grayscale array [0..255]
     * @param output     Output degraded grayscale array [0..255]
     * @param saltProb   Probability of salt impulse (val = 255)
     * @param pepperProb Probability of pepper impulse (val = 0)
     */
    public static void applySaltAndPepperNoise(
            @NonNull int[] input,
            @NonNull int[] output,
            float saltProb,
            float pepperProb
    ) {
        int len = input.length;
        for (int i = 0; i < len; i++) {
            float r = dynamicRandom.nextFloat();
            if (r < pepperProb) {
                output[i] = 0; // Pepper impulse (0)
            } else if (r < pepperProb + saltProb) {
                output[i] = 255; // Salt impulse (255)
            } else {
                output[i] = input[i] & 0xFF; // Uncorrupted signal
            }
        }
    }

    /**
     * 5.2.1 Gaussian Additive Noise Generator.
     * Simulates electronic sensor thermal agitation noise with standard deviation sigma:
     * g(x,y) = f(x,y) + eta(x,y), where eta ~ N(mean, stdDev^2)
     */
    public static void applyGaussianNoise(
            @NonNull int[] input,
            @NonNull int[] output,
            float mean,
            float stdDev
    ) {
        int len = input.length;
        for (int i = 0; i < len; i++) {
            int original = input[i] & 0xFF;
            double noise = mean + dynamicRandom.nextGaussian() * stdDev;
            int restored = (int) Math.round(original + noise);
            output[i] = Math.max(0, Math.min(255, restored));
        }
    }

    /**
     * 5.3.1 Arithmetic Mean Filter:
     * f_hat(x,y) = 1/(mn) * sum_{(s,t) in S_xy} g(s,t)
     */
    public static void applyArithmeticMeanFilter(
            @NonNull int[] input,
            @NonNull int[] output,
            int width,
            int height,
            int kernelRadius
    ) {
        int rad = Math.max(1, kernelRadius);
        for (int y = 0; y < height; y++) {
            int yMin = Math.max(0, y - rad);
            int yMax = Math.min(height - 1, y + rad);

            for (int x = 0; x < width; x++) {
                int xMin = Math.max(0, x - rad);
                int xMax = Math.min(width - 1, x + rad);

                long sum = 0;
                int count = 0;
                for (int ky = yMin; ky <= yMax; ky++) {
                    int rowOffset = ky * width;
                    for (int kx = xMin; kx <= xMax; kx++) {
                        sum += (input[rowOffset + kx] & 0xFF);
                        count++;
                    }
                }

                output[y * width + x] = (int) (sum / count);
            }
        }
    }

    /**
     * 5.3.1 Geometric Mean Filter:
     * f_hat(x,y) = [ prod_{(s,t) in S_xy} g(s,t) ]^(1 / mn)
     * Computed via logarithmic accumulation: exp( 1/N * sum( ln(g(s,t) + epsilon) ) )
     * Achieves smoothing comparable to arithmetic mean while preserving finer image details.
     */
    public static void applyGeometricMeanFilter(
            @NonNull int[] input,
            @NonNull int[] output,
            int width,
            int height,
            int kernelRadius
    ) {
        int rad = Math.max(1, kernelRadius);
        for (int y = 0; y < height; y++) {
            int yMin = Math.max(0, y - rad);
            int yMax = Math.min(height - 1, y + rad);

            for (int x = 0; x < width; x++) {
                int xMin = Math.max(0, x - rad);
                int xMax = Math.min(width - 1, x + rad);

                double logSum = 0.0;
                int count = 0;
                for (int ky = yMin; ky <= yMax; ky++) {
                    int rowOffset = ky * width;
                    for (int kx = xMin; kx <= xMax; kx++) {
                        int val = input[rowOffset + kx] & 0xFF;
                        logSum += Math.log(Math.max(1, val));
                        count++;
                    }
                }

                int result = (int) Math.round(Math.exp(logSum / count));
                output[y * width + x] = Math.max(0, Math.min(255, result));
            }
        }
    }

    /**
     * 5.3.1 Harmonic Mean Filter:
     * f_hat(x,y) = mn / sum_{(s,t) in S_xy} [1 / g(s,t)]
     * Well-suited for salt noise and Gaussian noise; fails on pepper noise (where g=0).
     */
    public static void applyHarmonicMeanFilter(
            @NonNull int[] input,
            @NonNull int[] output,
            int width,
            int height,
            int kernelRadius
    ) {
        int rad = Math.max(1, kernelRadius);
        for (int y = 0; y < height; y++) {
            int yMin = Math.max(0, y - rad);
            int yMax = Math.min(height - 1, y + rad);

            for (int x = 0; x < width; x++) {
                int xMin = Math.max(0, x - rad);
                int xMax = Math.min(width - 1, x + rad);

                double invSum = 0.0;
                int count = 0;
                for (int ky = yMin; ky <= yMax; ky++) {
                    int rowOffset = ky * width;
                    for (int kx = xMin; kx <= xMax; kx++) {
                        int val = Math.max(1, input[rowOffset + kx] & 0xFF);
                        invSum += (1.0 / val);
                        count++;
                    }
                }

                int result = (int) Math.round(count / invSum);
                output[y * width + x] = Math.max(0, Math.min(255, result));
            }
        }
    }

    /**
     * 5.3.1 Contraharmonic Mean Filter:
     * f_hat(x,y) = sum( g(s,t)^(Q+1) ) / sum( g(s,t)^Q )
     * - Q > 0: eliminates pepper noise (dark impulse outliers)
     * - Q < 0: eliminates salt noise (bright impulse outliers)
     * - Q = 0: reduces to arithmetic mean
     * - Q = -1: reduces to harmonic mean
     */
    public static void applyContraharmonicMeanFilter(
            @NonNull int[] input,
            @NonNull int[] output,
            int width,
            int height,
            int kernelRadius,
            float qOrder
    ) {
        int rad = Math.max(1, kernelRadius);
        for (int y = 0; y < height; y++) {
            int yMin = Math.max(0, y - rad);
            int yMax = Math.min(height - 1, y + rad);

            for (int x = 0; x < width; x++) {
                int xMin = Math.max(0, x - rad);
                int xMax = Math.min(width - 1, x + rad);

                double num = 0.0;
                double den = 0.0;
                for (int ky = yMin; ky <= yMax; ky++) {
                    int rowOffset = ky * width;
                    for (int kx = xMin; kx <= xMax; kx++) {
                        double val = Math.max(1e-2, input[rowOffset + kx] & 0xFF);
                        num += Math.pow(val, qOrder + 1.0);
                        den += Math.pow(val, qOrder);
                    }
                }

                int result = (den > 1e-9) ? (int) Math.round(num / den) : 0;
                output[y * width + x] = Math.max(0, Math.min(255, result));
            }
        }
    }

    /**
     * 5.3.2 Alpha-Trimmed Mean Filter:
     * Deletes the d/2 lowest and d/2 highest intensity values of g(s,t) in S_xy
     * and averages the remaining (mn - d) pixels.
     * Highly effective in medical images suffering from mixed Gaussian + impulse noise.
     */
    public static void applyAlphaTrimmedMeanFilter(
            @NonNull int[] input,
            @NonNull int[] output,
            int width,
            int height,
            int kernelRadius,
            int dTrim
    ) {
        int rad = Math.max(1, kernelRadius);
        int maxWindow = (2 * rad + 1) * (2 * rad + 1);
        int[] window = new int[maxWindow];

        for (int y = 0; y < height; y++) {
            int yMin = Math.max(0, y - rad);
            int yMax = Math.min(height - 1, y + rad);

            for (int x = 0; x < width; x++) {
                int xMin = Math.max(0, x - rad);
                int xMax = Math.min(width - 1, x + rad);

                int count = 0;
                for (int ky = yMin; ky <= yMax; ky++) {
                    int rowOffset = ky * width;
                    for (int kx = xMin; kx <= xMax; kx++) {
                        window[count++] = input[rowOffset + kx] & 0xFF;
                    }
                }

                Arrays.sort(window, 0, count);

                int actualTrim = Math.min(dTrim, count - 1);
                int start = actualTrim / 2;
                int end = count - (actualTrim - start);

                long sum = 0;
                int remCount = 0;
                for (int i = start; i < end; i++) {
                    sum += window[i];
                    remCount++;
                }

                int result = (remCount > 0) ? (int) (sum / remCount) : window[count / 2];
                output[y * width + x] = Math.max(0, Math.min(255, result));
            }
        }
    }

    /**
     * 5.8 Minimum Mean Square Error (Wiener) Filter.
     * In spatial domain, local adaptive Wiener filter:
     * f_hat(x,y) = mu + (max(0, sigma^2 - v^2) / max(sigma^2, v^2)) * (g(x,y) - mu)
     * where mu is local mean, sigma^2 is local variance, v^2 is noise variance estimate.
     */
    public static void applyAdaptiveWienerFilter(
            @NonNull int[] input,
            @NonNull int[] output,
            int width,
            int height,
            int kernelRadius,
            float noiseVarianceEstimate
    ) {
        int rad = Math.max(1, kernelRadius);
        for (int y = 0; y < height; y++) {
            int yMin = Math.max(0, y - rad);
            int yMax = Math.min(height - 1, y + rad);

            for (int x = 0; x < width; x++) {
                int xMin = Math.max(0, x - rad);
                int xMax = Math.min(width - 1, x + rad);

                double sum = 0.0;
                double sumSq = 0.0;
                int count = 0;

                for (int ky = yMin; ky <= yMax; ky++) {
                    int rowOffset = ky * width;
                    for (int kx = xMin; kx <= xMax; kx++) {
                        int val = input[rowOffset + kx] & 0xFF;
                        sum += val;
                        sumSq += (val * val);
                        count++;
                    }
                }

                double mu = sum / count;
                double sigmaSq = (sumSq / count) - (mu * mu);
                sigmaSq = Math.max(0.0, sigmaSq);

                int centerPixel = input[y * width + x] & 0xFF;
                double weight = (sigmaSq <= noiseVarianceEstimate) ? 0.0 : (sigmaSq - noiseVarianceEstimate) / sigmaSq;

                int restored = (int) Math.round(mu + weight * (centerPixel - mu));
                output[y * width + x] = Math.max(0, Math.min(255, restored));
            }
        }
    }
}
