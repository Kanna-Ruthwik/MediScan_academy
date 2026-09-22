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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.example.R;

import java.util.Locale;

/**
 * Step-by-Step Interactive Onboarding and Feature Walkthrough Tour.
 * Explains Digital Image Processing paradigms, UI controls, 3D Topography,
 * and diagnostic navigation for students and medical practitioners.
 */
public class WalkthroughDialog extends DialogFragment {

    public interface OnWalkthroughActionCallback {
        void onOpen3DRequested();
        void onOpenGuideRequested();
    }

    private static class WalkthroughStep {
        final int iconRes;
        final String title;
        final String subtitle;
        final String description;
        final String tipTitle;
        final String tipBody;

        WalkthroughStep(int iconRes, String title, String subtitle, String description, String tipTitle, String tipBody) {
            this.iconRes = iconRes;
            this.title = title;
            this.subtitle = subtitle;
            this.description = description;
            this.tipTitle = tipTitle;
            this.tipBody = tipBody;
        }
    }

    private static final WalkthroughStep[] STEPS = {
            new WalkthroughStep(
                    R.drawable.ic_theory,
                    "Welcome to MediScan Academy v2.0",
                    "Native Clinical & Pedagogical DSP Workstation",
                    "MediScan Academy brings the landmark textbook Digital Image Processing (Gonzalez & Woods, 4th Ed.) to life. Every filter, transformation, and 2D Fourier transform runs directly on raw memory buffers in pure Java mathematical loops.",
                    "⚡ Real-Time Math:",
                    "Zero mock data. All pixel arrays are transformed mathematically on the CPU with instant latency metrics."
            ),
            new WalkthroughStep(
                    R.drawable.ic_camera,
                    "Dual Viewports & Live Camera DSP",
                    "CameraX Zero-Copy Y-Plane vs. 5 Clinical Scans",
                    "Toggle effortlessly between your device's Live Camera (analyzing live Y-plane luminance in real-time) and 5 curated medical scans: Chest Radiograph, Brain MRI, CT Thorax, Histopathology Biopsy, and Coronary Angiography.",
                    "🔬 Camera Tip:",
                    "Point your camera at printed textbook figures, paper X-rays, or high-contrast patterns to test filtering in real time."
            ),
            new WalkthroughStep(
                    R.drawable.ic_sample_scans,
                    "30+ DIP Algorithms (Chapters 1 to 10)",
                    "Comprehensive Mathematical Coverage",
                    "Explore Quantization & Subsampling (Ch 2), Intensity Transforms, Spatial Correlation & Convolution (Ch 3), 2D FFT Frequency Filtering (Ch 4), Noise Degradation & Restoration (Ch 5), Pseudocolor (Ch 6), Morphology (Ch 9), and Segmentation (Ch 10).",
                    "🎛️ Interactive Tuning:",
                    "Every algorithm has an interactive parameter slider underneath the viewport to adjust gamma (γ), cutoff (D₀), Wiener variance, or kernel types."
            ),
            new WalkthroughStep(
                    R.drawable.ic_theory,
                    "3D Intensity Topography & Slicer",
                    "Elevation Surfaces f(x, y) = z & Isophotes",
                    "Visualize any image as a continuous 3D relief surface! Orbit in 3D with touch gestures, examine gradient peaks and background valleys, and cut through the terrain with an interactive slicing plane at height T to highlight isophote contours.",
                    "🏔️ 3D Slicing:",
                    "Tap the '3D Slicer' button in the toolbar anytime to open the full interactive 3D Topography Workstation."
            ),
            new WalkthroughStep(
                    R.drawable.ic_gallery,
                    "When to Use Which Feature",
                    "Diagnostic Clinical Decision Matrix",
                    "Wondering which algorithm solves a specific imaging challenge? The Diagnostic Decision Matrix matches real clinical problems (underexposure, speckle noise, bone occlusion, tumor margins) to exact algorithms with 1-tap activation.",
                    "🩺 Decision Guide:",
                    "Tap the 'Guide' button in the top toolbar to browse clinical scenarios and apply recommended solutions instantly."
            )
    };

    private int currentStepIndex = 0;

    private TextView tvStepBadge;
    private ImageView ivIcon;
    private TextView tvTitle;
    private TextView tvSubtitle;
    private TextView tvDescription;
    private TextView tvTipTitle;
    private TextView tvTipBody;
    private Button btnPrev;
    private Button btnNext;
    private View[] dotViews;

    private OnWalkthroughActionCallback actionCallback;

    public static WalkthroughDialog newInstance() {
        return new WalkthroughDialog();
    }

    public void setActionCallback(OnWalkthroughActionCallback callback) {
        this.actionCallback = callback;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_walkthrough, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvStepBadge = view.findViewById(R.id.tv_walkthrough_step_badge);
        ivIcon = view.findViewById(R.id.iv_walkthrough_icon);
        tvTitle = view.findViewById(R.id.tv_walkthrough_title);
        tvSubtitle = view.findViewById(R.id.tv_walkthrough_subtitle);
        tvDescription = view.findViewById(R.id.tv_walkthrough_description);
        tvTipTitle = view.findViewById(R.id.tv_walkthrough_callout_title);
        tvTipBody = view.findViewById(R.id.tv_walkthrough_callout_body);
        btnPrev = view.findViewById(R.id.btn_walkthrough_prev);
        btnNext = view.findViewById(R.id.btn_walkthrough_next);

        ImageButton btnClose = view.findViewById(R.id.btn_walkthrough_close);
        btnClose.setOnClickListener(v -> dismiss());

        dotViews = new View[]{
                view.findViewById(R.id.dot_0),
                view.findViewById(R.id.dot_1),
                view.findViewById(R.id.dot_2),
                view.findViewById(R.id.dot_3),
                view.findViewById(R.id.dot_4)
        };

        btnPrev.setOnClickListener(v -> {
            if (currentStepIndex > 0) {
                currentStepIndex--;
                bindStep(currentStepIndex);
            }
        });

        btnNext.setOnClickListener(v -> {
            if (currentStepIndex < STEPS.length - 1) {
                currentStepIndex++;
                bindStep(currentStepIndex);
            } else {
                dismiss();
            }
        });

        bindStep(0);
    }

    private void bindStep(int index) {
        WalkthroughStep step = STEPS[index];

        tvStepBadge.setText(String.format(Locale.US, "STEP %d OF %d", index + 1, STEPS.length));
        ivIcon.setImageResource(step.iconRes);
        tvTitle.setText(step.title);
        tvSubtitle.setText(step.subtitle);
        tvDescription.setText(step.description);
        tvTipTitle.setText(step.tipTitle);
        tvTipBody.setText(step.tipBody);

        btnPrev.setVisibility(index == 0 ? View.INVISIBLE : View.VISIBLE);
        btnNext.setText(index == STEPS.length - 1 ? "Get Started!" : "Next");

        // Update dots
        for (int i = 0; i < dotViews.length; i++) {
            if (i == index) {
                dotViews[i].setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_chip_selected));
            } else {
                dotViews[i].setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_chip_normal));
            }
        }
    }
}
