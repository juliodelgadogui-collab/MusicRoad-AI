package com.musicroad.ai;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline-first music flow.
 *
 * The MusicRoad server is used only to fetch light library metadata. The user
 * chooses Google Drive folders and the selected audio files are downloaded
 * directly Drive -> phone. Playback of Drive tracks then uses local files only.
 */
final class OfflineFolderManager {
    private static final String PREFS="musicroad_offline_folder_flow_v1";
    private static final String KEY_FIRST_PROMPTED="first_prompted";
    private static final String KEY_SELECTED="selected_folders";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean FIRST_LOADING=new AtomicBoolean(false);
    private static final AtomicBoolean DIALOG_OPEN=new AtomicBoolean(false);
    private static volatile long lastFirstAttemptAt=0L;

    private OfflineFolderManager(){}

    static void maybeShowFirstRun(MainActivity activity){
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return;
        SharedPreferences p=prefs(activity);
        if(p.getBoolean(KEY_FIRST_PROMPTED,false))return;
        if(DIALOG_OPEN.get()||FIRST_LOADING.get())return;
        if(!MusicOfflineStore.isNetworkConnected(activity))return;
        if(DriveMediaResolver.nativeCookie(activity).isEmpty())return;
        long now=System.currentTimeMillis();
        if(now-lastFirstAttemptAt<4000L)return;
        lastFirstAttemptAt=now;
        if(!FIRST_LOADING.compareAndSet(false,true))return;
        loadDriveTracks(activity,new LoadCallback(){
            @Override public void done(List<MusicTrack> tracks,String error){
                FIRST_LOADING.set(false);
                if(activity.isFinishing()||activity.isDestroyed())return;
                if(error!=null){return;}
                if(tracks.isEmpty()){
                    prefs(activity).edit().putBoolean(KEY_FIRST_PROMPTED,true).apply();
                    return;
                }
                showFolderDialog(activity,tracks,true,null);
            }
        });
    }

    static void showManager(MainActivity activity){showManager(activity,null);}

    static void showManager(MainActivity activity,String preferredFolder){
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return;
        if(!MusicOfflineStore.isNetworkConnected(activity)){
            Toast.makeText(activity,"Conecte-se à internet para baixar novas pastas.",Toast.LENGTH_LONG).show();
            return;
        }
        if(DIALOG_OPEN.get())return;
        Toast.makeText(activity,"Carregando pastas do Google Drive…",Toast.LENGTH_SHORT).show();
        loadDriveTracks(activity,new LoadCallback(){
            @Override public void done(List<MusicTrack> tracks,String error){
                if(activity.isFinishing()||activity.isDestroyed())return;
                if(error!=null){Toast.makeText(activity,error,Toast.LENGTH_LONG).show();return;}
                if(tracks.isEmpty()){Toast.makeText(activity,"Nenhuma pasta de música do Drive encontrada.",Toast.LENGTH_LONG).show();return;}
                showFolderDialog(activity,tracks,false,preferredFolder);
            }
        });
    }

    private static void showFolderDialog(MainActivity activity,List<MusicTrack> tracks,boolean firstRun,String preferredFolder){
        if(!DIALOG_OPEN.compareAndSet(false,true))return;
        IO.execute(()->{
            LinkedHashMap<String,FolderInfo> folders=new LinkedHashMap<>();
            for(MusicTrack t:tracks){
                String key=folderKey(t);
                FolderInfo info=folders.get(key);
                if(info==null){info=new FolderInfo(key);folders.put(key,info);}
                info.total++;
                if(t.fileSize>0)info.bytes+=t.fileSize;
                if(MusicOfflineStore.isDownloaded(t))info.downloaded++;
            }
            ArrayList<FolderInfo> infos=new ArrayList<>(folders.values());
            activity.runOnUiThread(()->{
                if(activity.isFinishing()||activity.isDestroyed()){DIALOG_OPEN.set(false);return;}
                String[] labels=new String[infos.size()];
                boolean[] checked=new boolean[infos.size()];
                for(int i=0;i<infos.size();i++){
                    FolderInfo f=infos.get(i);
                    String state=f.downloaded>=f.total?"✓ offline":(f.downloaded>0?f.downloaded+"/"+f.total+" baixadas":f.total+" músicas");
                    labels[i]=displayFolder(f.key)+"  •  "+state+(f.bytes>0?"  •  "+formatBytes(f.bytes):"");
                    checked[i]=preferredFolder!=null&&sameFolder(preferredFolder,f.key)&&f.downloaded<f.total;
                }
                AlertDialog dialog=new AlertDialog.Builder(activity)
                        .setTitle(firstRun?"Escolha suas músicas offline":"Gerenciar músicas offline")
                        .setMessage(firstRun
                                ?"Selecione as pastas que quer manter neste aparelho. O MusicRoad baixa direto do Google Drive e depois toca sem acessar o Drive."
                                :"Escolha novas pastas para baixar. Pastas já completas aparecem como ✓ offline.")
                        .setMultiChoiceItems(labels,checked,(d,which,isChecked)->checked[which]=isChecked)
                        .setPositiveButton("Baixar selecionadas",null)
                        .setNegativeButton(firstRun?"Agora não":"Fechar",(d,w)->{
                            if(firstRun)prefs(activity).edit().putBoolean(KEY_FIRST_PROMPTED,true).apply();
                        })
                        .setNeutralButton("Selecionar tudo",null)
                        .create();
                dialog.setOnDismissListener(d->DIALOG_OPEN.set(false));
                dialog.setOnShowListener(d->{
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                        for(int i=0;i<checked.length;i++){
                            checked[i]=infos.get(i).downloaded<infos.get(i).total;
                            dialog.getListView().setItemChecked(i,checked[i]);
                        }
                    });
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                        ArrayList<String> selected=new ArrayList<>();
                        for(int i=0;i<checked.length;i++)if(checked[i])selected.add(infos.get(i).key);
                        if(selected.isEmpty()){
                            Toast.makeText(activity,"Selecione pelo menos uma pasta.",Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if(firstRun)prefs(activity).edit().putBoolean(KEY_FIRST_PROMPTED,true).apply();
                        rememberSelected(activity,selected);
                        Intent intent=OfflineFolderDownloadService.startIntent(activity,selected);
                        try{
                            if(android.os.Build.VERSION.SDK_INT>=26)activity.startForegroundService(intent);else activity.startService(intent);
                            Toast.makeText(activity,"Download iniciado. Você pode continuar usando o MusicRoad.",Toast.LENGTH_LONG).show();
                        }catch(Exception e){
                            Toast.makeText(activity,"Não foi possível iniciar os downloads.",Toast.LENGTH_LONG).show();
                            return;
                        }
                        dialog.dismiss();
                    });
                });
                dialog.show();
            });
        });
    }

    static String folderKey(MusicTrack t){
        if(t==null)return"Google Drive";
        String path=t.folderPath==null?"":t.folderPath.trim();
        if(path.isEmpty())path=t.folder==null?"":t.folder.trim();
        if(path.isEmpty())path=t.album==null?"":t.album.trim();
        if(path.isEmpty())path="Google Drive";
        path=path.replace('\\','/').replace(" / ","/");
        while(path.contains("//"))path=path.replace("//","/");
        while(path.startsWith("/"))path=path.substring(1);
        while(path.endsWith("/"))path=path.substring(0,path.length()-1);
        if(path.toLowerCase(Locale.ROOT).startsWith("google drive/"))path=path.substring("google drive/".length());
        return path.isEmpty()?"Google Drive":path;
    }

    static boolean isDrive(MusicTrack t){
        return t!=null&&t.origin!=null&&t.origin.toLowerCase(Locale.ROOT).contains("drive");
    }

    static MusicTrack trackFromLibrary(Context context,JSONObject o){
        if(o==null)return null;
        try{
            String origin=o.optString("origin","");
            String source=o.optString("source",o.optString("source_url",""));
            String ref=o.optString("origin_ref","").trim();
            String base=NativeApiClient.normalizeBase(BuildConfig.MUSICROAD_URL);
            if(source.isEmpty()&&origin.toLowerCase(Locale.ROOT).contains("drive")&&!ref.isEmpty()){
                source="api/drive_stream.php?id="+URLEncoder.encode(ref, StandardCharsets.UTF_8.name());
            }
            if(!source.isEmpty()&&!source.startsWith("http://")&&!source.startsWith("https://")&&!source.startsWith("file://")&&!source.startsWith("content://")){
                source=base+source.replaceFirst("^/+","");
            }
            o.put("source",source);
            if(o.optString("folder_path","").trim().isEmpty()){
                String f=o.optString("folder","").trim();
                if(f.isEmpty()&&origin.toLowerCase(Locale.ROOT).contains("drive"))f=o.optString("artist","").trim();
                o.put("folder",f);o.put("folder_path",f);
            }
            return MusicTrack.fromJson(o);
        }catch(Exception ignored){return null;}
    }

    static void loadDriveTracks(Context context,LoadCallback callback){
        IO.execute(()->{
            ArrayList<MusicTrack> out=new ArrayList<>();String error=null;
            try{
                NativeApiClient api=new NativeApiClient(context);
                String base=NativeApiClient.normalizeBase(BuildConfig.MUSICROAD_URL);
                NativeApiClient.Response r=api.get(base,"api/library.php?action=list");
                if(!r.ok())throw new Exception("Não consegui carregar a biblioteca do servidor.");
                JSONArray arr=r.json().optJSONArray("tracks");
                if(arr!=null)for(int i=0;i<arr.length();i++){
                    JSONObject raw=arr.optJSONObject(i);if(raw==null)continue;
                    MusicTrack t=trackFromLibrary(context,new JSONObject(raw.toString()));
                    if(t!=null&&isDrive(t))out.add(t);
                }
            }catch(Exception e){error=e.getMessage()==null?"Falha ao carregar as pastas do Drive.":e.getMessage();}
            String finalError=error;
            if(context instanceof MainActivity){((MainActivity)context).runOnUiThread(()->callback.done(out,finalError));}
            else callback.done(out,finalError);
        });
    }

    static void rememberSelected(Context context,List<String> selected){
        Set<String> merged=new LinkedHashSet<>(prefs(context).getStringSet(KEY_SELECTED,new LinkedHashSet<>()));
        merged.addAll(selected);
        prefs(context).edit().putStringSet(KEY_SELECTED,merged).apply();
    }

    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static boolean sameFolder(String a,String b){return normalize(a).equalsIgnoreCase(normalize(b));}
    private static String normalize(String x){return x==null?"":x.replace('\\','/').replace(" / ","/").replaceAll("/+","/").trim();}
    private static String displayFolder(String s){return s==null||s.trim().isEmpty()?"Google Drive":s.replace('/', '›');}
    private static String formatBytes(long b){double mb=b/1024d/1024d;if(mb<1024)return String.format(Locale.ROOT,"%.0f MB",mb);return String.format(Locale.ROOT,"%.1f GB",mb/1024d);}

    interface LoadCallback{void done(List<MusicTrack> tracks,String error);}
    private static final class FolderInfo{final String key;int total,downloaded;long bytes;FolderInfo(String key){this.key=key;}}
}
