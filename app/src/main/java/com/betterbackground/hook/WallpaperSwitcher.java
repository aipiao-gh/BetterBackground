package com.betterbackground.hook;

import static com.betterbackground.Constants.*;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserManager;

import com.betterbackground.BuildConfig;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 运行在 system_server 中：监听全局配置变化，深浅色切换时设置对应壁纸。
 * <p>
 * 图片由模块 App 通过 {@code BridgeProvider} 分块传过来，保存在 /data/system/betterbackground，
 * 因此真正切换时不依赖模块 App 进程存活（不受 HyperOS 后台管控影响）。
 * 所有工作都在独立的 HandlerThread 上执行，不阻塞系统线程。
 */
final class WallpaperSwitcher {
    private static final File DIR = new File("/data/system/betterbackground");
    private static final File CONFIG_FILE = new File(DIR, "config.properties");
    private static final File STATE_FILE = new File(DIR, "state.properties");
    /** HyperOS 切换深色模式时可能连续触发多次配置变化，合并处理。 */
    private static final long DEBOUNCE_MS = 400;

    private final Context context;
    private final Handler handler;
    private final Properties config = new Properties();
    private final Properties state = new Properties();
    private final Runnable checkRunnable = this::checkMode;

    private WallpaperSwitcher(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
    }

    static void install(Context context) {
        HandlerThread thread = new HandlerThread("BetterBackground");
        thread.start();
        new WallpaperSwitcher(context, new Handler(thread.getLooper())).start();
    }

    private void start() {
        handler.post(() -> {
            load(CONFIG_FILE, config);
            load(STATE_FILE, state);
        });

        BroadcastReceiver systemReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                handler.removeCallbacks(checkRunnable);
                handler.postDelayed(checkRunnable, DEBOUNCE_MS);
            }
        };
        IntentFilter systemFilter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        systemFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        systemFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        context.registerReceiver(systemReceiver, systemFilter, null, handler, Context.RECEIVER_NOT_EXPORTED);

        BroadcastReceiver bridgeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                // 先让广播返回，再在同一线程里干活
                if (ACTION_SYNC.equals(intent.getAction())) {
                    handler.post(WallpaperSwitcher.this::sync);
                } else if (ACTION_PING.equals(intent.getAction())) {
                    handler.post(() -> report(KIND_PING, true, null));
                }
            }
        };
        IntentFilter bridgeFilter = new IntentFilter(ACTION_SYNC);
        bridgeFilter.addAction(ACTION_PING);
        // 发送方必须持有签名级权限，即只有本模块 App 能触发
        context.registerReceiver(bridgeReceiver, bridgeFilter, PERMISSION, handler, Context.RECEIVER_EXPORTED);

        Log.i("installed, version " + BuildConfig.VERSION_NAME);
    }

    private void checkMode() {
        if (!isEnabled()) return;
        UserManager um = context.getSystemService(UserManager.class);
        if (um != null && !um.isUserUnlocked()) return;
        String mode = currentMode();
        if (mode.equals(state.getProperty(KEY_LAST_MODE))) return;
        try {
            Log.i("mode changed -> " + mode + ": " + apply(mode));
        } catch (Throwable t) {
            Log.e("apply " + mode + " failed", t);
        }
    }

    /** 从模块 App 拉取配置与图片，然后立即应用当前模式。 */
    private void sync() {
        boolean ok;
        String message;
        try {
            ContentResolver cr = context.getContentResolver();
            Bundle cfg = cr.call(BRIDGE_URI, METHOD_GET_CONFIG, null, null);
            if (cfg == null) throw new IOException("无法读取模块配置");
            if (!DIR.isDirectory() && !DIR.mkdirs()) throw new IOException("无法创建 " + DIR);

            for (String slot : SLOTS) {
                String sigKey = KEY_SIG_PREFIX + slot;
                String sig = cfg.getString(sigKey);
                File target = new File(DIR, slot);
                if (sig == null) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    config.remove(sigKey);
                } else if (!sig.equals(config.getProperty(sigKey)) || !target.isFile()) {
                    download(cr, slot, target);
                    config.setProperty(sigKey, sig);
                }
            }
            config.setProperty(KEY_ENABLED, String.valueOf(cfg.getBoolean(KEY_ENABLED)));
            for (String mode : new String[]{MODE_LIGHT, MODE_DARK}) {
                config.setProperty(lockModeKey(mode), String.valueOf(cfg.getInt(lockModeKey(mode), LOCK_NONE)));
            }
            save(CONFIG_FILE, config);

            message = isEnabled() ? apply(currentMode()) : "已保存，自动切换已关闭";
            ok = true;
        } catch (Throwable t) {
            Log.e("sync failed", t);
            ok = false;
            message = "失败：" + t;
        }
        Log.i("sync: " + message);
        report(KIND_SYNC, ok, message);
    }

    private void download(ContentResolver cr, String slot, File target) throws IOException {
        File tmp = new File(DIR, slot + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            long offset = 0;
            while (true) {
                Bundle req = new Bundle();
                req.putLong(KEY_OFFSET, offset);
                req.putInt(KEY_LENGTH, CHUNK_SIZE);
                Bundle res = cr.call(BRIDGE_URI, METHOD_READ, slot, req);
                if (res == null) throw new IOException("读取图片失败：" + slot);
                byte[] data = res.getByteArray(KEY_DATA);
                if (data != null && data.length > 0) {
                    out.write(data);
                    offset += data.length;
                }
                if (res.getBoolean(KEY_EOF) || data == null || data.length == 0) break;
            }
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) throw new IOException("保存图片失败：" + slot);
    }

    /** @return 给用户看的结果描述 */
    private String apply(String mode) throws IOException {
        File home = new File(DIR, mode + SLOT_HOME);
        File lock = new File(DIR, mode + SLOT_LOCK);
        int lockMode = parseInt(config.getProperty(lockModeKey(mode)), LOCK_NONE);
        String label = MODE_DARK.equals(mode) ? "深色" : "亮色";
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context);
            StringBuilder applied = new StringBuilder();
            if (home.isFile()) {
                int which = WallpaperManager.FLAG_SYSTEM;
                if (lockMode == LOCK_SAME) which |= WallpaperManager.FLAG_LOCK;
                setWallpaper(wm, home, which);
                applied.append(lockMode == LOCK_SAME ? "桌面和锁屏" : "桌面");
            }
            if (lockMode == LOCK_CUSTOM && lock.isFile()) {
                setWallpaper(wm, lock, WallpaperManager.FLAG_LOCK);
                applied.append(applied.length() > 0 ? "、锁屏" : "锁屏");
            }
            return applied.length() > 0
                    ? "已应用" + label + "壁纸（" + applied + "）"
                    : label + "模式未设置壁纸，保持不变";
        } finally {
            // 无论成败都记录，避免每次旋转屏幕都重试
            state.setProperty(KEY_LAST_MODE, mode);
            save(STATE_FILE, state);
        }
    }

    private static void setWallpaper(WallpaperManager wm, File file, int which) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            if (wm.setStream(in, null, false, which) == 0) {
                throw new IOException("WallpaperManager 拒绝设置（which=" + which + "）");
            }
        }
    }

    private void report(String kind, boolean ok, String message) {
        Bundle b = new Bundle();
        b.putString(KEY_KIND, kind);
        b.putBoolean(KEY_OK, ok);
        b.putString(KEY_MESSAGE, message);
        b.putString(KEY_CURRENT_MODE, currentMode());
        b.putString(KEY_LAST_MODE, state.getProperty(KEY_LAST_MODE));
        b.putInt(KEY_HOOK_VERSION, BuildConfig.VERSION_CODE);
        try {
            context.getContentResolver().call(BRIDGE_URI, METHOD_REPORT, null, b);
        } catch (Throwable t) {
            Log.e("report failed", t);
        }
    }

    private boolean isEnabled() {
        return Boolean.parseBoolean(config.getProperty(KEY_ENABLED));
    }

    private String currentMode() {
        // system_server 的 Resources 会随全局配置同步更新
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? MODE_DARK : MODE_LIGHT;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void load(File file, Properties props) {
        if (!file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            Log.e("load " + file + " failed", e);
        }
    }

    private static void save(File file, Properties props) {
        if (!DIR.isDirectory() && !DIR.mkdirs()) return;
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            props.store(out, null);
            out.getFD().sync();
        } catch (IOException e) {
            Log.e("save " + file + " failed", e);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }
}
