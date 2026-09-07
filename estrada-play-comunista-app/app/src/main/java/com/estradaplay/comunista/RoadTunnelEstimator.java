package com.estradaplay.comunista;

/**
 * TUNNEL_TOLERANCE_V330: short straight-line dead reckoning for map continuity only.
 * The safety engine never evaluates hazards from this estimated point and stops using it quickly.
 */
final class RoadTunnelEstimator {
    static final class Point {
        final double lat,lon;
        Point(double lat,double lon){this.lat=lat;this.lon=lon;}
    }

    private RoadTunnelEstimator(){}

    static Point project(double lat,double lon,double headingDeg,double speedKmh,long ageMs){
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||!Double.isFinite(headingDeg)||!Double.isFinite(speedKmh))return null;
        if(speedKmh<10||speedKmh>220||ageMs<4000L||ageMs>22_000L)return null;
        double seconds=Math.min(20.0,ageMs/1000.0);
        double distanceM=(speedKmh/3.6)*seconds;
        // Keep estimation conservative even at motorway speed.
        distanceM=Math.min(distanceM,650.0);
        double rad=Math.toRadians(headingDeg);
        double north=Math.cos(rad)*distanceM;
        double east=Math.sin(rad)*distanceM;
        double outLat=lat+north/110540.0;
        double cos=Math.max(.25,Math.cos(Math.toRadians(lat)));
        double outLon=lon+east/(111320.0*cos);
        return new Point(outLat,outLon);
    }
}
