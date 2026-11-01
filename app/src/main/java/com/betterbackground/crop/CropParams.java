package com.betterbackground.crop;

import java.util.Locale;

/**
 * 壁纸在屏幕上的摆放方式，与分辨率无关，预览和最终输出共用同一套几何。
 * <p>
 * 设图片宽高为 w×h、屏幕（画框）宽高为 W×H，“铺满”缩放 cover = max(W/w, H/h)，
 * 实际缩放 s = cover × zoom；画框中心落在图片的 (cx·w, cy·h) 处。
 */
public final class CropParams {
    public static final int FILL_BLUR = 0;
    public static final int FILL_BLACK = 1;
    public static final float MAX_ZOOM = 5f;

    /** 画框中心在图片中的位置，0~1 归一化。 */
    public float cx = 0.5f;
    public float cy = 0.5f;
    /** 相对“刚好铺满屏幕”的缩放，小于 1 时图片不能铺满，空白处按 fill 填充。 */
    public float zoom = 1f;
    public int fill = FILL_BLUR;

    public CropParams copy() {
        CropParams p = new CropParams();
        p.cx = cx;
        p.cy = cy;
        p.zoom = zoom;
        p.fill = fill;
        return p;
    }

    public static float cover(float w, float h, float frameW, float frameH) {
        return Math.max(frameW / w, frameH / h);
    }

    /** 允许的最小 zoom：整张图刚好完整放进画框。 */
    public static float minZoom(float w, float h, float frameW, float frameH) {
        return Math.min(frameW / w, frameH / h) / cover(w, h, frameW, frameH);
    }

    /**
     * 限制缩放和位置：某个方向上图片比画框大时，画框不能超出图片；
     * 比画框小时，图片不能超出画框。
     */
    public void clamp(float w, float h, float frameW, float frameH) {
        zoom = Math.max(minZoom(w, h, frameW, frameH), Math.min(MAX_ZOOM, zoom));
        float s = cover(w, h, frameW, frameH) * zoom;
        cx = clampAxis(cx * w, w, frameW / (2 * s)) / w;
        cy = clampAxis(cy * h, h, frameH / (2 * s)) / h;
    }

    private static float clampAxis(float c, float size, float halfFrame) {
        float a = halfFrame;
        float b = size - halfFrame;
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    public boolean coversFrame() {
        return zoom >= 0.999f;
    }

    public String serialize() {
        return String.format(Locale.ROOT, "%f,%f,%f,%d", cx, cy, zoom, fill);
    }

    public static CropParams parse(String s) {
        CropParams p = new CropParams();
        if (s == null) return p;
        try {
            String[] parts = s.split(",");
            p.cx = Float.parseFloat(parts[0]);
            p.cy = Float.parseFloat(parts[1]);
            p.zoom = Float.parseFloat(parts[2]);
            p.fill = Integer.parseInt(parts[3]);
        } catch (RuntimeException e) {
            return new CropParams();
        }
        return p;
    }
}
