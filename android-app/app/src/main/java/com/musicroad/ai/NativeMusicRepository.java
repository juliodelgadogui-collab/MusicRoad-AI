package com.musicroad.ai;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;

public final class NativeMusicRepository {
    private NativeMusicRepository(){}
    public static String permissionName(){return Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_AUDIO:Manifest.permission.READ_EXTERNAL_STORAGE;}
    public static boolean hasPermission(Context c){return c.checkSelfPermission(permissionName())== PackageManager.PERMISSION_GRANTED;}
    public static List<MusicTrack> scan(Context c){
        ArrayList<MusicTrack> out=new ArrayList<>();
        Uri collection=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM,MediaStore.Audio.Media.DURATION};
        String selection=MediaStore.Audio.Media.IS_MUSIC+"!=0";
        try(Cursor cur=c.getContentResolver().query(collection,projection,selection,null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")){
            if(cur==null)return out;int iid=cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID),it=cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE),ia=cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST),ial=cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM),iduration=cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            while(cur.moveToNext()){long mediaId=cur.getLong(iid),dur=cur.getLong(iduration);String title=cur.getString(it),artist=cur.getString(ia),album=cur.getString(ial);Uri u=ContentUris.withAppendedId(collection,mediaId);out.add(new MusicTrack(mediaId,title==null?"Sem título":title,artist==null?"":artist,album==null?"":album,u.toString(),dur));}
        }catch(Exception ignored){}
        return out;
    }
}
