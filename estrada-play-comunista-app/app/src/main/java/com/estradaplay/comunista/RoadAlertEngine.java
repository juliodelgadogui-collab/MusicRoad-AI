package com.estradaplay.comunista;

import java.util.Locale;

/** Deterministic driving alerts. No AI, no network dependency. */
final class RoadAlertEngine {
    static final int PRIORITY_NONE=0,PRIORITY_INFO=1,PRIORITY_CAUTION=2,PRIORITY_URGENT=3;
    static final class Alert {
        final int priority;final String title,detail,code;
        Alert(int p,String t,String d,String c){priority=p;title=t;detail=d;code=c;}
        boolean present(){return priority>0;}
    }

    static Alert evaluate(double speedKmh,int limitKmh,String hazardType,String hazardLabel,double distanceM,double fuelRangeKm,long movingMs){
        String type=hazardType==null?"":hazardType.trim().toUpperCase(Locale.ROOT);
        String label=hazardLabel==null?"":hazardLabel.trim();
        if(limitKmh>0&&speedKmh>limitKmh+8)return new Alert(PRIORITY_URGENT,"REDUZA A VELOCIDADE",Math.round(speedKmh)+" km/h · limite "+limitKmh+" km/h","overspeed");
        if(!label.isEmpty()&&distanceM>0&&distanceM<=450){
            int p=("ACIDENTE".equals(type)||"PASSAGEM_NIVEL".equals(type)||"OBJETO".equals(type))?PRIORITY_URGENT:PRIORITY_CAUTION;
            String action="QUEBRA_MOLAS".equals(type)?"REDUZA":"ATENÇÃO À FRENTE";
            return new Alert(p,action,label+" · "+distance(distanceM),"hazard");
        }
        if(fuelRangeKm>=0&&fuelRangeKm<=35)return new Alert(PRIORITY_CAUTION,"COMBUSTÍVEL BAIXO","Autonomia estimada: "+Math.round(fuelRangeKm)+" km","fuel");
        if(movingMs>=2L*60L*60L*1000L)return new Alert(PRIORITY_INFO,"PAUSA RECOMENDADA","Você já dirige há cerca de 2 horas.","break");
        if(!label.isEmpty()&&distanceM>0&&distanceM<=1500)return new Alert(PRIORITY_INFO,"PRÓXIMO ALERTA",label+" · "+distance(distanceM),"upcoming");
        return new Alert(PRIORITY_NONE,"","","none");
    }

    private static String distance(double m){return m>=1000?String.format(Locale.getDefault(),"%.1f km",m/1000.0):Math.round(m)+" m";}
}
