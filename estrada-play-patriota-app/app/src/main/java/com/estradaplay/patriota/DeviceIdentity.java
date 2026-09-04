package com.estradaplay.patriota;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class DeviceIdentity {
    private DeviceIdentity() {}

    static String token(Context context) {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String seed = (androidId == null ? "unknown" : androidId) + "|" + Build.MANUFACTURER + "|" + Build.MODEL + "|estradaplay-v1";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : hash) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            return String.format(Locale.US, "%064x", seed.hashCode() & 0xffffffffL);
        }
    }

    static String label() {
        String manufacturer = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "Aparelho" : Build.MODEL.trim();
        return (manufacturer + " " + model).trim();
    }
}
