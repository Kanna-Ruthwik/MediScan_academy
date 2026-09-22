package com.example.mediscan.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Interactive 3D Intensity Topography and Slicer View.
 * Renders digital medical images as 3D elevation terrains f(x, y) = z in accordance
 * with Gonzalez & Woods Digital Image Processing (4th Ed.), Section 2.4 & 10.1.
 * Features 3D touch orbital rotation, interactive slicing plane z = T,
 * height-based false-color palette shading, and bounding box axis cues.
 */
public class Intensity3DView extends View {

    public interface OnSliceStatsListener {
        void onStatsUpdated(int sliceHeight, float percentAbove, int minIntensity, int maxIntensity);
    }

    private static final int GRID_SIZE = 54; // 54x54 = 2,916 elevation nodes

    private final Paint wireframePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slicePlanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sliceLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path quadPath = new Path();

    // 2D elevation grid data [GRID_SIZE][GRID_SIZE]
    private final float[][] elevationGrid = new float[GRID_SIZE][GRID_SIZE];
    private final int[][] colorGrid = new int[GRID_SIZE][GRID_SIZE];

    // Transformed screen coordinates [GRID_SIZE][GRID_SIZE][2] (0=X, 1=Y)
    private final float[][][] screenCoords = new float[GRID_SIZE][GRID_SIZE][2];
    private final float[][] rotatedDepth = new float[GRID_SIZE][GRID_SIZE];

    // Slicing parameters
    private int sliceHeight = 128; // T in [0..255]
    private int paletteType = 0; // 0: Jet, 1: Thermal, 2: Monochrome
    private float heightScale = 1.2f;

    // Viewing angles (in degrees)
    private float azimuth = 35.0f;    // Orbit around Y/Z
    private float elevation = 30.0f;  // Tilt from horizontal plane

    // Touch interaction
    private float lastTouchX;
    private float lastTouchY;
    private boolean isDragging = false;

    // Auto rotation
    private boolean isAutoRotate = false;

    // Stats
    private int globalMin = 0;
    private int globalMax = 255;
    private float percentAbove = 50.0f;
    private OnSliceStatsListener statsListener;

    public Intensity3DView(Context context) {
        super(context);
        init();
    }

    public Intensity3DView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public Intensity3DView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        wireframePaint.setStyle(Paint.Style.STROKE);
        wireframePaint.setStrokeWidth(1.2f);
        wireframePaint.setColor(Color.argb(70, 0, 229, 255));

        fillPaint.setStyle(Paint.Style.FILL);

        slicePlanePaint.setStyle(Paint.Style.FILL);
        slicePlanePaint.setColor(Color.argb(80, 255, 171, 0)); // Amber translucent plane

        sliceLinePaint.setStyle(Paint.Style.STROKE);
        sliceLinePaint.setStrokeWidth(2.5f);
        sliceLinePaint.setColor(Color.argb(230, 255, 215, 0)); // Gold glowing edge

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1.5f);
        axisPaint.setColor(Color.argb(120, 180, 180, 180));

        textPaint.setColor(Color.argb(200, 200, 200, 200));
        textPaint.setTextSize(26f);
        textPaint.setAntiAlias(true);

        // Initialize sample synthetic peak if no data loaded yet
        generateDefaultGaussianRidge();
    }

    private void generateDefaultGaussianRidge() {
        for (int y = 0; y < GRID_SIZE; y++) {
            float ny = (y - GRID_SIZE / 2.0f) / (GRID_SIZE / 3.0f);
            for (int x = 0; x < GRID_SIZE; x++) {
                float nx = (x - GRID_SIZE / 2.0f) / (GRID_SIZE / 3.0f);
                float distSq = nx * nx + ny * ny;
                float val = (float) (255.0 * Math.exp(-distSq / 1.4));
                elevationGrid[y][x] = val;
            }
        }
        recomputePaletteColors();
    }

    public void setSourceData(@NonNull int[] grayscale, int width, int height) {
        if (width <= 0 || height <= 0 || grayscale.length == 0) return;

        globalMin = 255;
        globalMax = 0;

        float stepX = (float) width / GRID_SIZE;
        float stepY = (float) height / GRID_SIZE;

        for (int gy = 0; gy < GRID_SIZE; gy++) {
            int srcY = Math.min(height - 1, (int) (gy * stepY));
            int rowOffset = srcY * width;
            for (int gx = 0; gx < GRID_SIZE; gx++) {
                int srcX = Math.min(width - 1, (int) (gx * stepX));
                int val = grayscale[rowOffset + srcX] & 0xFF;

                elevationGrid[gy][gx] = val;
                if (val < globalMin) globalMin = val;
                if (val > globalMax) globalMax = val;
            }
        }

        recomputePaletteColors();
        recomputeStats();
        postInvalidate();
    }

    public void setSliceHeight(int heightT) {
        this.sliceHeight = Math.max(0, Math.min(255, heightT));
        recomputePaletteColors();
        recomputeStats();
        postInvalidate();
    }

    public void setPalette(int palette) {
        this.paletteType = palette;
        recomputePaletteColors();
        postInvalidate();
    }

    public void setHeightScale(float scale) {
        this.heightScale = Math.max(0.4f, Math.min(2.5f, scale));
        postInvalidate();
    }

    public void setAutoRotate(boolean enabled) {
        this.isAutoRotate = enabled;
        if (isAutoRotate) {
            postInvalidateOnAnimation();
        }
    }

    public void resetOrientation() {
        this.azimuth = 35.0f;
        this.elevation = 30.0f;
        postInvalidate();
    }

    public void setOnSliceStatsListener(OnSliceStatsListener listener) {
        this.statsListener = listener;
        recomputeStats();
    }

    private void recomputeStats() {
        int countAbove = 0;
        int total = GRID_SIZE * GRID_SIZE;
        for (int y = 0; y < GRID_SIZE; y++) {
            for (int x = 0; x < GRID_SIZE; x++) {
                if (elevationGrid[y][x] >= sliceHeight) {
                    countAbove++;
                }
            }
        }
        percentAbove = (countAbove * 100.0f) / total;
        if (statsListener != null) {
            statsListener.onStatsUpdated(sliceHeight, percentAbove, globalMin, globalMax);
        }
    }

    private void recomputePaletteColors() {
        for (int y = 0; y < GRID_SIZE; y++) {
            for (int x = 0; x < GRID_SIZE; x++) {
                float val = elevationGrid[y][x];
                colorGrid[y][x] = computeColor(val, val >= sliceHeight);
            }
        }
    }

    private int computeColor(float val, boolean isAboveSlice) {
        int v = Math.max(0, Math.min(255, (int) val));
        int r, g, b;

        switch (paletteType) {
            case 0: // Rainbow / Jet
                if (v < 64) {
                    r = 0;
                    g = v * 4;
                    b = 255;
                } else if (v < 128) {
                    r = 0;
                    g = 255;
                    b = 255 - (v - 64) * 4;
                } else if (v < 192) {
                    r = (v - 128) * 4;
                    g = 255;
                    b = 0;
                } else {
                    r = 255;
                    g = 255 - (v - 192) * 4;
                    b = 0;
                }
                break;

            case 1: // Thermal Hot
                if (v < 85) {
                    r = v * 3;
                    g = 0;
                    b = v;
                } else if (v < 170) {
                    r = 255;
                    g = (v - 85) * 3;
                    b = 0;
                } else {
                    r = 255;
                    g = 255;
                    b = (v - 170) * 3;
                }
                break;

            case 2: // Monochrome Clinical Height
            default:
                r = v;
                g = v;
                b = v;
                break;
        }

        // Highlights vertices above the slice plane T with higher alpha/saturation
        int alpha = isAboveSlice ? 230 : 90;
        return Color.argb(alpha, r, g, b);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                isDragging = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;

                    azimuth += dx * 0.55f;
                    elevation -= dy * 0.45f;

                    // Clamp elevation to avoid inverse flipping
                    if (elevation < 5.0f) elevation = 5.0f;
                    if (elevation > 85.0f) elevation = 85.0f;

                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    postInvalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        // Auto rotation step
        if (isAutoRotate && !isDragging) {
            azimuth = (azimuth + 0.6f) % 360f;
            postInvalidateOnAnimation();
        }

        float centerX = viewW * 0.5f;
        float centerY = viewH * 0.52f;
        float sceneScale = Math.min(viewW, viewH) * 0.42f;

        // Trigonometric rotation angles
        double radAz = Math.toRadians(azimuth);
        double radEl = Math.toRadians(elevation);

        double cosAz = Math.cos(radAz);
        double sinAz = Math.sin(radAz);
        double cosEl = Math.cos(radEl);
        double sinEl = Math.sin(radEl);

        // Project all 3D grid vertices to 2D screen coordinates
        float invGrid = 2.0f / (GRID_SIZE - 1);
        for (int y = 0; y < GRID_SIZE; y++) {
            float modelY = -1.0f + (y * invGrid); // [-1 .. 1]
            for (int x = 0; x < GRID_SIZE; x++) {
                float modelX = -1.0f + (x * invGrid); // [-1 .. 1]
                float modelZ = (elevationGrid[y][x] / 255.0f) * 0.85f * heightScale; // [0 .. 0.85 * scale]

                // 1. Azimuth rotation around vertical axis
                double rotX = modelX * cosAz - modelY * sinAz;
                double rotY = modelX * sinAz + modelY * cosAz;
                double rotZ = modelZ;

                // 2. Elevation tilt
                double projX = rotX;
                double projY = rotY * cosEl - rotZ * sinEl;
                double depth = rotY * sinEl + rotZ * cosEl;

                screenCoords[y][x][0] = (float) (centerX + projX * sceneScale);
                screenCoords[y][x][1] = (float) (centerY - projY * sceneScale);
                rotatedDepth[y][x] = (float) depth;
            }
        }

        // Draw 3D Base Bounding Box / Reference Platform
        drawBoundingPlatform(canvas, centerX, centerY, sceneScale, cosAz, sinAz, cosEl, sinEl);

        // Draw Slicing Plane at height T
        drawSlicingPlane(canvas, centerX, centerY, sceneScale, cosAz, sinAz, cosEl, sinEl);

        // Draw 3D Topographic Mesh (Quads with height-colored vertices)
        for (int y = 0; y < GRID_SIZE - 1; y++) {
            for (int x = 0; x < GRID_SIZE - 1; x++) {
                float x0 = screenCoords[y][x][0];
                float y0 = screenCoords[y][x][1];
                float x1 = screenCoords[y][x + 1][0];
                float y1 = screenCoords[y][x + 1][1];
                float x2 = screenCoords[y + 1][x + 1][0];
                float y2 = screenCoords[y + 1][x + 1][1];
                float x3 = screenCoords[y + 1][x][0];
                float y3 = screenCoords[y + 1][x][1];

                quadPath.reset();
                quadPath.moveTo(x0, y0);
                quadPath.lineTo(x1, y1);
                quadPath.lineTo(x2, y2);
                quadPath.lineTo(x3, y3);
                quadPath.close();

                // Fill quad with interpolated vertex color
                fillPaint.setColor(colorGrid[y][x]);
                canvas.drawPath(quadPath, fillPaint);

                // Wireframe edges on alternate intervals for crisp clean look
                if ((x % 2 == 0) && (y % 2 == 0)) {
                    canvas.drawPath(quadPath, wireframePaint);
                }
            }
        }

        // Overlay isophote contour cut where elevation crosses sliceHeight
        drawIsophoteContours(canvas);

        // Draw on-screen HUD cues
        drawHudCues(canvas, viewW, viewH);
    }

    private void drawBoundingPlatform(Canvas canvas, float cx, float cy, float scale,
                                       double cosAz, double sinAz, double cosEl, double sinEl) {
        float[][] corners = {
                {-1f, -1f, 0f},
                { 1f, -1f, 0f},
                { 1f,  1f, 0f},
                {-1f,  1f, 0f}
        };

        float[] px = new float[4];
        float[] py = new float[4];

        for (int i = 0; i < 4; i++) {
            double rx = corners[i][0] * cosAz - corners[i][1] * sinAz;
            double ry = corners[i][0] * sinAz + corners[i][1] * cosAz;
            double rz = corners[i][2];

            px[i] = (float) (cx + rx * scale);
            py[i] = (float) (cy - (ry * cosEl - rz * sinEl) * scale);
        }

        Path boxPath = new Path();
        boxPath.moveTo(px[0], py[0]);
        for (int i = 1; i < 4; i++) boxPath.lineTo(px[i], py[i]);
        boxPath.close();

        canvas.drawPath(boxPath, axisPaint);

        // Draw vertical scale pole at back corner
        float topZ = 0.85f * heightScale;
        double rx0 = corners[0][0] * cosAz - corners[0][1] * sinAz;
        double ry0 = corners[0][0] * sinAz + corners[0][1] * cosAz;
        float topScreenX = (float) (cx + rx0 * scale);
        float topScreenY = (float) (cy - (ry0 * cosEl - topZ * sinEl) * scale);

        canvas.drawLine(px[0], py[0], topScreenX, topScreenY, axisPaint);
        canvas.drawText("Z = 255", topScreenX - 55, topScreenY - 10, textPaint);
        canvas.drawText("Z = 0", px[0] - 35, py[0] + 30, textPaint);
    }

    private void drawSlicingPlane(Canvas canvas, float cx, float cy, float scale,
                                  double cosAz, double sinAz, double cosEl, double sinEl) {
        float sliceZ = (sliceHeight / 255.0f) * 0.85f * heightScale;
        float[][] planeCorners = {
                {-1.08f, -1.08f, sliceZ},
                { 1.08f, -1.08f, sliceZ},
                { 1.08f,  1.08f, sliceZ},
                {-1.08f,  1.08f, sliceZ}
        };

        float[] px = new float[4];
        float[] py = new float[4];

        for (int i = 0; i < 4; i++) {
            double rx = planeCorners[i][0] * cosAz - planeCorners[i][1] * sinAz;
            double ry = planeCorners[i][0] * sinAz + planeCorners[i][1] * cosAz;
            double rz = planeCorners[i][2];

            px[i] = (float) (cx + rx * scale);
            py[i] = (float) (cy - (ry * cosEl - rz * sinEl) * scale);
        }

        Path planePath = new Path();
        planePath.moveTo(px[0], py[0]);
        for (int i = 1; i < 4; i++) planePath.lineTo(px[i], py[i]);
        planePath.close();

        // Draw translucent slice plane
        canvas.drawPath(planePath, slicePlanePaint);

        // Draw glowing perimeter line
        canvas.drawPath(planePath, sliceLinePaint);

        // Label plane height
        canvas.drawText(String.format("Slice Plane T = %d", sliceHeight), px[1] + 12, py[1] + 4, textPaint);
    }

    private void drawIsophoteContours(Canvas canvas) {
        // March through adjacent grid edges to trace intersection with z = sliceHeight
        for (int y = 0; y < GRID_SIZE - 1; y++) {
            for (int x = 0; x < GRID_SIZE - 1; x++) {
                float v0 = elevationGrid[y][x];
                float v1 = elevationGrid[y][x + 1];
                float v2 = elevationGrid[y + 1][x];

                // Check horizontal edge
                if ((v0 <= sliceHeight && v1 >= sliceHeight) || (v0 >= sliceHeight && v1 <= sliceHeight)) {
                    if (Math.abs(v1 - v0) > 0.001f) {
                        float t = (sliceHeight - v0) / (v1 - v0);
                        float ix = screenCoords[y][x][0] + t * (screenCoords[y][x + 1][0] - screenCoords[y][x][0]);
                        float iy = screenCoords[y][x][1] + t * (screenCoords[y][x + 1][1] - screenCoords[y][x][1]);
                        canvas.drawCircle(ix, iy, 2.0f, sliceLinePaint);
                    }
                }

                // Check vertical edge
                if ((v0 <= sliceHeight && v2 >= sliceHeight) || (v0 >= sliceHeight && v2 <= sliceHeight)) {
                    if (Math.abs(v2 - v0) > 0.001f) {
                        float t = (sliceHeight - v0) / (v2 - v0);
                        float ix = screenCoords[y][x][0] + t * (screenCoords[y + 1][x][0] - screenCoords[y][x][0]);
                        float iy = screenCoords[y][x][1] + t * (screenCoords[y + 1][x][1] - screenCoords[y][x][1]);
                        canvas.drawCircle(ix, iy, 2.0f, sliceLinePaint);
                    }
                }
            }
        }
    }

    private void drawHudCues(Canvas canvas, int w, int h) {
        textPaint.setTextSize(24f);
        textPaint.setColor(Color.argb(220, 0, 229, 255));
        canvas.drawText("Gonzalez & Woods 2.4: 3D Intensity Topography f(x,y)=z", 24, 38, textPaint);

        textPaint.setColor(Color.argb(190, 255, 215, 0));
        canvas.drawText(String.format("Surface Area above T: %.1f%%", percentAbove), 24, 68, textPaint);

        textPaint.setColor(Color.argb(160, 200, 200, 200));
        canvas.drawText(String.format("Orbit: Az %.0f° / El %.0f° (Drag to rotate)", azimuth, elevation), 24, h - 24, textPaint);
    }
}
