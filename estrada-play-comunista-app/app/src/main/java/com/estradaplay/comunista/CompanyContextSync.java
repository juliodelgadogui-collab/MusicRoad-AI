package com.estradaplay.comunista;

import android.content.Context;

import org.json.JSONObject;

/** Refreshes company membership without making normal login depend on the fleet module. */
final class CompanyContextSync {
    private CompanyContextSync() {}

    static void refresh(Context context) {
        try {
            CompanyApi api = new CompanyApi(context);
            JSONObject response = api.context();
            JSONObject company = response.optJSONObject("company");
            if (company != null) {
                try {
                    JSONObject convoy = api.convoyStatus().optJSONObject("active_convoy");
                    if (convoy == null) company.remove("active_convoy");
                    else company.put("active_convoy", convoy);
                } catch (Throwable ignored) {
                    // Membership/vehicle context is still valid even if the convoy endpoint is temporarily unavailable.
                }
            }
            CompanyAccount.saveCompany(context, company);
        } catch (Throwable ignored) {
            // Keep the last cached company context. A temporary company API outage must not block the app.
        }
    }
}
