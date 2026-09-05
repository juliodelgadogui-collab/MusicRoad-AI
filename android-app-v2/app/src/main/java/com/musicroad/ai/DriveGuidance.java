package com.musicroad.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Lightweight DriveOS guidance helpers built on top of the route returned by
 * FastMapboxRouteEngine. This is intentionally not Mapbox Navigation SDK.
 */
final class DriveGuidance {
    static final class Maneuver {
        final int index;
        final String instruction;
        final String arrow;
        final double distanceMeters;
        Maneuver(int index,String instruction,String arrow,double distanceMeters){
            this.index=index;this.instruction=instruction;this.arrow=arrow;this.distanceMeters=distanceMeters;
        }
    }

    static final class Hazard {
        final JSONObject raw;
        final String label;
        final double distanceMeters;
        final int speedKph;
        final boolean bump;
        Hazard(JSONObject raw,String label,double distanceMeters,int speedKph,boolean bump){
            this.raw=raw;this.label=label;this.distanceMeters=distanceMeters;this.speedKph=speedKph;this.bump=bump;
        }
    }

    private DriveGuidance(){}

    static Maneuver maneuver(JSONArray steps,int currentIndex,double lat,double lon){
        if(steps==null||steps.length()==0)return new Maneuver(0,"Siga a rota destacada","↑",Double.NaN);
        int index=Math.max(0,Math.min(currentIndex,steps.length()-1));
        if(index==0&&distanceToStep(steps.optJSONObject(0),lat,lon)>300d){
            double best=Double.MAX_VALUE;int nearest=0;
            for(int i=0;i<steps.length();i++){
                double d=distanceToStep(steps.optJSONObject(i),lat,lon);
                if(Double.isFinite(d)&&d<best){best=d;nearest=i;}
            }
            index=nearest;
        }
        double distance=distanceToStep(steps.optJSONObject(index),lat,lon);
        while(index<steps.length()-1&&Double.isFinite(distance)&&distance<18d){
            index++;distance=distanceToStep(steps.optJSONObject(index),lat,lon);
        }
        JSONObject step=steps.optJSONObject(index),m=step==null?null:step.optJSONObject("maneuver");
        String instruction=m==null?"":m.optString("instruction","").trim();
        if(instruction.isEmpty())instruction=fallbackInstruction(step,m);
        return new Maneuver(index,instruction,arrow(m),distance);
    }

    static int speedLimitKph(JSONArray coords,JSONArray maxspeeds,double lat,double lon){
        if(coords==null||coords.length()<2||maxspeeds==null||maxspeeds.length()==0)return 0;
        int segment=nearestSegmentIndex(coords,lat,lon);
        if(segment<0)return 0;
        int mapped=maxspeeds.length()==1?0:(int)Math.round((segment/(double)Math.max(1,coords.length()-2))*(maxspeeds.length()-1));
        mapped=Math.max(0,Math.min(mapped,maxspeeds.length()-1));
        Object raw=maxspeeds.opt(mapped);
        if(raw instanceof JSONObject){
            JSONObject o=(JSONObject)raw;double speed=o.optDouble("speed",0d);String unit=o.optString("unit","km/h").toLowerCase(Locale.ROOT);
            if(speed<=0)return 0;if(unit.contains("mph"))speed*=1.609344d;return (int)Math.round(speed);
        }
        if(raw instanceof Number)return Math.max(0,(int)Math.round(((Number)raw).doubleValue()));
        return 0;
    }

    static double remainingMeters(JSONArray coords,double lat,double lon){
        if(coords==null||coords.length()<2)return 0d;
        int segment=nearestSegmentIndex(coords,lat,lon);if(segment<0)return 0d;
        JSONArray start=coords.optJSONArray(Math.min(segment+1,coords.length()-1));
        double remaining=0d;
        if(start!=null&&start.length()>=2)remaining=distance(lat,lon,start.optDouble(1),start.optDouble(0));
        for(int i=Math.max(1,segment+2);i<coords.length();i++){
            JSONArray a=coords.optJSONArray(i-1),b=coords.optJSONArray(i);if(a==null||b==null||a.length()<2||b.length()<2)continue;
            remaining+=distance(a.optDouble(1),a.optDouble(0),b.optDouble(1),b.optDouble(0));
        }
        return remaining;
    }

    static Hazard nearestHazard(JSONArray hazards,double lat,double lon){
        if(hazards==null||hazards.length()==0)return null;
        JSONObject bestRaw=null;double best=Double.MAX_VALUE;
        for(int i=0;i<hazards.length();i++){
            JSONObject h=hazards.optJSONObject(i);if(h==null)continue;
            double hlat=h.optDouble("latitude",Double.NaN),hlon=h.optDouble("longitude",Double.NaN);
            if(!Double.isFinite(hlat)||!Double.isFinite(hlon))continue;
            double d=distance(lat,lon,hlat,hlon);if(d<best){best=d;bestRaw=h;}
        }
        if(bestRaw==null)return null;
        String kind=(bestRaw.optString("tipo",bestRaw.optString("type",bestRaw.optString("categoria","")))).toUpperCase(Locale.ROOT);
        boolean bump=kind.contains("QUEBRA")||kind.contains("BUMP")||kind.contains("LOMBADA");
        String label=bump?"Quebra-mola":kind.contains("SEMAF")?"Fiscalização semafórica":kind.contains("VIDEO")?"Vídeo monitoramento":kind.contains("PORTAT")?"Fiscalização portátil":kind.contains("RADAR")?"Radar de velocidade":"Alerta na via";
        int speed=bestRaw.optInt("velocidade",bestRaw.optInt("speed",bestRaw.optInt("speed_limit",0)));
        return new Hazard(bestRaw,label,best,speed,bump);
    }

    static int nearestSegmentIndex(JSONArray coords,double lat,double lon){
        if(coords==null||coords.length()<2)return -1;
        double best=Double.MAX_VALUE;int bestIndex=-1,step=Math.max(1,(coords.length()-1)/2500);double cos=Math.cos(Math.toRadians(lat));
        for(int i=0;i<coords.length()-1;i+=step){
            JSONArray a=coords.optJSONArray(i),b=coords.optJSONArray(Math.min(i+step,coords.length()-1));if(a==null||b==null||a.length()<2||b.length()<2)continue;
            double x1=(a.optDouble(0)-lon)*111320d*cos,y1=(a.optDouble(1)-lat)*110540d,x2=(b.optDouble(0)-lon)*111320d*cos,y2=(b.optDouble(1)-lat)*110540d;
            double dx=x2-x1,dy=y2-y1,den=dx*dx+dy*dy,t=den<=1e-6?0d:-(x1*dx+y1*dy)/den;t=Math.max(0d,Math.min(1d,t));
            double x=x1+t*dx,y=y1+t*dy,d=x*x+y*y;if(d<best){best=d;bestIndex=i;}
        }
        return bestIndex;
    }

    static String distanceLabel(double meters){
        if(!Double.isFinite(meters))return "--";
        if(meters<1000d)return Math.max(0,Math.round(meters))+" m";
        return String.format(Locale.getDefault(),"%.1f km",meters/1000d);
    }

    private static double distanceToStep(JSONObject step,double lat,double lon){
        JSONObject m=step==null?null:step.optJSONObject("maneuver");JSONArray p=m==null?null:m.optJSONArray("location");
        if(p==null||p.length()<2)return Double.NaN;return distance(lat,lon,p.optDouble(1),p.optDouble(0));
    }

    private static String fallbackInstruction(JSONObject step,JSONObject maneuver){
        String type=maneuver==null?"":maneuver.optString("type","").toLowerCase(Locale.ROOT),mod=maneuver==null?"":maneuver.optString("modifier","").toLowerCase(Locale.ROOT),road=step==null?"":step.optString("name","").trim();
        String action=mod.contains("left")?"Vire à esquerda":mod.contains("right")?"Vire à direita":type.contains("roundabout")?"Entre na rotatória":type.equals("arrive")?"Você chegou ao destino":type.equals("depart")?"Inicie a viagem":"Siga em frente";
        return road.isEmpty()?action:action+" na "+road;
    }

    private static String arrow(JSONObject maneuver){
        String type=maneuver==null?"":maneuver.optString("type","").toLowerCase(Locale.ROOT),mod=maneuver==null?"":maneuver.optString("modifier","").toLowerCase(Locale.ROOT);
        if(type.contains("roundabout")||type.contains("rotary"))return "⟳";if(type.equals("arrive"))return "●";if(mod.contains("uturn"))return "↶";if(mod.contains("left"))return "↰";if(mod.contains("right"))return "↱";return "↑";
    }

    private static double distance(double lat1,double lon1,double lat2,double lon2){
        if(!Double.isFinite(lat1)||!Double.isFinite(lon1)||!Double.isFinite(lat2)||!Double.isFinite(lon2))return Double.NaN;
        double r=6371000d,dLat=Math.toRadians(lat2-lat1),dLon=Math.toRadians(lon2-lon1),a=Math.sin(dLat/2d)*Math.sin(dLat/2d)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2d)*Math.sin(dLon/2d);
        return 2d*r*Math.atan2(Math.sqrt(a),Math.sqrt(1d-a));
    }
}
