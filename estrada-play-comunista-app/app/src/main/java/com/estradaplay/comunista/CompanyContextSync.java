package com.estradaplay.comunista;

import android.content.Context;

import org.json.JSONObject;

/** Refreshes company membership without making normal login depend on the fleet module. */
final class CompanyContextSync {
    private CompanyContextSync() {}

    static void refresh(Context context) {
        try {
            JSONObject response = new CompanyApi(context).context();
            JSONObject company = response.optJSONObject("company");
            CompanyAccount.saveCompany(context, company);
        } catch (Throwable ignored) {
            // Keep the last cached company context. A temporary company API outage must not block the app.
        }
    }
}
