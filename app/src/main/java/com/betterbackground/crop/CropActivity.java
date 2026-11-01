package com.betterbackground.crop;

import static com.betterbackground.Constants.SLOTS;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.betterbackground.R;
import com.betterbackground.bridge.Store;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 调整某个槽位壁纸的显示位置与大小，完成后按屏幕分辨率重新生成壁纸。 */
public class CropActivity extends AppCompatActivity {
    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_TITLE = "title";
    private static final int PREVIEW_SIDE = 1440;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Store store;
    private String slot;
    private Point screen;
    private CropView cropView;
    private MaterialButton doneButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        slot = getIntent().getStringExtra(EXTRA_SLOT);
        if (slot == null || !Arrays.asList(SLOTS).contains(slot)) {
            finish();
            return;
        }
        store = Store.get(this);
        screen = WallpaperRenderer.screenSize(this);

        View root = findViewById(R.id.crop_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ((TextView) findViewById(R.id.crop_title)).setText(getIntent().getStringExtra(EXTRA_TITLE));
        cropView = findViewById(R.id.crop_view);
        cropView.setScreenAspect(screen.x, screen.y);

        CropParams initial = store.crop(slot);
        // 恢复 Activity 重建前的编辑状态
        if (savedInstanceState != null) initial = CropParams.parse(savedInstanceState.getString("params"));

        View fillRow = findViewById(R.id.fill_row);
        MaterialButtonToggleGroup fillGroup = findViewById(R.id.fill_group);
        fillGroup.check(initial.fill == CropParams.FILL_BLACK ? R.id.fill_black : R.id.fill_blur);
        fillGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) cropView.setFill(id == R.id.fill_black ? CropParams.FILL_BLACK : CropParams.FILL_BLUR);
        });
        cropView.setListener(p -> fillRow.setVisibility(p.coversFrame() ? View.INVISIBLE : View.VISIBLE));

        findViewById(R.id.crop_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.crop_reset).setOnClickListener(v -> cropView.reset());
        doneButton = findViewById(R.id.crop_done);
        doneButton.setEnabled(false);
        doneButton.setOnClickListener(v -> save());

        CropParams start = initial;
        io.execute(() -> {
            try {
                File src = store.ensureSource(slot);
                BitmapFactory.Options b = WallpaperRenderer.bounds(src);
                Bitmap preview = WallpaperRenderer.decodeSampled(src, PREVIEW_SIDE);
                if (preview == null) throw new IllegalStateException("无法读取图片");
                Bitmap blurred = WallpaperRenderer.blur(preview);
                main.post(() -> {
                    if (isDestroyed()) return;
                    cropView.setImage(preview, blurred, b.outWidth, b.outHeight, start);
                    doneButton.setEnabled(true);
                });
            } catch (Exception e) {
                main.post(() -> {
                    Toast.makeText(this, "打开图片失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cropView != null) outState.putString("params", cropView.getParams().serialize());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void save() {
        doneButton.setEnabled(false);
        doneButton.setText("正在生成…");
        CropParams params = cropView.getParams();
        io.execute(() -> {
            try {
                store.setCrop(slot, params);
                store.render(slot, screen);
                main.post(() -> {
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception | OutOfMemoryError e) {
                main.post(() -> {
                    Toast.makeText(this, "生成壁纸失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    doneButton.setText(R.string.crop_done);
                    doneButton.setEnabled(true);
                });
            }
        });
    }
}
