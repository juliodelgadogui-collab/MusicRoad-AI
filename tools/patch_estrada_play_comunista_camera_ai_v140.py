#!/usr/bin/env python3
from pathlib import Path

ROOT=Path('estrada-play-comunista-app')
if not ROOT.exists(): raise SystemExit('estrada-play-comunista-app not generated')

def read(rel): return (ROOT/rel).read_text(encoding='utf-8')
def write(rel,value):
    p=ROOT/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value,encoding='utf-8')

build=read('app/build.gradle')
if "com.google.mlkit:text-recognition" not in build:
    build=build.replace("    implementation 'androidx.camera:camera-video:1.4.2'", "    implementation 'androidx.camera:camera-video:1.4.2'\n    implementation 'com.google.mlkit:text-recognition:16.0.1'")
write('app/build.gradle',build)

write('app/src/main/java/com/estradaplay/comunista/CameraActivity.java', r'''package com.estradaplay.comunista;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional road camera. 1.4 adds a circular dashcam and conservative local sign OCR.
 * Camera intelligence is opt-in and never replaces the GPS/official road database.
 */
public final class CameraActivity extends ComponentActivity {
    private static final String PREFS="epc_camera_v1";
    private static final String KEY_ENABLED="enabled";
    private static final String KEY_MIGRATED_PUBLIC="videos_public_migrated_v131";
    private static final String KEY_AUTO="auto_dashcam_v140";
    private static final String KEY_SMART="smart_signs_v140";
    private static final int REQ_CAMERA=1901,REQ_STORAGE=1902;
    private static final long SEGMENT_MS=2L*60L*1000L;
    private static final int MAX_AUTO_SEGMENTS=5;
    private static final Pattern SPEED_PATTERN=Pattern.compile("(?<![0-9])(20|30|40|50|60|70|80|90|100|110|120|130)(?![0-9])");

    private final int BG=Color.rgb(7,4,6),PANEL=Color.rgb(24,9,13),BORDER=Color.rgb(88,34,43),TEXT=Color.rgb(247,239,226),MUTED=Color.rgb(179,154,149),RED=Color.rgb(196,20,41),GREEN=Color.rgb(68,213,132),GOLD=Color.rgb(224,184,76);
    private FrameLayout root; private SharedPreferences prefs; private PreviewView previewView; private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture; private Recording recording; private boolean recordingAuto; private boolean started;
    private Button recordButton,autoButton,smartButton; private TextView cameraState,speedState,limitState,visionState,thermalState;
    private boolean receiverRegistered; private int thermalStatus; private int roadLimit;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor=Executors.newSingleThreadExecutor();
    private TextRecognizer recognizer; private long lastAnalysisAt; private String visionCandidate=""; private int visionHits;
    private final ArrayDeque<Uri> rollingUris=new ArrayDeque<>(); private boolean protectCurrent;

    private final Runnable rotateSegment=()->{
        if(recording!=null&&recordingAuto&&started){
            try{recording.stop();}catch(Throwable ignored){}
        }
    };

    private final BroadcastReceiver roadReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        double speed=i.getDoubleExtra("speed_kmh",0); roadLimit=i.getIntExtra("road_limit_kmh",0); thermalStatus=i.getIntExtra("thermal_status",0);
        if(speedState!=null)speedState.setText(Math.max(0,Math.round(speed))+" KM/H");
        if(limitState!=null)limitState.setText(roadLimit>0?"LIMITE "+roadLimit:"LIMITE --");
        if(thermalState!=null)thermalState.setText(thermalStatus>=3?"MODO TÉRMICO":"SISTEMA NORMAL");
        SafetyAlertOverlay.show(CameraActivity.this,root,i);
    }};

    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);prefs=getSharedPreferences(PREFS,MODE_PRIVATE);root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);migrateLegacyVideosAsync();pruneOldAutoSegmentsAsync();if(isEnabled()&&hasCameraPermission())buildCameraUi();else buildGateUi();}
    @Override protected void onStart(){super.onStart();started=true;registerRoadReceiver();if(isEnabled()&&hasCameraPermission()&&previewView!=null)startCamera();}
    @Override protected void onStop(){started=false;handler.removeCallbacks(rotateSegment);stopRecording(false);if(cameraProvider!=null)try{cameraProvider.unbindAll();}catch(Throwable ignored){}unregisterRoadReceiver();super.onStop();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);try{if(recognizer!=null)recognizer.close();}catch(Throwable ignored){}analysisExecutor.shutdownNow();super.onDestroy();}

    private void buildGateUi(){previewView=null;root.removeAllViews();LinearLayout page=column();page.setGravity(Gravity.CENTER_HORIZONTAL);page.setPadding(dp(24),dp(24),dp(24),dp(24));root.addView(page,new FrameLayout.LayoutParams(-1,-1));
        TextView over=text("MÓDULO OPCIONAL",11,RED,true);over.setLetterSpacing(.14f);page.addView(over);page.addView(text("CÂMERA DE BORDO",29,TEXT,true));TextView body=text("A proteção por GPS funciona sem câmera. Ative somente se quiser visão da estrada, dashcam ou leitura visual experimental.",13,MUTED,false);body.setGravity(Gravity.CENTER);page.addView(body);margins(body,0,8,0,16);
        LinearLayout privacy=column();privacy.setPadding(dp(15),dp(14),dp(15),dp(14));privacy.setBackground(box(PANEL,14,BORDER));privacy.addView(text("PRIVACIDADE",10,GREEN,true));privacy.addView(text("• Vídeos ficam no aparelho.\n• Nenhum vídeo é enviado ao servidor.\n• A leitura de placas roda localmente e é experimental.\n• GPS e radares não dependem da câmera.",12,TEXT,false));page.addView(privacy,new LinearLayout.LayoutParams(-1,-2));
        CheckBox auto=new CheckBox(this);auto.setText("DASHCAM CIRCULAR AUTOMÁTICA\nBlocos de 2 min; mantém cerca de 10 min e apaga os automáticos mais antigos.");auto.setTextColor(TEXT);auto.setChecked(autoDashcam());auto.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean(KEY_AUTO,v).apply());page.addView(auto,new LinearLayout.LayoutParams(-1,-2));margins(auto,0,12,0,0);
        CheckBox smart=new CheckBox(this);smart.setText("LEITURA DE PLACAS · BETA\nSó mostra um limite depois de leituras repetidas. Nunca altera a base oficial.");smart.setTextColor(TEXT);smart.setChecked(smartSigns());smart.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean(KEY_SMART,v).apply());page.addView(smart,new LinearLayout.LayoutParams(-1,-2));
        Button videos=button("MEUS VÍDEOS",false);page.addView(videos,new LinearLayout.LayoutParams(-1,dp(50)));videos.setOnClickListener(v->openVideos());View space=new View(this);page.addView(space,new LinearLayout.LayoutParams(1,0,1));Button activate=button("ATIVAR CÂMERA",true);page.addView(activate,new LinearLayout.LayoutParams(-1,dp(58)));activate.setOnClickListener(v->activateCamera());Button back=button("VOLTAR",false);page.addView(back,new LinearLayout.LayoutParams(-1,dp(50)));back.setOnClickListener(v->finish());}

    private void buildCameraUi(){root.removeAllViews();previewView=new PreviewView(this);previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);root.addView(previewView,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=column();top.setPadding(dp(13),dp(10),dp(13),dp(10));top.setBackgroundColor(Color.argb(218,12,5,8));LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);LinearLayout info=column();cameraState=text("CÂMERA DE BORDO · ATIVA",12,GREEN,true);info.addView(cameraState);thermalState=text("SISTEMA NORMAL",9,MUTED,true);info.addView(thermalState);line.addView(info,new LinearLayout.LayoutParams(0,-2,1));speedState=text("0 KM/H",22,TEXT,true);line.addView(speedState);top.addView(line);LinearLayout telemetry=row();limitState=text("LIMITE --",11,GOLD,true);visionState=text(smartSigns()?"VISÃO BETA · PROCURANDO PLACAS":"VISÃO BETA · DESLIGADA",11,smartSigns()?GOLD:MUTED,true);telemetry.addView(limitState,new LinearLayout.LayoutParams(0,-2,1));visionState.setGravity(Gravity.RIGHT);telemetry.addView(visionState,new LinearLayout.LayoutParams(0,-2,1));top.addView(telemetry);FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);tp.setMargins(dp(10),dp(10),dp(10),0);root.addView(top,tp);
        LinearLayout bottom=column();bottom.setPadding(dp(10),dp(10),dp(10),dp(10));bottom.setBackground(box(Color.argb(232,17,7,10),16,BORDER));bottom.addView(text("GALERIA · MOVIES/ESTRADAPLAYCOMUNISTA · SEM UPLOAD",9,MUTED,true));
        LinearLayout toggles=row();autoButton=button(autoDashcam()?"AUTO · ON":"AUTO · OFF",autoDashcam());smartButton=button(smartSigns()?"PLACA BETA · ON":"PLACA BETA · OFF",smartSigns());toggles.addView(autoButton,new LinearLayout.LayoutParams(0,dp(45),1));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(45),1);sp.setMargins(dp(7),0,0,0);toggles.addView(smartButton,sp);bottom.addView(toggles);margins(toggles,0,7,0,0);autoButton.setOnClickListener(v->toggleAuto());smartButton.setOnClickListener(v->toggleSmart());
        LinearLayout actions=row();recordButton=button(autoDashcam()?"SALVAR MOMENTO":"GRAVAR",true);recordButton.setEnabled(false);Button videos=button("VÍDEOS",false);Button back=button("VOLTAR",false);actions.addView(recordButton,new LinearLayout.LayoutParams(0,dp(52),1));LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(0,dp(52),1);vp.setMargins(dp(7),0,0,0);actions.addView(videos,vp);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(52),1);bp.setMargins(dp(7),0,0,0);actions.addView(back,bp);bottom.addView(actions);margins(actions,0,7,0,0);recordButton.setOnClickListener(v->{if(autoDashcam())preserveMoment();else toggleManualRecording();});videos.setOnClickListener(v->openVideos());back.setOnClickListener(v->finish());Button disable=button("DESATIVAR CÂMERA",false);bottom.addView(disable,new LinearLayout.LayoutParams(-1,dp(44)));margins(disable,0,7,0,0);disable.setOnClickListener(v->disableCamera());FrameLayout.LayoutParams btm=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);btm.setMargins(dp(10),0,dp(10),dp(10));root.addView(bottom,btm);}

    private void activateCamera(){if(hasCameraPermission()){prefs.edit().putBoolean(KEY_ENABLED,true).apply();buildCameraUi();startCamera();return;}requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants){super.onRequestPermissionsResult(requestCode,permissions,grants);if(requestCode==REQ_STORAGE){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){if(autoDashcam())startAutoSegment();else toggleManualRecording();}else toast("Sem acesso ao armazenamento neste Android.");return;}if(requestCode!=REQ_CAMERA)return;boolean ok=grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED;if(!ok){prefs.edit().putBoolean(KEY_ENABLED,false).apply();toast("Câmera continua desativada.");buildGateUi();return;}prefs.edit().putBoolean(KEY_ENABLED,true).apply();buildCameraUi();startCamera();}
    private void disableCamera(){stopRecording(false);if(cameraProvider!=null)try{cameraProvider.unbindAll();}catch(Throwable ignored){}prefs.edit().putBoolean(KEY_ENABLED,false).apply();toast("Câmera de bordo desativada.");buildGateUi();}

    private void startCamera(){if(!isEnabled()||!hasCameraPermission()||previewView==null)return;if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)){toast("Este aparelho não possui câmera disponível.");return;}var future=ProcessCameraProvider.getInstance(this);future.addListener(()->{try{cameraProvider=future.get();Preview preview=new Preview.Builder().build();preview.setSurfaceProvider(previewView.getSurfaceProvider());Quality q=thermalStatus>=3?Quality.SD:Quality.HD;Recorder recorder=new Recorder.Builder().setQualitySelector(QualitySelector.from(q)).build();videoCapture=VideoCapture.withOutput(recorder);cameraProvider.unbindAll();if(smartSigns()&&thermalStatus<3){ImageAnalysis analysis=new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();analysis.setAnalyzer(analysisExecutor,this::analyzeFrame);cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,videoCapture,analysis);}else cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,videoCapture);if(recordButton!=null)recordButton.setEnabled(true);if(cameraState!=null)cameraState.setText("CÂMERA DE BORDO · ATIVA");if(autoDashcam())handler.postDelayed(this::startAutoSegment,650);}catch(Throwable e){videoCapture=null;if(recordButton!=null)recordButton.setEnabled(false);if(cameraState!=null)cameraState.setText("CÂMERA INDISPONÍVEL");toast("Não foi possível abrir a câmera traseira.");}},getMainExecutor());}

    private void toggleAuto(){boolean value=!autoDashcam();prefs.edit().putBoolean(KEY_AUTO,value).apply();if(autoButton!=null){autoButton.setText(value?"AUTO · ON":"AUTO · OFF");autoButton.setBackground(box(value?RED:Color.rgb(24,17,20),12,value?RED:BORDER));}if(recordButton!=null)recordButton.setText(value?"SALVAR MOMENTO":"GRAVAR");if(value)startAutoSegment();else if(recordingAuto)stopRecording(false);}
    private void toggleSmart(){boolean value=!smartSigns();prefs.edit().putBoolean(KEY_SMART,value).apply();if(smartButton!=null)smartButton.setText(value?"PLACA BETA · ON":"PLACA BETA · OFF");if(visionState!=null)visionState.setText(value?"VISÃO BETA · REABRINDO CÂMERA":"VISÃO BETA · DESLIGADA");if(cameraProvider!=null)try{cameraProvider.unbindAll();}catch(Throwable ignored){}handler.postDelayed(this::startCamera,250);}
    private void toggleManualRecording(){if(recording!=null){stopRecording(false);return;}startRecording(false);}
    private void startAutoSegment(){if(!started||!autoDashcam()||recording!=null||videoCapture==null)return;startRecording(true);}

    private void startRecording(boolean auto){if(recording!=null||videoCapture==null)return;if(Build.VERSION.SDK_INT<=28&&!hasLegacyStoragePermission()){requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_STORAGE);return;}String prefix=auto?"EPC-AUTO-":"EPC-";String name=prefix+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".mp4";ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");if(Build.VERSION.SDK_INT>=29)values.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/EstradaPlayComunista");try{MediaStoreOutputOptions options=new MediaStoreOutputOptions.Builder(getContentResolver(),MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build();recordingAuto=auto;recording=videoCapture.getOutput().prepareRecording(this,options).start(getMainExecutor(),event->{if(event instanceof VideoRecordEvent.Start){if(cameraState!=null)cameraState.setText(auto?"● DASHCAM CIRCULAR":"● GRAVANDO NA GALERIA");if(!auto&&recordButton!=null)recordButton.setText("PARAR");if(auto){handler.removeCallbacks(rotateSegment);handler.postDelayed(rotateSegment,SEGMENT_MS);}}else if(event instanceof VideoRecordEvent.Finalize){VideoRecordEvent.Finalize done=(VideoRecordEvent.Finalize)event;Uri uri=done.getOutputResults().getOutputUri();boolean wasAuto=recordingAuto;recording=null;recordingAuto=false;handler.removeCallbacks(rotateSegment);if(cameraState!=null)cameraState.setText("CÂMERA DE BORDO · ATIVA");if(!autoDashcam()&&recordButton!=null)recordButton.setText("GRAVAR");if(done.hasError()){toast("A gravação foi encerrada com erro.");}else if(wasAuto){if(uri!=null&&uri!=Uri.EMPTY){if(protectCurrent){protectUri(uri);protectCurrent=false;}else{rollingUris.addLast(uri);pruneRollingQueue();}}}else toast("Vídeo salvo na Galeria.");if(wasAuto&&started&&autoDashcam())handler.postDelayed(this::startAutoSegment,450);}});}catch(Throwable e){recording=null;recordingAuto=false;toast("Não foi possível iniciar a gravação.");}}
    private void stopRecording(boolean preserve){Recording r=recording;if(preserve)protectCurrent=true;recording=null;handler.removeCallbacks(rotateSegment);if(r!=null)try{r.stop();}catch(Throwable ignored){}if(recordButton!=null&&!autoDashcam())recordButton.setText("GRAVAR");}

    private void preserveMoment(){if(!autoDashcam()){toast("Ative a dashcam automática primeiro.");return;}for(Uri uri:rollingUris)protectUri(uri);rollingUris.clear();if(recording!=null&&recordingAuto)protectCurrent=true;toast("Momento preservado. Os últimos trechos não serão apagados.");}
    private void protectUri(Uri uri){if(uri==null)return;try{ContentValues v=new ContentValues();String old=queryName(uri);if(old.startsWith("EPC-AUTO-"))v.put(MediaStore.MediaColumns.DISPLAY_NAME,old.replaceFirst("EPC-AUTO-","EPC-SALVO-"));if(v.size()>0)getContentResolver().update(uri,v,null,null);}catch(Throwable ignored){}}
    private String queryName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Throwable ignored){}return "";}
    private void pruneRollingQueue(){while(rollingUris.size()>MAX_AUTO_SEGMENTS){Uri old=rollingUris.removeFirst();try{getContentResolver().delete(old,null,null);}catch(Throwable ignored){}}}
    private void pruneOldAutoSegmentsAsync(){new Thread(()->{try{Uri collection=MediaStore.Video.Media.EXTERNAL_CONTENT_URI;String[] projection={MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME};String sel=MediaStore.Video.Media.DISPLAY_NAME+" LIKE ?";String[] args={"EPC-AUTO-%"};try(Cursor c=getContentResolver().query(collection,projection,sel,args,MediaStore.Video.Media.DATE_ADDED+" DESC")){if(c==null)return;int n=0;while(c.moveToNext()){n++;if(n<=MAX_AUTO_SEGMENTS)continue;long id=c.getLong(0);try{getContentResolver().delete(Uri.withAppendedPath(collection,String.valueOf(id)),null,null);}catch(Throwable ignored){}}}}catch(Throwable ignored){}},"epc-prune-dashcam").start();}

    private void analyzeFrame(ImageProxy proxy){long now=System.currentTimeMillis();if(!smartSigns()||thermalStatus>=3||now-lastAnalysisAt<1200L){proxy.close();return;}lastAnalysisAt=now;if(proxy.getImage()==null){proxy.close();return;}if(recognizer==null)recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);InputImage image=InputImage.fromMediaImage(proxy.getImage(),proxy.getImageInfo().getRotationDegrees());recognizer.process(image).addOnSuccessListener(result->processVisionText(result.getText())).addOnCompleteListener(task->proxy.close());}
    private void processVisionText(String raw){if(raw==null)return;Matcher m=SPEED_PATTERN.matcher(raw);int value=0;while(m.find()){try{int v=Integer.parseInt(m.group(1));if(v>=20&&v<=130){value=v;break;}}catch(Throwable ignored){}}if(value==0){visionHits=Math.max(0,visionHits-1);return;}String v=String.valueOf(value);if(v.equals(visionCandidate))visionHits++;else{visionCandidate=v;visionHits=1;}if(visionHits<3)return;visionHits=0;final int confirmed=value;runOnUiThread(()->{if(visionState!=null){String msg="PLACA LIDA · "+confirmed;if(roadLimit>0&&roadLimit!=confirmed)msg+=" · BASE "+roadLimit;visionState.setText(msg);visionState.setTextColor(GOLD);}toast("Leitura visual: possível limite de "+confirmed+" km/h. Confirme pela sinalização.");});}

    private void openVideos(){startActivity(new Intent(this,VideoLibraryActivity.class));}
    private void migrateLegacyVideosAsync(){if(Build.VERSION.SDK_INT<29||prefs.getBoolean(KEY_MIGRATED_PUBLIC,false))return;new Thread(()->{int moved=0;try{File base=getExternalFilesDir(Environment.DIRECTORY_MOVIES);File dir=base==null?null:new File(base,"EstradaPlayComunista");File[] files=dir==null?null:dir.listFiles((d,n)->n.toLowerCase(Locale.ROOT).endsWith(".mp4"));if(files!=null)for(File file:files){if(file!=null&&file.isFile()&&file.length()>0&&copyLegacyToGallery(file)){moved++;try{file.delete();}catch(Throwable ignored){}}}prefs.edit().putBoolean(KEY_MIGRATED_PUBLIC,true).apply();}catch(Throwable ignored){}int total=moved;if(total>0)runOnUiThread(()->toast(total+" vídeo(s) antigo(s) recuperado(s) para a Galeria."));},"epc-video-migration").start();}
    private boolean copyLegacyToGallery(File file){Uri uri=null;try{ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,file.getName());v.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");v.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/EstradaPlayComunista");v.put(MediaStore.Video.Media.IS_PENDING,1);uri=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);if(uri==null)return false;try(FileInputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new Exception("sem saída");byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}ContentValues done=new ContentValues();done.put(MediaStore.Video.Media.IS_PENDING,0);getContentResolver().update(uri,done,null,null);return true;}catch(Throwable e){if(uri!=null)try{getContentResolver().delete(uri,null,null);}catch(Throwable ignored){}return false;}}

    private void registerRoadReceiver(){if(receiverRegistered)return;IntentFilter f=new IntentFilter(RoadSafetyService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(roadReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(roadReceiver,f);receiverRegistered=true;}
    private void unregisterRoadReceiver(){if(!receiverRegistered)return;try{unregisterReceiver(roadReceiver);}catch(Throwable ignored){}receiverRegistered=false;}
    private boolean isEnabled(){return prefs.getBoolean(KEY_ENABLED,false);}private boolean autoDashcam(){return prefs.getBoolean(KEY_AUTO,false);}private boolean smartSigns(){return prefs.getBoolean(KEY_SMART,false);}private boolean hasCameraPermission(){return checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;}private boolean hasLegacyStoragePermission(){return Build.VERSION.SDK_INT>28||checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private Button button(String value,boolean primary){Button b=new Button(this);b.setText(value);b.setTextSize(11);b.setTextColor(TEXT);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setAllCaps(false);b.setBackground(box(primary?RED:Color.rgb(24,17,20),12,primary?RED:BORDER));return b;}
    private TextView text(String value,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(0,1.08f);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable box(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}private void toast(String v){Toast.makeText(this,v,Toast.LENGTH_SHORT).show();}
}
''')

print('Estrada Play Comunista 1.4.0 dashcam + camera intelligence patched')
