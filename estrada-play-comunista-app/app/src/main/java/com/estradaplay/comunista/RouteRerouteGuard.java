package com.estradaplay.comunista;

/**
 * REROUTE_GUARD_V330: confirms moderate departures before spending a new route request.
 * It only operates on the route resolved in the current process; a fresh app session is not
 * forced to reuse an old cached route.
 */
final class RouteRerouteGuard {
    private static int activeRouteHash;
    private static double activeToLat=Double.NaN,activeToLon=Double.NaN;
    private static long lastModerateHoldAt;

    private RouteRerouteGuard(){}

    static synchronized void remember(RouteEngine.Route route,double toLat,double toLon){
        if(route==null||route.geoJson==null)return;
        activeRouteHash=route.geoJson.hashCode();activeToLat=toLat;activeToLon=toLon;
        lastModerateHoldAt=0L;
    }

    static synchronized boolean shouldReuseOnce(RouteEngine.Route cached,double lat,double lon,double toLat,double toLon,long now){
        if(cached==null||cached.geoJson==null||activeRouteHash==0||cached.geoJson.hashCode()!=activeRouteHash)return false;
        if(!Double.isFinite(activeToLat)||RouteEngine.distanceM(activeToLat,activeToLon,toLat,toLon)>300)return false;
        RouteEngine.Match m=cached.match(lat,lon,Double.NaN,-1,0);
        if(!m.valid)return false;
        if(m.lateralM>155.0)return false;
        if(m.lateralM<=68.0)return true;
        // 68–155 m: hold the first route request only. If another request arrives during the
        // next minute, the departure persisted and the online recalculation is allowed.
        if(lastModerateHoldAt<=0L||now-lastModerateHoldAt>60_000L){lastModerateHoldAt=now;return true;}
        return false;
    }
}
