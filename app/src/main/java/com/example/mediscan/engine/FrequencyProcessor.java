package com.example.mediscan.engine;

/**
 * 2D Frequency Domain Image Processing Engine.
 * Implements 1D and 2D Radix-2 Cooley-Tukey Fast Fourier Transform (FFT),
 * FFT Shift frequency centering, Magnitude Spectrum Log Visualization,
 * and Spectral Filtering (Ideal Lowpass & Gaussian Lowpass).
 * Reference: Gonzalez & Woods, Digital Image Processing (4th Ed.), Chapter 4.
 */
public final class FrequencyProcessor {

    private FrequencyProcessor() {
        // Pure mathematical processor
    }

    /**
     * Finds the largest power of 2 less than or equal to n, clamped to a max size for real-time mobile DSP.
     */
    public static int getValidFftDimension(int size, int maxSize) {
        int p = 1;
        while ((p << 1) <= size && (p << 1) <= maxSize) {
            p <<= 1;
        }
        return Math.max(64, p);
    }

    /**
     * 1D In-Place Radix-2 Decimation-in-Time (DIT) Cooley-Tukey FFT.
     * Computes X[k] = sum_{n=0}^{N-1} x[n] * exp(-/+ j * 2 * pi * k * n / N)
     *
     * @param real   Real component array of length N (N must be a power of 2)
     * @param imag   Imaginary component array of length N
     * @param invert If true, computes the Inverse FFT (IFFT)
     */
    public static void fft1D(double[] real, double[] imag, boolean invert) {
        int n = real.length;
        if (n <= 1) return;

        // 1. Bit-reversal permutation
        int j = 0;
        for (int i = 0; i < n - 1; i++) {
            if (i < j) {
                double tempR = real[i];
                real[i] = real[j];
                real[j] = tempR;

                double tempI = imag[i];
                imag[i] = imag[j];
                imag[j] = tempI;
            }
            int k = n >> 1;
            while (k <= j) {
                j -= k;
                k >>= 1;
            }
            j += k;
        }

        // 2. Cooley-Tukey iterative butterfly computation
        for (int len = 2; len <= n; len <<= 1) {
            int halfLen = len >> 1;
            double angle = 2.0 * Math.PI / len * (invert ? 1.0 : -1.0);
            double wStepR = Math.cos(angle);
            double wStepI = Math.sin(angle);

            for (int i = 0; i < n; i += len) {
                double wR = 1.0;
                double wI = 0.0;

                for (int m = 0; m < halfLen; m++) {
                    int posA = i + m;
                    int posB = i + m + halfLen;

                    double uR = real[posA];
                    double uI = imag[posA];

                    // Complex multiplication: (wR + j*wI) * (real[posB] + j*imag[posB])
                    double vR = real[posB] * wR - imag[posB] * wI;
                    double vI = real[posB] * wI + imag[posB] * wR;

                    real[posA] = uR + vR;
                    imag[posA] = uI + vI;

                    real[posB] = uR - vR;
                    imag[posB] = uI - vI;

                    // Next twiddle factor
                    double nextWR = wR * wStepR - wI * wStepI;
                    double nextWI = wR * wStepI + wI * wStepR;
                    wR = nextWR;
                    wI = nextWI;
                }
            }
        }

        // Normalization for IFFT
        if (invert) {
            double invN = 1.0 / n;
            for (int i = 0; i < n; i++) {
                real[i] *= invN;
                imag[i] *= invN;
            }
        }
    }

    /**
     * 2D Fast Fourier Transform via Matrix Separability:
     * 1. 1D FFT along each row
     * 2. 1D FFT along each column
     *
     * @param real   1D row-major array of size N x N
     * @param imag   1D row-major array of size N x N
     * @param n      Dimension size (must be power of 2)
     * @param invert If true, computes 2D IFFT
     */
    public static void fft2D(double[] real, double[] imag, int n, boolean invert) {
        double[] rowR = new double[n];
        double[] rowI = new double[n];
        double[] colR = new double[n];
        double[] colI = new double[n];

        // Step 1: 1D FFT on rows
        for (int y = 0; y < n; y++) {
            int offset = y * n;
            System.arraycopy(real, offset, rowR, 0, n);
            System.arraycopy(imag, offset, rowI, 0, n);

            fft1D(rowR, rowI, invert);

            System.arraycopy(rowR, 0, real, offset, n);
            System.arraycopy(rowI, 0, imag, offset, n);
        }

        // Step 2: 1D FFT on columns
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                int idx = y * n + x;
                colR[y] = real[idx];
                colI[y] = imag[idx];
            }

            fft1D(colR, colI, invert);

            for (int y = 0; y < n; y++) {
                int idx = y * n + x;
                real[idx] = colR[y];
                imag[idx] = colI[y];
            }
        }
    }

    /**
     * Computes the 2D Centered Fourier Magnitude Spectrum for display:
     * 1. Multiplies input by (-1)^(x+y) to shift DC component to center (M/2, N/2).
     * 2. Computes 2D FFT.
     * 3. Calculates magnitude: |F(u, v)| = sqrt(R^2 + I^2).
     * 4. Compresses dynamic range via log transform: s = c * log(1 + |F(u, v)|).
     */
    public static void computeMagnitudeSpectrum(
            int[] input,
            int inWidth,
            int inHeight,
            int[] output,
            int fftSize) {

        double[] real = new double[fftSize * fftSize];
        double[] imag = new double[fftSize * fftSize];

        // Center sample and copy with (-1)^(x+y) FFT shift
        int startX = Math.max(0, (inWidth - fftSize) / 2);
        int startY = Math.max(0, (inHeight - fftSize) / 2);

        for (int y = 0; y < fftSize; y++) {
            int srcY = Math.min(inHeight - 1, startY + y);
            int srcRow = srcY * inWidth;
            int fftRow = y * fftSize;

            for (int x = 0; x < fftSize; x++) {
                int srcX = Math.min(inWidth - 1, startX + x);
                int val = input[srcRow + srcX];

                // Centering multiplication: f(x, y) * (-1)^(x+y)
                double sign = ((x + y) % 2 == 0) ? 1.0 : -1.0;
                real[fftRow + x] = val * sign;
                imag[fftRow + x] = 0.0;
            }
        }

        // Forward 2D FFT
        fft2D(real, imag, fftSize, false);

        // Magnitude array and peak detection
        double[] mag = new double[fftSize * fftSize];
        double maxMag = 0.0;

        for (int i = 0; i < mag.length; i++) {
            double m = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
            mag[i] = m;
            if (m > maxMag) {
                maxMag = m;
            }
        }

        // Logarithmic dynamic range compression: s = (255 / log(1 + maxMag)) * log(1 + m)
        double c = 255.0 / Math.log(1.0 + Math.max(1.0, maxMag));
        for (int i = 0; i < mag.length; i++) {
            int s = (int) (c * Math.log(1.0 + mag[i]) + 0.5);
            output[i] = Math.max(0, Math.min(255, s));
        }
    }

    /**
     * Applies a 2D Frequency Domain Spectral Filter (Ideal Lowpass or Gaussian Lowpass):
     * 1. Centering FFT Shift: f(x, y) * (-1)^(x+y)
     * 2. Forward 2D FFT -> F(u, v)
     * 3. Filter multiplication: G(u, v) = H(u, v) * F(u, v)
     * 4. Inverse 2D FFT -> g(x, y)
     * 5. Un-shift real component: Re{g(x, y)} * (-1)^(x+y)
     *
     * @param filterType 0: Ideal Lowpass (ILPF), 1: Gaussian Lowpass (GLPF)
     * @param cutoffD0   Cutoff frequency radius D_0
     */
    public static void applyFrequencyFilter(
            int[] input,
            int inWidth,
            int inHeight,
            int[] output,
            int fftSize,
            int filterType,
            double cutoffD0) {

        double[] real = new double[fftSize * fftSize];
        double[] imag = new double[fftSize * fftSize];

        int startX = Math.max(0, (inWidth - fftSize) / 2);
        int startY = Math.max(0, (inHeight - fftSize) / 2);

        // 1. Centering multiplication (-1)^(x+y)
        for (int y = 0; y < fftSize; y++) {
            int srcY = Math.min(inHeight - 1, startY + y);
            int srcRow = srcY * inWidth;
            int fftRow = y * fftSize;

            for (int x = 0; x < fftSize; x++) {
                int srcX = Math.min(inWidth - 1, startX + x);
                double sign = ((x + y) % 2 == 0) ? 1.0 : -1.0;
                real[fftRow + x] = input[srcRow + srcX] * sign;
                imag[fftRow + x] = 0.0;
            }
        }

        // 2. Forward 2D FFT
        fft2D(real, imag, fftSize, false);

        // 3. Spectral Transfer Function H(u, v)
        double center = fftSize / 2.0;
        double twoD0Sq = 2.0 * cutoffD0 * cutoffD0;

        for (int v = 0; v < fftSize; v++) {
            double dv = v - center;
            int row = v * fftSize;

            for (int u = 0; u < fftSize; u++) {
                double du = u - center;
                double dist = Math.hypot(du, dv); // D(u, v)

                double H;
                if (filterType == 0) {
                    // Ideal Lowpass Filter (ILPF)
                    H = (dist <= cutoffD0) ? 1.0 : 0.0;
                } else {
                    // Gaussian Lowpass Filter (GLPF): exp(-D^2 / (2*D0^2))
                    H = Math.exp(-(dist * dist) / Math.max(1e-5, twoD0Sq));
                }

                // G(u, v) = H(u, v) * F(u, v)
                real[row + u] *= H;
                imag[row + u] *= H;
            }
        }

        // 4. Inverse 2D FFT
        fft2D(real, imag, fftSize, true);

        // 5. Un-shift real part: Re{g(x, y)} * (-1)^(x+y) and clamp to [0..255]
        for (int y = 0; y < fftSize; y++) {
            int row = y * fftSize;
            for (int x = 0; x < fftSize; x++) {
                double sign = ((x + y) % 2 == 0) ? 1.0 : -1.0;
                double val = real[row + x] * sign;
                int intVal = (int) Math.round(val);
                output[row + x] = Math.max(0, Math.min(255, intVal));
            }
        }
    }
}
