package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/** Cached company context attached to the normal Estrada Play account. */
final class CompanyAccount {
    private static final String PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    private CompanyAccount() {}

    static JSONObject account(Context context) {
        try {
            SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    static JSONObject company(Context context) { return company(account(context)); }

    static JSONObject company(JSONObject account) {
        if (account == null) return null;
        JSONObject c = account.optJSONObject("company");
        return c != null && c.optInt("id", 0) > 0 ? c : null;
    }

    static boolean hasCompany(Context context) { return company(context) != null; }

    static boolean shouldUseCompanyCentral(Context context) {
        JSONObject c = company(context);
        if (c == null) return false;
        String role = c.optString("role", "").trim().toLowerCase(Locale.ROOT);
        return "owner".equals(role) || "admin".equals(role) || "manager".equals(role);
    }

    static boolean isDriverContext(Context context) {
        JSONObject c = company(context);
        if (c == null) return false;
        String role = c.optString("role", "").trim().toLowerCase(Locale.ROOT);
        return "driver".equals(role) || c.optJSONObject("active_vehicle") != null;
    }

    static int companyId(Context context) {
        JSONObject c = company(context);
        return c == null ? 0 : c.optInt("id", 0);
    }

    static String companyName(Context context) {
        JSONObject c = company(context);
        String name = c == null ? "" : c.optString("name", "").trim();
        return name.isEmpty() ? "Empresa" : name;
    }

    static String role(Context context) {
        JSONObject c = company(context);
        return c == null ? "" : c.optString("role", "").trim();
    }

    static JSONObject activeVehicle(Context context) {
        JSONObject c = company(context);
        return c == null ? null : c.optJSONObject("active_vehicle");
    }

    static int activeVehicleId(Context context) {
        JSONObject v = activeVehicle(context);
        return v == null ? 0 : v.optInt("id", 0);
    }

    static void saveCompany(Context context, JSONObject company) {
        try {
            SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject account = new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
            if (company == null || company.optInt("id", 0) <= 0) account.remove("company");
            else account.put("company", company);
            p.edit().putString(KEY_ACCOUNT, account.toString()).apply();
        } catch (Throwable ignored) {}
    }
}
