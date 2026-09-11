package com.example.mediscan.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Custom Android View rendering a 256-bin grayscale intensity histogram
 * with peak normalization, dynamic grid lines, and Otsu threshold marker.
 * Reference: Gonzalez & Woods, Digital Image Processing (4th Ed.), Chapter 3.
 */
public class HistogramView extends View {

    private final int[] histogram = new int[256];
    private int maxBinValue = 1;
    private int otsuThreshold = -1;
    private double meanIntensity = -1;

    private Paint barPaint;
    private Paint fillPaint;
    private Paint linePaint;
    private Paint gridPaint;
    private Paint otsuPaint;
    private Paint meanPaint;
    private Path areaPath;

    public HistogramView(Context context) {
        super(context);
        init();
    }

    public HistogramView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HistogramView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setColor(0xFF00E676);
        barPaint.setStrokeWidth(1f);
        barPaint.setStyle(Paint.Style.STROKE);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(0xFF00E676);
        linePaint.setStrokeWidth(2f);
        linePaint.setStyle(Paint.Style.STROKE);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(0x33555555);
        gridPaint.setStrokeWidth(1f);

        otsuPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        otsuPaint.setColor(0xFFFF5252); // Bright coral-red marker for Otsu k*
        otsuPaint.setStrokeWidth(2f);
        otsuPaint.setStyle(Paint.Style.STROKE);

        meanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        meanPaint.setColor(0xFF00B0FF); // Sky blue marker for mean intensity
        meanPaint.setStrokeWidth(1.5f);
        meanPaint.setStyle(Paint.Style.STROKE);

        areaPath = new Path();
    }

    /**
     * Updates the 256-bin histogram data on the UI thread and triggers a redraw.
     */
    public void updateHistogram(int[] bins, int otsuK, double meanVal) {
        if (bins == null || bins.length < 256) return;

        int peak = 1;
        // Ignore bin 0 and 255 if they skew the display excessively (e.g. background black/white borders)
        // while determining max scale, or find the maximum across all bins:
        for (int i = 0; i < 256; i++) {
            histogram[i] = bins[i];
            if (bins[i] > peak) {
                peak = bins[i];
            }
        }
        this.maxBinValue = Math.max(peak, 1);
        this.otsuThreshold = otsuK;
        this.meanIntensity = meanVal;
        postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            fillPaint.setShader(new LinearGradient(
                    0, 0, 0, h,
                    0x8800E676, 0x0500E676,
                    Shader.TileMode.CLAMP
            ));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Draw horizontal subtle grid lines (25%, 50%, 75%)
        canvas.drawLine(0, height * 0.25f, width, height * 0.25f, gridPaint);
        canvas.drawLine(0, height * 0.50f, width, height * 0.50f, gridPaint);
        canvas.drawLine(0, height * 0.75f, width, height * 0.75f, gridPaint);

        // Draw vertical quartile markers (0, 64, 128, 192, 255)
        float binWidth = (float) width / 256.0f;
        canvas.drawLine(binWidth * 64, 0, binWidth * 64, height, gridPaint);
        canvas.drawLine(binWidth * 128, 0, binWidth * 128, height, gridPaint);
        canvas.drawLine(binWidth * 192, 0, binWidth * 192, height, gridPaint);

        areaPath.reset();
        areaPath.moveTo(0, height);

        for (int i = 0; i < 256; i++) {
            float x = i * binWidth;
            float normalized = (float) histogram[i] / (float) maxBinValue;
            float y = height - (normalized * (height - 4f));
            if (i == 0) {
                areaPath.lineTo(x, y);
            } else {
                areaPath.lineTo(x, y);
            }
        }

        areaPath.lineTo(width, height);
        areaPath.close();

        // Draw smooth gradient fill
        canvas.drawPath(areaPath, fillPaint);

        // Draw stroke line along the top curve
        for (int i = 0; i < 255; i++) {
            float x1 = i * binWidth;
            float y1 = height - ((float) histogram[i] / maxBinValue * (height - 4f));
            float x2 = (i + 1) * binWidth;
            float y2 = height - ((float) histogram[i + 1] / maxBinValue * (height - 4f));
            canvas.drawLine(x1, y1, x2, y2, linePaint);
        }

        // Draw Otsu threshold marker line if valid
        if (otsuThreshold >= 0 && otsuThreshold < 256) {
            float otsuX = otsuThreshold * binWidth;
            canvas.drawLine(otsuX, 0, otsuX, height, otsuPaint);
        }

        // Draw Mean intensity marker line if valid
        if (meanIntensity >= 0 && meanIntensity < 256) {
            float meanX = (float) (meanIntensity * binWidth);
            canvas.drawLine(meanX, 0, meanX, height, meanPaint);
        }
    }
}
