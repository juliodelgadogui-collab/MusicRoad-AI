package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Automotive speedometer shared by the native production cockpit. */
final class ReferenceSpeedometerView extends View {
    private final Paint arcBg=new Paint(Paint.ANTI_ALIAS_FLAG),arcRed=new Paint(Paint.ANTI_ALIAS_FLAG),tick=new Paint(Paint.ANTI_ALIAS_FLAG),text=new Paint(Paint.ANTI_ALIAS_FLAG),muted=new Paint(Paint.ANTI_ALIAS_FLAG),status=new Paint(Paint.ANTI_ALIAS_FLAG);
    private double speed;
    private boolean gpsAvailable=true;

    ReferenceSpeedometerView(Context c){
        super(c);setWillNotDraw(false);
        arcBg.setStyle(Paint.Style.STROKE);arcBg.setStrokeCap(Paint.Cap.ROUND);arcBg.setColor(Color.rgb(70,68,73));
        arcRed.setStyle(Paint.Style.STROKE);arcRed.setStrokeCap(Paint.Cap.ROUND);arcRed.setColor(Color.rgb(227,13,39));
        tick.setStrokeCap(Paint.Cap.ROUND);tick.setColor(Color.rgb(218,215,216));
        text.setColor(Color.WHITE);text.setTextAlign(Paint.Align.CENTER);text.setFakeBoldText(true);
        muted.setColor(Color.rgb(173,164,165));muted.setTextAlign(Paint.Align.CENTER);
        status.setTextAlign(Paint.Align.CENTER);status.setFakeBoldText(true);
    }

    void setSpeed(double value){speed=Math.max(0,Math.min(240,Double.isFinite(value)?value:0));invalidate();}
    void setGpsAvailable(boolean value){gpsAvailable=value;invalidate();}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float w=getWidth(),h=getHeight(),cx=w/2f,cy=h*.49f,r=Math.min(w,h)*.37f;
        float stroke=Math.max(dp(4),Math.min(w,h)*.025f);arcBg.setStrokeWidth(stroke);arcRed.setStrokeWidth(stroke);
        RectF oval=new RectF(cx-r,cy-r,cx+r,cy+r);
        final float start=145f,sweep=250f;
        c.drawArc(oval,start,sweep,false,arcBg);
        c.drawArc(oval,start,(float)(sweep*Math.min(1.0,speed/200.0)),false,arcRed);

        for(int i=0;i<=20;i++){
            double v=i*10.0,ang=Math.toRadians(start+(sweep*i/20f));
            float outer=r-stroke*.1f,inner=r-(i%4==0?stroke*1.55f:stroke*.9f);
            float x1=cx+(float)Math.cos(ang)*inner,y1=cy+(float)Math.sin(ang)*inner;
            float x2=cx+(float)Math.cos(ang)*outer,y2=cy+(float)Math.sin(ang)*outer;
            tick.setStrokeWidth(i%4==0?dp(1.7f):dp(1f));c.drawLine(x1,y1,x2,y2,tick);
            if(i%4==0){
                String label=String.valueOf((int)v);muted.setTextSize(Math.max(dp(8),Math.min(w,h)*.052f));
                float lr=r-stroke*3.05f;
                float lx=cx+(float)Math.cos(ang)*lr,ly=cy+(float)Math.sin(ang)*lr-muted.ascent()/3f;
                c.drawText(label,lx,ly,muted);
            }
        }

        text.setTextSize(Math.max(dp(35),Math.min(w,h)*.25f));c.drawText(String.valueOf(Math.round(speed)),cx,cy+text.getTextSize()*.22f,text);
        muted.setTextSize(Math.max(dp(10),Math.min(w,h)*.06f));c.drawText("km/h",cx,cy+text.getTextSize()*.72f,muted);
        status.setColor(gpsAvailable?Color.rgb(20,220,113):Color.rgb(242,181,65));
        status.setTextSize(Math.max(dp(9),Math.min(w,h)*.052f));
        c.drawText(gpsAvailable?"●  GPS ATIVO":"●  GPS BUSCANDO",cx,cy+r*.82f,status);
    }

    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
