package com.estradaplay.comunista;

import java.util.ArrayDeque;

/**
 * Uses recent GPS movement as the primary heading source for hazard matching.
 * This reduces false alerts from parallel lanes and brief GPS-bearing jumps.
 */
final class RoadTrajectoryHeading {
    private static final long RESET_GAP_MS = 30_000L;
    private static final long KEEP_MS = 22_000L;
    private static final int MAX_SEGMENTS = 8;
    private static final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private static double lastLat = Double.NaN, lastLon = Double.NaN;
    private static long lastAt;

    private RoadTrajectoryHeading() {}

    static synchronized double observe(double lat, double lon, double gpsHeading, double speedKmh, long nowMs) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return gpsHeading;
        if (lastAt > 0 && (nowMs - lastAt > RESET_GAP_MS || nowMs < lastAt)) reset();

        if (Double.isFinite(lastLat) && Double.isFinite(lastLon) && lastAt > 0) {
            double d = distanceM(lastLat, lastLon, lat, lon);
            long dt = Math.max(1L, nowMs - lastAt);
            // Ignore GPS jitter and impossible jumps. Real movement of 4 m+ contributes.
            if (d >= 4.0 && d <= 350.0 && dt <= 15_000L) {
                double bearing = bearing(lastLat, lastLon, lat, lon);
                segments.addLast(new Segment(bearing, Math.min(80.0, d), nowMs));
                while (segments.size() > MAX_SEGMENTS) segments.removeFirst();
            }
        }
        lastLat = lat; lastLon = lon; lastAt = nowMs;
        while (!segments.isEmpty() && nowMs - segments.peekFirst().at > KEEP_MS) segments.removeFirst();

        if (segments.isEmpty() || speedKmh < 6.0) return gpsHeading;
        double sx = 0.0, sy = 0.0, weight = 0.0;
        for (Segment s : segments) {
            double r = Math.toRadians(s.bearing);
            sx += Math.sin(r) * s.weight;
            sy += Math.cos(r) * s.weight;
            weight += s.weight;
        }
        if (weight < 10.0 || (Math.abs(sx) < 1e-6 && Math.abs(sy) < 1e-6)) return gpsHeading;
        double track = normalize(Math.toDegrees(Math.atan2(sx, sy)));
        if (!Double.isFinite(gpsHeading)) return track;

        // Blend only when the sensors broadly agree. If they disagree sharply,
        // recent movement wins because it reflects the actual road trajectory.
        double diff = angleDiff(track, gpsHeading);
        if (diff > 55.0) return track;
        double rr = Math.toRadians(track), rg = Math.toRadians(gpsHeading);
        double bx = Math.sin(rr) * 0.78 + Math.sin(rg) * 0.22;
        double by = Math.cos(rr) * 0.78 + Math.cos(rg) * 0.22;
        return normalize(Math.toDegrees(Math.atan2(bx, by)));
    }

    static synchronized void reset() {
        segments.clear(); lastLat = Double.NaN; lastLon = Double.NaN; lastAt = 0L;
    }

    private static double distanceM(double aLat,double aLon,double bLat,double bLon) {
        double north=(bLat-aLat)*110540.0;
        double east=(bLon-aLon)*111320.0*Math.max(.25,Math.cos(Math.toRadians((aLat+bLat)*.5)));
        return Math.hypot(east,north);
    }

    private static double bearing(double aLat,double aLon,double bLat,double bLon) {
        double p1=Math.toRadians(aLat), p2=Math.toRadians(bLat), dl=Math.toRadians(bLon-aLon);
        double y=Math.sin(dl)*Math.cos(p2);
        double x=Math.cos(p1)*Math.sin(p2)-Math.sin(p1)*Math.cos(p2)*Math.cos(dl);
        return normalize(Math.toDegrees(Math.atan2(y,x)));
    }

    private static double normalize(double d){double v=d%360.0;return v<0?v+360.0:v;}
    private static double angleDiff(double a,double b){double d=Math.abs(a-b)%360.0;return d>180.0?360.0-d:d;}

    private static final class Segment {
        final double bearing, weight; final long at;
        Segment(double bearing,double weight,long at){this.bearing=bearing;this.weight=weight;this.at=at;}
    }
}
