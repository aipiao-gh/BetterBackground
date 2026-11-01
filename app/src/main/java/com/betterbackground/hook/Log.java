package com.betterbackground.hook;

import de.robv.android.xposed.XposedBridge;

/** 同时输出到 logcat 和 LSPosed 日志。 */
final class Log {
    private static final String TAG = "BetterBackground";

    private Log() {}

    static void i(String msg) {
        android.util.Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    static void e(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
        XposedBridge.log(TAG + ": " + msg + ": " + android.util.Log.getStackTraceString(t));
    }
}
