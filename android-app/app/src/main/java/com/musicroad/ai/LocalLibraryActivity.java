package com.musicroad.ai;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class LocalLibraryActivity extends Activity {
    private static final int REQ_AUDIO = 2001;
    private ListView listView;
    private TextView status;
    private List<MusicTrack> tracks = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8,17,31));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,24);
        root.setBackgroundColor(Color.rgb(8,17,31));
        TextView title = new TextView(this);
        title.setText("Músicas do celular"); title.setTextSize(24); title.setTextColor(Color.WHITE); title.setPadding(0,0,0,16);
        status = new TextView(this); status.setTextColor(Color.LTGRAY); status.setPadding(0,0,0,16);
        Button permission = new Button(this); permission.setText("Permitir acesso às músicas"); permission.setOnClickListener(v -> requestAudio());
        listView = new ListView(this); listView.setDividerHeight(1);
        root.addView(title); root.addView(status); root.addView(permission);
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        setContentView(root);
        listView.setOnItemClickListener((parent,view,position,id)->play(position));
        load();
    }

    private void requestAudio(){
        if(NativeMusicRepository.hasPermission(this)){load();return;}
        requestPermissions(new String[]{NativeMusicRepository.permissionName()},REQ_AUDIO);
    }

    private void load(){
        if(!NativeMusicRepository.hasPermission(this)){
            status.setText("Permita o acesso para o MusicRoad localizar automaticamente os áudios do aparelho.");
            listView.setAdapter(null); return;
        }
        tracks = NativeMusicRepository.scan(this);
        status.setText(tracks.size()+" músicas encontradas no MediaStore");
        ArrayList<String> rows = new ArrayList<>();
        for(MusicTrack t:tracks) rows.add(t.title+"\n"+t.artist+(t.album.isEmpty()?"":" • "+t.album));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows){
            @Override public android.view.View getView(int position,android.view.View convertView,ViewGroup parent){
                android.view.View v=super.getView(position,convertView,parent); ((TextView)v).setTextColor(Color.WHITE); ((TextView)v).setTextSize(16); v.setBackgroundColor(Color.TRANSPARENT); return v;
            }
        };
        listView.setAdapter(adapter);
    }

    private void play(int position){
        try{
            JSONArray arr=new JSONArray(); for(MusicTrack t:tracks)arr.put(t.toJson());
            Intent i=PlaybackService.intentSetQueue(this,arr.toString(),position,true);
            startForegroundServiceCompat(i);
        }catch(Exception e){Toast.makeText(this,"Não foi possível iniciar a reprodução.",Toast.LENGTH_SHORT).show();}
    }

    private void startForegroundServiceCompat(Intent i){
        if(android.os.Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_AUDIO){ if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)load(); else status.setText("Permissão de áudio negada."); }
    }
}

