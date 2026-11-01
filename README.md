# 深浅壁纸 BetterBackground

LSPosed 模块：为 **亮色模式** 和 **深色模式** 分别设置壁纸（桌面 / 锁屏），系统切换深浅色时自动更换。针对小米 HyperOS 设计。

## 使用

1. 安装 `app/build/outputs/apk/release/app-release.apk`。
2. 在 LSPosed 中启用模块，作用域勾选 **系统框架**，然后重启手机。
3. 打开「深浅壁纸」，顶部显示「✅ 模块已生效」即说明 Hook 成功。
4. 分别为亮色、深色选择桌面壁纸，锁屏可选：
   - **不修改**：只换桌面，锁屏保持原样
   - **同桌面**：锁屏和桌面用同一张
   - **单独设置**：锁屏单独选一张
5. 选好图片后会自动打开调整界面，画框比例与手机屏幕一致：
   - 双指缩放、拖动调整显示位置与大小
   - 双击在「铺满屏幕」和「完整显示」之间切换
   - 缩小到铺不满屏幕时，空白处可选「模糊填充」或「黑色」
   - 之后点击预览图可再次调整，原图会一直保留，反复调整不会损失画质
6. 点击「保存并立即应用」。之后不管是手动切换、控制中心切换还是定时切换深色模式，壁纸都会自动跟随。

## 工作原理

```
模块 App ──(签名权限广播 SYNC)──▶ system_server 中的 Hook
   ▲                                   │
   └──── BridgeProvider.call() 分块读取图片 ◀┘  复制到 /data/system/betterbackground/

system_server 监听 ACTION_CONFIGURATION_CHANGED
   └─ uiMode 的 NIGHT 位变化 → WallpaperManager.setStream(FLAG_SYSTEM / FLAG_LOCK)
```

- 裁剪在模块 App 里完成：按屏幕物理分辨率，用 `BitmapRegionDecoder` 只解码可见区域，再输出 JPEG(95)。系统拿到的就是与屏幕尺寸完全一致的图，不会再被 HyperOS 二次裁切。带 EXIF 旋转或 HEIF 等格式的图片在导入时会先转正并转成 JPEG。
- Hook 点是 `ActivityManagerService.systemReady`，拿到系统 Context 后注册广播接收器，所有工作都在独立的 `HandlerThread` 上执行。
- 图片保存一份在系统目录，切换时 **不需要模块 App 在后台运行**，所以不受 HyperOS 杀后台影响。
- 图片通过 `ContentProvider.call()` 按 256KB 分块传输，没有直接传文件描述符，因此不会被 SELinux 拦下。
- 只有在深浅色真正变化时才设置壁纸，屏幕旋转等其他配置变化会被忽略；上次应用的模式记录在 `state.properties` 里，重启后若模式已变化也会补一次。
- Provider 只允许 system_server（uid 1000）调用，同步广播要求发送方持有本模块的签名级权限。

## 已知限制

- 若开启了 HyperOS「锁屏画报 / 壁纸轮播」，锁屏壁纸可能被其覆盖；部分 HyperOS 版本的锁屏可能不接受 `FLAG_LOCK`，这种情况选「不修改」即可。
- 「深色模式下壁纸调暗」是系统功能，开启时深色壁纸会被额外压暗，可在 设置 → 显示 中关闭。
- 设置壁纸会替换当前的动态壁纸 / 超级壁纸。
- 只处理主用户（user 0）。
- 若开启了 HyperOS「桌面壁纸滚动」，系统会把壁纸放大到更宽的尺寸，调整好的构图可能被放大。
- 切换屏幕分辨率（FHD+/WQHD+）后比例不变，不影响显示；如果是折叠屏换了屏幕，需要重新调整。

## 调试

```bash
adb logcat -s BetterBackground
```

LSPosed 管理器的日志中也会有 `BetterBackground:` 开头的记录。

## 编译

需要 JDK 17+ 和 Android SDK（platform 35、build-tools 35.0.0）。在 `local.properties` 里写好 `sdk.dir`，然后：

```bash
./gradlew assembleRelease
```

release 包默认用 debug 签名，可以直接安装；要正式发布请换成自己的签名配置。

## 许可

[MIT](LICENSE)
