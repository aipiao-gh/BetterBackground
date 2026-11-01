package com.betterbackground.hook;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed 入口，作用域：系统框架（android / system_server）。 */
public class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName) || !"android".equals(lpparam.processName)) {
            return;
        }
        try {
            Class<?> ams = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", lpparam.classLoader);
            XposedBridge.hookAllMethods(ams, "systemReady", new XC_MethodHook() {
                private boolean installed;

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (installed) return;
                    installed = true;
                    try {
                        Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        WallpaperSwitcher.install(context);
                    } catch (Throwable t) {
                        Log.e("install failed", t);
                    }
                }
            });
            Log.i("hooked ActivityManagerService.systemReady");
        } catch (Throwable t) {
            Log.e("hook failed", t);
        }
    }
}
