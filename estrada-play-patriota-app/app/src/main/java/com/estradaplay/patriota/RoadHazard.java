package com.estradaplay.patriota;

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
        String type = normalizeType(o.optString("type", o.optString("tipo", "RADAR")));
        String road = o.optString("road", o.optString("rodovia", ""));
        int speed = o.has("speed") ? o.optInt("speed", 0) : o.optInt("velocidade", 0);
        String source = o.optString("source", o.optString("fonte", "MusicRoad"));
        return new RoadHazard(
                id,
                type,
                lat,
                lon,
                road,
                speed,
                o.isNull("heading") ? Double.NaN : o.optDouble("heading", Double.NaN),
                source
        );
    }

    private static String normalizeType(String raw) {
        String key = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        key = key.replace('Á','A').replace('À','A').replace('Ã','A').replace('Â','A')
                .replace('É','E').replace('Ê','E').replace('Í','I').replace('Ó','O')
                .replace('Ô','O').replace('Õ','O').replace('Ú','U').replace('Ç','C')
                .replace('-', '_').replace(' ', '_');
        if (key.isEmpty()) return "RADAR";
        if (key.contains("RADAR") || key.contains("SPEED_CAMERA") || key.contains("MAXSPEED") || key.contains("ENFORCEMENT")) return "RADAR";
        if (key.contains("QUEBRA") || key.contains("LOMBADA") || key.contains("SPEED_BUMP") || key.contains("SPEED_HUMP")) return "QUEBRA_MOLAS";
        if (key.contains("SEMAFOR") || key.contains("TRAFFIC_SIGNAL")) return "SEMAFORO";
        if (key.contains("PEDAG") || key.contains("TOLL")) return "PEDAGIO";
        if (key.contains("PASSAGEM_NIVEL") || key.contains("LEVEL_CROSSING")) return "PASSAGEM_NIVEL";
        if (key.contains("CAMERA") || key.contains("CCTV") || key.contains("SURVEILLANCE") || key.contains("MONITORAMENTO")) return "CAMERA_MONITORAMENTO";
        return key;
    }

    // UNIVERSAL_CONFIDENCE_V160: provenance label; never invents an official status.
    String confidenceLabel() {
        String s = source == null ? "" : source.toUpperCase(java.util.Locale.ROOT);
        if (s.contains("DER-") || s.contains("DNIT") || s.contains("PRF") || s.contains("OFICIAL")) return "OFICIAL";
        if (s.contains("OPENSTREETMAP") || s.contains("OSM")) return "MAPA · A CONFIRMAR";
        if (s.contains("USU") || s.contains("COLET") || s.contains("COMUN")) return "COMUNIDADE";
        if (!s.isEmpty()) return "BASE LOCAL";
        return "ORIGEM NÃO INFORMADA";
    }

    String label() {
        switch (type) {
            case "SEMAFORO": return "Semáforo";
            case "QUEBRA_MOLAS": return "Quebra-molas";
            case "PEDAGIO": return "Pedágio";
            case "PASSAGEM_NIVEL": return "Passagem de nível";
            case "CAMERA_MONITORAMENTO": return "Câmera de monitoramento";
            default: return "Radar";
        }
    }
}
