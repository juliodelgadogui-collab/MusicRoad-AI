package com.estradaplay.patriota;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Storage repair/cleanup surface for offline music. Destructive actions require confirmation. */
public final class MusicStorageActivity extends ComponentActivity {
    private static final int REQ_AUDIO = 4202;
    private static final int REQ_DELETE_LEGACY = 4203;
    private final int BG=Color.rgb(7,4,5), SURFACE=Color.rgb(27,12,16), SURFACE2=Color.rgb(40,17,22), BORDER=Color.rgb(92,43,51);
    private final int TEXT=Color.rgb(247,239,226), MUTED=Color.rgb(180,154,150), RED=Color.rgb(202,18,42), GREEN=Color.rgb(58,190,119), GOLD=Color.rgb(226,184,74);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LibraryStore library;
    private LinearLayout content;
    private TextView state;
    private volatile List<LegacyItem> legacyItems=new ArrayList<>();

    @Override protected void onCreate(android.os.Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);library=new LibraryStore(this);build();refresh();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}

    private void build(){
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(BG);setContentView(UnifiedAppShell.wrap(this,"music",frame));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        content=col();content.setPadding(dp(20),dp(18),dp(20),dp(32));scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);LinearLayout titles=col();titles.addView(over("BIBLIOTECA OFFLINE",RED));titles.addView(text("Armazenamento e limpeza",28,TEXT,true));titles.addView(text("Recupere músicas já baixadas e remova cópias que não precisa mais.",12,MUTED,false));top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));Button back=button("VOLTAR",false);top.addView(back,new LinearLayout.LayoutParams(dp(108),dp(52)));back.setOnClickListener(v->finish());content.addView(top);
        state=text("Analisando armazenamento…",12,MUTED,false);LinearLayout.LayoutParams stp=new LinearLayout.LayoutParams(-1,-2);stp.setMargins(0,dp(16),0,0);content.addView(state,stp);
    }

    private void refresh(){
        setBusy("Analisando arquivos e reconstruindo o índice…");
        io.execute(()->{
            LibraryStore.ReconcileResult rec=library.reconcileOffline();
            LibraryStore.StorageStats stats=library.storageStats();
            List<LegacyItem> legacy=hasAudioPermission()?scanLegacy():new ArrayList<>();
            legacyItems=legacy;
            runOnUiThread(()->render(stats,rec,legacy));
        });
    }

    private void render(LibraryStore.StorageStats s, LibraryStore.ReconcileResult rec, List<LegacyItem> legacy){
        while(content.getChildCount()>2)content.removeViewAt(2);
        state.setText(rec.recovered>0?"✓ "+rec.recovered+" música(s) recuperada(s) automaticamente.":"Biblioteca conferida. O índice agora acompanha os arquivos reais.");
        state.setTextColor(rec.recovered>0?GREEN:MUTED);
        LinearLayout stats=col();stats.setPadding(dp(16),dp(14),dp(16),dp(14));stats.setBackground(panel(SURFACE,18,BORDER));
        stats.addView(metric("MÚSICAS RECONHECIDAS",String.valueOf(s.indexedFiles),LibraryStore.humanBytes(s.indexedBytes)));
        stats.addView(divider());stats.addView(metric("ARQUIVOS NO ESPAÇO DO APP",String.valueOf(s.diskFiles),LibraryStore.humanBytes(s.diskBytes)));
        stats.addView(divider());stats.addView(metric("ÓRFÃOS / NÃO VINCULADOS",String.valueOf(s.orphanFiles),LibraryStore.humanBytes(s.orphanBytes)));
        stats.addView(divider());stats.addView(metric("ESPAÇO LIVRE",LibraryStore.humanBytes(s.freeBytes),"no armazenamento usado pelo app"));add(stats,dp(18));

        LinearLayout safe=card();safe.addView(over("RECUPERAÇÃO",GOLD));safe.addView(text("Músicas baixadas não aparecem?",20,TEXT,true));safe.addView(text("O EPP agora verifica os arquivos reais e reconstrói o índice. Use este botão após restaurar, atualizar ou mover arquivos.",12,MUTED,false));Button reindex=button("REINDEXAR MÚSICAS AGORA",true);addTo(safe,reindex,dp(14));reindex.setOnClickListener(v->refresh());add(safe,dp(12));

        LinearLayout clean=card();clean.addView(over("LIMPEZA SEGURA",RED));clean.addView(text("Arquivos do próprio app",20,TEXT,true));clean.addView(text("Downloads novos ficam na área privada do Estrada Play. Essa área é removida pelo Android quando este pacote é desinstalado.",12,MUTED,false));Button orphan=button("REMOVER ÓRFÃOS · "+s.orphanFiles+" · "+LibraryStore.humanBytes(s.orphanBytes),false);addTo(clean,orphan,dp(14));orphan.setEnabled(s.orphanFiles>0);orphan.setOnClickListener(v->confirm("Remover arquivos órfãos?","Somente arquivos sem vínculo com nenhuma música do catálogo serão apagados.",()->io.execute(()->{int n=library.removeOrphanFiles();runOnUiThread(()->{Toast.makeText(this,n+" arquivo(s) removido(s).",Toast.LENGTH_SHORT).show();refresh();});})));
        Button all=button("APAGAR TODAS AS MÚSICAS OFFLINE",false);all.setTextColor(Color.rgb(255,121,133));addTo(clean,all,dp(10));all.setOnClickListener(v->confirm("Apagar todas as músicas offline?","Isso remove as cópias baixadas deste app. As músicas originais continuam no servidor/Drive.",()->io.execute(()->{LibraryStore.DeleteResult r=library.removeAllOffline();runOnUiThread(()->{Toast.makeText(this,"Liberado: "+LibraryStore.humanBytes(r.bytes),Toast.LENGTH_LONG).show();refresh();});})));add(clean,dp(12));

        LinearLayout old=card();old.addView(over("ARMAZENAMENTO ANTIGO",RED));old.addView(text("Cópias de versões antigas",20,TEXT,true));
        if(!hasAudioPermission()){old.addView(text("Permita acesso aos arquivos de áudio para procurar cópias antigas. A busca compara nomes e IDs com o catálogo do EPP, mesmo que a pasta tenha outro nome.",12,MUTED,false));Button p=button("PROCURAR MÚSICAS NO CELULAR",false);addTo(old,p,dp(14));p.setOnClickListener(v->requestAudioPermission());}
        else{long legacyBytes=0;for(LegacyItem x:legacy)legacyBytes+=x.size;old.addView(text(legacy.size()+" arquivo(s) antigo(s) encontrado(s) · "+LibraryStore.humanBytes(legacyBytes),14,legacy.isEmpty()?MUTED:GOLD,true));old.addView(text("Recuperar copia os arquivos reconhecidos para o espaço atual sem apagar o original. Depois de conferir o player, você pode remover as cópias antigas.",12,MUTED,false));Button recover=button("RECUPERAR MÚSICAS ANTIGAS",true);addTo(old,recover,dp(14));recover.setEnabled(!legacy.isEmpty());recover.setOnClickListener(v->migrateLegacy());Button del=button("REMOVER CÓPIAS ANTIGAS",false);del.setTextColor(Color.rgb(255,121,133));addTo(old,del,dp(10));del.setEnabled(!legacy.isEmpty());del.setOnClickListener(v->confirm("Remover cópias antigas?","Use esta opção depois de recuperar e conferir as músicas. O Android pode abrir uma confirmação do sistema.",this::requestDeleteLegacy));}add(old,dp(12));

        LinearLayout info=card();info.addView(over("IMPORTANTE",GREEN));info.addView(text("Por que isso acontecia?",18,TEXT,true));info.addView(text("A lista offline dependia do índice salvo. Se o índice fosse perdido, os arquivos continuavam ocupando espaço, mas o player mostrava 0 músicas. A versão 2.0.2 passa a reconciliar índice + arquivos automaticamente.",12,MUTED,false));add(info,dp(12));
    }

    private void migrateLegacy(){final List<LegacyItem> items=new ArrayList<>(legacyItems);if(items.isEmpty())return;setBusy("Recuperando músicas antigas…");io.execute(()->{List<Track> catalog=library.catalog();int recovered=0,unmatched=0;for(LegacyItem item:items){Track t=matchLegacy(item,catalog);if(t==null){unmatched++;continue;}File target=library.targetFile(t);try{if(!target.isFile()||target.length()<=0){File parent=target.getParentFile();if(parent!=null&&!parent.exists())parent.mkdirs();File part=new File(target.getAbsolutePath()+".migrate");try(InputStream in=getContentResolver().openInputStream(item.uri);FileOutputStream out=new FileOutputStream(part)){if(in==null)throw new Exception("sem entrada");byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);out.flush();}if(part.length()<=0){part.delete();continue;}if(target.exists())target.delete();if(!part.renameTo(target)){part.delete();continue;}}library.saveDownloaded(t,target);recovered++;}catch(Exception e){unmatched++;}}library.reconcileOffline();int ok=recovered,miss=unmatched;runOnUiThread(()->{Toast.makeText(this,"Recuperadas: "+ok+" · não identificadas: "+miss,Toast.LENGTH_LONG).show();refresh();});});}

    private Track matchLegacy(LegacyItem item,List<Track> catalog){String name=LibraryStore.normalizeFileToken(stripExt(item.name)),path=LibraryStore.normalizeFileToken(item.path);Track best=null;int bestScore=0;for(Track t:catalog){String title=LibraryStore.normalizeFileToken(t.title);if(title.length()<2)continue;int score=0;if(name.equals(title))score+=100;else if(name.endsWith(title)||name.contains(title))score+=70;String id=t.id==null?"":t.id.replaceAll("[^A-Za-z0-9_-]+","").toLowerCase(Locale.ROOT);if(!id.isEmpty()&&item.name.toLowerCase(Locale.ROOT).contains(id))score+=120;String folder=LibraryStore.normalizeFileToken(t.folderPath);if(!folder.isEmpty()&&path.contains(folder))score+=30;if(score>bestScore){best=t;bestScore=score;}}return bestScore>=70?best:null;}

    // PUBLIC_DISCOVERY_V203: public/legacy music may live in a folder that is not named EstradaPlay.
    private List<LegacyItem> scanLegacy(){
        ArrayList<LegacyItem> out=new ArrayList<>();
        List<Track> catalog=library.catalog();
        ContentResolver cr=getContentResolver();Uri base=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        ArrayList<String> cols=new ArrayList<>();cols.add(MediaStore.Audio.Media._ID);cols.add(MediaStore.Audio.Media.DISPLAY_NAME);cols.add(MediaStore.Audio.Media.SIZE);
        if(Build.VERSION.SDK_INT>=29)cols.add(MediaStore.Audio.Media.RELATIVE_PATH);else cols.add(MediaStore.Audio.Media.DATA);
        try(Cursor c=cr.query(base,cols.toArray(new String[0]),null,null,MediaStore.Audio.Media.DATE_ADDED+" DESC")){
            if(c==null)return out;
            int id=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID),name=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME),size=c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE),path=c.getColumnIndex(Build.VERSION.SDK_INT>=29?MediaStore.Audio.Media.RELATIVE_PATH:MediaStore.Audio.Media.DATA);
            while(c.moveToNext()){
                String n=c.getString(name),p=path>=0?c.getString(path):"";
                LegacyItem item=new LegacyItem(ContentUris.withAppendedId(base,c.getLong(id)),n==null?"Música":n,p==null?"":p,Math.max(0,c.getLong(size)));
                String probe=((p==null?"":p)+"/"+(n==null?"":n)).toLowerCase(Locale.ROOT).replace(" ","");
                boolean branded=probe.contains("estradaplay")||probe.contains("musicroad")||probe.contains("estradaplaypatriota");
                if(!branded && matchLegacy(item,catalog)==null)continue;
                out.add(item);if(out.size()>=5000)break;
            }
        }catch(Throwable ignored){}
        return out;
    }

    private void requestDeleteLegacy(){List<LegacyItem> items=new ArrayList<>(legacyItems);if(items.isEmpty())return;ArrayList<Uri> uris=new ArrayList<>();for(LegacyItem x:items)uris.add(x.uri);if(Build.VERSION.SDK_INT>=30){try{PendingIntent pi=MediaStore.createDeleteRequest(getContentResolver(),uris);startIntentSenderForResult(pi.getIntentSender(),REQ_DELETE_LEGACY,null,0,0,0);}catch(Throwable e){Toast.makeText(this,"Não consegui abrir a autorização de limpeza.",Toast.LENGTH_LONG).show();}}else{io.execute(()->{int n=0;for(Uri u:uris)try{n+=getContentResolver().delete(u,null,null);}catch(Throwable ignored){}int done=n;runOnUiThread(()->{Toast.makeText(this,"Removidos: "+done,Toast.LENGTH_LONG).show();refresh();});});}}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_DELETE_LEGACY){Toast.makeText(this,resultCode==RESULT_OK?"Limpeza autorizada.":"Limpeza cancelada.",Toast.LENGTH_SHORT).show();refresh();}}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQ_AUDIO)refresh();}
    private boolean hasAudioPermission(){if(Build.VERSION.SDK_INT>=33)return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)==PackageManager.PERMISSION_GRANTED;if(Build.VERSION.SDK_INT>=23)return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;return true;}
    private void requestAudioPermission(){if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO},REQ_AUDIO);else if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},REQ_AUDIO);else refresh();}
    private void confirm(String title,String msg,Runnable yes){new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setNegativeButton("CANCELAR",null).setPositiveButton("CONFIRMAR",(d,w)->yes.run()).show();}
    private void setBusy(String v){runOnUiThread(()->{if(state!=null){state.setText(v);state.setTextColor(GOLD);}});}

    private View metric(String label,String value,String sub){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);LinearLayout left=col();left.addView(over(label,MUTED));left.addView(text(sub,11,MUTED,false));r.addView(left,new LinearLayout.LayoutParams(0,-2,1));r.addView(text(value,22,TEXT,true));r.setPadding(0,dp(9),0,dp(9));return r;}
    private LinearLayout card(){LinearLayout x=col();x.setPadding(dp(17),dp(16),dp(17),dp(17));x.setBackground(panel(SURFACE,18,BORDER));return x;}
    private void add(View v,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,top,0,0);content.addView(v,p);}private void addTo(LinearLayout p,View v,int top){LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-1,dp(54));q.setMargins(0,top,0,0);p.addView(v,q);}
    private View divider(){View v=new View(this);v.setBackgroundColor(BORDER);v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));return v;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setLineSpacing(0,1.08f);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,10,c,true);t.setLetterSpacing(.12f);return t;}
    private Button button(String v,boolean primary){Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setTextColor(TEXT);b.setTextSize(11);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setStateListAnimator(null);b.setBackground(panel(primary?RED:SURFACE2,14,primary?0:BORDER));return b;}
    private GradientDrawable panel(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static String stripExt(String n){int p=n==null?-1:n.lastIndexOf('.');return p>0?n.substring(0,p):(n==null?"":n);}
    private static final class LegacyItem{final Uri uri;final String name,path;final long size;LegacyItem(Uri u,String n,String p,long s){uri=u;name=n;path=p;size=s;}}
}
