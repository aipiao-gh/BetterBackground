package com.betterbackground.bridge;

import static com.betterbackground.Constants.*;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * system_server 通过它读取配置和图片、回报状态。
 * <p>
 * 图片用 call() 分块传输而不是传文件描述符，避开 SELinux 对 system_server 读取 app_data_file 的限制。
 */
public class BridgeProvider extends ContentProvider {
    private static final int SYSTEM_APP_ID = Process.SYSTEM_UID;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int appId = Binder.getCallingUid() % 100000;
        if (appId != SYSTEM_APP_ID && Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("only system_server may call this provider");
        }
        Store store = Store.get(getContext());
        switch (method) {
            case METHOD_GET_CONFIG:
                return store.buildConfig();
            case METHOD_READ:
                return read(store, arg, extras);
            case METHOD_REPORT:
                saveReport(store, extras);
                return Bundle.EMPTY;
            default:
                return null;
        }
    }

    private static Bundle read(Store store, String slot, Bundle extras) {
        if (slot == null || !Arrays.asList(SLOTS).contains(slot) || extras == null) return null;
        File file = store.file(slot);
        long offset = extras.getLong(KEY_OFFSET);
        int length = Math.min(extras.getInt(KEY_LENGTH, CHUNK_SIZE), CHUNK_SIZE);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long size = raf.length();
            int toRead = (int) Math.max(0, Math.min(length, size - offset));
            byte[] data = new byte[toRead];
            raf.seek(offset);
            raf.readFully(data);
            Bundle b = new Bundle();
            b.putByteArray(KEY_DATA, data);
            b.putBoolean(KEY_EOF, offset + toRead >= size);
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private static void saveReport(Store store, Bundle extras) {
        if (extras == null) return;
        String kind = extras.getString(KEY_KIND);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = store.statusPrefs().edit()
                .putString(KEY_KIND, kind)
                .putString(KEY_CURRENT_MODE, extras.getString(KEY_CURRENT_MODE))
                .putString(KEY_LAST_MODE, extras.getString(KEY_LAST_MODE))
                .putInt(KEY_HOOK_VERSION, extras.getInt(KEY_HOOK_VERSION))
                .putLong(KEY_TIME, now);
        if (KIND_SYNC.equals(kind)) {
            // ping 不覆盖上一次同步的结果
            editor.putBoolean(KEY_OK, extras.getBoolean(KEY_OK))
                    .putString(KEY_MESSAGE, extras.getString(KEY_MESSAGE))
                    .putLong(KEY_SYNC_TIME, now);
        }
        // 用 commit 而非 apply，保证 UI 的监听器在 system_server 调用返回前就能拿到数据
        editor.commit();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
