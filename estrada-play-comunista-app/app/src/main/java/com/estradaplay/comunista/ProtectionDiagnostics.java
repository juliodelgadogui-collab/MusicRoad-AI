package com.estradaplay.comunista;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.List;

/** Hidden diagnostic used only by long-press; normal driver UI stays clean. */
final class ProtectionDiagnostics {
    private ProtectionDiagnostics() {}

    static void show(Context context,double lat,double lon){
        if(context==null)return;
        Context app=context.getApplicationContext();
        if(!Double.isFinite(lat)||!Double.isFinite(lon)){
            Toast.makeText(context,"Diagnóstico: aguardando GPS",Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            int radar=0,signal=0,camera=0,bump=0,other=0,total=0;
            try{
                RoadPackStore store=new RoadPackStore(app);
                List<RoadHazard> rows=store.nearby(lat,lon,25000);
                total=rows.size();
                for(RoadHazard h:rows){
                    if(h==null)continue;
                    String t=h.type==null?"":h.type;
                    if("RADAR".equals(t))radar++;
                    else if("SEMAFORO_RADAR".equals(t)||"SEMAFORO".equals(t))signal++;
                    else if("CAMERA_MONITORAMENTO".equals(t))camera++;
                    else if("QUEBRA_MOLAS".equals(t))bump++;
                    else other++;
                }
            }catch(Throwable ignored){}
            final String msg="Proteção local · "+total+" pontos\n"
                    +"Radares "+radar+" · Semáforos "+signal+"\n"
                    +"Câmeras "+camera+" · Quebra-molas "+bump
                    +(other>0?" · Outros "+other:"");
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context,msg,Toast.LENGTH_LONG).show());
        },"epc-protection-diagnostic").start();
    }
}
