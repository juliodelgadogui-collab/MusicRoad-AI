package com.estradaplay.app;

import org.json.JSONObject;

final class RoadHazard {
    final String id;
    final String type;
    final double lat;
    final double lon;
    final String road;
    final int speed;
    final double heading;
    final String source;

    RoadHazard(String id, String type, double lat, double lon, String road, int speed, double heading, String source) {
        this.id = id == null ? "" : id;
        this.type = type == null ? "RADAR" : type;
        this.lat = lat;
        this.lon = lon;
        this.road = road == null ? "" : road;
        this.speed = speed;
        this.heading = heading;
        this.source = source == null ? "" : source;
    }

    static RoadHazard fromJson(JSONObject o) {
        if (o == null) return null;
        // MUSICROAD_RADARS_V174: accept both schemas without conversion server-side.
        double lat = o.has("lat") ? o.optDouble("lat", Double.NaN) : o.optDouble("latitude", Double.NaN);
        double lon = o.has("lon") ? o.optDouble("lon", Double.NaN) : o.optDouble("longitude", Double.NaN);
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        String id = o.optString("id", o.optString("external_id", ""));
        String type = o.optString("type", o.optString("tipo", "RADAR"));
        String road = o.optString("road", o.optString("rodovia", ""));
        int speed = o.has("speed") ? o.optInt("speed", 0) : o.optInt("velocidade", 0);
        String source = o.optString("source", o.optString("fonte", "MusicRoad"));
        return new RoadHazard(
                id,
                type == null || type.trim().isEmpty() ? "RADAR" : type.trim().toUpperCase(java.util.Locale.ROOT),
                lat,
                lon,
                road,
                speed,
                o.isNull("heading") ? Double.NaN : o.optDouble("heading", Double.NaN),
                source
        );
    }

    String label() {
        switch (type) {
            case "SEMAFORO": return "Semáforo";
            case "QUEBRA_MOLAS": return "Quebra-molas";
            case "PEDAGIO": return "Pedágio";
            case "PASSAGEM_NIVEL": return "Passagem de nível";
            default: return "Radar";
        }
    }
}
