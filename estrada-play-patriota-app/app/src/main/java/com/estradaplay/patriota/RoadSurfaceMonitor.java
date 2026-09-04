package com.estradaplay.patriota;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;

final class RoadSurfaceMonitor implements SensorEventListener {
    interface Listener { void onImpact(Impact impact); }
    static final class Impact {
        final double lat,lon,speedKmh,force;
        final long at;
        Impact(double lat,double lon,double speedKmh,double force,long at){this.lat=lat;this.lon=lon;this.speedKmh=speedKmh;this.force=force;this.at=at;}
    }
    private final Context app; private final SensorManager manager; private final Sensor sensor; private final Listener listener;
    private volatile double lat=Double.NaN,lon=Double.NaN,speedKmh; private long lastImpactAt;
    RoadSurfaceMonitor(Context c,Listener l){app=c.getApplicationContext();listener=l;manager=(SensorManager)app.getSystemService(Context.SENSOR_SERVICE);Sensor linear=manager==null?null:manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);sensor=linear!=null?linear:(manager==null?null:manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));}
    void start(){if(manager!=null&&sensor!=null)try{manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);}catch(Throwable ignored){}}
    void stop(){if(manager!=null)try{manager.unregisterListener(this);}catch(Throwable ignored){}}
    void updateDriveState(Location l,double speed){if(l!=null){lat=l.getLatitude();lon=l.getLongitude();}speedKmh=Math.max(0,speed);}
    @Override public void onSensorChanged(SensorEvent e){if(e==null||e.values==null||e.values.length<3||speedKmh<15||!Double.isFinite(lat)||!Double.isFinite(lon))return;double x=e.values[0],y=e.values[1],z=e.values[2];double mag=Math.sqrt(x*x+y*y+z*z);if(e.sensor!=null&&e.sensor.getType()==Sensor.TYPE_ACCELEROMETER)mag=Math.abs(mag-SensorManager.GRAVITY_EARTH);double threshold=DriveSettings.impactSensitivity(app)==0?7.4:(DriveSettings.impactSensitivity(app)==2?4.8:5.9);long now=System.currentTimeMillis();if(mag<threshold||now-lastImpactAt<4000L)return;lastImpactAt=now;double severity=Math.min(10.0,Math.max(1.0,(mag-threshold+1.0)*1.7));if(listener!=null)listener.onImpact(new Impact(lat,lon,speedKmh,severity,now));}
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
}
