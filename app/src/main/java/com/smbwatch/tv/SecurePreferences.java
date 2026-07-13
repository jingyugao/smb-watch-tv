package com.smbwatch.tv;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

final class SecurePreferences {
    private static final String LEGACY_NAME = "smb_pref";
    private static final String SECURE_NAME = "smb_secure_pref";
    private static SharedPreferences instance;

    private SecurePreferences() { }

    static synchronized SharedPreferences get(Context context) {
        if (instance != null) return instance;
        Context app = context.getApplicationContext();
        try {
            MasterKey key = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            instance = EncryptedSharedPreferences.create(
                    app,
                    SECURE_NAME,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            migrateLegacy(app, instance);
            return instance;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("无法初始化安全存储", e);
        }
    }

    private static void migrateLegacy(Context context, SharedPreferences secure) {
        SharedPreferences legacy = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = secure.edit();
        boolean changed = false;
        for (String key : new String[]{"connections", "playlists"}) {
            if (!secure.contains(key) && legacy.contains(key)) {
                editor.putString(key, legacy.getString(key, ""));
                changed = true;
            }
        }
        if (changed) editor.commit();
        legacy.edit().clear().apply();
    }
}
