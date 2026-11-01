package com.betterbackground.crop;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** 按 {@link CropParams} 把原图渲染成与屏幕分辨率一致的壁纸。 */
public final class WallpaperRenderer {
    private WallpaperRenderer() {}

    /** 竖屏方向的物理分辨率（宽 < 高）。 */
    public static Point screenSize(Activity activity) {
        Rect b = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
        return new Point(Math.min(b.width(), b.height()), Math.max(b.width(), b.height()));
    }

    public static BitmapFactory.Options bounds(File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), opts);
        return opts;
    }

    /** 解码到长边不小于 maxSide 的最小 2 的幂采样。 */
    public static Bitmap decodeSampled(File file, int maxSide) {
        BitmapFactory.Options opts = bounds(file);
        int longSide = Math.max(opts.outWidth, opts.outHeight);
        int sample = 1;
        while (longSide / (sample * 2) >= maxSide) sample *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }

    /** 低成本模糊：先缩到极小再放大，双线性插值自然就糊了。 */
    public static Bitmap blur(Bitmap src) {
        int w = 24;
        int h = Math.max(1, Math.round(w * (float) src.getHeight() / src.getWidth()));
        Bitmap tiny = Bitmap.createScaledBitmap(src, w, h, true);
        Bitmap result = Bitmap.createScaledBitmap(tiny, w * 4, h * 4, true);
        if (tiny != result) tiny.recycle();
        return result;
    }

    /** 把 bitmap 以“铺满”方式画满 width×height。 */
    public static void drawCover(Canvas canvas, Bitmap bitmap, float left, float top, float width, float height, Paint paint) {
        float s = CropParams.cover(bitmap.getWidth(), bitmap.getHeight(), width, height);
        float dw = bitmap.getWidth() * s;
        float dh = bitmap.getHeight() * s;
        float l = left + (width - dw) / 2;
        float t = top + (height - dh) / 2;
        canvas.drawBitmap(bitmap, null, new RectF(l, t, l + dw, t + dh), paint);
    }

    public static void render(File src, CropParams params, int outW, int outH, File out) throws IOException {
        BitmapFactory.Options b = bounds(src);
        int w = b.outWidth;
        int h = b.outHeight;
        if (w <= 0 || h <= 0) throw new IOException("无法读取图片");

        CropParams p = params.copy();
        p.clamp(w, h, outW, outH);
        float s = CropParams.cover(w, h, outW, outH) * p.zoom;
        float cx = p.cx * w;
        float cy = p.cy * h;
        float halfW = outW / (2 * s);
        float halfH = outH / (2 * s);
        Rect region = new Rect(
                (int) Math.floor(Math.max(0, cx - halfW)),
                (int) Math.floor(Math.max(0, cy - halfH)),
                (int) Math.ceil(Math.min(w, cx + halfW)),
                (int) Math.ceil(Math.min(h, cy + halfH)));
        // 1/s = 每个输出像素对应的原图像素数；采样不超过它，保证清晰度又不浪费内存
        int sample = 1;
        while (sample * 2 <= 1f / s) sample *= 2;

        Bitmap output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(Color.BLACK);
        if (!p.coversFrame() && p.fill == CropParams.FILL_BLUR) {
            Bitmap small = decodeSampled(src, 256);
            if (small != null) {
                Bitmap blurred = blur(small);
                drawCover(canvas, blurred, 0, 0, outW, outH, paint);
                canvas.drawColor(0x33000000);
                blurred.recycle();
                small.recycle();
            }
        }

        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(src.getPath());
        Bitmap part;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            part = decoder.decodeRegion(region, opts);
        } finally {
            decoder.recycle();
        }
        if (part == null) throw new IOException("解码图片失败");
        RectF dst = new RectF(
                (region.left - cx) * s + outW / 2f,
                (region.top - cy) * s + outH / 2f,
                (region.right - cx) * s + outW / 2f,
                (region.bottom - cy) * s + outH / 2f);
        canvas.drawBitmap(part, null, dst, paint);
        part.recycle();

        File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            if (!output.compress(Bitmap.CompressFormat.JPEG, 95, fos)) throw new IOException("保存图片失败");
        } finally {
            output.recycle();
        }
        if (!tmp.renameTo(out)) throw new IOException("保存图片失败");
    }
}
