package com.betterbackground.ui;

import static com.betterbackground.Constants.*;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.betterbackground.R;
import com.betterbackground.bridge.Store;
import com.betterbackground.crop.CropActivity;
import com.betterbackground.crop.WallpaperRenderer;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final long PING_TIMEOUT_MS = 3000;
    private static final long SYNC_TIMEOUT_MS = 20000;
    /** 缩略图长边，卡片约半屏宽，按屏幕比例换算后足够清晰。 */
    private static final int THUMB_SIDE = 960;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = this::onRequestTimeout;

    private Store store;
    private ActivityResultLauncher<PickVisualMediaRequest> picker;
    private ActivityResultLauncher<Intent> cropLauncher;
    private String pendingSlot;
    private String pendingTitle;
    private Point screen;

    private TextView statusTitle;
    private TextView statusDetail;
    private MaterialButton applyButton;
    private ModeCard lightCard;
    private ModeCard darkCard;

    /** 当前等待 system_server 回应的请求类型，null 表示没有在等。 */
    private String waitingKind;
    private long requestTime;
    private boolean connected;
    private boolean timedOut;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View content = findViewById(R.id.content);
        int pad = content.getPaddingLeft();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });

        store = Store.get(this);
        screen = WallpaperRenderer.screenSize(this);
        if (savedInstanceState != null) {
            pendingSlot = savedInstanceState.getString("pending_slot");
            pendingTitle = savedInstanceState.getString("pending_title");
        }
        picker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onPicked);
        cropLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            refreshCards();
            if (result.getResultCode() == RESULT_OK) toast("已调整，点击「保存并立即应用」后生效");
        });

        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        applyButton = findViewById(R.id.btn_apply);
        applyButton.setOnClickListener(v -> send(ACTION_SYNC, KIND_SYNC, SYNC_TIMEOUT_MS));

        MaterialSwitch enabled = findViewById(R.id.switch_enabled);
        enabled.setChecked(store.isEnabled());
        enabled.setOnCheckedChangeListener((b, checked) -> store.setEnabled(checked));

        lightCard = new ModeCard(findViewById(R.id.card_light), MODE_LIGHT, R.string.mode_light, R.string.mode_light_desc);
        darkCard = new ModeCard(findViewById(R.id.card_dark), MODE_DARK, R.string.mode_dark, R.string.mode_dark_desc);
        refreshCards();
    }

    @Override
    protected void onStart() {
        super.onStart();
        store.statusPrefs().registerOnSharedPreferenceChangeListener(this);
        if (waitingKind == null) send(ACTION_PING, KIND_PING, PING_TIMEOUT_MS);
        renderStatus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        store.statusPrefs().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdown();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("pending_slot", pendingSlot);
        outState.putString("pending_title", pendingTitle);
    }

    // ---------------------------------------------------------------- 与 system_server 通信

    private void send(String action, String kind, long timeoutMs) {
        waitingKind = kind;
        requestTime = System.currentTimeMillis();
        timedOut = false;
        if (KIND_SYNC.equals(kind)) applyButton.setEnabled(false);
        Intent intent = new Intent(action).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        sendBroadcast(intent, PERMISSION);
        main.removeCallbacks(timeoutRunnable);
        main.postDelayed(timeoutRunnable, timeoutMs);
        renderStatus();
    }

    private void onRequestTimeout() {
        if (waitingKind == null) return;
        if (KIND_SYNC.equals(waitingKind)) toast("系统框架没有响应，请确认模块已在 LSPosed 中生效");
        waitingKind = null;
        timedOut = true;
        applyButton.setEnabled(true);
        renderStatus();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (!KEY_TIME.equals(key) || waitingKind == null) return;
        if (sp.getLong(KEY_TIME, 0) < requestTime) return;
        String kind = sp.getString(KEY_KIND, null);
        if (!waitingKind.equals(kind)) return;
        main.removeCallbacks(timeoutRunnable);
        waitingKind = null;
        connected = true;
        timedOut = false;
        applyButton.setEnabled(true);
        if (KIND_SYNC.equals(kind)) toast(sp.getString(KEY_MESSAGE, "已应用"));
        renderStatus();
    }

    private void renderStatus() {
        SharedPreferences sp = store.statusPrefs();
        if (KIND_SYNC.equals(waitingKind)) {
            statusTitle.setText("⏳ 正在同步到系统…");
        } else if (connected) {
            statusTitle.setText("✅ 模块已生效");
        } else if (timedOut) {
            statusTitle.setText("⚠️ 未检测到模块");
            statusDetail.setText("请在 LSPosed 中启用本模块，作用域勾选「系统框架」，然后重启手机。");
            return;
        } else {
            statusTitle.setText("正在检测模块状态…");
        }

        StringBuilder detail = new StringBuilder("系统当前：")
                .append(modeName(connected ? sp.getString(KEY_CURRENT_MODE, null) : localMode()));
        String last = sp.getString(KEY_LAST_MODE, null);
        if (connected && last != null) detail.append("\n最近一次自动应用：").append(modeName(last)).append("壁纸");
        long syncTime = sp.getLong(KEY_SYNC_TIME, 0);
        if (syncTime > 0) {
            detail.append("\n上次同步（")
                    .append(DateFormat.format("MM-dd HH:mm", syncTime))
                    .append("）：")
                    .append(sp.getString(KEY_MESSAGE, ""));
        }
        statusDetail.setText(detail);
    }

    private String localMode() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? MODE_DARK : MODE_LIGHT;
    }

    private static String modeName(String mode) {
        return MODE_DARK.equals(mode) ? "深色模式" : "亮色模式";
    }

    // ---------------------------------------------------------------- 选图

    private void pick(String slot, String title) {
        pendingSlot = slot;
        pendingTitle = title;
        picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void onPicked(Uri uri) {
        String slot = pendingSlot;
        pendingSlot = null;
        if (uri == null || slot == null) return;
        String title = pendingTitle;
        io.execute(() -> {
            try {
                store.importImage(slot, uri, screen);
                main.post(() -> {
                    refreshCards();
                    // 选完直接进入调整；取消也会保留默认的居中铺满
                    openCrop(slot, title);
                });
            } catch (Exception | OutOfMemoryError e) {
                main.post(() -> toast("导入失败：" + e.getMessage()));
            }
        });
    }

    private void openCrop(String slot, String title) {
        cropLauncher.launch(new Intent(this, CropActivity.class)
                .putExtra(CropActivity.EXTRA_SLOT, slot)
                .putExtra(CropActivity.EXTRA_TITLE, title));
    }

    private void refreshCards() {
        lightCard.refresh();
        darkCard.refresh();
    }

    private void loadThumbnail(ImageView view, File file) {
        String key = file.isFile() ? file.getPath() + "@" + file.length() + "-" + file.lastModified() : null;
        if (key != null && key.equals(view.getTag())) return;
        view.setTag(key);
        view.setImageDrawable(null);
        if (key == null) return;
        io.execute(() -> {
            Bitmap bmp = WallpaperRenderer.decodeSampled(file, THUMB_SIDE);
            main.post(() -> {
                if (key.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 视图

    /** 一个模式（亮/暗）的卡片：桌面图、锁屏图、锁屏策略。 */
    private final class ModeCard {
        private final String mode;
        private final SlotView home;
        private final SlotView lock;

        ModeCard(View root, String mode, int title, int subtitle) {
            this.mode = mode;
            ((TextView) root.findViewById(R.id.mode_title)).setText(title);
            ((TextView) root.findViewById(R.id.mode_subtitle)).setText(subtitle);
            String name = getString(title);
            home = new SlotView(root.findViewById(R.id.slot_home), mode + SLOT_HOME, R.string.slot_home,
                    name + " · " + getString(R.string.slot_home));
            lock = new SlotView(root.findViewById(R.id.slot_lock), mode + SLOT_LOCK, R.string.slot_lock,
                    name + " · " + getString(R.string.slot_lock));

            MaterialButtonToggleGroup group = root.findViewById(R.id.lock_mode);
            group.check(buttonFor(store.lockMode(mode)));
            group.addOnButtonCheckedListener((g, id, checked) -> {
                if (!checked) return;
                store.setLockMode(mode, lockModeFor(id));
                refresh();
            });
        }

        void refresh() {
            home.show(store.file(home.slot), true, getString(R.string.slot_pick));
            switch (store.lockMode(mode)) {
                case LOCK_SAME:
                    lock.show(store.file(home.slot), false, "与桌面相同");
                    break;
                case LOCK_CUSTOM:
                    lock.show(store.file(lock.slot), true, getString(R.string.slot_pick));
                    break;
                default:
                    lock.show(null, false, "保持当前锁屏");
                    break;
            }
        }

        private int buttonFor(int lockMode) {
            switch (lockMode) {
                case LOCK_NONE: return R.id.lock_none;
                case LOCK_CUSTOM: return R.id.lock_custom;
                default: return R.id.lock_same;
            }
        }

        private int lockModeFor(int buttonId) {
            if (buttonId == R.id.lock_none) return LOCK_NONE;
            if (buttonId == R.id.lock_custom) return LOCK_CUSTOM;
            return LOCK_SAME;
        }
    }

    /** 一个图片槽位：没图时点击选图，有图时点击调整位置。 */
    private final class SlotView {
        final String slot;
        private final AspectCardView card;
        private final ImageView image;
        private final TextView hint;
        private final View badge;
        private final View replace;
        private final View clear;
        private boolean hasImage;

        SlotView(View root, String slot, int label, String title) {
            this.slot = slot;
            card = root.findViewById(R.id.slot_card);
            image = root.findViewById(R.id.slot_image);
            hint = root.findViewById(R.id.slot_hint);
            badge = root.findViewById(R.id.slot_badge);
            replace = root.findViewById(R.id.slot_replace);
            clear = root.findViewById(R.id.slot_clear);
            ((TextView) root.findViewById(R.id.slot_label)).setText(label);
            card.setRatio((float) screen.y / screen.x);
            card.setOnClickListener(v -> {
                if (hasImage) openCrop(slot, title);
                else pick(slot, title);
            });
            replace.setOnClickListener(v -> pick(slot, title));
            clear.setOnClickListener(v -> {
                store.clear(slot);
                refreshCards();
            });
        }

        /**
         * @param file     要预览的图片，null 表示无
         * @param editable 是否允许点击选择/调整/移除
         */
        void show(File file, boolean editable, String emptyHint) {
            hasImage = file != null && file.isFile();
            boolean actions = editable && hasImage;
            card.setClickable(editable);
            card.setAlpha(editable ? 1f : 0.55f);
            replace.setVisibility(actions ? View.VISIBLE : View.INVISIBLE);
            clear.setVisibility(actions ? View.VISIBLE : View.INVISIBLE);
            badge.setVisibility(actions ? View.VISIBLE : View.GONE);
            hint.setText(emptyHint);
            hint.setVisibility(actions ? View.GONE : View.VISIBLE);
            loadThumbnail(image, hasImage ? file : new File(""));
        }
    }
}
