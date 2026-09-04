package com.estradaplay.patriota;

/** BASE_CONSOLIDADA_V210: pure geometric policy extracted from RoadSafetyService. */
final class RoadHazardMatcher {
    static final class Match {
        final boolean valid;
        final double forwardM;
        final double lateralM;
        final double distanceM;

        Match(boolean valid, double forwardM, double lateralM, double distanceM) {
            this.valid = valid;
            this.forwardM = forwardM;
            this.lateralM = lateralM;
            this.distanceM = distanceM;
        }

        static Match no() { return new Match(false, 0, 0, 0); }
    }

    private RoadHazardMatcher() {}

    static Match match(double lat, double lon, double heading, double speedKmh,
                       RoadHazard hazard, boolean rain) {
        if (hazard == null || !Double.isFinite(lat) || !Double.isFinite(lon)
                || !Double.isFinite(heading) || !Double.isFinite(speedKmh)) return Match.no();

        double dLat = hazard.lat - lat;
        double dLon = hazard.lon - lon;
        double north = dLat * 110540.0;
        double east = dLon * 111320.0 * Math.max(0.25, Math.cos(Math.toRadians(lat)));
        double rad = Math.toRadians(heading);
        double forward = east * Math.sin(rad) + north * Math.cos(rad);
        double lateral = Math.abs(east * Math.cos(rad) - north * Math.sin(rad));
        double distance = Math.hypot(east, north);

        // Keep the exact 2.0.x safety gates. A bump can be warned almost under the car,
        // while other point hazards must still be positively ahead of the vehicle.
        if ("QUEBRA_MOLAS".equals(hazard.type)) {
            if (forward < -12.0) return Match.no();
        } else if (forward <= 12.0) {
            return Match.no();
        }

        double maxDistance;
        double maxLateral;
        double minSpeed;
        switch (hazard.type) {
            case "SEMAFORO":
                maxDistance = speedKmh >= 55 ? 300 : 220; maxLateral = 55; minSpeed = 18; break;
            case "QUEBRA_MOLAS":
                maxDistance = speedKmh >= 55 ? 430 : 300; maxLateral = 55; minSpeed = 3; break;
            case "CAMERA_MONITORAMENTO":
                maxDistance = speedKmh >= 80 ? 650 : 450; maxLateral = 75; minSpeed = 8; break;
            case "PEDAGIO":
                maxDistance = speedKmh >= 80 ? 1100 : 800; maxLateral = 150; minSpeed = 10; break;
            case "PASSAGEM_NIVEL":
                maxDistance = speedKmh >= 70 ? 700 : 500; maxLateral = 85; minSpeed = 10; break;
            default:
                maxDistance = speedKmh >= 95 ? 1250 : (speedKmh >= 70 ? 1000 : 700);
                maxLateral = speedKmh >= 70 ? 115 : 85;
                minSpeed = 10;
                break;
        }
        if (rain) maxDistance *= 1.18;
        if (speedKmh < minSpeed || forward > maxDistance || lateral > maxLateral
                || distance > maxDistance * 1.18) return Match.no();
        if (Double.isFinite(hazard.heading) && angleDiff(heading, hazard.heading) > 75.0) return Match.no();
        return new Match(true, forward, lateral, distance);
    }

    static double priorityBias(String type) {
        if ("QUEBRA_MOLAS".equals(type)) return -220.0;
        if ("RADAR".equals(type)) return -160.0;
        if ("PASSAGEM_NIVEL".equals(type)) return -130.0;
        if ("SEMAFORO".equals(type)) return -90.0;
        if ("CAMERA_MONITORAMENTO".equals(type)) return -35.0;
        return 0.0;
    }

    static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }
}