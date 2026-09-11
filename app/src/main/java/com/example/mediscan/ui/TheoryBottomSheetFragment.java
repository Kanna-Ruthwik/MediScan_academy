package com.example.mediscan.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * BottomSheetDialogFragment presenting Gonzalez & Woods (4th Ed.)
 * mathematical formulations, chapter citations, and clinical diagnostic applications.
 */
public class TheoryBottomSheetFragment extends BottomSheetDialogFragment {

    public static final String TAG = "TheoryBottomSheetFragment";

    public static TheoryBottomSheetFragment newInstance() {
        return new TheoryBottomSheetFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_theory_bottom_sheet, container, false);
        View closeBtn = view.findViewById(R.id.btn_close_theory);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> dismiss());
        }
        return view;
    }
}
