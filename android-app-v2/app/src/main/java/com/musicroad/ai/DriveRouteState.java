package com.musicroad.ai;

import org.json.JSONArray;

/**
 * Route metadata shared with the landscape DriveOS cockpit.
 * It is populated by FastMapboxRouteEngine from the normal server/Mapbox route.
 * This is not Mapbox Navigation SDK state.
 */
final class DriveRouteState {
    private static String coordinates="[]";
    private static String steps="[]";
    private static String maxspeeds="[]";
    private static double distanceMeters=0d;
    private static double durationSeconds=0d;
    private static String destination="";
    private static long updatedAt=0L;

    private DriveRouteState() {}

    static synchronized void update(JSONArray coords, JSONArray routeSteps, JSONArray routeMaxspeeds,
                                    double distance, double duration, String label) {
        coordinates=coords==null?"[]":coords.toString();
        steps=routeSteps==null?"[]":routeSteps.toString();
        maxspeeds=routeMaxspeeds==null?"[]":routeMaxspeeds.toString();
        distanceMeters=Math.max(0d,distance);
        durationSeconds=Math.max(0d,duration);
        destination=label==null?"":label;
        updatedAt=System.currentTimeMillis();
    }

    static synchronized void clear(){
        coordinates="[]";steps="[]";maxspeeds="[]";distanceMeters=0d;durationSeconds=0d;destination="";updatedAt=System.currentTimeMillis();
    }

    static synchronized JSONArray coordinates(){return array(coordinates);}
    static synchronized JSONArray steps(){return array(steps);}
    static synchronized JSONArray maxspeeds(){return array(maxspeeds);}
    static synchronized double distanceMeters(){return distanceMeters;}
    static synchronized double durationSeconds(){return durationSeconds;}
    static synchronized String destination(){return destination;}
    static synchronized long updatedAt(){return updatedAt;}

    private static JSONArray array(String raw){try{return new JSONArray(raw);}catch(Exception e){return new JSONArray();}}
}
