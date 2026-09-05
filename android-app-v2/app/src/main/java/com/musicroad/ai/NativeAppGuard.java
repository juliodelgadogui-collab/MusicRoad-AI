package com.musicroad.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.CookieManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight runtime guard for the native shell and offline-first music UI. */
final class NativeAppGuard {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static WeakReference<MainActivity> current=new WeakReference<>(null);
    private static WeakReference<View> lastRoot=new WeakReference<>(null);
    private static long lastMusicFingerprint=Long.MIN_VALUE;
    private static final long TICK_MS=650L;

    private static final Runnable TICK=new Runnable(){
        @Override public void run(){
            MainActivity a=current.get();
            if(a==null||a.isFinishing()||a.isDestroyed())return;
            repairContentLayers(a);
            normalizeIfChanged(a);
            OfflineFolderManager.maybeShowFirstRun(a);
            MAIN.postDelayed(this,TICK_MS);
        }
    };

    private NativeAppGuard(){}

    static void start(MainActivity activity){
        MusicOfflineStore.init(activity);
        current=new WeakReference<>(activity);
        lastRoot.clear();lastMusicFingerprint=Long.MIN_VALUE;
        MAIN.removeCallbacks(TICK);
        syncNativeSessionCookie(activity);
        IO.execute(MusicOfflineStore::reconcileDownloads);
        MAIN.post(TICK);
    }

    static void stop(MainActivity activity){
        if(current.get()==activity)current.clear();
        MAIN.removeCallbacks(TICK);
    }

    static void invalidateMusicUi(){
        lastRoot.clear();lastMusicFingerprint=Long.MIN_VALUE;
        MainActivity a=current.get();if(a!=null)MAIN.post(()->normalizeIfChanged(a));
    }

    private static void normalizeIfChanged(MainActivity activity){
        try{
            FrameLayout content=content(activity);if(content==null||content.getChildCount()==0)return;
            View root=content.getChildAt(content.getChildCount()-1);
            long fingerprint=musicFingerprint(activity);
            if(root==lastRoot.get()&&fingerprint==lastMusicFingerprint)return;
            lastRoot=new WeakReference<>(root);lastMusicFingerprint=fingerprint;
            normalizeNode(activity,root);
        }catch(Throwable ignored){}
    }

    private static long musicFingerprint(MainActivity activity){
        try{
            Field f=MainActivity.class.getDeclaredField("shownTracks");f.setAccessible(true);Object raw=f.get(activity);
            if(!(raw instanceof List))return 0L;List<?> list=(List<?>)raw;long h=list.size()*1000003L;
            if(!list.isEmpty()){
                Object first=list.get(0),last=list.get(list.size()-1);
                if(first instanceof MusicTrack){MusicTrack t=(MusicTrack)first;h=31*h+(t.id+"|"+t.title).hashCode();}
                if(last instanceof MusicTrack){MusicTrack t=(MusicTrack)last;h=31*h+(t.id+"|"+t.title).hashCode();}
            }
            return h;
        }catch(Throwable ignored){return 0L;}
    }

    private static void repairContentLayers(MainActivity activity){
        try{
            FrameLayout content=content(activity);if(content==null)return;int count=content.getChildCount();if(count<=1)return;
            View newest=content.getChildAt(count-1);content.removeAllViews();content.addView(newest,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
            lastRoot.clear();
        }catch(Throwable ignored){}
    }

    private static void normalizeNode(MainActivity activity,View view){
        if(view==null)return;
        if(view instanceof TextView&&!(view instanceof Button)){
            TextView tv=(TextView)view;String value=String.valueOf(tv.getText()).trim();
            if("Downloads offline".equals(value)||"Offline integrado".equals(value)||"Gerenciar músicas offline".equals(value)){
                tv.setText("Gerenciar músicas offline");
                ViewGroup textBox=parentGroup(tv);if(textBox!=null){
                    for(int i=0;i<textBox.getChildCount();i++){
                        View child=textBox.getChildAt(i);if(child instanceof TextView&&child!=tv){((TextView)child).setText("Escolha pastas do Drive para baixar. Depois a reprodução é 100% local.");break;}
                    }
                    ViewParent cardParent=textBox.getParent();if(cardParent instanceof View){
                        View card=(View)cardParent;card.setClickable(true);card.setFocusable(true);card.setOnClickListener(v->OfflineFolderManager.showManager(activity));
                    }
                }
            }
        }

        if(view instanceof Button){
            Button b=(Button)view;String label=String.valueOf(b.getText()).trim();ViewGroup row=parentGroup(b);
            MusicTrack track=row==null?null:findTrack(activity,rowTitle(row),rowSubtitle(row));
            if(track!=null&&OfflineFolderManager.isDrive(track)){
                boolean downloaded=MusicOfflineStore.isDownloaded(track);
                if("↓".equals(label)||"⇩".equals(label)||"✓".equals(label)){
                    if(downloaded){
                        b.setText("✓");b.setEnabled(true);b.setContentDescription("Disponível offline. Segure para remover esta música.");
                        b.setOnClickListener(v->Toast.makeText(activity,"Esta música já está offline.",Toast.LENGTH_SHORT).show());
                        b.setOnLongClickListener(v->MusicDirectDownload.remove(activity,track,b));
                    }else{
                        b.setText("⇩");b.setEnabled(true);b.setContentDescription("Baixar a pasta desta música");b.setOnLongClickListener(null);
                        b.setOnClickListener(v->OfflineFolderManager.showManager(activity,OfflineFolderManager.folderKey(track)));
                    }
                }else if("▶".equals(label)&&!downloaded){
                    b.setContentDescription("Música ainda não baixada");
                    b.setOnClickListener(v->{Toast.makeText(activity,"Baixe esta pasta para tocar sem acessar o Drive.",Toast.LENGTH_LONG).show();OfflineFolderManager.showManager(activity,OfflineFolderManager.folderKey(track));});
                }
            }
        }

        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)normalizeNode(activity,group.getChildAt(i));}
    }

    private static ViewGroup parentGroup(View v){ViewParent p=v==null?null:v.getParent();return p instanceof ViewGroup?(ViewGroup)p:null;}

    private static MusicTrack findTrack(MainActivity activity,String title,String subtitle){
        if(title==null||title.trim().isEmpty())return null;
        try{
            Field f=MainActivity.class.getDeclaredField("shownTracks");f.setAccessible(true);Object raw=f.get(activity);if(!(raw instanceof List))return null;
            MusicTrack first=null;for(Object o:(List<?>)raw){
                if(!(o instanceof MusicTrack))continue;MusicTrack t=(MusicTrack)o;if(!title.trim().equals(t.title==null?"":t.title.trim()))continue;if(first==null)first=t;
                String artist=t.artist==null?"":t.artist.trim(),folder=t.folder==null?"":t.folder.trim();
                if(subtitle!=null&&((!artist.isEmpty()&&subtitle.contains(artist))||(!folder.isEmpty()&&subtitle.contains(folder))))return t;
            }
            return first;
        }catch(Throwable ignored){return null;}
    }

    private static String rowTitle(ViewGroup row){
        for(int i=0;i<row.getChildCount();i++){View child=row.getChildAt(i);if(!(child instanceof ViewGroup))continue;ViewGroup box=(ViewGroup)child;
            for(int j=0;j<box.getChildCount();j++){View nested=box.getChildAt(j);if(nested instanceof TextView&&!(nested instanceof Button)){String value=String.valueOf(((TextView)nested).getText()).trim();if(!value.isEmpty())return value;}}}
        return"";
    }
    private static String rowSubtitle(ViewGroup row){
        for(int i=0;i<row.getChildCount();i++){View child=row.getChildAt(i);if(!(child instanceof ViewGroup))continue;ViewGroup box=(ViewGroup)child;int found=0;
            for(int j=0;j<box.getChildCount();j++){View nested=box.getChildAt(j);if(nested instanceof TextView&&!(nested instanceof Button)){String value=String.valueOf(((TextView)nested).getText()).trim();if(value.isEmpty())continue;if(++found==2)return value;}}}
        return"";
    }

    private static FrameLayout content(MainActivity activity){
        try{Field f=MainActivity.class.getDeclaredField("content");f.setAccessible(true);Object raw=f.get(activity);return raw instanceof FrameLayout?(FrameLayout)raw:null;}catch(Throwable ignored){return null;}
    }

    private static void syncNativeSessionCookie(Context context){
        try{
            SharedPreferences p=context.getSharedPreferences("musicroad_native_api_v1",Context.MODE_PRIVATE);String jar=p.getString("cookie","");if(jar==null||jar.trim().isEmpty())return;
            String base=NativeApiClient.normalizeBase(BuildConfig.MUSICROAD_URL);CookieManager cm=CookieManager.getInstance();cm.setAcceptCookie(true);
            for(String part:jar.split(";\\s*")){String pair=part==null?"":part.trim();if(pair.isEmpty()||!pair.contains("="))continue;cm.setCookie(base,pair+"; Path=/; Secure; SameSite=Lax");}
            cm.flush();
        }catch(Throwable ignored){}
    }
}
