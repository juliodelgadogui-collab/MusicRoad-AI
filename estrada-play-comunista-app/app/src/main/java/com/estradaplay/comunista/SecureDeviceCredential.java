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
 * SESSION_PERSIST_V232: keeps an app-private recovery copy of the random installation secret for
 * OEM/automotive ROMs whose Keystore can encrypt but later fails to reopen the key.
 *
 * SESSION_RECOVERY_V234: access/refresh tokens also receive an app-private recovery mirror bounded
 * by their server expiry. android:allowBackup=false keeps these mirrors out of Android backup and
 * uninstall/clear-data removes them. The Keystore copy remains the primary storage; recovery exists
 * only so a process restart does not become a password prompt on broken OEM Keystore providers.
 */
final class SecureDeviceCredential {
    private static final String PREFS = "estradaplay_secure_auth_v207";
    private static final String KEY_ALIAS = "estradaplay.auth.v207";
    private static final String KEY_SECRET = "device_secret";
    private static final String KEY_SECRET_RECOVERY = "device_secret_recovery_v232";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_ACCESS_RECOVERY = "access_token_recovery_v234";
    private static final String KEY_REFRESH_RECOVERY = "refresh_token_recovery_v234";
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
        long exp = prefs.getLong(KEY_ACCESS_EXP, 0L);
        if (exp > 0L && System.currentTimeMillis() >= exp - 15_000L) return "";

        String value = load(KEY_ACCESS);
        return validToken(value) ? value : "";
    }

    synchronized String refreshToken() {
        long exp = prefs.getLong(KEY_REFRESH_EXP, 0L);
        if (exp > 0L && System.currentTimeMillis() >= exp - 30_000L) {
            clearTokens();
            return "";
        }

        String value = load(KEY_REFRESH);
        if (!validToken(value)) {
            value = loadRecoveryToken(KEY_REFRESH_RECOVERY);
            if (validToken(value)) save(KEY_REFRESH, value);
        }
        return validToken(value) ? value : "";
    }

    synchronized void saveTokens(String access, String refresh, long accessExpiresAtMs, long refreshExpiresAtMs) {
        String a = access == null ? "" : access.trim();
        String r = refresh == null ? "" : refresh.trim();
        if (!validToken(a) || !validToken(r)) return;

        secret();
        save(KEY_ACCESS, a);
        save(KEY_REFRESH, r);

        // Commit is deliberate: auth is read by a separately isolated radio process in v2.3.4.
        prefs.edit()
                .remove(KEY_ACCESS_RECOVERY)
                .putString(KEY_REFRESH_RECOVERY, encodeRecoveryToken(r))
                .putLong(KEY_ACCESS_EXP, Math.max(0L, accessExpiresAtMs))
                .putLong(KEY_REFRESH_EXP, Math.max(0L, refreshExpiresAtMs))
                .commit();
    }

    synchronized void clearTokens() {
        prefs.edit()
                .remove(KEY_ACCESS)
                .remove(KEY_REFRESH)
                .remove(KEY_ACCESS_RECOVERY)
                .remove(KEY_REFRESH_RECOVERY)
                .remove(KEY_ACCESS_EXP)
                .remove(KEY_REFRESH_EXP)
                .commit();
    }

    synchronized void clearAllForReset() {
        prefs.edit().clear().commit();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (Throwable ignored) {}
    }

    private boolean validSecret(String value) {
        return value != null && value.length() >= 40 && value.length() <= 192;
    }

    private boolean validToken(String value) {
        return value != null && value.length() >= 24 && value.length() <= 512;
    }

    private void ensureRecoverySecret(String value) {
        if (!validSecret(value)) return;
        String existing = loadRecoverySecret();
        if (value.equals(existing)) return;
        String encoded = Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        prefs.edit().putString(KEY_SECRET_RECOVERY, encoded).commit();
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

    private String encodeRecoveryToken(String value) {
        if (!validToken(value)) return "";
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private String loadRecoveryToken(String key) {
        String encoded = prefs.getString(key, "");
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] raw = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String value = new String(raw, StandardCharsets.UTF_8);
            return validToken(value) ? value : "";
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
            stored = PREFIX_PRIVATE_FALLBACK + Base64.encodeToString(
                    value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.NO_PADDING);
        }
        prefs.edit().putString(key, stored).commit();
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
