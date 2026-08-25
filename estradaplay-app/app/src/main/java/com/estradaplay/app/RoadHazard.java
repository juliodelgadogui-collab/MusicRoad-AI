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
        double lat = o.optDouble("lat", Double.NaN);
        double lon = o.optDouble("lon", Double.NaN);
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        return new RoadHazard(
                o.optString("id", ""),
                o.optString("type", "RADAR"),
                lat,
                lon,
                o.optString("road", ""),
                o.optInt("speed", 0),
                o.isNull("heading") ? Double.NaN : o.optDouble("heading", Double.NaN),
                o.optString("source", "")
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
