package com.example.mediscan.engine;

import androidx.annotation.NonNull;

/**
 * Gonzalez & Woods (4th Edition) Chapter 6: Color Image Processing.
 *
 * Implements pure unassisted Java mathematical operations on int[] pixels:
 * - 6.2 Color Models: RGB to HSI (Hue, Saturation, Intensity) conversion & visualization
 * - 6.3 Pseudocolor Image Processing:
 *   - 6.3.1 Intensity Slicing (Color-coded diagnostic tissue zoning)
 *   - 6.3.2 Gray-Level to Color Transformation (Rainbow/Jet, Medical Thermal, PET Hot Metal)
 */
public final class ColorProcessingProcessor {

    private ColorProcessingProcessor() {
        // Prevent instantiation
    }

    public enum ColormapType {
        RAINBOW_JET,
        MEDICAL_THERMAL_HOT,
        PET_HOT_METAL,
        INTENSITY_SLICING_ISOPHOTES
    }

    /**
     * 6.3.2 Gray-Level to Color Transformations (Pseudocoloring).
     * Maps scalar intensity in [0, 255] to a 32-bit ARGB diagnostic false-color mapping.
     *
     * @param input        Input array containing grayscale [0..255] or ARGB pixels
     * @param output       Output array receiving packed 32-bit ARGB colors (0xFFRRGGBB)
     * @param colormapType Diagnostic false-color palette
     */
    public static void applyPseudocolor(
            @NonNull int[] input,
            @NonNull int[] output,
            @NonNull ColormapType colormapType
    ) {
        int len = input.length;

        // Precompute 256-entry lookup table for O(1) pixel transformation
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = computeColormapPixel(i, colormapType);
        }

        for (int i = 0; i < len; i++) {
            int pixel = input[i];
            int gray;
            if ((pixel & 0xFF000000) != 0) {
                // If input is ARGB, extract BT.601 luminance
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                gray = (int) (0.299f * r + 0.587f * g + 0.114f * b + 0.5f);
            } else {
                gray = pixel & 0xFF;
            }
            gray = Math.max(0, Math.min(255, gray));
            output[i] = lut[gray];
        }
    }

    private static int computeColormapPixel(int gray, ColormapType type) {
        float norm = gray / 255.0f;
        int r = 0;
        int g = 0;
        int b = 0;

        switch (type) {
            case RAINBOW_JET: {
                // Standard 4-zone rainbow spectral distribution (Blue -> Cyan -> Green -> Yellow -> Red)
                if (norm < 0.25f) {
                    float t = norm / 0.25f;
                    r = 0;
                    g = (int) (t * 255);
                    b = 255;
                } else if (norm < 0.50f) {
                    float t = (norm - 0.25f) / 0.25f;
                    r = 0;
                    g = 255;
                    b = (int) ((1.0f - t) * 255);
                } else if (norm < 0.75f) {
                    float t = (norm - 0.50f) / 0.25f;
                    r = (int) (t * 255);
                    g = 255;
                    b = 0;
                } else {
                    float t = (norm - 0.75f) / 0.25f;
                    r = 255;
                    g = (int) ((1.0f - t) * 255);
                    b = 0;
                }
                break;
            }

            case MEDICAL_THERMAL_HOT: {
                // Black -> Red -> Yellow -> White (Diagnostic Thermography / Inflammation)
                if (norm < 0.33f) {
                    float t = norm / 0.33f;
                    r = (int) (t * 255);
                    g = 0;
                    b = 0;
                } else if (norm < 0.66f) {
                    float t = (norm - 0.33f) / 0.33f;
                    r = 255;
                    g = (int) (t * 255);
                    b = 0;
                } else {
                    float t = (norm - 0.66f) / 0.34f;
                    r = 255;
                    g = 255;
                    b = (int) (t * 255);
                }
                break;
            }

            case PET_HOT_METAL: {
                // Positron Emission Tomography radioactive FDG uptake scale: Black -> Violet -> Red -> Yellow -> White
                if (norm < 0.25f) {
                    float t = norm / 0.25f;
                    r = (int) (t * 120);
                    g = 0;
                    b = (int) (t * 160);
                } else if (norm < 0.55f) {
                    float t = (norm - 0.25f) / 0.30f;
                    r = (int) (120 + t * 135);
                    g = (int) (t * 40);
                    b = (int) (160 * (1.0f - t));
                } else if (norm < 0.85f) {
                    float t = (norm - 0.55f) / 0.30f;
                    r = 255;
                    g = (int) (40 + t * 190);
                    b = 0;
                } else {
                    float t = (norm - 0.85f) / 0.15f;
                    r = 255;
                    g = 255;
                    b = (int) (t * 255);
                }
                break;
            }

            case INTENSITY_SLICING_ISOPHOTES:
            default: {
                // 6.3.1 Discrete 8-zone slicing palette for diagnostic tissue zoning
                int zone = Math.min(7, gray / 32);
                switch (zone) {
                    case 0: r = 15;  g = 20;  b = 50;  break; // Air / background
                    case 1: r = 20;  g = 70;  b = 160; break; // Low attenuation / lung
                    case 2: r = 30;  g = 160; b = 220; break; // Fluid / CSF
                    case 3: r = 35;  g = 190; b = 95;  break; // Soft tissue
                    case 4: r = 200; g = 215; b = 35;  break; // Organ parenchyma
                    case 5: r = 245; g = 130; b = 20;  break; // Dense tissue / contrast
                    case 6: r = 235; g = 35;  b = 25;  break; // Hypervascular lesion
                    case 7: r = 255; g = 255; b = 255; break; // Cortical bone / calcification
                }
                break;
            }
        }

        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 6.2.3 & 6.2.4 RGB to HSI (Hue, Saturation, Intensity) Extraction.
     * Computes the mathematical HSI representation per Gonzalez & Woods:
     * - theta = arccos( 0.5 * ((R-G) + (R-B)) / sqrt((R-G)^2 + (R-B)(G-B)) )
     * - H = theta if B <= G, else 360 - theta
     * - S = 1 - 3/(R+G+B) * min(R,G,B)
     * - I = (R+G+B) / 3
     *
     * Outputs 8-bit scalar values [0..255] representing the decoupled channel.
     *
     * @param input     Input array of ARGB pixels or grayscale intensities
     * @param output    Output array receiving 8-bit channel values [0..255]
     * @param component 0: Hue, 1: Saturation, 2: Intensity
     */
    public static void extractHsiComponent(
            @NonNull int[] input,
            @NonNull int[] output,
            int component
    ) {
        int len = input.length;
        for (int i = 0; i < len; i++) {
            int pixel = input[i];
            float r, g, b;

            if ((pixel & 0xFF000000) != 0) {
                // True ARGB pixel
                r = ((pixel >> 16) & 0xFF) / 255.0f;
                g = ((pixel >> 8) & 0xFF) / 255.0f;
                b = (pixel & 0xFF) / 255.0f;
            } else {
                // Scalar monochrome pixel
                float gray = (pixel & 0xFF) / 255.0f;
                r = gray;
                g = gray;
                b = gray;
            }

            int val;
            if (component == 0) {
                // Hue in [0, 255]
                float sum = r + g + b;
                float minRgb = Math.min(r, Math.min(g, b));
                float maxRgb = Math.max(r, Math.max(g, b));

                if (maxRgb - minRgb < 1e-4f) {
                    // Achromatic / monochromatic pixel: Hue is 0
                    val = 0;
                } else {
                    float num = 0.5f * ((r - g) + (r - b));
                    float den = (float) Math.sqrt((r - g) * (r - g) + (r - b) * (g - b));
                    float ratio = Math.max(-1.0f, Math.min(1.0f, num / Math.max(1e-6f, den)));
                    float theta = (float) Math.toDegrees(Math.acos(ratio));
                    float h = (b <= g) ? theta : (360.0f - theta);
                    val = (int) ((h / 360.0f) * 255.0f);
                }
            } else if (component == 1) {
                // Saturation in [0, 255]
                float sum = r + g + b;
                if (sum <= 1e-5f) {
                    val = 0;
                } else {
                    float minRgb = Math.min(r, Math.min(g, b));
                    float s = 1.0f - (3.0f / sum) * minRgb;
                    val = (int) (Math.max(0.0f, Math.min(1.0f, s)) * 255.0f);
                }
            } else {
                // Intensity in [0, 255]
                float intensity = (r + g + b) / 3.0f;
                val = (int) (intensity * 255.0f);
            }

            output[i] = Math.max(0, Math.min(255, val));
        }
    }
}
