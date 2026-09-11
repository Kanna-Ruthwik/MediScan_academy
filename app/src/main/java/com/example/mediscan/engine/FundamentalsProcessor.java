package com.example.mediscan.engine;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Digital Image Fundamentals Processor (Gonzalez & Woods, 4th Ed., Chapter 2).
 * Implements:
 * - 2.1 Elements of Visual Perception: Mach Bands illusion, Simultaneous Contrast illusion
 * - 2.4 Image Sampling & Quantization: Spatial sub-sampling (pixelation), Intensity resolution (bit-depth quantization, false contouring)
 * - 2.5 Basic Relationships Between Pixels: Distance transforms (Euclidean De, City-Block D4, Chessboard D8)
 * - 2.6 Basic Mathematical Tools: Bit-Plane Slicing (Bits 0-7), Digital Subtraction Angiography (DSA difference imaging)
 */
public final class FundamentalsProcessor {

    private FundamentalsProcessor() {
        // Utility class
    }

    public enum DistanceMetric {
        EUCLIDEAN,
        CITY_BLOCK_D4,
        CHESSBOARD_D8
    }

    /**
     * 2.4.2 Intensity Resolution & False Contouring.
     * Quantizes an 8-bit image (256 levels) to a reduced bit depth (1 to 7 bits).
     * Illustrates the emergence of false contouring (banding along isophotes)
     * as described in Gonzalez & Woods Fig 2.21.
     *
     * @param input    Input 8-bit grayscale array [0..255]
     * @param output   Output 8-bit grayscale array
     * @param bitDepth Target bit depth (1 to 8 bits)
     */
    public static void applyBitDepthQuantization(@NonNull int[] input, @NonNull int[] output, int bitDepth) {
        if (bitDepth >= 8) {
            System.arraycopy(input, 0, output, 0, input.length);
            return;
        }

        int clampedBits = Math.max(1, Math.min(7, bitDepth));
        int levels = 1 << clampedBits; // 2^k levels (e.g. k=1 -> 2, k=2 -> 4, k=4 -> 16)
        int step = 256 / levels; // Step size between quantization bins

        // Precompute LUT for O(1) pixel transformation
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            int bin = i / step;
            if (bin >= levels) bin = levels - 1;
            // Scale bin back to full dynamic range [0..255]
            lut[i] = (bin * 255) / (levels - 1);
        }

        for (int i = 0; i < input.length; i++) {
            int val = input[i];
            if (val < 0) val = 0;
            else if (val > 255) val = 255;
            output[i] = lut[val];
        }
    }

    /**
     * 2.4.1 Spatial Resolution & Sampling (Pixelation).
     * Subsamples the image by integer factor N, then reconstructs via zero-order hold
     * (nearest neighbor) to demonstrate spatial resolution degradation and checkerboard artifacts.
     *
     * @param input  Input grayscale array
     * @param output Output grayscale array
     * @param width  Image width
     * @param height Image height
     * @param factor Downsampling grid factor (e.g. 2, 4, 8, 16, 32)
     */
    public static void applySpatialSubsampling(
            @NonNull int[] input,
            @NonNull int[] output,
            int width,
            int height,
            int factor) {

        int step = Math.max(1, factor);
        if (step == 1) {
            System.arraycopy(input, 0, output, 0, input.length);
            return;
        }

        for (int y = 0; y < height; y += step) {
            int maxBlockY = Math.min(y + step, height);
            for (int x = 0; x < width; x += step) {
                int maxBlockX = Math.min(x + step, width);

                // Sample single pixel at (x, y)
                int samplePixel = input[y * width + x];

                // Replicate across the step x step block
                for (int by = y; by < maxBlockY; by++) {
                    int rowOffset = by * width;
                    for (int bx = x; bx < maxBlockX; bx++) {
                        output[rowOffset + bx] = samplePixel;
                    }
                }
            }
        }
    }

    /**
     * 2.6.2 & 3.2.4 Bit-Plane Slicing.
     * Extracts the n-th bit plane from each pixel.
     * Higher-order bit planes (7, 6, 5) contain the majority of visually significant
     * anatomical information; lower-order bit planes (0, 1) contain subtle detail or noise.
     *
     * @param input    Input grayscale array [0..255]
     * @param output   Output grayscale array (binary: 0 or 255)
     * @param bitPlane Bit plane index [0..7], where 0 is LSB and 7 is MSB
     */
    public static void applyBitPlaneSlicing(@NonNull int[] input, @NonNull int[] output, int bitPlane) {
        int plane = Math.max(0, Math.min(7, bitPlane));
        int mask = 1 << plane;

        for (int i = 0; i < input.length; i++) {
            int pixel = input[i];
            output[i] = ((pixel & mask) != 0) ? 255 : 0;
        }
    }

    /**
     * 2.1.3 Mach Bands Visual Perception Illusion.
     * Synthesizes a ramp of constant intensity steps. Although each vertical band has
     * an objectively uniform physical luminance, human visual perception (due to retinal
     * lateral inhibition) perceives a bright scalloped peak at the lighter transition edge
     * and a dark valley at the darker transition edge.
     *
     * @param output Output array of size width * height
     * @param width  Image width
     * @param height Image height
     */
    public static void generateMachBands(@NonNull int[] output, int width, int height) {
        int numBands = 8;
        int bandWidth = width / numBands;

        int[] bandLevels = new int[numBands];
        for (int b = 0; b < numBands; b++) {
            bandLevels[b] = (b * 255) / (numBands - 1);
        }

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int bandIdx = Math.min(numBands - 1, x / bandWidth);
                output[rowOffset + x] = bandLevels[bandIdx];
            }
        }
    }

    /**
     * 2.1.3 Simultaneous Contrast Visual Perception Phenomenon.
     * Generates three identical square patches with the exact same physical gray level (128),
     * situated on three distinctly different backgrounds: Dark (0), Medium Gray (128), and Light (240).
     * Demonstrates that perceived brightness is heavily modulated by surrounding context.
     *
     * @param output Output array of size width * height
     * @param width  Image width
     * @param height Image height
     */
    public static void generateSimultaneousContrast(@NonNull int[] output, int width, int height) {
        int colWidth = width / 3;
        int patchSize = Math.min(width, height) / 5;
        int centerY = height / 2;

        int[] bgIntensities = {30, 128, 235}; // Dark, Medium, Bright backgrounds
        int targetIntensity = 128; // The exact same patch intensity in all 3 regions

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            boolean inPatchY = Math.abs(y - centerY) <= (patchSize / 2);

            for (int x = 0; x < width; x++) {
                int regionIdx = Math.min(2, x / colWidth);
                int regionCenterX = regionIdx * colWidth + (colWidth / 2);

                boolean inPatchX = Math.abs(x - regionCenterX) <= (patchSize / 2);

                if (inPatchX && inPatchY) {
                    output[rowOffset + x] = targetIntensity;
                } else {
                    output[rowOffset + x] = bgIntensities[regionIdx];
                }
            }
        }
    }

    /**
     * 2.5 Basic Relationships Between Pixels: Distance Transforms.
     * Computes distance metric map from foreground objects (or center if no foreground).
     * - Euclidean: De(p, q) = sqrt((x-s)^2 + (y-t)^2)  -> Circular isodistance contours
     * - City-block (D4): D4(p, q) = |x-s| + |y-t|        -> Diamond isodistance contours
     * - Chessboard (D8): D8(p, q) = max(|x-s|, |y-t|)    -> Square isodistance contours
     *
     * @param input     Input image
     * @param output    Output visualization array [0..255]
     * @param width     Image width
     * @param height    Image height
     * @param metric    DistanceMetric (EUCLIDEAN, CITY_BLOCK_D4, CHESSBOARD_D8)
     * @param threshold Binarization threshold to identify seed foreground pixels
     */
    public static void computeDistanceTransform(
            @NonNull int[] input,
            @NonNull int[] output,
            int width,
            int height,
            @NonNull DistanceMetric metric,
            int threshold) {

        int totalPixels = width * height;
        float[] dist = new float[totalPixels];
        float maxVal = 1e8f;
        Arrays.fill(dist, maxVal);

        // Identify seeds: pixels where input[i] >= threshold
        int seedCount = 0;
        for (int i = 0; i < totalPixels; i++) {
            if (input[i] >= threshold) {
                dist[i] = 0.0f;
                seedCount++;
            }
        }

        // If no seed found, use image center as seed
        if (seedCount == 0) {
            int cx = width / 2;
            int cy = height / 2;
            dist[cy * width + cx] = 0.0f;
        }

        // Two-pass Raster Distance Transform (Rosenfeld-Pfaltz algorithm)
        // Pass 1: Forward raster scan (top-left to bottom-right)
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int idx = rowOffset + x;
                float current = dist[idx];

                // Check left neighbor (x - 1, y)
                if (x > 0) {
                    current = Math.min(current, dist[idx - 1] + 1.0f);
                }
                // Check top neighbor (x, y - 1)
                if (y > 0) {
                    current = Math.min(current, dist[idx - width] + 1.0f);
                }

                if (metric == DistanceMetric.EUCLIDEAN) {
                    if (x > 0 && y > 0) {
                        current = Math.min(current, dist[idx - width - 1] + 1.4142f);
                    }
                    if (x + 1 < width && y > 0) {
                        current = Math.min(current, dist[idx - width + 1] + 1.4142f);
                    }
                } else if (metric == DistanceMetric.CHESSBOARD_D8) {
                    if (x > 0 && y > 0) {
                        current = Math.min(current, dist[idx - width - 1] + 1.0f);
                    }
                    if (x + 1 < width && y > 0) {
                        current = Math.min(current, dist[idx - width + 1] + 1.0f);
                    }
                }
                dist[idx] = current;
            }
        }

        // Pass 2: Backward raster scan (bottom-right to top-left)
        for (int y = height - 1; y >= 0; y--) {
            int rowOffset = y * width;
            for (int x = width - 1; x >= 0; x--) {
                int idx = rowOffset + x;
                float current = dist[idx];

                // Check right neighbor (x + 1, y)
                if (x + 1 < width) {
                    current = Math.min(current, dist[idx + 1] + 1.0f);
                }
                // Check bottom neighbor (x, y + 1)
                if (y + 1 < height) {
                    current = Math.min(current, dist[idx + width] + 1.0f);
                }

                if (metric == DistanceMetric.EUCLIDEAN) {
                    if (x + 1 < width && y + 1 < height) {
                        current = Math.min(current, dist[idx + width + 1] + 1.4142f);
                    }
                    if (x > 0 && y + 1 < height) {
                        current = Math.min(current, dist[idx + width - 1] + 1.4142f);
                    }
                } else if (metric == DistanceMetric.CHESSBOARD_D8) {
                    if (x + 1 < width && y + 1 < height) {
                        current = Math.min(current, dist[idx + width + 1] + 1.0f);
                    }
                    if (x > 0 && y + 1 < height) {
                        current = Math.min(current, dist[idx + width - 1] + 1.0f);
                    }
                }
                dist[idx] = current;
            }
        }

        // Find maximum finite distance for dynamic range normalization
        float maxDist = 1.0f;
        for (int i = 0; i < totalPixels; i++) {
            if (dist[i] < maxVal && dist[i] > maxDist) {
                maxDist = dist[i];
            }
        }

        // Produce isodistance banding visual: cyclic modulo 32 + linear brightness gradient
        for (int i = 0; i < totalPixels; i++) {
            float d = dist[i];
            if (d >= maxVal) {
                output[i] = 255;
            } else {
                // Isodistance contour lines every 16 units
                int contour = ((int) d) % 16;
                int baseIntensity = (int) ((d / maxDist) * 200.0f);
                if (contour <= 1) {
                    output[i] = 255; // White contour fringe line
                } else {
                    output[i] = Math.min(240, baseIntensity + 20);
                }
            }
        }
    }

    /**
     * 2.6.3 Image Subtraction / Digital Subtraction Angiography (DSA).
     * Implements g(x, y) = c * |f(x, y) - h(x, y)|.
     * Simulates mask-mode radiography: the pre-contrast anatomy f(x,y) contains dense bone
     * (ribs/spine/skull); the live image h(x,y) contains the anatomy plus an injected radiopaque
     * iodine contrast microvascular tree. Subtraction cancels stationary bone/tissue structures,
     * highlighting the isolated blood vessels.
     *
     * @param input         Input medical scan (live contrast frame)
     * @param output        Output subtracted angiogram [0..255]
     * @param width         Image width
     * @param height        Image height
     * @param contrastBoost Amplification gain c (e.g. 1.0 to 4.0)
     */
    public static void applyDigitalSubtractionAngiography(
            @NonNull int[] input,
            @NonNull int[] output,
            int width,
            int height,
            float contrastBoost) {

        float cx = width / 2.0f;
        float cy = height / 2.0f;
        float boost = Math.max(1.0f, contrastBoost);

        // Synthesize simulated pre-contrast mask image f0 (stationary background)
        // and add vascular tree to input image to simulate contrast dye injection
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            float ny = (y - cy) / cy;

            for (int x = 0; x < width; x++) {
                float nx = (x - cx) / cx;

                // Simulate arterial tree: bifurcating tree structure
                double artery1 = Math.abs(nx - Math.sin(ny * 4.0) * 0.25);
                double artery2 = Math.abs(nx + 0.25 - Math.cos(ny * 5.0) * 0.2);
                double artery3 = Math.abs(ny - Math.sin(nx * 6.0) * 0.15);

                boolean isVessel = (artery1 < 0.02) || (artery2 < 0.015) || (artery3 < 0.012);

                int maskPixel = input[rowOffset + x];
                // In radiopaque angiography, contrast agent darkens the vessel in X-ray
                int livePixel = isVessel ? Math.max(0, maskPixel - 90) : maskPixel;

                // Subtraction: difference image cancels non-vascular background
                int diff = Math.abs(livePixel - maskPixel);
                int enhanced = (int) (diff * boost * 2.5f);
                output[rowOffset + x] = Math.min(255, Math.max(0, enhanced));
            }
        }
    }
}
