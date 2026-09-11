package com.example.mediscan.engine;

/**
 * Morphological Image Processing Engine implementing Gonzalez & Woods (4th Ed.) Chapter 9.
 * Computes binary erosion, dilation, opening, closing, and boundary extraction
 * with customizable 3x3 structuring elements (Square, Cross, Disk).
 */
public final class MorphologyProcessor {

    public enum StructuringElementType {
        SQUARE,
        CROSS,
        DISK
    }

    private MorphologyProcessor() {
        // Pure mathematical processor
    }

    /**
     * Generates a 3x3 binary Structuring Element (SE) matrix.
     */
    public static int[][] getStructuringElement(StructuringElementType type) {
        switch (type) {
            case CROSS:
                return new int[][]{
                        {0, 1, 0},
                        {1, 1, 1},
                        {0, 1, 0}
                };
            case DISK:
                return new int[][]{
                        {0, 1, 0},
                        {1, 1, 1},
                        {0, 1, 0}
                };
            case SQUARE:
            default:
                return new int[][]{
                        {1, 1, 1},
                        {1, 1, 1},
                        {1, 1, 1}
                };
        }
    }

    /**
     * Binary Erosion: A (-) B = { z | (B)_z is subset of A }
     * An output pixel is set to 255 if and only if EVERY structuring element position matches 255 in input.
     */
    public static void applyErosion(
            int[] input,
            int[] output,
            int width,
            int height,
            int[][] se) {

        int seHeight = se.length;
        int seWidth = se[0].length;
        int radiusY = seHeight / 2;
        int radiusX = seWidth / 2;

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                boolean fits = true;

                for (int sy = 0; sy < seHeight; sy++) {
                    int sampleY = y + sy - radiusY;
                    int sampleRow = sampleY * width;

                    for (int sx = 0; sx < seWidth; sx++) {
                        if (se[sy][sx] == 1) {
                            int sampleX = x + sx - radiusX;
                            // Outside boundaries counts as background (0)
                            if (sampleX < 0 || sampleX >= width || sampleY < 0 || sampleY >= height) {
                                fits = false;
                                break;
                            }
                            if (input[sampleRow + sampleX] < 128) {
                                fits = false;
                                break;
                            }
                        }
                    }
                    if (!fits) break;
                }

                output[rowOffset + x] = fits ? 255 : 0;
            }
        }
    }

    /**
     * Binary Dilation: A (+) B = { z | (B_hat)_z intersects A }
     * An output pixel is set to 255 if ANY structuring element position touches 255 in input.
     */
    public static void applyDilation(
            int[] input,
            int[] output,
            int width,
            int height,
            int[][] se) {

        int seHeight = se.length;
        int seWidth = se[0].length;
        int radiusY = seHeight / 2;
        int radiusX = seWidth / 2;

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                boolean hits = false;

                for (int sy = 0; sy < seHeight; sy++) {
                    int sampleY = y + sy - radiusY;
                    if (sampleY < 0 || sampleY >= height) continue;
                    int sampleRow = sampleY * width;

                    for (int sx = 0; sx < seWidth; sx++) {
                        if (se[sy][sx] == 1) {
                            int sampleX = x + sx - radiusX;
                            if (sampleX < 0 || sampleX >= width) continue;

                            if (input[sampleRow + sampleX] >= 128) {
                                hits = true;
                                break;
                            }
                        }
                    }
                    if (hits) break;
                }

                output[rowOffset + x] = hits ? 255 : 0;
            }
        }
    }

    /**
     * Morphological Opening: A o B = (A (-) B) (+) B
     * Erosion followed by Dilation.
     */
    public static void applyOpening(
            int[] input,
            int[] output,
            int width,
            int height,
            int[][] se) {

        int[] temp = new int[width * height];
        applyErosion(input, temp, width, height, se);
        applyDilation(temp, output, width, height, se);
    }

    /**
     * Morphological Closing: A . B = (A (+) B) (-) B
     * Dilation followed by Erosion.
     */
    public static void applyClosing(
            int[] input,
            int[] output,
            int width,
            int height,
            int[][] se) {

        int[] temp = new int[width * height];
        applyDilation(input, temp, width, height, se);
        applyErosion(temp, output, width, height, se);
    }

    /**
     * Morphological Boundary Extraction: beta(A) = A - (A (-) B)
     * Subtracts the eroded image from the original set.
     */
    public static void applyBoundaryExtraction(
            int[] input,
            int[] output,
            int width,
            int height,
            int[][] se) {

        int[] eroded = new int[width * height];
        applyErosion(input, eroded, width, height, se);

        int total = width * height;
        for (int i = 0; i < total; i++) {
            int originalVal = input[i] >= 128 ? 255 : 0;
            int erodedVal = eroded[i] >= 128 ? 255 : 0;
            // Set difference A - (A (-) B)
            output[i] = (originalVal == 255 && erodedVal == 0) ? 255 : 0;
        }
    }
}
