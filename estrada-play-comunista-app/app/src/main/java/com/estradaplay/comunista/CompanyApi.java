package com.estradaplay.comunista;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Thin client for the company/fleet API. */
final class CompanyApi {
    private final ApiClient api;

    CompanyApi(Context context) { api = new ApiClient(context.getApplicationContext()); }

    JSONObject context() throws Exception { return call("context", new JSONObject()); }
    JSONObject dashboard() throws Exception { return call("dashboard", new JSONObject()); }
    JSONObject vehicles() throws Exception { return call("vehicles", new JSONObject()); }
    JSONObject drivers() throws Exception { return callPath("api/native_company_drivers.php", new JSONObject()); }
    JSONObject journeys() throws Exception { return callPath("api/native_company_journeys.php", new JSONObject()); }
    JSONObject driverStatus() throws Exception { return callPath("api/native_company_driver_status.php", new JSONObject()); }
    JSONObject convoyStatus() throws Exception { return callPath("api/native_company_convoy.php?action=status", new JSONObject()); }
    JSONObject convoyRoster() throws Exception { return callPath("api/native_company_convoy_roster.php?action=status", new JSONObject()); }
    JSONObject fleetMap() throws Exception { return callPath("api/native_company_map.php", new JSONObject()); }
    JSONObject claimConvoyLeadership() throws Exception { return callPath("api/native_company_convoy_claim_leader.php", new JSONObject()); }
    JSONObject maintenance() throws Exception { return callPath("api/native_company_maintenance.php?action=list", new JSONObject()); }

    JSONObject report(int days) throws Exception {
        JSONObject d = new JSONObject();
        d.put("days", days);
        return callPath("api/native_company_report.php", d);
    }

    JSONObject saveMaintenanceTask(int id, int vehicleId, String title, double intervalKm, int intervalDays, String notes) throws Exception {
        JSONObject d = new JSONObject();
        if (id > 0) d.put("id", id);
        d.put("vehicle_id", vehicleId);
        d.put("title", title == null ? "" : title.trim());
        d.put("interval_km", Math.max(0.0, intervalKm));
        d.put("interval_days", Math.max(0, intervalDays));
        d.put("notes", notes == null ? "" : notes.trim());
        return callPath("api/native_company_maintenance.php?action=save", d);
    }

    JSONObject completeMaintenanceTask(int id) throws Exception {
        JSONObject d = new JSONObject();
        d.put("id", id);
        return callPath("api/native_company_maintenance.php?action=complete", d);
    }

    JSONObject createCompany(String name) throws Exception {
        JSONObject d = new JSONObject();
        d.put("name", name == null ? "" : name.trim());
        return callPath("api/native_company_create.php", d);
    }

    JSONObject publishConvoy(String code, String title) throws Exception {
        JSONObject d = new JSONObject();
        d.put("code", code == null ? "" : code.trim());
        d.put("title", title == null ? "Comboio da empresa" : title.trim());
        return callPath("api/native_company_convoy.php?action=publish", d);
    }

    JSONObject saveConvoyRoster(JSONArray driverIds, int leaderUserId) throws Exception {
        JSONObject d = new JSONObject();
        d.put("driver_ids", driverIds == null ? new JSONArray() : driverIds);
        d.put("leader_user_id", Math.max(0, leaderUserId));
        return callPath("api/native_company_convoy_roster.php?action=save", d);
    }

    JSONObject closeConvoy() throws Exception {
        return callPath("api/native_company_convoy.php?action=close", new JSONObject());
    }

    JSONObject saveVehicle(int id, String plate, String nickname, String model, int year, double odometerKm) throws Exception {
        JSONObject d = new JSONObject();
        if (id > 0) d.put("id", id);
        d.put("plate", plate == null ? "" : plate.trim());
        d.put("nickname", nickname == null ? "" : nickname.trim());
        d.put("model", model == null ? "" : model.trim());
        if (year > 0) d.put("year", year);
        if (Double.isFinite(odometerKm) && odometerKm >= 0) d.put("odometer_km", odometerKm);
        return call("save_vehicle", d);
    }

    JSONObject addDriver(String identifier) throws Exception {
        JSONObject d = new JSONObject();
        d.put("identifier", identifier == null ? "" : identifier.trim());
        return call("add_driver", d);
    }

    JSONObject assignVehicle(int userId, int vehicleId) throws Exception {
        JSONObject d = new JSONObject();
        d.put("user_id", userId);
        d.put("vehicle_id", vehicleId);
        return call("assign", d);
    }

    JSONObject journeyPing(double lat, double lon, double speedKmh, double heading) throws Exception {
        JSONObject d = new JSONObject();
        d.put("lat", lat);
        d.put("lon", lon);
        d.put("speed_kmh", Math.max(0.0, speedKmh));
        if (Double.isFinite(heading) && heading >= 0) d.put("heading", heading);
        d.put("client_time_ms", System.currentTimeMillis());
        return call("journey_ping", d);
    }

    JSONObject journeyStop() throws Exception { return call("journey_stop", new JSONObject()); }

    private JSONObject call(String action, JSONObject payload) throws Exception {
        return callPath("api/native_company.php?action=" + action, payload);
    }

    private JSONObject callPath(String path, JSONObject payload) throws Exception {
        ApiClient.Response r = api.post(path, payload == null ? new JSONObject() : payload);
        JSONObject j = r.json();
        if (!r.ok() || !j.optBoolean("ok", false)) {
            throw new Exception(j.optString("error", "Módulo empresa indisponível."));
        }
        return j;
    }
}
