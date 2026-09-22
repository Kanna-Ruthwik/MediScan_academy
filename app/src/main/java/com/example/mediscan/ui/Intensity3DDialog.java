package com.example.mediscan.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.example.R;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.Locale;

/**
 * Fullscreen Interactive 3D Intensity Topography and Slicer Dialog.
 * Enables clinicians and students to manipulate spatial 3D terrains f(x, y) = z,
 * inspect cross-sectional isophotes, and perform dynamic intensity slicing.
 */
public class Intensity3DDialog extends DialogFragment {

    private static final String ARG_WIDTH = "width";
    private static final String ARG_HEIGHT = "height";

    private static int[] sCachedGrayscale = null;

    private Intensity3DView intensity3DView;
    private TextView tvSliceStat;
    private TextView tvAreaStat;
    private TextView tvRangeStat;
    private TextView tvSliceValue;
    private SeekBar seekbarSlice;
    private Button btnAutoRotate;

    private boolean isSpinning = false;

    public static Intensity3DDialog newInstance(int[] grayscale, int width, int height) {
        sCachedGrayscale = grayscale;
        Intensity3DDialog dialog = new Intensity3DDialog();
        Bundle args = new Bundle();
        args.putInt(ARG_WIDTH, width);
        args.putInt(ARG_HEIGHT, height);
        dialog.setArguments(args);
        return dialog;
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
        return inflater.inflate(R.layout.dialog_intensity_3d, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        intensity3DView = view.findViewById(R.id.intensity_3d_view);
        tvSliceStat = view.findViewById(R.id.tv_3d_slice_stat);
        tvAreaStat = view.findViewById(R.id.tv_3d_area_stat);
        tvRangeStat = view.findViewById(R.id.tv_3d_range_stat);
        tvSliceValue = view.findViewById(R.id.tv_slice_value);
        seekbarSlice = view.findViewById(R.id.seekbar_slice_height);
        btnAutoRotate = view.findViewById(R.id.btn_auto_rotate);

        ImageButton btnClose = view.findViewById(R.id.btn_close_3d);
        btnClose.setOnClickListener(v -> dismiss());

        // Load image data into 3D view
        Bundle args = getArguments();
        if (args != null && sCachedGrayscale != null) {
            int w = args.getInt(ARG_WIDTH);
            int h = args.getInt(ARG_HEIGHT);
            intensity3DView.setSourceData(sCachedGrayscale, w, h);
        }

        // Live statistics callback from 3D renderer
        intensity3DView.setOnSliceStatsListener((sliceHeight, percentAbove, minIntensity, maxIntensity) -> {
            if (isAdded()) {
                tvSliceStat.setText(String.format(Locale.US, "Slice Plane: T = %d", sliceHeight));
                tvAreaStat.setText(String.format(Locale.US, "Above Plane: %.1f%%", percentAbove));
                tvRangeStat.setText(String.format(Locale.US, "Range: [%d .. %d]", minIntensity, maxIntensity));
            }
        });

        // Slicing height seekbar
        seekbarSlice.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSliceValue.setText(String.valueOf(progress));
                intensity3DView.setSliceHeight(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Quick presets
        view.findViewById(R.id.btn_preset_64).setOnClickListener(v -> seekbarSlice.setProgress(64));
        view.findViewById(R.id.btn_preset_128).setOnClickListener(v -> seekbarSlice.setProgress(128));
        view.findViewById(R.id.btn_preset_192).setOnClickListener(v -> seekbarSlice.setProgress(192));

        // Color palette toggle
        MaterialButtonToggleGroup togglePalette = view.findViewById(R.id.toggle_palette);
        togglePalette.check(R.id.btn_palette_jet);
        togglePalette.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_palette_jet) {
                intensity3DView.setPalette(0);
            } else if (checkedId == R.id.btn_palette_thermal) {
                intensity3DView.setPalette(1);
            } else if (checkedId == R.id.btn_palette_mono) {
                intensity3DView.setPalette(2);
            }
        });

        // Auto spin toggle
        btnAutoRotate.setOnClickListener(v -> {
            isSpinning = !isSpinning;
            intensity3DView.setAutoRotate(isSpinning);
            btnAutoRotate.setText(isSpinning ? "Stop Spin" : "Auto Spin");
        });

        // Reset angle
        view.findViewById(R.id.btn_reset_view).setOnClickListener(v -> intensity3DView.resetOrientation());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sCachedGrayscale = null;
    }
}
