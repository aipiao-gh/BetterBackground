package com.betterbackground.bridge;

import static com.betterbackground.Constants.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;

import com.betterbackground.crop.CropParams;
import com.betterbackground.crop.WallpaperRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** 模块 App 侧的配置与图片存储。 */
public final class Store {
    public static final String STATUS_PREFS = "status";
    private static final String KEY_CROP_PREFIX = "crop_";
    private static final int MAX_NORMALIZED_SIDE = 8192;
    private static final double MAX_NORMALIZED_PIXELS = 24_000_000;

    private static volatile Store instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final File dir;

    private Store(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("config", Context.MODE_PRIVATE);
        this.dir = new File(this.context.getFilesDir(), "wallpapers");
    }

    public static Store get(Context context) {
        if (instance == null) {
            synchronized (Store.class) {
                if (instance == null) instance = new Store(context);
            }
        }
        return instance;
    }

    public File file(String slot) {
        return new File(dir, slot);
    }

    public boolean has(String slot) {
        return file(slot).isFile();
    }

    public String signature(String slot) {
        File f = file(slot);
        return f.isFile() ? f.length() + "-" + f.lastModified() : null;
    }

    /** 用户选的原图；{@link #file(String)} 是按屏幕裁好、真正交给系统的壁纸。 */
    public File source(String slot) {
        return new File(dir, slot + ".src");
    }

    /** 旧版本只保存了成品图，第一次编辑时把它当作原图。 */
    public File ensureSource(String slot) throws IOException {
        File src = source(slot);
        if (!src.isFile()) {
            File out = file(slot);
            if (!out.isFile()) throw new IOException("没有图片");
            Files.copy(out.toPath(), src.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return src;
    }

    /** 导入原图，并按默认摆放（居中铺满）生成壁纸。 */
    public void importImage(String slot, Uri uri, Point screen) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录");
        File tmp = new File(dir, slot + ".import");
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("无法打开图片");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            normalize(tmp);
            if (!tmp.renameTo(source(slot))) throw new IOException("保存失败");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        setCrop(slot, new CropParams());
        render(slot, screen);
    }

    /**
     * 渲染器用 BitmapRegionDecoder 按区域解码，它不认 EXIF 方向、也不支持 HEIF 等格式；
     * 遇到这些情况先用 ImageDecoder 转正并重新编码为 JPEG。普通图片保持原样不损失画质。
     */
    private static void normalize(File file) throws IOException {
        BitmapFactory.Options b = WallpaperRenderer.bounds(file);
        String mime = b.outMimeType;
        boolean regionDecodable = "image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime);
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(file.getPath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) {
        }
        boolean rotated = orientation != ExifInterface.ORIENTATION_NORMAL
                && orientation != ExifInterface.ORIENTATION_UNDEFINED;
        if (b.outWidth > 0 && b.outHeight > 0 && regionDecodable && !rotated) return;

        Bitmap bitmap;
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                double f = Math.min(1, Math.min(Math.sqrt(MAX_NORMALIZED_PIXELS / ((double) w * h)),
                        (double) MAX_NORMALIZED_SIDE / Math.max(w, h)));
                if (f < 1) decoder.setTargetSize((int) Math.round(w * f), (int) Math.round(h * f));
            });
        } catch (IOException | RuntimeException e) {
            throw new IOException("不是有效的图片");
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("转换图片失败");
        } finally {
            bitmap.recycle();
        }
    }

    public CropParams crop(String slot) {
        return CropParams.parse(prefs.getString(KEY_CROP_PREFIX + slot, null));
    }

    public void setCrop(String slot, CropParams params) {
        prefs.edit().putString(KEY_CROP_PREFIX + slot, params.serialize()).apply();
    }

    /** 按保存的摆放参数，把原图渲染成 screen 分辨率的壁纸。 */
    public void render(String slot, Point screen) throws IOException {
        WallpaperRenderer.render(ensureSource(slot), crop(slot), screen.x, screen.y, file(slot));
    }

    public void clear(String slot) {
        //noinspection ResultOfMethodCallIgnored
        file(slot).delete();
        //noinspection ResultOfMethodCallIgnored
        source(slot).delete();
        prefs.edit().remove(KEY_CROP_PREFIX + slot).apply();
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public int lockMode(String mode) {
        return prefs.getInt(lockModeKey(mode), LOCK_SAME);
    }

    public void setLockMode(String mode, int lockMode) {
        prefs.edit().putInt(lockModeKey(mode), lockMode).apply();
    }

    /** 交给 system_server 的配置快照。 */
    public Bundle buildConfig() {
        Bundle b = new Bundle();
        b.putBoolean(KEY_ENABLED, isEnabled());
        for (String mode : new String[]{MODE_LIGHT, MODE_DARK}) {
            b.putInt(lockModeKey(mode), lockMode(mode));
        }
        for (String slot : SLOTS) {
            // 锁屏模式不是“单独设置”时，不必传锁屏图片
            if (slot.endsWith(SLOT_LOCK) && lockMode(slot.substring(0, slot.length() - SLOT_LOCK.length())) != LOCK_CUSTOM) {
                continue;
            }
            String sig = signature(slot);
            if (sig != null) b.putString(KEY_SIG_PREFIX + slot, sig);
        }
        return b;
    }

    public SharedPreferences statusPrefs() {
        return context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE);
    }
}
