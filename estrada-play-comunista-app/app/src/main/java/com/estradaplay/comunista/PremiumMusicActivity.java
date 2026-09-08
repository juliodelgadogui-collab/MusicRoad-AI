package com.estradaplay.comunista;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Estrada Play Premium music library. */
public final class PremiumMusicActivity extends ComponentActivity {
    private static final int REQ_AUDIO = 5315;
    private static final String ALL = "__ALL__";

    private EstradaTheme theme;
    private FrameLayout frame;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<Track> all = new ArrayList<>();
    private final ArrayList<Track> visible = new ArrayList<>();
    private String folder = ALL;
    private String query = "";
    private LinearLayout folderBar;
    private TextView count, nowTitle, nowArtist, nowState;
    private Button playToggle;
    private EditText search;
    private TrackAdapter adapter;
    private boolean playerRegistered;

    private final BroadcastReceiver playerState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String title = i.getStringExtra("title");
            String artist = i.getStringExtra("artist");
            String state = i.getStringExtra("state");
            boolean playing = i.getBooleanExtra("playing", false);
            if (nowTitle != null) nowTitle.setText(empty(title) ? "Escolha uma música" : title.trim());
            if (nowArtist != null) nowArtist.setText(empty(artist) ? "Biblioteca local" : artist.trim());
            if (nowState != null) {
                nowState.setText(playing ? "● TOCANDO" : (empty(state) ? "PRONTO" : state.toUpperCase(Locale.ROOT)));
                nowState.setTextColor(playing ? theme.success : theme.muted);
            }
            if (playToggle != null) playToggle.setText(playing ? "Ⅱ" : "▶");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        theme = EstradaTheme.get(this);
        build();
        registerPlayer();
        load(false);
        queryPlayer();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        theme = EstradaTheme.get(this);
        build();
        render(new ArrayList<>(all));
        queryPlayer();
    }

    @Override protected void onDestroy() {
        if (playerRegistered) try { unregisterReceiver(playerState); } catch (Throwable ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        frame = new FrameLayout(this);
        frame.setBackgroundColor(theme.background);
        setContentView(UnifiedAppShell.wrap(this, "music", frame));

        boolean landscape = getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels;
        LinearLayout root = landscape ? PremiumUi.row(this) : PremiumUi.col(this);
        root.setPadding(dp(14), dp(12), dp(14), dp(14));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout player = playerCard();
        LinearLayout library = libraryCard();
        if (landscape) {
            root.addView(player, new LinearLayout.LayoutParams(0, -1, .34f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, .66f); lp.setMargins(dp(10),0,0,0); root.addView(library, lp);
        } else {
            root.addView(player, new LinearLayout.LayoutParams(-1, dp(164)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f); lp.setMargins(0,dp(10),0,0); root.addView(library, lp);
        }
    }

    private LinearLayout playerCard() {
        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(15), dp(13), dp(15), dp(12));
        card.setBackground(PremiumUi.gradient(this, theme.surfaceAlt, blend(theme.surfaceAlt, theme.primary, .16f), theme.radiusDp+3));

        LinearLayout top = PremiumUi.row(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView art = PremiumUi.text(this, "♪", 28, theme.secondary, true); art.setGravity(Gravity.CENTER);
        art.setBackground(PremiumUi.gradient(this, PremiumUi.withAlpha(theme.primary,190), PremiumUi.withAlpha(theme.secondary,150), 18));
        top.addView(art, new LinearLayout.LayoutParams(dp(62), dp(62)));
        LinearLayout meta = PremiumUi.col(this); meta.setGravity(Gravity.CENTER_VERTICAL);
        nowState = PremiumUi.overline(this, "PRONTO", theme.muted); meta.addView(nowState);
        nowTitle = PremiumUi.text(this, "Escolha uma música", 18, theme.text, true); nowTitle.setSingleLine(true); meta.addView(nowTitle);
        nowArtist = PremiumUi.text(this, "Biblioteca local", 10, theme.muted, false); nowArtist.setSingleLine(true); meta.addView(nowArtist);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,-2,1f); mp.setMargins(dp(12),0,0,0); top.addView(meta,mp);
        card.addView(top);

        View spacer = new View(this); card.addView(spacer,new LinearLayout.LayoutParams(1,0,1f));
        LinearLayout controls = PremiumUi.row(this); controls.setGravity(Gravity.CENTER);
        Button prev = control("‹",false), next = control("›",false); playToggle = control("▶",true);
        controls.addView(prev,new LinearLayout.LayoutParams(dp(54),dp(48)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(72),dp(48)); pp.setMargins(dp(8),0,dp(8),0); controls.addView(playToggle,pp);
        controls.addView(next,new LinearLayout.LayoutParams(dp(54),dp(48)));
        card.addView(controls);
        prev.setOnClickListener(v->command(PlayerService.ACTION_PREVIOUS));
        playToggle.setOnClickListener(v->command(PlayerService.ACTION_TOGGLE));
        next.setOnClickListener(v->command(PlayerService.ACTION_NEXT));
        return card;
    }

    private LinearLayout libraryCard() {
        LinearLayout box = PremiumUi.col(this);
        box.setPadding(dp(14),dp(12),dp(14),dp(12));
        box.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp+3));

        LinearLayout head = PremiumUi.row(this); head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = PremiumUi.col(this); words.addView(PremiumUi.overline(this,"NO APARELHO",theme.secondary));
        count = PremiumUi.text(this,"Biblioteca local",17,theme.text,true); words.addView(count);
        head.addView(words,new LinearLayout.LayoutParams(0,-2,1f));
        Button storage = control("ARQUIVOS",false); storage.setTextSize(9); storage.setOnClickListener(v->startActivity(new Intent(this,MusicStorageActivity.class)));
        head.addView(storage,new LinearLayout.LayoutParams(dp(88),dp(42)));
        box.addView(head);

        search = new EditText(this); search.setSingleLine(true); search.setHint("Buscar música, artista ou pasta"); search.setHintTextColor(theme.muted); search.setTextColor(theme.text); search.setTextSize(14); search.setPadding(dp(14),0,dp(14),0);
        search.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1,dp(50)); sp.setMargins(0,dp(10),0,0); box.addView(search,sp);
        search.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int st,int c,int after){}@Override public void onTextChanged(CharSequence s,int st,int before,int c){}@Override public void afterTextChanged(Editable e){query=e==null?"":e.toString();filter();}});

        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); folderBar = PremiumUi.row(this); folderBar.setGravity(Gravity.CENTER_VERTICAL); hs.addView(folderBar,new HorizontalScrollView.LayoutParams(-2,-1));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1,dp(48)); fp.setMargins(0,dp(7),0,0); box.addView(hs,fp);

        LinearLayout actions = PremiumUi.row(this);
        Button downloads = control("BAIXAR",false); downloads.setTextSize(9); downloads.setOnClickListener(v->{Intent i=new Intent(this,MainActivity.class).putExtra("open","library");startActivity(i);});
        actions.addView(downloads,new LinearLayout.LayoutParams(0,dp(42),1f));
        Button refresh = control(PhoneMp3Store.hasPermission(this)?"ATUALIZAR MP3":"PERMITIR MÚSICAS",false); refresh.setTextSize(9);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(42),1f);rp.setMargins(dp(8),0,0,0);actions.addView(refresh,rp);
        refresh.setOnClickListener(v->{if(PhoneMp3Store.hasPermission(this)){PhoneMp3Store.invalidate(this);load(true);}else requestAudio();});
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.setMargins(0,0,0,dp(7));box.addView(actions,ap);

        ListView list = new ListView(this); list.setDividerHeight(0); list.setBackgroundColor(Color.TRANSPARENT); list.setCacheColorHint(Color.TRANSPARENT); list.setFastScrollEnabled(true); adapter=new TrackAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((p,v,pos,id)->{Track t=adapter.getItem(pos);if(t!=null)play(t);});
        box.addView(list,new LinearLayout.LayoutParams(-1,0,1f));
        return box;
    }

    private void load(boolean fresh) {
        if (io.isShutdown()) return;
        io.execute(()->{
            List<Track> own=FastMusicLibrary.downloadedTracks(this);
            List<Track> merged=PhoneMp3Store.mergeCached(this,own);
            runOnUiThread(()->render(merged));
            if(PhoneMp3Store.hasPermission(this)&&(fresh||PhoneMp3Store.needsRefresh(this))){
                List<Track> updated=PhoneMp3Store.mergeFresh(this,own);
                runOnUiThread(()->render(updated));
            }
        });
    }

    private void render(List<Track> tracks) {
        all.clear(); if(tracks!=null)for(Track t:tracks)if(t!=null)all.add(t);
        all.sort((x,y)->{int f=fold(LibraryStore.folderKey(x)).compareTo(fold(LibraryStore.folderKey(y)));if(f!=0)return f;int n=fold(x.title).compareTo(fold(y.title));return n!=0?n:fold(x.artist).compareTo(fold(y.artist));});
        rebuildFolders(); filter();
    }

    private void rebuildFolders() {
        if(folderBar==null)return;folderBar.removeAllViews();addFolder("Todas",ALL);
        TreeSet<String> names=new TreeSet<>((x,y)->fold(x).compareTo(fold(y)));for(Track t:all)names.add(LibraryStore.folderKey(t));for(String f:names)addFolder(shortFolder(f),f);
    }

    private void addFolder(String label,String key){boolean active=key.equals(folder);Button b=PremiumUi.button(this,label,active);b.setTextSize(10);if(!active)b.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,13));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(38));p.setMargins(0,dp(5),dp(7),dp(5));folderBar.addView(b,p);b.setOnClickListener(v->{folder=key;rebuildFolders();filter();});}

    private void filter(){if(adapter==null)return;String q=fold(query);visible.clear();for(Track t:all){String f=LibraryStore.folderKey(t);if(!ALL.equals(folder)&&!folder.equals(f))continue;if(!q.isEmpty()&&!fold(t.title+" "+t.artist+" "+t.album+" "+f).contains(q))continue;visible.add(t);}adapter.notifyDataSetChanged();if(count!=null)count.setText(visible.size()+" "+(visible.size()==1?"música":"músicas"));}

    private void play(Track t){try{String token=PlayerService.stageQueue(this,new ArrayList<>(visible));Intent i=new Intent(this,PlayerService.class).setAction(PlayerService.ACTION_PLAY_TRACK).putExtra(PlayerService.EXTRA_KEY,t.key()).putExtra(PlayerService.EXTRA_FOLDER,ALL.equals(folder)?"__ALL__":folder).putExtra(PlayerService.EXTRA_TRACK_JSON,t.toStored().toString());if(token!=null&&!token.isEmpty())i.putExtra(PlayerService.EXTRA_QUEUE_TOKEN,token);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Throwable e){Toast.makeText(this,"Não consegui tocar esta música.",Toast.LENGTH_SHORT).show();}}

    private void command(String action){try{Intent i=new Intent(this,PlayerService.class).setAction(action);if(Build.VERSION.SDK_INT>=26&&PlayerService.ACTION_PLAY_TRACK.equals(action))startForegroundService(i);else startService(i);}catch(Throwable ignored){}}
    private void queryPlayer(){try{startService(new Intent(this,PlayerService.class).setAction(PlayerService.ACTION_QUERY_STATE));}catch(Throwable ignored){}}
    private void registerPlayer(){if(playerRegistered)return;InternalBroadcasts.register(this,playerState,new IntentFilter(PlayerService.ACTION_STATE));playerRegistered=true;}

    private void requestAudio(){if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO},REQ_AUDIO);else if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},REQ_AUDIO);else load(true);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==REQ_AUDIO&&PhoneMp3Store.hasPermission(this)){PhoneMp3Store.invalidate(this);load(true);}else if(requestCode==REQ_AUDIO)Toast.makeText(this,"Sem permissão, ficam disponíveis os downloads do Estrada Play.",Toast.LENGTH_LONG).show();}

    private Button control(String text,boolean primary){Button b=PremiumUi.button(this,text,primary);return b;}

    private final class TrackAdapter extends BaseAdapter {
        @Override public int getCount(){return visible.size();}
        @Override public Track getItem(int position){return position>=0&&position<visible.size()?visible.get(position):null;}
        @Override public long getItemId(int position){Track t=getItem(position);return t==null?position:t.key().hashCode();}
        @Override public View getView(int position,View convertView,android.view.ViewGroup parent){Track t=getItem(position);LinearLayout row=PremiumUi.row(PremiumMusicActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(7),dp(6),dp(7));TextView art=PremiumUi.text(PremiumMusicActivity.this,"♪",18,theme.secondary,true);art.setGravity(Gravity.CENTER);art.setBackground(PremiumUi.panel(PremiumMusicActivity.this,PremiumUi.withAlpha(theme.primary,24),PremiumUi.withAlpha(theme.primary,75),14));row.addView(art,new LinearLayout.LayoutParams(dp(46),dp(46)));LinearLayout meta=PremiumUi.col(PremiumMusicActivity.this);TextView title=PremiumUi.text(PremiumMusicActivity.this,t==null?"":t.title,14,theme.text,true);title.setSingleLine(true);meta.addView(title);TextView sub=PremiumUi.text(PremiumMusicActivity.this,t==null?"":t.artist+" · "+shortFolder(LibraryStore.folderKey(t)),10,theme.muted,false);sub.setSingleLine(true);meta.addView(sub);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,-2,1f);mp.setMargins(dp(10),0,dp(6),0);row.addView(meta,mp);TextView go=PremiumUi.text(PremiumMusicActivity.this,"▶",14,theme.primary,true);go.setGravity(Gravity.CENTER);row.addView(go,new LinearLayout.LayoutParams(dp(42),dp(42)));return row;}
    }

    private static String shortFolder(String raw){String s=raw==null?"":raw.trim();if(s.isEmpty())return "Sem pasta";String[] p=s.replace('\\','/').split("/");return p.length==0?s:p[p.length-1];}
    private static String fold(String raw){String n=Normalizer.normalize(raw==null?"":raw,Normalizer.Form.NFD).replaceAll("\\p{M}+","");return n.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();}
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private int dp(float v){return PremiumUi.dp(this,v);}
    private static int blend(int a,int b,float x){x=Math.max(0f,Math.min(1f,x));return Color.rgb(Math.round(Color.red(a)*(1-x)+Color.red(b)*x),Math.round(Color.green(a)*(1-x)+Color.green(b)*x),Math.round(Color.blue(a)*(1-x)+Color.blue(b)*x));}
}
