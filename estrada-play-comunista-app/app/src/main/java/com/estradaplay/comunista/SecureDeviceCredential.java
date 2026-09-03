package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * SECURITY_V207: separates the public device identifier from the credential used to authenticate it.
 * Secrets are encrypted with Android Keystore whenever the automotive ROM supports it.
 *
 * SESSION_PERSIST_V232: some OEM/automotive ROMs can encrypt successfully and then fail to reopen
 * the same Keystore key after the process is recreated. A recovery copy of ONLY the random device
 * secret is therefore kept in this application's private SharedPreferences. It is still removed on
 * uninstall/clear-data, is never derived from Android ID, and access/refresh tokens remain protected
 * by the normal Keystore/fallback path. This lets device_login mint fresh short-lived tokens without
 * asking the driver for a password after every app restart.
 */
final class SecureDeviceCredential {
    private static final String PREFS = "estradaplay_secure_auth_v207";
    private static final String KEY_ALIAS = "estradaplay.auth.v207";
    private static final String KEY_SECRET = "device_secret";
    private static final String KEY_SECRET_RECOVERY = "device_secret_recovery_v232";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_ACCESS_EXP = "access_exp";
    private static final String KEY_REFRESH_EXP = "refresh_exp";
    private static final String PREFIX_ENCRYPTED = "g1:";
    private static final String PREFIX_PRIVATE_FALLBACK = "p1:";

    private final Context app;
    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    SecureDeviceCredential(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized String secret() {
        String current = load(KEY_SECRET);
        if (validSecret(current)) {
            ensureRecoverySecret(current);
            return current;
        }

        String recovered = loadRecoverySecret();
        if (validSecret(recovered)) {
            // Repair the Keystore-backed copy when an OEM provider temporarily lost access to it.
            save(KEY_SECRET, recovered);
            return recovered;
        }

        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String generated = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        save(KEY_SECRET, generated);
        ensureRecoverySecret(generated);
        return generated;
    }

    synchronized String accessToken() {
        String value = load(KEY_ACCESS);
        if (value == null || value.isEmpty()) return "";
        long exp = prefs.getLong(KEY_ACCESS_EXP, 0L);
        if (exp > 0L && System.currentTimeMillis() >= exp - 15_000L) return "";
        return value;
    }

    synchronized String refreshToken() {
        String value = load(KEY_REFRESH);
        if (value == null || value.isEmpty()) return "";
        long exp = prefs.getLong(KEY_REFRESH_EXP, 0L);
        if (exp > 0L && System.currentTimeMillis() >= exp - 30_000L) {
            clearTokens();
            return "";
        }
        return value;
    }

    synchronized void saveTokens(String access, String refresh, long accessExpiresAtMs, long refreshExpiresAtMs) {
        if (access == null || access.trim().isEmpty() || refresh == null || refresh.trim().isEmpty()) return;
        // Touch secret() so every successful secure session also gets the v2.3.2 recovery mirror.
        secret();
        save(KEY_ACCESS, access.trim());
        save(KEY_REFRESH, refresh.trim());
        prefs.edit()
                .putLong(KEY_ACCESS_EXP, Math.max(0L, accessExpiresAtMs))
                .putLong(KEY_REFRESH_EXP, Math.max(0L, refreshExpiresAtMs))
                .apply();
    }

    synchronized void clearTokens() {
        prefs.edit()
                .remove(KEY_ACCESS)
                .remove(KEY_REFRESH)
                .remove(KEY_ACCESS_EXP)
                .remove(KEY_REFRESH_EXP)
                .apply();
    }

    synchronized void clearAllForReset() {
        prefs.edit().clear().apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (Throwable ignored) {}
    }

    private boolean validSecret(String value) {
        return value != null && value.length() >= 40 && value.length() <= 192;
    }

    private void ensureRecoverySecret(String value) {
        if (!validSecret(value)) return;
        String existing = loadRecoverySecret();
        if (value.equals(existing)) return;
        // App-private recovery mirror. android:allowBackup=false keeps it out of Android backup.
        String encoded = Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        prefs.edit().putString(KEY_SECRET_RECOVERY, encoded).apply();
    }

    private String loadRecoverySecret() {
        String encoded = prefs.getString(KEY_SECRET_RECOVERY, "");
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] raw = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String value = new String(raw, StandardCharsets.UTF_8);
            return validSecret(value) ? value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void save(String key, String value) {
        if (value == null) return;
        String stored;
        try {
            stored = encrypt(value);
        } catch (Throwable unavailableKeystore) {
            // Some old automotive ROMs ship broken Keystore providers. App-private storage is a
            // compatibility fallback; the credential is still random and never derived from Android ID.
            stored = PREFIX_PRIVATE_FALLBACK + Base64.encodeToString(
                    value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.NO_PADDING);
        }
        prefs.edit().putString(key, stored).apply();
    }

    private String load(String key) {
        String stored = prefs.getString(key, "");
        if (stored == null || stored.isEmpty()) return "";
        try {
            if (stored.startsWith(PREFIX_ENCRYPTED)) return decrypt(stored);
            if (stored.startsWith(PREFIX_PRIVATE_FALLBACK)) {
                byte[] raw = Base64.decode(stored.substring(PREFIX_PRIVATE_FALLBACK.length()), Base64.DEFAULT);
                return new String(raw, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private String encrypt(String value) throws Exception {
        SecretKey key = keystoreKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return PREFIX_ENCRYPTED
                + Base64.encodeToString(iv, Base64.NO_WRAP | Base64.NO_PADDING) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":", 3);
        if (parts.length != 3) return "";
        byte[] iv = Base64.decode(parts[1], Base64.DEFAULT);
        byte[] encrypted = Base64.decode(parts[2], Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private SecretKey keystoreKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
