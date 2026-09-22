package com.example.mediscan.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.example.R;
import com.example.mediscan.engine.MedicalImageEngine;

/**
 * Diagnostic Decision Matrix: "When to Use Which Feature".
 * Connects clinical radiology imaging challenges to exact Digital Image Processing
 * mathematical solutions with one-tap execution.
 */
public class DiagnosticGuideDialog extends DialogFragment {

    public interface OnApplySolutionListener {
        void onApplySolution(MedicalImageEngine.Algorithm algorithm, int sampleScanIndex, float customParam);
    }

    private OnApplySolutionListener listener;

    public static DiagnosticGuideDialog newInstance() {
        return new DiagnosticGuideDialog();
    }

    public void setOnApplySolutionListener(OnApplySolutionListener listener) {
        this.listener = listener;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_diagnostic_guide, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageButton btnClose = view.findViewById(R.id.btn_close_guide);
        btnClose.setOnClickListener(v -> dismiss());

        setupCard(
                view.findViewById(R.id.card_gamma),
                "CHEST X-RAY / CT",
                "Gonzalez & Woods 3.2.2",
                "Problem: Low Contrast in Shadows / Underexposure",
                "Image is dark or narrow in dynamic range; pulmonary infiltrates or soft tissue structures are buried in shadow values.",
                "✓ Solution: Power-Law (Gamma γ < 1) or Histogram Equalization",
                "Formula: s = c · r^γ (γ = 0.5 expands dark values; CDF equalizes p(r_k))",
                MedicalImageEngine.Algorithm.GAMMA_CORRECTION,
                0,
                0.55f
        );

        setupCard(
                view.findViewById(R.id.card_log),
                "HIGH DYNAMIC RANGE CT",
                "Gonzalez & Woods 3.2.1",
                "Problem: Extreme Intensity Gap (Dense Bone vs Soft Tissue)",
                "Intense radiographic reflections from cortical bone overpower faint parenchyma and soft tissue details.",
                "✓ Solution: Logarithmic Dynamic Range Compression",
                "Formula: s = c · log(1 + r) — compresses high-intensity peaks and boosts shadows.",
                MedicalImageEngine.Algorithm.LOG_TRANSFORM,
                2,
                1.0f
        );

        setupCard(
                view.findViewById(R.id.card_median),
                "ULTRASOUND / ECHOCARDIOGRAPHY",
                "Gonzalez & Woods 3.5.3",
                "Problem: Acoustic Speckle & Impulse Dropouts",
                "Coherent acoustic wave interference creates speckle salt-and-pepper noise that obscures cardiac valve borders.",
                "✓ Solution: 3x3 Order-Statistic Non-Linear Median Filter",
                "Formula: g(x,y) = median{f(s,t)} — removes impulse outliers without blurring edges.",
                MedicalImageEngine.Algorithm.MEDIAN_FILTER,
                0,
                0.0f
        );

        setupCard(
                view.findViewById(R.id.card_wiener),
                "LOW-DOSE RADIOGRAPHY / MRI",
                "Gonzalez & Woods 5.8",
                "Problem: Additive Thermal Gaussian Noise Agitation",
                "Sensor thermal excitation produces continuous additive zero-mean Gaussian electronic noise across the image.",
                "✓ Solution: Local Adaptive Wiener Filter (MMSE)",
                "Formula: g = μ + [(σ² - σ_η²) / σ²](f - μ) — smooths flat noise, preserves sharp edges.",
                MedicalImageEngine.Algorithm.RESTORE_ADAPTIVE_WIENER,
                1,
                350.0f
        );

        setupCard(
                view.findViewById(R.id.card_dsa),
                "C-ARM FLUOROSCOPY / ANGIOGRAPHY",
                "Gonzalez & Woods 2.6.3",
                "Problem: Dense Skull & Rib Bones Occlude Arteries",
                "Diagnostic catheter angiograms are obscured by static rib and skull anatomy in baseline X-rays.",
                "✓ Solution: Digital Subtraction Angiography (DSA)",
                "Formula: g(x,y) = f_contrast(x,y) - f_mask(x,y) — cancels invariant static bone.",
                MedicalImageEngine.Algorithm.IMAGE_SUBTRACTION_DSA,
                4,
                2.5f
        );

        setupCard(
                view.findViewById(R.id.card_fft),
                "MRI RF COIL / GRID ARTIFACTS",
                "Gonzalez & Woods 4.5",
                "Problem: Periodic Horizontal/Vertical Stripe Interference",
                "Electromagnetic interference or scanning sensor defects generate repeating harmonic lines across the scan.",
                "✓ Solution: 2D FFT Spectrum & Gaussian Low-Pass Filter",
                "Formula: F(u,v) = 2D Fourier Transform — harmonic noise appears as impulse spikes.",
                MedicalImageEngine.Algorithm.FFT_SPECTRUM,
                1,
                36.0f
        );

        setupCard(
                view.findViewById(R.id.card_sobel),
                "ONCOLOGY / TUMOR MARGINS",
                "Gonzalez & Woods 3.6.2",
                "Problem: Subtle Lesion Boundaries & Organ Wall Delineation",
                "Neoplasm borders are difficult to delineate against surrounding parenchyma in standard grayscale.",
                "✓ Solution: Sobel Gradient Vector Magnitude M(x, y)",
                "Formula: M(x,y) = sqrt(Gx² + Gy²) — isolates spatial rate of change boundaries.",
                MedicalImageEngine.Algorithm.SOBEL_GRADIENTS,
                1,
                0.0f
        );

        setupCard(
                view.findViewById(R.id.card_otsu),
                "CYTOLOGY / HISTOPATHOLOGY",
                "Gonzalez & Woods 10.3",
                "Problem: Automated Nuclei Segmentation & Cell Counting",
                "Need objective, repeatable binarization of dark cell nuclei from light stroma for diagnostic staging.",
                "✓ Solution: Otsu's Optimum Between-Class Variance Thresholding",
                "Formula: T* = argmax σ_B²(T) = ω₀ω₁(μ₀ - μ₁)² — maximizes statistical class separation.",
                MedicalImageEngine.Algorithm.OTSU_BINARIZATION,
                3,
                0.0f
        );

        setupCard(
                view.findViewById(R.id.card_pseudocolor),
                "PET / NUCLEAR MEDICINE / ONCOLOGY",
                "Gonzalez & Woods 6.3",
                "Problem: Human Visual System Limited to ~30 Shades of Gray",
                "Subtle tissue radiotracer uptake differences in PET are undetectable to human grayscale perception.",
                "✓ Solution: Pseudocolor False-Color Palette (PET Hot Metal / Jet)",
                "Formula: Gray-level to RGB mapping assigns thousands of discernable color hues.",
                MedicalImageEngine.Algorithm.PSEUDOCOLOR_PET_HOT_METAL,
                3,
                0.0f
        );

        setupCard(
                view.findViewById(R.id.card_correlation),
                "SPATIAL LINEAR FILTERING",
                "Gonzalez & Woods 3.4.1",
                "Problem: Correlation vs Convolution Kernel Flipping",
                "Understanding the critical mathematical distinction between correlation (w ★ f) and convolution (w * f).",
                "✓ Solution: Spatial Convolution (180° Rotated Kernel)",
                "Formula: w * f = sum sum w(-s, -t) f(x+s, y+t) — guarantees linear commutativity.",
                MedicalImageEngine.Algorithm.SPATIAL_CONVOLUTION,
                0,
                0.0f
        );

        setupCard(
                view.findViewById(R.id.card_harmonic),
                "TRANSMITTER IMPULSE DROPOUTS",
                "Gonzalez & Woods 5.3.1",
                "Problem: Saturated Salt Noise (White Impulse Spikes = 255)",
                "Telemetry or communication channel saturation adds bright white impulse blips.",
                "✓ Solution: Harmonic Mean Filter (Order -1)",
                "Formula: f_hat = mn / sum(1/g) — suppresses salt impulses as 1/255 is small.",
                MedicalImageEngine.Algorithm.RESTORE_HARMONIC_MEAN,
                0,
                1.0f
        );

        setupCard(
                view.findViewById(R.id.card_contraharmonic),
                "DEAD SENSOR PIXELS",
                "Gonzalez & Woods 5.3.1",
                "Problem: Saturated Pepper Noise (Black Dead Pixels = 0)",
                "Dead sensor elements leave zero-intensity black dropouts.",
                "✓ Solution: Contraharmonic Mean Filter (Order Q > 0)",
                "Formula: f_hat = sum(g^(Q+1)) / sum(g^Q) with Q = +1.5 — eliminates pepper noise.",
                MedicalImageEngine.Algorithm.RESTORE_CONTRAHARMONIC_MEAN,
                0,
                1.5f
        );
    }

    private void setupCard(
            View cardView,
            String modality,
            String reference,
            String problemTitle,
            String problemDesc,
            String solutionName,
            String solutionFormula,
            MedicalImageEngine.Algorithm algorithm,
            int sampleScanIndex,
            float customParam) {

        if (cardView == null) return;

        TextView tvModality = cardView.findViewById(R.id.tv_card_modality);
        TextView tvRef = cardView.findViewById(R.id.tv_card_reference);
        TextView tvProbTitle = cardView.findViewById(R.id.tv_card_problem_title);
        TextView tvProbDesc = cardView.findViewById(R.id.tv_card_problem_desc);
        TextView tvSolName = cardView.findViewById(R.id.tv_card_solution_name);
        TextView tvSolFormula = cardView.findViewById(R.id.tv_card_solution_formula);
        Button btnApply = cardView.findViewById(R.id.btn_card_apply);

        if (tvModality != null) tvModality.setText(modality);
        if (tvRef != null) tvRef.setText(reference);
        if (tvProbTitle != null) tvProbTitle.setText(problemTitle);
        if (tvProbDesc != null) tvProbDesc.setText(problemDesc);
        if (tvSolName != null) tvSolName.setText(solutionName);
        if (tvSolFormula != null) tvSolFormula.setText(solutionFormula);

        if (btnApply != null) {
            btnApply.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onApplySolution(algorithm, sampleScanIndex, customParam);
                }
                dismiss();
            });
        }
    }
}
