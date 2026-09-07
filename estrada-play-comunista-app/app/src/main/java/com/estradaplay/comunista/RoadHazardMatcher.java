package com.estradaplay.comunista;

/** BASE_CONSOLIDADA_V210: pure geometric policy extracted from RoadSafetyService. */
final class RoadHazardMatcher {
    static final class Match {
        final boolean valid;
        final double forwardM;
        final double lateralM;
        final double distanceM;

        Match(boolean valid, double forwardM, double lateralM, double distanceM) {
            this.valid = valid; this.forwardM = forwardM; this.lateralM = lateralM; this.distanceM = distanceM;
        }
        static Match no() { return new Match(false,0,0,0); }
    }

    private RoadHazardMatcher() {}

    static Match match(double lat,double lon,double heading,double speedKmh,RoadHazard hazard,boolean rain) {
        if(hazard==null||!Double.isFinite(lat)||!Double.isFinite(lon)||!Double.isFinite(heading)||!Double.isFinite(speedKmh))return Match.no();
        double dLat=hazard.lat-lat,dLon=hazard.lon-lon;
        double north=dLat*110540.0,east=dLon*111320.0*Math.max(0.25,Math.cos(Math.toRadians(lat)));
        double rad=Math.toRadians(heading);
        double forward=east*Math.sin(rad)+north*Math.cos(rad);
        double lateral=Math.abs(east*Math.cos(rad)-north*Math.sin(rad));
        double distance=Math.hypot(east,north);

        if("QUEBRA_MOLAS".equals(hazard.type)){if(forward < -12.0)return Match.no();}
        else if(forward<=12.0)return Match.no();

        double maxDistance=warningDistance(hazard.type,speedKmh,rain);
        double maxLateral,minSpeed;
        switch(hazard.type){
            case "SEMAFORO_RADAR": maxLateral=72;minSpeed=7;break;
            case "SEMAFORO": maxLateral=48;minSpeed=12;break;
            case "QUEBRA_MOLAS": maxLateral=50;minSpeed=3;break;
            case "CAMERA_MONITORAMENTO": maxLateral=65;minSpeed=7;break;
            case "PEDAGIO": maxLateral=135;minSpeed=8;break;
            case "PASSAGEM_NIVEL": maxLateral=75;minSpeed=8;break;
            default: maxLateral=speedKmh>=70?100:78;minSpeed=8;break;
        }
        if(speedKmh<minSpeed||forward>maxDistance||lateral>maxLateral||distance>maxDistance*1.16)return Match.no();

        // Equipment with a known direction gets a stricter tolerance at road speed.
        // Low speed gets a little more tolerance for curves and junction approaches.
        if(Double.isFinite(hazard.heading)){
            double allowed=speedKmh>=80?48.0:(speedKmh>=40?58.0:72.0);
            if(angleDiff(heading,hazard.heading)>allowed)return Match.no();
        }
        return new Match(true,forward,lateral,distance);
    }

    /** Continuous warning window: faster travel means earlier warning, without abrupt thresholds. */
    static double warningDistance(String type,double speedKmh,boolean rain){
        double v=Math.max(0.0,Math.min(180.0,speedKmh));
        double d;
        switch(type==null?"":type){
            case "QUEBRA_MOLAS": d=210.0+v*4.1; d=clamp(d,260,720); break;
            case "SEMAFORO_RADAR": d=330.0+v*5.8; d=clamp(d,420,1050); break;
            case "SEMAFORO": d=150.0+v*2.8; d=clamp(d,190,500); break;
            case "CAMERA_MONITORAMENTO": d=300.0+v*4.0; d=clamp(d,380,850); break;
            case "PEDAGIO": d=580.0+v*5.8; d=clamp(d,720,1450); break;
            case "PASSAGEM_NIVEL": d=360.0+v*4.6; d=clamp(d,480,1050); break;
            default: d=470.0+v*7.2; d=clamp(d,620,1450); break;
        }
        return rain?Math.min(1650.0,d*1.18):d;
    }

    static double priorityBias(String type) {
        if("QUEBRA_MOLAS".equals(type))return -240.0;
        if("RADAR".equals(type))return -160.0;
        if("SEMAFORO_RADAR".equals(type))return -155.0;
        if("PASSAGEM_NIVEL".equals(type))return -130.0;
        if("SEMAFORO".equals(type))return -90.0;
        if("CAMERA_MONITORAMENTO".equals(type))return -35.0;
        return 0.0;
    }

    static double angleDiff(double a,double b){double d=Math.abs(a-b)%360.0;return d>180.0?360.0-d:d;}
    private static double clamp(double v,double min,double max){return Math.max(min,Math.min(max,v));}
}
