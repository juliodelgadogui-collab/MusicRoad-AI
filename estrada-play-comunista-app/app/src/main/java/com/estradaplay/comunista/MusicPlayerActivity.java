package com.estradaplay.comunista;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated music surface. Opening Music never redirects to the download manager. */
public final class MusicPlayerActivity extends ComponentActivity {
    private static final int REQ_DEVICE_AUDIO=4310;
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(29,14,18),BORDER=Color.rgb(76,38,44);
    private final int TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout root,listBox;private ScrollView musicScroll;private TextView title,artist,state,count;private LibraryStore library;private boolean registered;

    private final BroadcastReceiver playerState=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String t=i.getStringExtra("title"),a=i.getStringExtra("artist"),s=i.getStringExtra("state");
        boolean playing=i.getBooleanExtra("playing",false);
        if(title!=null)title.setText(t==null||t.trim().isEmpty()?"Escolha uma música":t.trim());
        if(artist!=null)artist.setText(a==null||a.trim().isEmpty()?"Biblioteca local":a.trim());
        if(state!=null){state.setText(playing?"● TOCANDO":(s==null||s.isEmpty()?"PRONTO":s.toUpperCase(Locale.ROOT)));state.setTextColor(playing?GREEN:MUTED);}
    }};

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);library=new LibraryStore(this);build();loadTracks();register();queryPlayer();}
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);build();loadTracks();queryPlayer();}
    @Override protected void onResume(){super.onResume();if(library!=null)loadTracks();}
    @Override protected void onDestroy(){if(registered)try{unregisterReceiver(playerState);}catch(Throwable ignored){}io.shutdownNow();super.onDestroy();}

    private void build(){
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(BG);setContentView(UnifiedAppShell.wrap(this,"music",frame));
        musicScroll=new ScrollView(this);musicScroll.setFillViewport(true);musicScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);frame.addView(musicScroll,new FrameLayout.LayoutParams(-1,-1));
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(14),dp(18),dp(24));musicScroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);Button back=small("‹ MAPA");head.addView(back,new LinearLayout.LayoutParams(dp(92),dp(46)));back.setOnClickListener(v->finish());
        LinearLayout brand=col();brand.addView(over("ÁUDIO DE BORDO",RED));brand.addView(text("Música",27,TEXT,true));head.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button manage=small("BIBLIOTECA");head.addView(manage,new LinearLayout.LayoutParams(dp(112),dp(46)));manage.setOnClickListener(v->showOfflineLibrary());root.addView(head);

        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout body=landscape?row():col();LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.setMargins(0,dp(14),0,0);root.addView(body,bp);
        LinearLayout player=playerCard();LinearLayout.LayoutParams pp=landscape?new LinearLayout.LayoutParams(0,dp(390),.42f):new LinearLayout.LayoutParams(-1,dp(350));body.addView(player,pp);
        listBox=col();listBox.setPadding(dp(14),dp(14),dp(14),dp(14));listBox.setBackground(panel(SURFACE,18,BORDER));
        LinearLayout.LayoutParams lp=landscape?new LinearLayout.LayoutParams(0,dp(390),.58f):new LinearLayout.LayoutParams(-1,-2);if(landscape)lp.setMargins(dp(12),0,0,0);else lp.setMargins(0,dp(12),0,0);body.addView(listBox,lp);
        LinearLayout lh=row();lh.setGravity(Gravity.CENTER_VERTICAL);LinearLayout lt=col();lt.addView(over("NO APARELHO",MUTED));count=text("Carregando…",17,TEXT,true);lt.addView(count);lh.addView(lt,new LinearLayout.LayoutParams(0,-2,1));Button cleanup=small("LIMPEZA");lh.addView(cleanup,new LinearLayout.LayoutParams(dp(86),dp(42)));cleanup.setOnClickListener(v->openStorage());listBox.addView(lh);
        TextView wait=text("A biblioteca do Estrada Play aparece primeiro; as músicas do celular entram logo depois, sem consultar servidor.",12,MUTED,false);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.setMargins(0,dp(14),0,0);listBox.addView(wait,wp);
    }

    private LinearLayout playerCard(){
        LinearLayout p=col();p.setPadding(dp(20),dp(18),dp(20),dp(18));p.setBackground(panel(SURFACE2,20,RED));
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);TextView mark=text("♪",30,GOLD,true);mark.setGravity(Gravity.CENTER);mark.setBackground(panel(Color.rgb(50,19,25),16,Color.rgb(113,47,55)));top.addView(mark,new LinearLayout.LayoutParams(dp(64),dp(64)));
        LinearLayout meta=col();state=over("PRONTO",MUTED);meta.addView(state);title=text("Escolha uma música",22,TEXT,true);title.setMaxLines(2);meta.addView(title);artist=text("Biblioteca local",12,MUTED,false);meta.addView(artist);top.addView(meta,new LinearLayout.LayoutParams(0,-2,1));margins(meta,dp(14),0,0,0);p.addView(top);
        View spacer=new View(this);p.addView(spacer,new LinearLayout.LayoutParams(1,0,1));
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER);Button prev=control("‹‹",false),play=control("▶ Ⅱ",true),next=control("››",false);controls.addView(prev,new LinearLayout.LayoutParams(dp(64),dp(56)));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(96),dp(56));cp.setMargins(dp(10),0,dp(10),0);controls.addView(play,cp);controls.addView(next,new LinearLayout.LayoutParams(dp(64),dp(56)));p.addView(controls);
        prev.setOnClickListener(v->command(PlayerService.ACTION_PREVIOUS,null,null));play.setOnClickListener(v->command(PlayerService.ACTION_TOGGLE,null,null));next.setOnClickListener(v->command(PlayerService.ACTION_NEXT,null,null));
        TextView note=text("Downloads do Estrada Play também ganham uma cópia em Música/EstradaPlay. Essa cópia continua no celular mesmo se o app for removido.",10,MUTED,false);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(0,dp(13),0,0);p.addView(note,np);return p;
    }

    // MUSIC_FAST_SCAN_V2311: render app downloads first and scan MediaStore locally/cached afterwards.
    // SharedMusicPublisher no longer runs in this foreground search path; persistence already runs on download/app startup.
    private void loadTracks(){loadTracks(false);}
    private void loadTracks(boolean forceDeviceScan){if(io.isShutdown())return;io.execute(()->{
        LibraryStore.ReconcileResult rec=library.reconcileOffline();
        List<Track> appTracks=library.downloadedTracks();
        ArrayList<Track> immediate=new ArrayList<>(appTracks);
        runOnUiThread(()->{
            if(rec.recovered>0)Toast.makeText(this,rec.recovered+" música(s) offline recuperada(s).",Toast.LENGTH_LONG).show();
            renderTracks(immediate);
            if(DeviceMusicStore.hasPermission(this)&&count!=null)count.setText(immediate.size()+" do Estrada Play · localizando celular…");
        });
        if(!DeviceMusicStore.hasPermission(this))return;
        List<Track> tracks=DeviceMusicStore.merge(this,appTracks,forceDeviceScan);
        runOnUiThread(()->renderTracks(tracks));
    });}

    private void renderTracks(List<Track> tracks){if(listBox==null)return;while(listBox.getChildCount()>1)listBox.removeViewAt(1);int n=tracks==null?0:tracks.size();if(count!=null)count.setText(n+" "+(n==1?"música disponível":"músicas disponíveis"));
        LinearLayout source=col();source.setPadding(0,dp(12),0,0);Button phone=DeviceMusicStore.hasPermission(this)?small("ATUALIZAR MÚSICAS DO CELULAR"):primary("LOCALIZAR MÚSICAS DO CELULAR");source.addView(phone,new LinearLayout.LayoutParams(-1,dp(52)));phone.setOnClickListener(v->{if(DeviceMusicStore.hasPermission(this)){DeviceMusicStore.invalidate();loadTracks(true);}else requestDeviceAudio();});
        TextView sourceNote=text(DeviceMusicStore.hasPermission(this)?"Busca local rápida ativada. O resultado fica em cache por alguns segundos para não varrer o celular toda vez que abrir a tela.":"O acesso só é solicitado quando você tocar no botão. O Estrada Play não envia essas músicas para o servidor.",10,MUTED,false);LinearLayout.LayoutParams sn=new LinearLayout.LayoutParams(-1,-2);sn.setMargins(0,dp(7),0,0);source.addView(sourceNote,sn);listBox.addView(source);
        if(n==0){LinearLayout empty=col();empty.setPadding(0,dp(18),0,0);empty.addView(text("Nenhuma música reconhecida",19,TEXT,true));empty.addView(text("Você pode localizar as músicas já existentes no celular ou baixar músicas da biblioteca Estrada Play.",12,MUTED,false));Button add=small("ADICIONAR / BAIXAR MÚSICAS");LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(50));ap.setMargins(0,dp(12),0,0);empty.addView(add,ap);add.setOnClickListener(v->openDownloads());listBox.addView(empty);return;}
        ScrollView sv=new ScrollView(this);LinearLayout rows=col();sv.addView(rows,new ScrollView.LayoutParams(-1,-2));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(270));sp.setMargins(0,dp(10),0,0);listBox.addView(sv,sp);int limit=Math.min(n,240);for(int i=0;i<limit;i++){Track t=tracks.get(i);rows.addView(trackRow(t,i));if(i<limit-1){View d=new View(this);d.setBackgroundColor(BORDER);rows.addView(d,new LinearLayout.LayoutParams(-1,dp(1)));}}if(n>limit)rows.addView(text("Mostrando 240 de "+n+" faixas. Use a biblioteca do aparelho para organizar coleções muito grandes.",10,MUTED,false));}

    private View trackRow(Track t,int pos){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(8),dp(8),dp(6),dp(8));TextView idx=text(String.format(Locale.ROOT,"%02d",pos+1),10,GOLD,true);idx.setGravity(Gravity.CENTER);r.addView(idx,new LinearLayout.LayoutParams(dp(38),dp(44)));LinearLayout m=col();TextView tt=text(t.title,14,TEXT,true);tt.setMaxLines(1);m.addView(tt);String folder=LibraryStore.folderKey(t);TextView aa=text(t.artist+(folder==null||folder.isEmpty()?"":" · "+folder),10,MUTED,false);aa.setMaxLines(1);m.addView(aa);r.addView(m,new LinearLayout.LayoutParams(0,-2,1));Button go=control("▶",true);r.addView(go,new LinearLayout.LayoutParams(dp(48),dp(44)));go.setOnClickListener(v->command(PlayerService.ACTION_PLAY_TRACK,t.key(),"__ALL__"));r.setOnClickListener(v->command(PlayerService.ACTION_PLAY_TRACK,t.key(),"__ALL__"));return r;}

    private void requestDeviceAudio(){
        if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO},REQ_DEVICE_AUDIO);
        else if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},REQ_DEVICE_AUDIO);
        else loadTracks(true);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQ_DEVICE_AUDIO){if(DeviceMusicStore.hasPermission(this)){Toast.makeText(this,"Músicas do celular liberadas para o player.",Toast.LENGTH_SHORT).show();DeviceMusicStore.invalidate();loadTracks(true);}else Toast.makeText(this,"Sem essa permissão, o player continua usando somente as músicas do Estrada Play.",Toast.LENGTH_LONG).show();}}

    private void register(){if(registered)return;IntentFilter f=new IntentFilter(PlayerService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(playerState,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(playerState,f);registered=true;}
    private void queryPlayer(){try{startService(new Intent(this,PlayerService.class).setAction(PlayerService.ACTION_QUERY_STATE));}catch(Throwable ignored){}}
    private void command(String action,String key,String folder){try{Intent i=new Intent(this,PlayerService.class).setAction(action);if(key!=null)i.putExtra(PlayerService.EXTRA_KEY,key);if(folder!=null)i.putExtra(PlayerService.EXTRA_FOLDER,folder);if(Build.VERSION.SDK_INT>=26&&PlayerService.ACTION_PLAY_TRACK.equals(action))startForegroundService(i);else startService(i);}catch(Throwable e){Toast.makeText(this,"Não consegui iniciar o player agora.",Toast.LENGTH_SHORT).show();}}
    private void showOfflineLibrary(){if(musicScroll==null||listBox==null||root==null)return;listBox.post(()->{try{Rect r=new Rect();listBox.getDrawingRect(r);root.offsetDescendantRectToMyCoords(listBox,r);musicScroll.smoothScrollTo(0,Math.max(0,r.top-dp(12)));}catch(Throwable ignored){musicScroll.fullScroll(View.FOCUS_DOWN);}});}
    private void openDownloads(){Intent i=new Intent(this,MainActivity.class);i.putExtra("open","library");startActivity(i);}
    private void openStorage(){startActivity(new Intent(this,MusicStorageActivity.class));}

    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);t.setLineSpacing(0,1.06f);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.12f);return t;}
    private Button small(String v){Button b=control(v,false);b.setTextSize(9);return b;}private Button primary(String v){return control(v,true);}private Button control(String v,boolean pri){Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setTextColor(TEXT);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextSize(12);b.setStateListAnimator(null);b.setBackground(panel(pri?RED:Color.rgb(35,18,22),14,pri?0:BORDER));return b;}
    private GradientDrawable panel(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}private void margins(View v,int l,int t,int r,int b){ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)v.getLayoutParams();p.setMargins(l,t,r,b);v.setLayoutParams(p);}
}
