package com.betterbackground.crop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 壁纸位置编辑器：中间是与屏幕同比例的画框，双指缩放、单指/双指拖动，双击在“铺满”和“完整显示”间切换。
 * 画框外的部分压暗显示，方便看到被裁掉的内容。
 */
public class CropView extends View {
    public interface Listener {
        void onChanged(CropParams params);
    }

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final RectF tmpRect = new RectF();
    private final Path framePath = new Path();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final float density;

    private Bitmap image;
    private Bitmap blurred;
    /** 原图尺寸（预览图可能是降采样的，几何计算一律按原图）。 */
    private int srcW;
    private int srcH;
    private float aspect = 9f / 20f;
    private CropParams params = new CropParams();
    private Listener listener;
    private boolean interacting;

    public CropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f * density);
        borderPaint.setColor(Color.WHITE);
        gridPaint.setStrokeWidth(density);
        gridPaint.setColor(0x66FFFFFF);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(params.zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (image == null) return false;
                float s = scale();
                params.cx += dx / s / srcW;
                params.cy += dy / s / srcH;
                changed();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (image == null) return false;
                boolean atCover = Math.abs(params.zoom - 1f) < 0.01f;
                float target = atCover ? CropParams.minZoom(srcW, srcH, frame.width(), frame.height()) : 1f;
                zoomAround(target, e.getX(), e.getY());
                return true;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setScreenAspect(int width, int height) {
        aspect = (float) width / height;
        layoutFrame();
        invalidate();
    }

    public void setImage(Bitmap preview, Bitmap blurred, int srcW, int srcH, CropParams params) {
        this.image = preview;
        this.blurred = blurred;
        this.srcW = srcW;
        this.srcH = srcH;
        this.params = params.copy();
        changed();
    }

    public CropParams getParams() {
        return params.copy();
    }

    public void setFill(int fill) {
        params.fill = fill;
        invalidate();
    }

    public void reset() {
        params.cx = 0.5f;
        params.cy = 0.5f;
        params.zoom = 1f;
        changed();
    }

    private float scale() {
        return CropParams.cover(srcW, srcH, frame.width(), frame.height()) * params.zoom;
    }

    /** 以 (fx, fy) 为中心缩放，保证手指下的那一点不动。 */
    private void zoomAround(float zoom, float fx, float fy) {
        float old = scale();
        float px = params.cx * srcW + (fx - frame.centerX()) / old;
        float py = params.cy * srcH + (fy - frame.centerY()) / old;
        float min = CropParams.minZoom(srcW, srcH, frame.width(), frame.height());
        params.zoom = Math.max(min, Math.min(CropParams.MAX_ZOOM, zoom));
        float s = scale();
        params.cx = (px - (fx - frame.centerX()) / s) / srcW;
        params.cy = (py - (fy - frame.centerY()) / s) / srcH;
        changed();
    }

    private void changed() {
        if (image != null && frame.width() > 0) params.clamp(srcW, srcH, frame.width(), frame.height());
        if (listener != null) listener.onChanged(params.copy());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutFrame();
        changed();
    }

    private void layoutFrame() {
        float pad = 24 * density;
        float availW = getWidth() - 2 * pad;
        float availH = getHeight() - 2 * pad;
        if (availW <= 0 || availH <= 0) return;
        float fw;
        float fh;
        if (availW / availH > aspect) {
            fh = availH;
            fw = fh * aspect;
        } else {
            fw = availW;
            fh = fw / aspect;
        }
        float l = (getWidth() - fw) / 2;
        float t = (getHeight() - fh) / 2;
        frame.set(l, t, l + fw, t + fh);
        float r = fw * 0.07f;
        framePath.reset();
        framePath.addRoundRect(frame, r, r, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (image == null || frame.isEmpty()) return;
        float s = scale();
        float cx = params.cx * srcW;
        float cy = params.cy * srcH;

        // 画框内：图片铺不满时的填充
        if (!params.coversFrame()) {
            canvas.save();
            canvas.clipPath(framePath);
            canvas.drawColor(Color.BLACK);
            if (params.fill == CropParams.FILL_BLUR && blurred != null) {
                WallpaperRenderer.drawCover(canvas, blurred, frame.left, frame.top, frame.width(), frame.height(), bitmapPaint);
                canvas.drawColor(0x33000000);
            }
            canvas.restore();
        }

        tmpRect.set(frame.centerX() - cx * s, frame.centerY() - cy * s,
                frame.centerX() + (srcW - cx) * s, frame.centerY() + (srcH - cy) * s);
        canvas.drawBitmap(image, null, tmpRect, bitmapPaint);

        // 画框外压暗
        canvas.save();
        canvas.clipOutPath(framePath);
        canvas.drawColor(0xB3000000);
        canvas.restore();

        if (interacting) {
            for (int i = 1; i < 3; i++) {
                float x = frame.left + frame.width() * i / 3;
                float y = frame.top + frame.height() * i / 3;
                canvas.drawLine(x, frame.top, x, frame.bottom, gridPaint);
                canvas.drawLine(frame.left, y, frame.right, y, gridPaint);
            }
        }
        canvas.drawPath(framePath, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        boolean now = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
        if (now != interacting) {
            interacting = now;
            invalidate();
        }
        if (action == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
