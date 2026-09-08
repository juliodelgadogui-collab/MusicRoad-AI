package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Compact premium speedometer used by the Universal road cockpit. */
final class ReferenceSpeedometerView extends View {
    private final Paint arcBg=new Paint(Paint.ANTI_ALIAS_FLAG),arcAccent=new Paint(Paint.ANTI_ALIAS_FLAG),tick=new Paint(Paint.ANTI_ALIAS_FLAG),text=new Paint(Paint.ANTI_ALIAS_FLAG),muted=new Paint(Paint.ANTI_ALIAS_FLAG),status=new Paint(Paint.ANTI_ALIAS_FLAG);
    private double speed;private int limitKmh;private boolean gpsAvailable=true;

    ReferenceSpeedometerView(Context context){super(context);setWillNotDraw(false);arcBg.setStyle(Paint.Style.STROKE);arcBg.setStrokeCap(Paint.Cap.ROUND);arcAccent.setStyle(Paint.Style.STROKE);arcAccent.setStrokeCap(Paint.Cap.ROUND);tick.setStrokeCap(Paint.Cap.ROUND);text.setTextAlign(Paint.Align.CENTER);text.setFakeBoldText(true);muted.setTextAlign(Paint.Align.CENTER);status.setTextAlign(Paint.Align.CENTER);status.setFakeBoldText(true);}
    void setSpeed(double value){speed=Math.max(0,Math.min(240,Double.isFinite(value)?value:0));invalidate();}
    void setLimit(int value){limitKmh=Math.max(0,Math.min(180,value));invalidate();}
    void setGpsAvailable(boolean value){gpsAvailable=value;invalidate();}

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);EstradaTheme theme=EstradaTheme.get(getContext());float w=getWidth(),h=getHeight(),size=Math.max(1f,Math.min(w,h)),cx=w/2f,cy=h*.49f,radius=size*.36f,stroke=Math.max(size*.022f,2f);
        arcBg.setStrokeWidth(stroke);arcBg.setColor(PremiumUi.withAlpha(theme.muted,80));arcAccent.setStrokeWidth(stroke);arcAccent.setColor(theme.primary);tick.setColor(PremiumUi.withAlpha(theme.text,210));muted.setColor(theme.muted);
        RectF oval=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);final float start=145f,sweep=250f;canvas.drawArc(oval,start,sweep,false,arcBg);canvas.drawArc(oval,start,(float)(sweep*Math.min(1.0,speed/200.0)),false,arcAccent);
        for(int i=0;i<=20;i++){double angle=Math.toRadians(start+sweep*i/20f);float outer=radius-stroke*.15f,inner=radius-(i%4==0?stroke*1.8f:stroke*1.05f);float x1=cx+(float)Math.cos(angle)*inner,y1=cy+(float)Math.sin(angle)*inner,x2=cx+(float)Math.cos(angle)*outer,y2=cy+(float)Math.sin(angle)*outer;tick.setStrokeWidth(i%4==0?Math.max(1.8f,size*.008f):Math.max(1.2f,size*.005f));canvas.drawLine(x1,y1,x2,y2,tick);if(i%4==0){muted.setTextSize(size*.050f);float lr=radius-stroke*3.25f,lx=cx+(float)Math.cos(angle)*lr,ly=cy+(float)Math.sin(angle)*lr-muted.ascent()/3f;canvas.drawText(String.valueOf(i*10),lx,ly,muted);}}
        boolean over=limitKmh>0&&speed>limitKmh+2;text.setColor(over?theme.danger:theme.text);text.setTextSize(size*.235f);canvas.drawText(String.valueOf(Math.round(speed)),cx,cy+text.getTextSize()*.20f,text);muted.setTextSize(size*.058f);canvas.drawText("km/h",cx,cy+text.getTextSize()*.66f,muted);
        status.setColor(gpsAvailable?theme.success:theme.warning);status.setTextSize(size*.046f);canvas.drawText(gpsAvailable?"● GPS":"● BUSCANDO",cx,cy+radius*.83f,status);
    }
}
