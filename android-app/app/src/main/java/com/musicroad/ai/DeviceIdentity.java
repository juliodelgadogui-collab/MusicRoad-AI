package com.musicroad.ai;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class DeviceIdentity {
    private DeviceIdentity() {}
    public static String token(Context c) {
        String androidId = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        String raw = c.getPackageName()+"|"+(androidId==null?"":androidId)+"|"+Build.MANUFACTURER+"|"+Build.MODEL;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : h) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { return Integer.toHexString(raw.hashCode()); }
    }
    public static String label() {
        String m=(Build.MANUFACTURER==null?"Android":Build.MANUFACTURER).trim();
        String model=(Build.MODEL==null?"Dispositivo":Build.MODEL).trim();
        return m+" "+model;
    }
}
