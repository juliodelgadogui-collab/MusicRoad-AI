package com.musicroad.ai;

import android.net.Uri;
import android.os.Environment;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads a library track directly to the phone while preserving its folder metadata. */
final class MusicDirectDownload {
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);

    private MusicDirectDownload(){}

    static void start(MainActivity activity,MusicTrack track,Button button){
        if(activity==null||track==null||button==null)return;
        if(MusicOfflineStore.isDownloaded(track)){
            button.setText("✓");
            return;
        }
        if(!MusicOfflineStore.isNetworkConnected(activity)){
            Toast.makeText(activity,"Sem internet para baixar esta música.",Toast.LENGTH_SHORT).show();
            return;
        }
        MusicOfflineStore.remember(track);
        button.setEnabled(false);
        button.setText("…");
        IO.execute(()->download(activity,track,button));
    }

    private static void download(MainActivity activity,MusicTrack track,Button button){
        File part=null;
        HttpURLConnection c=null;
        try{
            DriveMediaResolver.Result resolved=DriveMediaResolver.resolve(activity,track,false);
            if(!resolved.ok||resolved.url.isEmpty())throw new Exception(resolved.error.isEmpty()?"Não foi possível resolver o arquivo.":resolved.error);

            c=(HttpURLConnection)new URL(resolved.url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(120000);
            c.setRequestProperty("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept","audio/*,application/octet-stream;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Encoding","identity");
            if(resolved.url.contains("/api/")){
                String cookie=DriveMediaResolver.nativeCookie(activity);
                if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
            }
            int code=c.getResponseCode();
            if(code<200||code>=300)throw new Exception("Download HTTP "+code);
            String type=c.getContentType()==null?resolved.mime:c.getContentType();
            String lowType=type==null?"":type.toLowerCase(Locale.ROOT);
            if(lowType.contains("text/html")||lowType.contains("application/json"))throw new Exception("O Drive devolveu uma página, não o áudio.");
            long total=c.getContentLengthLong();

            File target=targetFile(activity,track,type,resolved.url);
            File dir=target.getParentFile();
            if(dir==null||(!dir.exists()&&!dir.mkdirs()))throw new Exception("Não consegui criar a pasta offline.");
            part=new File(dir,target.getName()+".part");
            if(part.exists())part.delete();

            long done=0,lastUi=0;
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)){
                byte[] buf=new byte[65536];
                int n;
                while((n=in.read(buf))>0){
                    out.write(buf,0,n);
                    done+=n;
                    long now=System.currentTimeMillis();
                    if(now-lastUi>450){
                        lastUi=now;
                        long current=done;
                        activity.runOnUiThread(()->{
                            if(total>0){int pct=(int)Math.min(99,(current*100L)/total);button.setText(pct+"%");}
                            else button.setText("…");
                        });
                    }
                }
                out.flush();
            }
            if(part.length()<=0)throw new Exception("Arquivo vazio.");
            if(target.exists()&&!target.delete())throw new Exception("Não consegui substituir o download anterior.");
            if(!part.renameTo(target))copyReplace(part,target);
            if(!target.isFile()||target.length()<=0)throw new Exception("Download incompleto.");
            if(part.exists())part.delete();

            MusicOfflineStore.remember(track);
            activity.runOnUiThread(()->{
                button.setEnabled(true);
                button.setText("✓");
                button.setContentDescription("Baixada. Segure para remover do offline.");
                Toast.makeText(activity,"Baixada na mesma pasta da biblioteca.",Toast.LENGTH_SHORT).show();
            });
        }catch(Exception e){
            if(part!=null&&part.exists())part.delete();
            String msg=e.getMessage()==null?"Não foi possível baixar esta música.":e.getMessage();
            activity.runOnUiThread(()->{
                button.setEnabled(true);
                button.setText("↓");
                Toast.makeText(activity,msg,Toast.LENGTH_LONG).show();
            });
        }finally{
            if(c!=null)c.disconnect();
        }
    }

    static boolean remove(MainActivity activity,MusicTrack track,Button button){
        if(activity==null||track==null)return false;
        MusicTrack local=MusicOfflineStore.preferLocal(track);
        if(local==null||local.source==null||!local.source.startsWith("file://"))return false;
        try{
            String path=Uri.parse(local.source).getPath();
            File f=path==null?null:new File(path);
            boolean ok=f!=null&&f.isFile()&&f.delete();
            if(ok&&button!=null){button.setText("↓");button.setEnabled(true);button.setContentDescription("Baixar para uso offline");}
            if(ok)Toast.makeText(activity,"Download removido. A música continua no Drive.",Toast.LENGTH_SHORT).show();
            return ok;
        }catch(Exception ignored){return false;}
    }

    private static File targetFile(MainActivity activity,MusicTrack track,String mime,String url){
        File musicRoot=activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if(musicRoot==null)musicRoot=activity.getFilesDir();
        File dir=new File(musicRoot,"MusicRoad");
        dir=new File(dir,safeSegment(track.origin==null||track.origin.trim().isEmpty()?"Biblioteca":track.origin));
        String path=track.folderPath==null||track.folderPath.trim().isEmpty()?track.folder:track.folderPath;
        if(path!=null&&!path.trim().isEmpty()){
            String normalized=path.replace(" / ","/").replace('\\','/');
            for(String part:normalized.split("/+")){
                String s=safeSegment(part);
                if(!s.isEmpty()&&!"Google Drive".equalsIgnoreCase(s))dir=new File(dir,s);
            }
        }
        String ext=extensionFrom(track,mime,url);
        return new File(dir,stableToken(track)+" - "+MusicOfflineStore.safeName(track.title)+ext);
    }

    private static String stableToken(MusicTrack t){
        String id=t.id==null?"":t.id.replaceAll("[^A-Za-z0-9_-]+","");
        if(!id.isEmpty())return id.length()>28?id.substring(0,28):id;
        String key=(t.origin==null?"":t.origin)+":"+(t.title==null?"":t.title)+":"+(t.artist==null?"":t.artist);
        return Integer.toHexString(key.hashCode());
    }

    private static String safeSegment(String value){
        String s=MusicOfflineStore.safeName(value);
        if(".".equals(s)||"..".equals(s))return "Pasta";
        return s;
    }

    private static String extensionFrom(MusicTrack t,String mime,String url){
        String m=mime==null?"":mime.toLowerCase(Locale.ROOT);
        if(m.contains("mpeg"))return ".mp3";
        if(m.contains("mp4")||m.contains("m4a"))return ".m4a";
        if(m.contains("aac"))return ".aac";
        if(m.contains("flac"))return ".flac";
        if(m.contains("wav"))return ".wav";
        if(m.contains("opus"))return ".opus";
        if(m.contains("ogg"))return ".ogg";
        String original=t.source==null?"":t.source.toLowerCase(Locale.ROOT);
        String combined=(original+" "+(url==null?"":url.toLowerCase(Locale.ROOT)));
        for(String ext:new String[]{".mp3",".m4a",".aac",".flac",".wav",".opus",".ogg"})if(combined.contains(ext))return ext;
        return ".mp3";
    }

    private static void copyReplace(File source,File target)throws Exception{
        try(FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target)){
            byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);out.flush();
        }
        if(target.length()<=0)throw new Exception("Falha ao finalizar download.");
    }
}
