package co.carryai.chromeclone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * Visual crop picker: shows a frozen full-frame snapshot of the screen
 * capture with a draggable / resizable selection rectangle. On confirm, the
 * selected region is stored in {@link CaptureConfig} as FRACTIONS of the
 * frame (rotation- and resolution-proof) and applied to every uploaded
 * frame before encoding.
 *
 * Launched from MainActivity with the snapshot JPEG in EXTRA_JPEG.
 */
public class CropPickerActivity extends Activity {

    public static final String EXTRA_JPEG = "extra_jpeg";

    private CropOverlayView overlay;
    private CaptureConfig config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new CaptureConfig(this);

        byte[] jpeg = getIntent().getByteArrayExtra(EXTRA_JPEG);
        if (jpeg == null || jpeg.length == 0) {
            Toast.makeText(this, "No capture frame available — start Share Screen first",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) {
            Toast.makeText(this, "Could not decode the capture frame", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ImageView image = new ImageView(this);
        image.setImageBitmap(bmp);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        root.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new CropOverlayView(this);
        // Pre-select the existing crop (if any) so users can tweak it.
        if (config.hasCrop()) {
            overlay.setInitialFraction(config.getCropX(), config.getCropY(),
                    config.getCropW(), config.getCropH());
        }
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xCC101418);
        bar.setPadding(24, 12, 24, 12);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());

        Button full = new Button(this);
        full.setText("Full frame");
        full.setOnClickListener(v -> {
            config.clearCrop();
            Toast.makeText(this, "Crop cleared (full frame)", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });

        Button confirm = new Button(this);
        confirm.setText("Use this crop");
        confirm.setOnClickListener(v -> {
            float[] f = overlay.getCropFraction();
            if (f[2] < 0.05f || f[3] < 0.05f) {
                Toast.makeText(this, "Selection too small", Toast.LENGTH_SHORT).show();
                return;
            }
            config.setCrop(f[0], f[1], f[2], f[3]);
            Toast.makeText(this, String.format("Crop set: %.0f%%x%.0f%% at (%.0f%%, %.0f%%)",
                    f[2] * 100, f[3] * 100, f[0] * 100, f[1] * 100), Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });

        bar.addView(cancel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(full, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(confirm, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout rootCol = new LinearLayout(this);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.addView(root, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        rootCol.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(rootCol);
    }

    /**
     * Darkened overlay with a movable/resizable crop rectangle.
     * Drag inside = move; drag a corner = resize.
     */
    static class CropOverlayView extends View {
        private final Paint dim = new Paint();
        private final Paint border = new Paint();
        private final Paint handle = new Paint();
        private final RectF crop = new RectF();
        private float handleR;      // corner hit radius
        private boolean initialized = false;
        private float[] initialFraction;

        private static final int MODE_NONE = 0;
        private static final int MODE_MOVE = 1;
        private static final int MODE_CORNER_TL = 2;
        private static final int MODE_CORNER_TR = 3;
        private static final int MODE_CORNER_BL = 4;
        private static final int MODE_CORNER_BR = 5;
        private int mode = MODE_NONE;
        private float lastX, lastY;

        CropOverlayView(Context ctx) {
            super(ctx);
            dim.setColor(0xAA000000);
            dim.setStyle(Paint.Style.FILL);
            border.setColor(0xFF4D8DFF);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(4f);
            handle.setColor(0xFFFFFFFF);
            handle.setStyle(Paint.Style.FILL);
            handleR = 36 * ctx.getResources().getDisplayMetrics().density;
        }

        void setInitialFraction(float x, float y, float w, float h) {
            initialFraction = new float[]{x, y, w, h};
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (!initialized) {
                initialized = true;
                if (initialFraction != null) {
                    crop.set(initialFraction[0] * w, initialFraction[1] * h,
                            (initialFraction[0] + initialFraction[2]) * w,
                            (initialFraction[1] + initialFraction[3]) * h);
                } else {
                    // Default: centered 60% box.
                    crop.set(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.8f);
                }
            }
        }

        /** Crop as fractions of the view [x, y, w, h]. */
        float[] getCropFraction() {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return new float[]{0, 0, 1, 1};
            return new float[]{
                    crop.left / w, crop.top / h,
                    (crop.right - crop.left) / w, (crop.bottom - crop.top) / h
            };
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            // Dim everything OUTSIDE the crop.
            canvas.drawRect(0, 0, w, crop.top, dim);
            canvas.drawRect(0, crop.top, crop.left, crop.bottom, dim);
            canvas.drawRect(crop.right, crop.top, w, crop.bottom, dim);
            canvas.drawRect(0, crop.bottom, w, h, dim);
            // Crop border + corner handles.
            canvas.drawRect(crop, border);
            canvas.drawCircle(crop.left, crop.top, 12, handle);
            canvas.drawCircle(crop.right, crop.top, 12, handle);
            canvas.drawCircle(crop.left, crop.bottom, 12, handle);
            canvas.drawCircle(crop.right, crop.bottom, 12, handle);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    lastX = x;
                    lastY = y;
                    mode = hitTest(x, y);
                    return mode != MODE_NONE || crop.contains(x, y);
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = x - lastX;
                    float dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    if (mode == MODE_MOVE || (mode == MODE_NONE && crop.contains(x, y))) {
                        offset(dx, dy);
                    } else if (mode == MODE_CORNER_TL) {
                        crop.left = clamp(crop.left + dx, 0, crop.right - 20);
                        crop.top = clamp(crop.top + dy, 0, crop.bottom - 20);
                    } else if (mode == MODE_CORNER_TR) {
                        crop.right = clamp(crop.right + dx, crop.left + 20, getWidth());
                        crop.top = clamp(crop.top + dy, 0, crop.bottom - 20);
                    } else if (mode == MODE_CORNER_BL) {
                        crop.left = clamp(crop.left + dx, 0, crop.right - 20);
                        crop.bottom = clamp(crop.bottom + dy, crop.top + 20, getHeight());
                    } else if (mode == MODE_CORNER_BR) {
                        crop.right = clamp(crop.right + dx, crop.left + 20, getWidth());
                        crop.bottom = clamp(crop.bottom + dy, crop.top + 20, getHeight());
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mode = MODE_NONE;
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private int hitTest(float x, float y) {
            float r2 = handleR * handleR;
            if (dist2(x, y, crop.left, crop.top) <= r2) return MODE_CORNER_TL;
            if (dist2(x, y, crop.right, crop.top) <= r2) return MODE_CORNER_TR;
            if (dist2(x, y, crop.left, crop.bottom) <= r2) return MODE_CORNER_BL;
            if (dist2(x, y, crop.right, crop.bottom) <= r2) return MODE_CORNER_BR;
            if (crop.contains(x, y)) return MODE_MOVE;
            return MODE_NONE;
        }

        private void offset(float dx, float dy) {
            float w = crop.width(), h = crop.height();
            float nx = clamp(crop.left + dx, 0, getWidth() - w);
            float ny = clamp(crop.top + dy, 0, getHeight() - h);
            crop.set(nx, ny, nx + w, ny + h);
        }

        private static float dist2(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2, dy = y1 - y2;
            return dx * dx + dy * dy;
        }

        private static float clamp(float v, float min, float max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
