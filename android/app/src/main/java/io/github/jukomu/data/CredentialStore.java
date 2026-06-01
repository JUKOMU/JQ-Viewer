package io.github.jukomu.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * 凭据安全存储，使用 Android Keystore + AES-256 加密。
 * 加密不可用时禁用凭据持久化，避免明文保存密码。
 */
public class CredentialStore {

    private static final String PREF_NAME = "jq_credentials";
    private static CredentialStore instance;
    private final SharedPreferences prefs;

    public static synchronized CredentialStore getInstance(Context context) {
        if (instance == null) {
            instance = new CredentialStore(context.getApplicationContext());
        }
        return instance;
    }

    private CredentialStore(Context context) {
        SharedPreferences p = null;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            p = EncryptedSharedPreferences.create(
                PREF_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e("CredentialStore", "Encrypted storage unavailable; credentials will not be saved", e);
        }
        prefs = p;
    }

    public void save(String username, String password) {
        if (prefs == null) {
            Log.w("CredentialStore", "Skip saving credentials because encrypted storage is unavailable");
            return;
        }
        prefs.edit()
            .putString("username", username)
            .putString("password", password)
            .apply();
    }

    public String getUsername() {
        if (prefs == null) {
            return null;
        }
        return prefs.getString("username", null);
    }

    public String getPassword() {
        if (prefs == null) {
            return null;
        }
        return prefs.getString("password", null);
    }

    public void clear() {
        if (prefs == null) {
            return;
        }
        prefs.edit().clear().apply();
    }
}
