# Xposed 入口由 LSPosed 通过 assets/xposed_init 反射加载；
# hook 包整体保留原名，便于在 LSPosed 日志里看懂堆栈，也避免混淆短类名与宿主类冲突
-keep class com.betterbackground.hook.** { *; }
-dontwarn de.robv.android.xposed.**
