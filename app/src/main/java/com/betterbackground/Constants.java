package com.betterbackground;

import android.net.Uri;

/** 模块 App 与 system_server 中的 Hook 共用的常量。 */
public final class Constants {
    private Constants() {}

    public static final String PKG = "com.betterbackground";
    public static final String AUTHORITY = PKG + ".bridge";
    public static final Uri BRIDGE_URI = Uri.parse("content://" + AUTHORITY);
    public static final String PERMISSION = PKG + ".permission.BRIDGE";

    /** App -> system_server：重新拉取配置和图片，并立即应用当前模式。 */
    public static final String ACTION_SYNC = PKG + ".action.SYNC";
    /** App -> system_server：只回报状态，用来检测模块是否生效。 */
    public static final String ACTION_PING = PKG + ".action.PING";

    // BridgeProvider.call() 的方法名
    public static final String METHOD_GET_CONFIG = "getConfig";
    public static final String METHOD_READ = "read";
    public static final String METHOD_REPORT = "report";

    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    public static final String SLOT_HOME = "_home";
    public static final String SLOT_LOCK = "_lock";
    public static final String[] SLOTS = {
            MODE_LIGHT + SLOT_HOME, MODE_LIGHT + SLOT_LOCK,
            MODE_DARK + SLOT_HOME, MODE_DARK + SLOT_LOCK,
    };

    public static final int LOCK_NONE = 0;    // 不修改锁屏
    public static final int LOCK_SAME = 1;    // 锁屏与桌面相同
    public static final int LOCK_CUSTOM = 2;  // 锁屏单独一张

    // 配置键
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_LOCK_MODE_SUFFIX = "_lock_mode";
    /** "sig_" + slot：图片签名（大小+修改时间），存在即表示该槽位有图。 */
    public static final String KEY_SIG_PREFIX = "sig_";

    // 分块读取
    public static final String KEY_OFFSET = "offset";
    public static final String KEY_LENGTH = "length";
    public static final String KEY_DATA = "data";
    public static final String KEY_EOF = "eof";
    public static final int CHUNK_SIZE = 256 * 1024;

    // 状态回报
    public static final String KEY_KIND = "kind";        // "ping" / "sync"
    public static final String KEY_OK = "ok";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_CURRENT_MODE = "current_mode";
    public static final String KEY_LAST_MODE = "last_mode";
    public static final String KEY_HOOK_VERSION = "hook_version";
    public static final String KEY_TIME = "time";
    public static final String KEY_SYNC_TIME = "sync_time";
    public static final String KIND_PING = "ping";
    public static final String KIND_SYNC = "sync";

    public static String lockModeKey(String mode) {
        return mode + KEY_LOCK_MODE_SUFFIX;
    }
}
