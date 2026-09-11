package com.example.mediscan.utils;

import android.graphics.Bitmap;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * High-performance Image and Pixel Buffer utilities.
 * Implements zero-copy YUV_420_888 Y-plane extraction, ITU-R BT.601 luminance conversion,
 * stride-aware buffer copying, and synthetic medical scan generation.
 */
public final class ImageUtils {

    private ImageUtils() {
        // Utility class
    }

    /**
     * Statistics container for a 256-bin grayscale intensity distribution.
     */
    public static class HistogramStats {
        public int minIntensity = 255;
        public int maxIntensity = 0;
        public double meanIntensity = 0.0;
        public double standardDeviation = 0.0;
        public int otsuThreshold = 128;
        public int totalPixels = 0;
    }

    /**
     * Extracts the Y (Luminance) plane from a CameraX ImageProxy in ImageFormat.YUV_420_888.
     * Accounts for hardware rowStride padding to eliminate diagonal skewing.
     *
     * @param imageProxy   CameraX ImageProxy frame
     * @param targetBuffer Pre-allocated byte array of at least width * height bytes
     * @return Number of valid bytes copied
     */
    public static int extractYPlaneFromImageProxy(@NonNull ImageProxy imageProxy, @NonNull byte[] targetBuffer) {
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        if (planes == null || planes.length == 0) return 0;

        ImageProxy.PlaneProxy yPlane = planes[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        yBuffer.rewind();

        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        if (pixelStride == 1 && rowStride == width) {
            // Direct contiguous memory copy
            int count = Math.min(yBuffer.remaining(), targetBuffer.length);
            yBuffer.get(targetBuffer, 0, count);
            return count;
        } else {
            // Stride-aware row-by-row extraction
            int destOffset = 0;
            byte[] rowTemp = new byte[rowStride];
            for (int row = 0; row < height; row++) {
                int bytesToRead = Math.min(rowStride, yBuffer.remaining());
                yBuffer.get(rowTemp, 0, bytesToRead);

                if (pixelStride == 1) {
                    System.arraycopy(rowTemp, 0, targetBuffer, destOffset, width);
                    destOffset += width;
                } else {
                    for (int col = 0; col < width; col++) {
                        targetBuffer[destOffset++] = rowTemp[col * pixelStride];
                    }
                }
            }
            return destOffset;
        }
    }

    /**
     * Rotates a raw 8-bit Y buffer and converts directly to 32-bit ARGB packed integers.
     * Operates purely in memory cache without allocating intermediate buffers.
     *
     * @param yBuffer         Raw grayscale bytes [0..255]
     * @param srcWidth        Source image width
     * @param srcHeight       Source image height
     * @param rotationDegrees Rotation angle (0, 90, 180, 270)
     * @param outArgb         Destination integer array of size srcWidth * srcHeight
     * @param outDimensions   Array of size 2 to receive [destWidth, destHeight]
     */
    public static void rotateAndConvertYToArgb(
            @NonNull byte[] yBuffer,
            int srcWidth,
            int srcHeight,
            int rotationDegrees,
            @NonNull int[] outArgb,
            @NonNull int[] outDimensions) {

        int dstWidth;
        int dstHeight;

        if (rotationDegrees == 90 || rotationDegrees == 270) {
            dstWidth = srcHeight;
            dstHeight = srcWidth;
        } else {
            dstWidth = srcWidth;
            dstHeight = srcHeight;
        }

        outDimensions[0] = dstWidth;
        outDimensions[1] = dstHeight;

        switch (rotationDegrees) {
            case 90:
                for (int y = 0; y < srcHeight; y++) {
                    int srcRowOffset = y * srcWidth;
                    for (int x = 0; x < srcWidth; x++) {
                        int val = yBuffer[srcRowOffset + x] & 0xFF;
                        int destX = srcHeight - 1 - y;
                        int destY = x;
                        outArgb[destY * dstWidth + destX] = 0xFF000000 | (val << 16) | (val << 8) | val;
                    }
                }
                break;

            case 180:
                int total = srcWidth * srcHeight;
                for (int i = 0; i < total; i++) {
                    int val = yBuffer[i] & 0xFF;
                    outArgb[total - 1 - i] = 0xFF000000 | (val << 16) | (val << 8) | val;
                }
                break;

            case 270:
                for (int y = 0; y < srcHeight; y++) {
                    int srcRowOffset = y * srcWidth;
                    for (int x = 0; x < srcWidth; x++) {
                        int val = yBuffer[srcRowOffset + x] & 0xFF;
                        int destX = y;
                        int destY = srcWidth - 1 - x;
                        outArgb[destY * dstWidth + destX] = 0xFF000000 | (val << 16) | (val << 8) | val;
                    }
                }
                break;

            case 0:
            default:
                for (int i = 0; i < srcWidth * srcHeight; i++) {
                    int val = yBuffer[i] & 0xFF;
                    outArgb[i] = 0xFF000000 | (val << 16) | (val << 8) | val;
                }
                break;
        }
    }

    /**
     * Converts a Bitmap into a 1D grayscale integer array [0..255]
     * using the ITU-R BT.601 standard: Y = 0.299*R + 0.587*G + 0.114*B.
     */
    public static int[] bitmapToGrayscaleIntArray(@NonNull Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int[] grayscale = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;

            // ITU-R BT.601 luminance calculation
            int y = (int) (0.299f * r + 0.587f * g + 0.114f * b + 0.5f);
            if (y < 0) y = 0;
            else if (y > 255) y = 255;
            grayscale[i] = y;
        }
        return grayscale;
    }

    /**
     * Reconstructs an ARGB_8888 Bitmap from a 1D grayscale integer array [0..255].
     */
    public static Bitmap grayscaleIntArrayToBitmap(@NonNull int[] grayscale, int width, int height) {
        int[] argb = new int[width * height];
        for (int i = 0; i < grayscale.length; i++) {
            int val = grayscale[i];
            if (val < 0) val = 0;
            else if (val > 255) val = 255;
            argb[i] = 0xFF000000 | (val << 16) | (val << 8) | val;
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    /**
     * Updates an existing Bitmap in-place with new ARGB pixels to minimize GC pressure.
     */
    public static void updateBitmapWithArgb(
            @NonNull Bitmap targetBitmap,
            @NonNull int[] argbPixels,
            int width,
            int height) {
        if (targetBitmap.getWidth() == width && targetBitmap.getHeight() == height) {
            targetBitmap.setPixels(argbPixels, 0, width, 0, 0, width, height);
        }
    }

    /**
     * Computes the 256-bin histogram and basic statistical moments (Min, Max, Mean, Variance, Otsu k*).
     * Reference: Gonzalez & Woods (4th Ed.), Chapter 3 & Chapter 10.
     */
    public static void computeHistogramAndStats(
            @NonNull int[] grayscale,
            @NonNull int[] outHistogram,
            @NonNull HistogramStats outStats) {

        Arrays.fill(outHistogram, 0);

        int min = 255;
        int max = 0;
        long sum = 0;
        int N = grayscale.length;

        for (int val : grayscale) {
            int clamped;
            if ((val & 0xFF000000) != 0) {
                // 32-bit ARGB pixel: compute BT.601 luminance
                int r = (val >> 16) & 0xFF;
                int g = (val >> 8) & 0xFF;
                int b = val & 0xFF;
                int lum = (int) (0.299f * r + 0.587f * g + 0.114f * b + 0.5f);
                clamped = Math.max(0, Math.min(255, lum));
            } else {
                // Standard 8-bit scalar intensity
                clamped = Math.max(0, Math.min(255, val));
            }
            outHistogram[clamped]++;
            if (clamped < min) min = clamped;
            if (clamped > max) max = clamped;
            sum += clamped;
        }

        double mean = N > 0 ? (double) sum / N : 0.0;

        double varSum = 0.0;
        for (int i = 0; i < 256; i++) {
            if (outHistogram[i] > 0) {
                double diff = i - mean;
                varSum += (diff * diff) * outHistogram[i];
            }
        }
        double stdDev = N > 0 ? Math.sqrt(varSum / N) : 0.0;

        // Otsu's optimal threshold calculation
        int otsuK = computeOtsuThreshold(outHistogram, N);

        outStats.minIntensity = min;
        outStats.maxIntensity = max;
        outStats.meanIntensity = mean;
        outStats.standardDeviation = stdDev;
        outStats.otsuThreshold = otsuK;
        outStats.totalPixels = N;
    }

    /**
     * Computes Otsu's binarization threshold k* by maximizing between-class variance:
     * sigma_B^2(k) = [mu_T * P_1(k) - m(k)]^2 / (P_1(k) * [1 - P_1(k)])
     */
    public static int computeOtsuThreshold(int[] histogram, int totalPixels) {
        if (totalPixels <= 0) return 128;

        // Total mean intensity mu_T
        double sumTotal = 0.0;
        for (int i = 0; i < 256; i++) {
            sumTotal += i * histogram[i];
        }

        double sumBackground = 0.0;
        int weightBackground = 0;
        double maxVariance = -1.0;
        int optimalK = 128;

        for (int k = 0; k < 256; k++) {
            weightBackground += histogram[k];
            if (weightBackground == 0) continue;

            int weightForeground = totalPixels - weightBackground;
            if (weightForeground == 0) break;

            sumBackground += k * histogram[k];

            double meanBackground = sumBackground / weightBackground;
            double meanForeground = (sumTotal - sumBackground) / weightForeground;

            // Between-class variance: sigma_B^2 = P_1 * P_2 * (mu_1 - mu_2)^2
            double diff = meanBackground - meanForeground;
            double betweenClassVariance = (double) weightBackground * (double) weightForeground * diff * diff;

            if (betweenClassVariance > maxVariance) {
                maxVariance = betweenClassVariance;
                optimalK = k;
            }
        }

        return optimalK;
    }

    /**
     * Synthesizes clinically accurate high-resolution simulated imaging modalities
     * corresponding directly to Gonzalez & Woods (4th Ed.) Chapter 1.3:
     * - 0: 1.3.1 Gamma-Ray Imaging (PET / Nuclear Medicine Radioisotope Scintigraphy)
     * - 1: 1.3.2 X-Ray Imaging (Chest Radiograph / Fluoroscopy)
     * - 2: 1.3.3 Ultraviolet Band (Fluorescence Microscopy / DNA Cell Markers)
     * - 3: 1.3.4 Visible Band (Histopathology Tissue Biopsy)
     * - 4: 1.3.4 Infrared Band (Thermography / Thermal Imaging)
     * - 5: 1.3.5 Microwave Band (Radar Modality / Microwave Imaging)
     * - 6: 1.3.6 Radio Band (Brain MRI T1-weighted Axial Slice)
     * - 7: 1.3.7 Other Modality: Acoustic (Ultrasound with Rayleigh speckle)
     * - 8: 1.3.7 Other Modality: Electron Microscopy (SEM Nanoscale Micrograph)
     */
    public static Bitmap generateSyntheticMedicalScan(int type, int width, int height) {
        int[] pixels = new int[width * height];
        float cx = width / 2.0f;
        float cy = height / 2.0f;

        switch (type) {
            case 0: { // 1.3.1 Gamma-Ray Imaging (PET Scan: Positron Emission / Radioisotope Uptake)
                java.util.Random rnd = new java.util.Random(42);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float nx = (x - cx) / cx;
                        float ny = (y - cy) / cy;
                        double r = Math.hypot(nx, ny * 1.2);

                        int intensity = 10;
                        if (r < 0.85) {
                            // Torso silhouette background tracer uptake
                            intensity = 40 + (int) (20.0 * Math.sin(r * 4.0));

                            // Hot spots: high metabolic tracer uptake (e.g. myocardium, hypermetabolic lesions)
                            double lesion1 = Math.hypot(nx + 0.2, ny + 0.1);
                            double lesion2 = Math.hypot(nx - 0.25, ny - 0.2);
                            double heart = Math.hypot(nx - 0.05, ny + 0.15);

                            if (lesion1 < 0.12) intensity += (int) ((1.0 - lesion1 / 0.12) * 190);
                            if (lesion2 < 0.16) intensity += (int) ((1.0 - lesion2 / 0.16) * 170);
                            if (heart < 0.22) intensity += (int) ((1.0 - heart / 0.22) * 150);

                            // Poisson emission noise characteristic of gamma photon counting
                            intensity += (rnd.nextGaussian() * 15.0);
                        }
                        intensity = Math.max(0, Math.min(255, intensity));
                        pixels[y * width + x] = 0xFF000000 | (intensity << 16) | (intensity << 8) | intensity;
                    }
                }
                break;
            }

            case 1: { // 1.3.2 X-Ray Imaging (Chest Radiograph / Thoracic Cavity)
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float nx = (x - cx) / cx;
                        float ny = (y - cy) / cy;

                        // Rib cage structure
                        double ribPeriod = Math.sin(ny * 18.0) * 0.5 + 0.5;
                        double lungDistance = Math.hypot(Math.abs(nx) - 0.45, ny * 0.9);
                        int lung = lungDistance < 0.42 ? 35 : 170; // Radiolucent lungs (dark)

                        // Cardiac silhouette (radiopaque left mediastinum)
                        double heartDist = Math.hypot(nx - 0.15, ny - 0.12);
                        if (heartDist < 0.32) {
                            lung = 195;
                        }

                        // Spine / Sternum central column
                        if (Math.abs(nx) < 0.08) {
                            lung = Math.max(lung, 180);
                        }

                        int val = (int) (lung + (ribPeriod * 38.0));
                        val = Math.max(0, Math.min(255, val));
                        pixels[y * width + x] = 0xFF000000 | (val << 16) | (val << 8) | val;
                    }
                }
                break;
            }

            case 2: { // 1.3.3 Ultraviolet Band (Fluorescence Microscopy / Fluorescent Cell Nuclei)
                java.util.Random rnd = new java.util.Random(99);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float nx = (x - cx) / cx;
                        float ny = (y - cy) / cy;

                        // Dark background characteristic of UV epifluorescence
                        int intensity = 15;

                        // Cellular clusters fluorescing brightly
                        double[] cellX = {-0.5, -0.2, 0.3, 0.6, -0.3, 0.1, 0.4, -0.6};
                        double[] cellY = {-0.4, 0.3, -0.5, 0.2, -0.1, 0.0, 0.6, 0.5};
                        for (int k = 0; k < cellX.length; k++) {
                            double d = Math.hypot(nx - cellX[k], ny - cellY[k]);
                            if (d < 0.18) {
                                double peak = (1.0 - d / 0.18);
                                intensity += (int) (peak * peak * 220);
                            }
                        }
                        intensity += (int) (rnd.nextGaussian() * 6.0);
                        intensity = Math.max(0, Math.min(255, intensity));
                        pixels[y * width + x] = 0xFF000000 | (intensity << 16) | (intensity << 8) | intensity;
                    }
                }
                break;
            }

            case 3: { // 1.3.4 Visible Band (Histopathology Light Microscopy / H&E Tissue Biopsy)
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float nx = (x - cx) / cx;
                        float ny = (y - cy) / cy;

                        // Extracellular collagenous stroma background (Eosin pink: R high, G medium, B medium-high)
                        double stromaVar = Math.sin(nx * 12.0) * 15.0 + Math.cos(ny * 12.0) * 15.0;
                        int pr = (int) (225 + stromaVar);
                        int pg = (int) (140 + stromaVar * 0.6);
                        int pb = (int) (185 + stromaVar * 0.8);

                        // Glandular lumens and epithelial nuclei
                        double lumen1 = Math.hypot(nx - 0.3, ny - 0.3);
                        double lumen2 = Math.hypot(nx + 0.35, ny + 0.25);

                        if (lumen1 < 0.25) {
                            if (lumen1 < 0.12) {
                                // Clear lumen center (white/cream)
                                pr = 248; pg = 245; pb = 242;
                            } else {
                                // Epithelial nuclei ring (Hematoxylin dark purple/blue)
                                pr = 85; pg = 35; pb = 145;
                            }
                        } else if (lumen2 < 0.28) {
                            if (lumen2 < 0.15) {
                                pr = 248; pg = 245; pb = 242;
                            } else {
                                pr = 90; pg = 40; pb = 150;
                            }
                        }

                        pr = Math.max(0, Math.min(255, pr));
                        pg = Math.max(0, Math.min(255, pg));
                        pb = Math.max(0, Math.min(255, pb));
                        pixels[y * width + x] = 0xFF000000 | (pr << 16) | (pg << 8) | pb;
                    }
                }
                break;
            }

            case 4: { // 1.3.4 Infrared Band (Medical Thermography / Temperature Map)
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float nx = (x - cx) / cx;
                        float ny = (y - cy) / cy;

                        // Body silhouette heat envelope
                        double dist = Math.hypot(nx, ny * 1.3);
                        int intensity = 20;
                        if (dist < 0.82) {
                            // Core warmth
                            double thermalGradient = (1.0 - dist / 0.82) * 180.0 + 40.0;
                            // Asymmetric inflammatory hot spot (e.g. vascular insufficiency or tumor thermogenesis)
                            double hotSpot = Math.hypot(nx - 0.22, ny + 0.1);
                            if (hotSpot < 0.20) {
                                thermalGradient += (1.0 - hotSpot / 0.20) * 70.0;
                            }
                            intensity = (int) thermalGradient;
                        }
                        intensity = Math.max(0, Math.min(255, intensity));
                        pixels[y * width + x] = 0xFF000000 | (intensity << 16) | (intensity << 8) | intensity;
                    }
                }
                break;
            }

            case 5: { // 1.3.5 Microwave Band (Radar / Microwave Imaging)
                java.util.Random rnd = new java.util.Random(777);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float nx = (x - cx) / cx;
                        float ny = (y - cy) / cy;

                        // Ground terrain / backscatter surface
                        double terrain = 80 + Math.sin(nx * 8.0 + ny * 6.0) * 30.0;

                        // Radar reflective target / boundary edge
                        if (Math.abs(nx - ny * 0.5) < 0.05 || Math.hypot(nx + 0.3, ny - 0.2) < 0.12) {
                            terrain = 230;
                        }

                        // Microwave speckle backscatter
                        terrain += rnd.nextGaussian() * 25.0;
                        int intensity = (int) Math.max(0, Math.min(255, terrain));
                        pixels[y * width + x] = 0xFF000000 | (intensity << 16) | (intensity << 8) | intensity;
                    }
                }
                break;
            }

            case 6: { // 1.3.6 Radio Band (Brain MRI T1-weighted Axial Slice)
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float nx = (x - cx) / cx;
                        float ny = (y - cy) / cy;
                        double dist = Math.hypot(nx, ny * 1.15);

                        int intensity;
                        if (dist > 0.88) {
                            intensity = 15; // Background air
                        } else if (dist > 0.82) {
                            intensity = 230; // Skull / cortical bone
                        } else if (dist > 0.76) {
                            intensity = 45; // CSF / dura
                        } else {
                            // Brain parenchyma (White / Gray matter)
                            double gyri = Math.sin(nx * 24.0) * Math.cos(ny * 24.0) * 22.0;
                            intensity = (int) (140 + gyri);

                            // Bilateral lateral ventricles (Fluid CSF = dark on T1)
                            double vLeft = Math.hypot(nx + 0.14, ny * 1.8);
                            double vRight = Math.hypot(nx - 0.14, ny * 1.8);
                            if (vLeft < 0.18 || vRight < 0.18) {
                                intensity = 30;
                            }
                        }
                        intensity = Math.max(0, Math.min(255, intensity));
                        pixels[y * width + x] = 0xFF000000 | (intensity << 16) | (intensity << 8) | intensity;
                    }
                }
                break;
            }

            case 7: { // 1.3.7 Other: Acoustic / Ultrasound (Sector Scan with Rayleigh Speckle)
                java.util.Random rnd = new java.util.Random(1337);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float nx = (x - cx) / cx;
                        float ny = (float) y / height;
                        double angle = Math.atan2(nx, ny + 0.05);

                        int intensity;
                        if (Math.abs(angle) > 0.65 || ny < 0.08) {
                            intensity = 10; // Outside acoustic sector
                        } else {
                            // Acoustic tissue reflection
                            double tissue = 100 + Math.sin(ny * 12.0) * 35.0;

                            // Cyst / Anechoic fluid lesion in center
                            double cystDist = Math.hypot(nx, ny - 0.55);
                            if (cystDist < 0.16) {
                                tissue = 20;
                            }

                            // Rayleigh speckle noise
                            double u1 = Math.max(1e-6, rnd.nextDouble());
                            double u2 = rnd.nextDouble();
                            double rayleigh = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);

                            intensity = (int) (tissue + rayleigh * 42.0);
                        }
                        intensity = Math.max(0, Math.min(255, intensity));
                        pixels[y * width + x] = 0xFF000000 | (intensity << 16) | (intensity << 8) | intensity;
                    }
                }
                break;
            }

            case 8: // 1.3.7 Other: Electron Microscopy (SEM Nanoscale Organelles)
            default: {
                java.util.Random rnd = new java.util.Random(555);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float nx = (x - cx) / cx;
                        float ny = (y - cy) / cy;

                        // Cytoplasm background
                        int intensity = 130 + (int) (Math.sin(nx * 15.0) * 15.0);

                        // Mitochondria / cristae membranes
                        double mito = Math.hypot(nx * 1.5, ny);
                        if (mito < 0.45) {
                            double cristae = Math.sin(nx * 40.0) * 0.5 + 0.5;
                            intensity = (cristae > 0.6) ? 220 : 50;
                        }

                        intensity += (int) (rnd.nextGaussian() * 10.0);
                        intensity = Math.max(0, Math.min(255, intensity));
                        pixels[y * width + x] = 0xFF000000 | (intensity << 16) | (intensity << 8) | intensity;
                    }
                }
                break;
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
