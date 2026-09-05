package com.musicroad.ai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class NativeMapView extends View {
    private final Paint background=new Paint();
    private final Paint roadPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint userPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint userRing=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<double[]> roads=new ArrayList<>();
    private final List<double[]> route=new ArrayList<>();
    private final List<double[]> radars=new ArrayList<>();
    private double userLat=Double.NaN,userLon=Double.NaN;
    private double centerLat=-19.5,centerLon=-46.6;
    private double baseScale=800.0;
    private float zoom=1f,panX=0,panY=0,lastX,lastY;
    private boolean dragging=false;
    private final ScaleGestureDetector scaleDetector;
    private String message="Mapa nativo";

    NativeMapView(Context c){
        super(c);setBackgroundColor(Color.rgb(7,11,16));setFocusable(true);
        background.setColor(Color.rgb(7,11,16));
        roadPaint.setColor(Color.rgb(63,78,89));roadPaint.setStyle(Paint.Style.STROKE);roadPaint.setStrokeWidth(dp(1.6f));roadPaint.setStrokeCap(Paint.Cap.ROUND);roadPaint.setStrokeJoin(Paint.Join.ROUND);
        routePaint.setColor(Color.rgb(255,122,26));routePaint.setStyle(Paint.Style.STROKE);routePaint.setStrokeWidth(dp(5f));routePaint.setStrokeCap(Paint.Cap.ROUND);routePaint.setStrokeJoin(Paint.Join.ROUND);
        radarPaint.setColor(Color.rgb(255,80,80));radarPaint.setStyle(Paint.Style.FILL);
        userPaint.setColor(Color.rgb(70,170,255));userPaint.setStyle(Paint.Style.FILL);
        userRing.setColor(Color.WHITE);userRing.setStyle(Paint.Style.STROKE);userRing.setStrokeWidth(dp(2f));
        textPaint.setColor(Color.rgb(138,158,173));textPaint.setTextSize(dp(12f));
        scaleDetector=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){zoom=Math.max(.55f,Math.min(8f,zoom*detector.getScaleFactor()));invalidate();return true;}});
    }

    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private double worldX(double lon){return lon*Math.cos(Math.toRadians(centerLat));}
    private float sx(double lon){return (float)(getWidth()/2.0+(worldX(lon)-worldX(centerLon))*baseScale*zoom+panX);}
    private float sy(double lat){return (float)(getHeight()/2.0-(lat-centerLat)*baseScale*zoom+panY);}

    void setMessage(String value){message=value==null?"":value;invalidate();}
    void setUserLocation(double lat,double lon){userLat=lat;userLon=lon;if(route.isEmpty()&&roads.isEmpty()){centerLat=lat;centerLon=lon;}invalidate();}
    void clearRoute(){route.clear();invalidate();}

    void setRoute(JSONArray coords){
        route.clear();if(coords!=null)for(int i=0;i<coords.length();i++){JSONArray c=coords.optJSONArray(i);if(c==null||c.length()<2)continue;double lon=c.optDouble(0,Double.NaN),lat=c.optDouble(1,Double.NaN);if(Double.isFinite(lat)&&Double.isFinite(lon))route.add(new double[]{lat,lon});}
        fitData();invalidate();
    }

    void setOfflinePack(JSONObject pack){
        roads.clear();radars.clear();
        if(pack!=null){
            JSONObject map=pack.optJSONObject("map");JSONObject roadsFc=map==null?null:map.optJSONObject("roads");JSONArray features=roadsFc==null?null:roadsFc.optJSONArray("features");
            if(features!=null){int max=Math.min(features.length(),16000);for(int i=0;i<max;i++){JSONObject f=features.optJSONObject(i);JSONObject g=f==null?null:f.optJSONObject("geometry");JSONArray coords=g==null?null:g.optJSONArray("coordinates");if(coords==null||coords.length()<2)continue;int step=Math.max(1,coords.length()/600);ArrayList<Double> vals=new ArrayList<>();for(int j=0;j<coords.length();j+=step){JSONArray c=coords.optJSONArray(j);if(c==null||c.length()<2)continue;double lon=c.optDouble(0,Double.NaN),lat=c.optDouble(1,Double.NaN);if(Double.isFinite(lat)&&Double.isFinite(lon)){vals.add(lat);vals.add(lon);}}if(vals.size()>=4){double[] line=new double[vals.size()];for(int k=0;k<line.length;k++)line[k]=vals.get(k);roads.add(line);}}}
            JSONArray rr=pack.optJSONArray("radars");if(rr!=null){for(int i=0;i<rr.length();i++){JSONObject r=rr.optJSONObject(i);if(r==null)continue;double lat=r.optDouble("latitude",Double.NaN),lon=r.optDouble("longitude",Double.NaN);if(Double.isFinite(lat)&&Double.isFinite(lon))radars.add(new double[]{lat,lon});}}
        }
        fitData();invalidate();
    }

    void setRadars(JSONArray rr){radars.clear();if(rr!=null)for(int i=0;i<rr.length();i++){JSONObject r=rr.optJSONObject(i);if(r==null)continue;double lat=r.optDouble("latitude",Double.NaN),lon=r.optDouble("longitude",Double.NaN);if(Double.isFinite(lat)&&Double.isFinite(lon))radars.add(new double[]{lat,lon});}invalidate();}

    private void fitData(){
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;boolean any=false;
        for(double[] line:roads)for(int i=0;i+1<line.length;i+=2){double lat=line[i],lon=line[i+1];minLat=Math.min(minLat,lat);maxLat=Math.max(maxLat,lat);minLon=Math.min(minLon,lon);maxLon=Math.max(maxLon,lon);any=true;}
        for(double[] p:route){minLat=Math.min(minLat,p[0]);maxLat=Math.max(maxLat,p[0]);minLon=Math.min(minLon,p[1]);maxLon=Math.max(maxLon,p[1]);any=true;}
        if(!any&&Double.isFinite(userLat)){minLat=userLat-.03;maxLat=userLat+.03;minLon=userLon-.03;maxLon=userLon+.03;any=true;}
        if(!any)return;
        centerLat=(minLat+maxLat)/2;centerLon=(minLon+maxLon)/2;double dx=Math.max(.002,(maxLon-minLon)*Math.cos(Math.toRadians(centerLat))),dy=Math.max(.002,maxLat-minLat);double usableW=Math.max(260,getWidth()>0?getWidth()*.82:900),usableH=Math.max(220,getHeight()>0?getHeight()*.78:500);baseScale=Math.min(usableW/dx,usableH/dy);zoom=1;panX=panY=0;
    }

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(oldw==0||oldh==0)fitData();}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);c.drawRect(0,0,getWidth(),getHeight(),background);drawGrid(c);
        for(double[] line:roads)drawLine(c,line,roadPaint);
        if(route.size()>1){double[] line=new double[route.size()*2];for(int i=0;i<route.size();i++){line[i*2]=route.get(i)[0];line[i*2+1]=route.get(i)[1];}drawLine(c,line,routePaint);}
        float rr=Math.max(dp(3f),Math.min(dp(6f),dp(4f)*zoom*.65f));for(double[] p:radars){float x=sx(p[1]),y=sy(p[0]);if(x<-20||x>getWidth()+20||y<-20||y>getHeight()+20)continue;c.drawCircle(x,y,rr,radarPaint);}
        if(Double.isFinite(userLat)&&Double.isFinite(userLon)){float x=sx(userLon),y=sy(userLat);c.drawCircle(x,y,dp(8),userPaint);c.drawCircle(x,y,dp(11),userRing);}
        if(message!=null&&!message.isEmpty())c.drawText(message,dp(12),getHeight()-dp(14),textPaint);
    }

    private void drawGrid(Canvas c){Paint p=new Paint();p.setColor(Color.rgb(15,23,31));p.setStrokeWidth(1);int gap=(int)dp(42);for(int x=0;x<getWidth();x+=gap)c.drawLine(x,0,x,getHeight(),p);for(int y=0;y<getHeight();y+=gap)c.drawLine(0,y,getWidth(),y,p);}
    private void drawLine(Canvas c,double[] line,Paint paint){if(line.length<4)return;Path p=new Path();boolean started=false;for(int i=0;i+1<line.length;i+=2){float x=sx(line[i+1]),y=sy(line[i]);if(!started){p.moveTo(x,y);started=true;}else p.lineTo(x,y);}c.drawPath(p,paint);}

    @Override public boolean onTouchEvent(MotionEvent e){
        scaleDetector.onTouchEvent(e);switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:lastX=e.getX();lastY=e.getY();dragging=true;return true;
            case MotionEvent.ACTION_MOVE:if(dragging&&!scaleDetector.isInProgress()){panX+=e.getX()-lastX;panY+=e.getY()-lastY;lastX=e.getX();lastY=e.getY();invalidate();}return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:dragging=false;return true;
        }return true;
    }
}
