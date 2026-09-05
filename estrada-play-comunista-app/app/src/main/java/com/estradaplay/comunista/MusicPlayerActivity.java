package com.estradaplay.comunista;

import android.Manifest;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.activity.ComponentActivity;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated music surface. Opening Music never redirects to the download manager. */
public final class MusicPlayerActivity extends ComponentActivity {
    private static final int REQ_DEVICE_AUDIO = 4315;
    private static final String PERM_PREFS = "epc_music_permission_v2315";
    private static final String ALL_FOLDERS = "__ALL__";

    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(29,14,18),BORDER=Color.rgb(76,38,44);
    private final int TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76);
    private final ExecutorService io=Executors.newSingleThreadExecutor();

    private LinearLayout root, folderBar;
    private TextView title,artist,state,count,sourceNote;
    private EditText searchInput;
    private Button permissionButton;
    private ListView trackList;
    private TrackAdapter adapter;
    private final ArrayList<Track> allTracks = new ArrayList<>();
    private boolean registered;
    private String selectedFolder = ALL_FOLDERS;
    private String searchQuery = "";

    private final BroadcastReceiver playerState=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String t=i.getStringExtra("title"),a=i.getStringExtra("artist"),s=i.getStringExtra("state");
        boolean playing=i.getBooleanExtra("playing",false);
        if(title!=null)title.setText(t==null||t.trim().isEmpty()?"Escolha uma música":t.trim());
        if(artist!=null)artist.setText(a==null||a.trim().isEmpty()?"Biblioteca local":a.trim());
        if(state!=null){state.setText(playing?"● TOCANDO":(s==null||s.isEmpty()?"PRONTO":s.toUpperCase(Locale.ROOT)));state.setTextColor(playing?GREEN:MUTED);}
    }};

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        PhoneMp3Store.installObserver(this);
        build();
        loadTracks(false);
        register();
        queryPlayer();
        maybeAskAudioPermissionOnce();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c){
        super.onConfigurationChanged(c);
        build();
        renderTracks(new ArrayList<>(allTracks));
        queryPlayer();
    }

    @Override protected void onDestroy(){
        if(registered)try{unregisterReceiver(playerState);}catch(Throwable ignored){}
        io.shutdownNow();
        super.onDestroy();
    }

    // MUSIC_LIBRARY_UX_V2316: one real scrollable ListView, local search and folder grouping.
    // There is no ScrollView wrapped around the song list, so swipe/drag stays smooth even with thousands of tracks.
    private void build(){
        FrameLayout frame=new FrameLayout(this);
        frame.setBackgroundColor(BG);
        setContentView(UnifiedAppShell.wrap(this,"music",frame));

        root=col();
        root.setPadding(dp(18),dp(12),dp(18),dp(14));
        frame.addView(root,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout head=row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button back=small("‹ MAPA");
        head.addView(back,new LinearLayout.LayoutParams(dp(92),dp(46)));
        back.setOnClickListener(v->finish());
        LinearLayout brand=col();
        brand.addView(over("ÁUDIO DE BORDO",RED));
        brand.addView(text("Música",27,TEXT,true));
        head.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button search=small("BUSCAR");
        head.addView(search,new LinearLayout.LayoutParams(dp(92),dp(46)));
        search.setOnClickListener(v->focusSearch());
        root.addView(head,new LinearLayout.LayoutParams(-1,-2));

        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout body=landscape?row():col();
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,0,1);
        bp.setMargins(0,dp(12),0,0);
        root.addView(body,bp);

        LinearLayout player=playerCard();
        if(landscape){
            LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,-1,.40f);
            body.addView(player,pp);
        }else{
            body.addView(player,new LinearLayout.LayoutParams(-1,dp(245)));
        }

        LinearLayout library=libraryCard();
        if(landscape){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,.60f);
            lp.setMargins(dp(12),0,0,0);
            body.addView(library,lp);
        }else{
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,0,1);
            lp.setMargins(0,dp(10),0,0);
            body.addView(library,lp);
        }
    }

    private LinearLayout playerCard(){
        LinearLayout p=col();
        p.setPadding(dp(20),dp(18),dp(20),dp(16));
        p.setBackground(panel(SURFACE2,20,RED));
        LinearLayout top=row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark=text("♪",30,GOLD,true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(panel(Color.rgb(50,19,25),16,Color.rgb(113,47,55)));
        top.addView(mark,new LinearLayout.LayoutParams(dp(64),dp(64)));
        LinearLayout meta=col();
        state=over("PRONTO",MUTED);
        meta.addView(state);
        title=text("Escolha uma música",22,TEXT,true);
        title.setMaxLines(2);
        meta.addView(title);
        artist=text("Biblioteca local",12,MUTED,false);
        meta.addView(artist);
        top.addView(meta,new LinearLayout.LayoutParams(0,-2,1));
        margins(meta,dp(14),0,0,0);
        p.addView(top);

        View spacer=new View(this);
        p.addView(spacer,new LinearLayout.LayoutParams(1,0,1));

        LinearLayout controls=row();
        controls.setGravity(Gravity.CENTER);
        Button prev=control("‹‹",false),play=control("▶ Ⅱ",true),next=control("››",false);
        controls.addView(prev,new LinearLayout.LayoutParams(dp(64),dp(56)));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(96),dp(56));
        cp.setMargins(dp(10),0,dp(10),0);
        controls.addView(play,cp);
        controls.addView(next,new LinearLayout.LayoutParams(dp(64),dp(56)));
        p.addView(controls);
        prev.setOnClickListener(v->command(PlayerService.ACTION_PREVIOUS,null,null));
        play.setOnClickListener(v->command(PlayerService.ACTION_TOGGLE,null,null));
        next.setOnClickListener(v->command(PlayerService.ACTION_NEXT,null,null));

        TextView note=text("Biblioteca 100% local: busca e pastas usam o índice salvo no aparelho; tocar abre apenas o MP3 escolhido.",10,MUTED,false);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);
        np.setMargins(0,dp(12),0,0);
        p.addView(note,np);
        return p;
    }

    private LinearLayout libraryCard(){
        LinearLayout box=col();
        box.setPadding(dp(14),dp(12),dp(14),dp(12));
        box.setBackground(panel(SURFACE,18,BORDER));

        LinearLayout lh=row();
        lh.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout lt=col();
        lt.addView(over("NO APARELHO",MUTED));
        count=text("Biblioteca local",17,TEXT,true);
        lt.addView(count);
        lh.addView(lt,new LinearLayout.LayoutParams(0,-2,1));
        Button cleanup=small("LIMPEZA");
        lh.addView(cleanup,new LinearLayout.LayoutParams(dp(86),dp(42)));
        cleanup.setOnClickListener(v->openStorage());
        box.addView(lh);

        searchInput=new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Buscar música, artista ou pasta");
        searchInput.setHintTextColor(MUTED);
        searchInput.setTextColor(TEXT);
        searchInput.setTextSize(14);
        searchInput.setPadding(dp(14),0,dp(14),0);
        searchInput.setBackground(panel(Color.rgb(28,15,19),14,BORDER));
        searchInput.setSelectAllOnFocus(false);
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-1,dp(50));
        qp.setMargins(0,dp(10),0,0);
        box.addView(searchInput,qp);
        searchInput.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){}
            @Override public void afterTextChanged(Editable e){searchQuery=e==null?"":e.toString();applyFilters();}
        });

        HorizontalScrollView folders=new HorizontalScrollView(this);
        folders.setHorizontalScrollBarEnabled(false);
        folders.setFillViewport(false);
        folderBar=row();
        folderBar.setGravity(Gravity.CENTER_VERTICAL);
        folders.addView(folderBar,new HorizontalScrollView.LayoutParams(-2,-1));
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,dp(48));
        fp.setMargins(0,dp(8),0,0);
        box.addView(folders,fp);

        LinearLayout actions=row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        permissionButton=small("ATUALIZAR ÍNDICE MP3");
        actions.addView(permissionButton,new LinearLayout.LayoutParams(0,dp(44),1));
        permissionButton.setOnClickListener(v->{
            if(PhoneMp3Store.hasPermission(this)){PhoneMp3Store.invalidate(this);loadTracks(true);}
            else requestDeviceAudio();
        });
        box.addView(actions);

        sourceNote=text("",10,MUTED,false);
        LinearLayout.LayoutParams sn=new LinearLayout.LayoutParams(-1,-2);
        sn.setMargins(0,dp(6),0,dp(6));
        box.addView(sourceNote,sn);

        trackList=new ListView(this);
        trackList.setBackgroundColor(Color.TRANSPARENT);
        trackList.setDividerColor(BORDER);
        trackList.setDividerHeight(dp(1));
        trackList.setCacheColorHint(Color.TRANSPARENT);
        trackList.setFastScrollEnabled(true);
        trackList.setVerticalScrollBarEnabled(true);
        trackList.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        adapter=new TrackAdapter();
        trackList.setAdapter(adapter);
        trackList.setOnItemClickListener((parent,view,position,id)->{
            DisplayItem item=adapter.item(position);
            if(item==null||item.track==null)return;
            playTrack(item.track);
        });
        box.addView(trackList,new LinearLayout.LayoutParams(-1,0,1));
        updatePermissionControls();
        return box;
    }

    // Render the persistent local DB first. A MediaStore refresh, when needed, happens only
    // after the list is visible and never blocks searching, folder switching or scrolling.
    private void loadTracks(boolean forceRefresh){if(io.isShutdown())return;io.execute(()->{
        List<Track> appTracks=FastMusicLibrary.downloadedTracks(this);
        List<Track> cached=PhoneMp3Store.mergeCached(this,appTracks);
        runOnUiThread(()->renderTracks(cached));

        if(!PhoneMp3Store.hasPermission(this))return;
        if(!forceRefresh&&!PhoneMp3Store.needsRefresh(this))return;

        runOnUiThread(()->{if(count!=null)count.setText(cached.size()+" músicas · atualizando índice em segundo plano");});
        List<Track> fresh=PhoneMp3Store.mergeFresh(this,appTracks);
        boolean timeout=PhoneMp3Store.lastTimedOut();
        runOnUiThread(()->{
            renderTracks(fresh);
            if(timeout&&count!=null)count.setText(fresh.size()+" músicas · índice anterior mantido");
        });
    });}

    private void renderTracks(List<Track> tracks){
        allTracks.clear();
        if(tracks!=null)for(Track t:tracks)if(t!=null)allTracks.add(t);
        rebuildFolderBar();
        updatePermissionControls();
        applyFilters();
    }

    private void rebuildFolderBar(){
        if(folderBar==null)return;
        TreeSet<String> folders=new TreeSet<>((a,b)->{
            int c=fold(a).compareTo(fold(b));
            return c!=0?c:a.compareTo(b);
        });
        for(Track t:allTracks)folders.add(LibraryStore.folderKey(t));
        if(!ALL_FOLDERS.equals(selectedFolder)&&!folders.contains(selectedFolder))selectedFolder=ALL_FOLDERS;

        folderBar.removeAllViews();
        addFolderChip("TODAS",ALL_FOLDERS,allTracks.size());
        for(String folder:folders){
            int n=0;
            for(Track t:allTracks)if(folder.equals(LibraryStore.folderKey(t)))n++;
            addFolderChip(folderLabel(folder),folder,n);
        }
    }

    private void addFolderChip(String label,String key,int total){
        boolean active=key.equals(selectedFolder);
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(label+"  "+total);
        b.setTextSize(10);
        b.setTextColor(TEXT);
        b.setTypeface(Typeface.DEFAULT,active?Typeface.BOLD:Typeface.NORMAL);
        b.setPadding(dp(12),0,dp(12),0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setBackground(panel(active?RED:Color.rgb(34,18,22),12,active?0:BORDER));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(38));
        p.setMargins(0,dp(4),dp(7),dp(4));
        folderBar.addView(b,p);
        b.setOnClickListener(v->{selectedFolder=key;rebuildFolderBar();applyFilters();});
    }

    private void applyFilters(){
        if(adapter==null)return;
        String q=fold(searchQuery);
        ArrayList<Track> visible=new ArrayList<>();
        for(Track t:allTracks){
            if(t==null)continue;
            String folder=LibraryStore.folderKey(t);
            if(!ALL_FOLDERS.equals(selectedFolder)&&!selectedFolder.equals(folder))continue;
            if(!q.isEmpty()){
                String hay=fold(t.title+" "+t.artist+" "+t.album+" "+folder);
                if(!hay.contains(q))continue;
            }
            visible.add(t);
        }
        visible.sort((a,b)->{
            int f=fold(LibraryStore.folderKey(a)).compareTo(fold(LibraryStore.folderKey(b)));
            if(f!=0)return f;
            int t=fold(a.title).compareTo(fold(b.title));
            if(t!=0)return t;
            return fold(a.artist).compareTo(fold(b.artist));
        });

        adapter.setTracks(visible,true);
        int folders=countFolders(visible);
        if(count!=null){
            if(!q.isEmpty()||!ALL_FOLDERS.equals(selectedFolder))count.setText(visible.size()+" de "+allTracks.size()+" músicas · "+folders+" "+(folders==1?"pasta":"pastas"));
            else count.setText(allTracks.size()+" "+(allTracks.size()==1?"música":"músicas")+" · "+folders+" "+(folders==1?"pasta":"pastas"));
        }
    }

    private int countFolders(List<Track> tracks){
        HashSet<String> set=new HashSet<>();
        if(tracks!=null)for(Track t:tracks)if(t!=null)set.add(LibraryStore.folderKey(t));
        return set.size();
    }

    private void updatePermissionControls(){
        if(permissionButton==null||sourceNote==null)return;
        boolean allowed=PhoneMp3Store.hasPermission(this);
        permissionButton.setText(allowed?"ATUALIZAR ÍNDICE MP3":"PERMITIR ACESSO ÀS MÚSICAS");
        permissionButton.setBackground(panel(allowed?Color.rgb(35,18,22):RED,12,allowed?BORDER:0));
        sourceNote.setText(allowed
                ?"Deslize a lista normalmente. Use a busca acima ou toque numa pasta para filtrar. O índice continua 100% local."
                :"Autorize Músicas e áudio uma vez. Depois busca, pastas e reprodução funcionam pelo índice local, sem servidor.");
    }

    private void playTrack(Track t){
        if(t==null)return;
        String queueFolder=ALL_FOLDERS.equals(selectedFolder)?"__ALL__":selectedFolder;
        command(PlayerService.ACTION_PLAY_TRACK,t.key(),queueFolder);
    }

    private void focusSearch(){
        if(searchInput==null)return;
        searchInput.requestFocus();
        searchInput.setSelection(searchInput.getText()==null?0:searchInput.getText().length());
        try{
            InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(imm!=null)imm.showSoftInput(searchInput,InputMethodManager.SHOW_IMPLICIT);
        }catch(Throwable ignored){}
    }

    private static String folderLabel(String raw){
        String v=raw==null?"":raw.trim();
        if(v.isEmpty())return "Sem pasta";
        return v.replace(" / "," › ").replace("/"," › ");
    }

    private static String fold(String raw){
        String n=Normalizer.normalize(raw==null?"":raw,Normalizer.Form.NFD).replaceAll("\\p{M}+","");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
    }

    private void maybeAskAudioPermissionOnce(){
        if(PhoneMp3Store.hasPermission(this)||Build.VERSION.SDK_INT<23)return;
        android.content.SharedPreferences p=getSharedPreferences(PERM_PREFS,MODE_PRIVATE);
        if(p.getBoolean("asked",false))return;
        p.edit().putBoolean("asked",true).apply();
        if(root!=null)root.postDelayed(this::requestDeviceAudio,350);
    }

    private void requestDeviceAudio(){
        if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO},REQ_DEVICE_AUDIO);
        else if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},REQ_DEVICE_AUDIO);
        else loadTracks(true);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode!=REQ_DEVICE_AUDIO)return;
        if(PhoneMp3Store.hasPermission(this)){
            Toast.makeText(this,"Acesso liberado. Criando índice local de MP3.",Toast.LENGTH_SHORT).show();
            PhoneMp3Store.invalidate(this);
            updatePermissionControls();
            loadTracks(true);
        }else{
            renderTracks(PhoneMp3Store.mergeCached(this,FastMusicLibrary.downloadedTracks(this)));
            Toast.makeText(this,"Sem a permissão de músicas, o Estrada Play só mostra os próprios downloads.",Toast.LENGTH_LONG).show();
        }
    }

    private final class TrackAdapter extends BaseAdapter {
        private final ArrayList<DisplayItem> items=new ArrayList<>();

        void setTracks(List<Track> tracks,boolean headers){
            items.clear();
            String lastFolder=null;
            if(tracks!=null){
                for(Track t:tracks){
                    if(t==null)continue;
                    String folder=LibraryStore.folderKey(t);
                    if(headers&&!folder.equals(lastFolder)){
                        items.add(DisplayItem.header(folder));
                        lastFolder=folder;
                    }
                    items.add(DisplayItem.track(t));
                }
            }
            notifyDataSetChanged();
        }

        DisplayItem item(int position){return position>=0&&position<items.size()?items.get(position):null;}
        @Override public int getCount(){return items.size();}
        @Override public Object getItem(int position){return item(position);}
        @Override public long getItemId(int position){DisplayItem i=item(position);return i!=null&&i.track!=null?i.track.key().hashCode():position;}
        @Override public int getViewTypeCount(){return 2;}
        @Override public int getItemViewType(int position){DisplayItem i=item(position);return i!=null&&i.track!=null?1:0;}
        @Override public boolean isEnabled(int position){DisplayItem i=item(position);return i!=null&&i.track!=null;}

        @Override public View getView(int position,View convertView,android.view.ViewGroup parent){
            DisplayItem item=item(position);
            if(item==null)return new View(MusicPlayerActivity.this);
            if(item.track==null)return headerView(item.header,convertView);
            return trackView(item.track,convertView);
        }

        private View headerView(String folder,View convertView){
            TextView h;
            if(convertView instanceof TextView){h=(TextView)convertView;}
            else{
                h=text("",10,GOLD,true);
                h.setLetterSpacing(.08f);
                h.setPadding(dp(10),dp(13),dp(8),dp(7));
                h.setBackgroundColor(Color.rgb(14,8,10));
            }
            h.setText(folderLabel(folder).toUpperCase(Locale.ROOT));
            return h;
        }

        private View trackView(Track t,View convertView){
            TrackHolder holder;
            LinearLayout rowView;
            if(convertView instanceof LinearLayout && convertView.getTag() instanceof TrackHolder){
                rowView=(LinearLayout)convertView;
                holder=(TrackHolder)convertView.getTag();
            }else{
                rowView=row();
                rowView.setGravity(Gravity.CENTER_VERTICAL);
                rowView.setPadding(dp(8),dp(7),dp(6),dp(7));
                TextView icon=text("♪",18,GOLD,true);
                icon.setGravity(Gravity.CENTER);
                rowView.addView(icon,new LinearLayout.LayoutParams(dp(38),dp(46)));
                LinearLayout meta=col();
                TextView tt=text("",14,TEXT,true);
                tt.setMaxLines(1);
                TextView aa=text("",10,MUTED,false);
                aa.setMaxLines(1);
                meta.addView(tt);
                meta.addView(aa);
                rowView.addView(meta,new LinearLayout.LayoutParams(0,-2,1));
                Button go=control("▶",true);
                rowView.addView(go,new LinearLayout.LayoutParams(dp(48),dp(44)));
                holder=new TrackHolder(tt,aa,go);
                rowView.setTag(holder);
            }
            holder.title.setText(t.title);
            holder.meta.setText(t.artist+" · "+folderLabel(LibraryStore.folderKey(t)));
            holder.play.setOnClickListener(v->playTrack(t));
            rowView.setOnClickListener(v->playTrack(t));
            return rowView;
        }
    }

    private static final class TrackHolder{
        final TextView title,meta;
        final Button play;
        TrackHolder(TextView title,TextView meta,Button play){this.title=title;this.meta=meta;this.play=play;}
    }

    private static final class DisplayItem{
        final String header;
        final Track track;
        private DisplayItem(String header,Track track){this.header=header;this.track=track;}
        static DisplayItem header(String value){return new DisplayItem(value,null);}
        static DisplayItem track(Track value){return new DisplayItem(null,value);}
    }

    private void register(){if(registered)return;IntentFilter f=new IntentFilter(PlayerService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(playerState,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(playerState,f);registered=true;}
    private void queryPlayer(){try{startService(new Intent(this,PlayerService.class).setAction(PlayerService.ACTION_QUERY_STATE));}catch(Throwable ignored){}}
    private void command(String action,String key,String folder){try{Intent i=new Intent(this,PlayerService.class).setAction(action);if(key!=null)i.putExtra(PlayerService.EXTRA_KEY,key);if(folder!=null)i.putExtra(PlayerService.EXTRA_FOLDER,folder);if(Build.VERSION.SDK_INT>=26&&PlayerService.ACTION_PLAY_TRACK.equals(action))startForegroundService(i);else startService(i);}catch(Throwable e){Toast.makeText(this,"Não consegui iniciar o player agora.",Toast.LENGTH_SHORT).show();}}
    private void openDownloads(){Intent i=new Intent(this,MainActivity.class);i.putExtra("open","library");startActivity(i);}
    private void openStorage(){startActivity(new Intent(this,MusicStorageActivity.class));}

    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);t.setLineSpacing(0,1.06f);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.12f);return t;}
    private Button small(String v){Button b=control(v,false);b.setTextSize(9);return b;}
    private Button control(String v,boolean pri){Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setTextColor(TEXT);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextSize(12);b.setStateListAnimator(null);b.setBackground(panel(pri?RED:Color.rgb(35,18,22),14,pri?0:BORDER));return b;}
    private GradientDrawable panel(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void margins(View v,int l,int t,int r,int b){ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)v.getLayoutParams();p.setMargins(l,t,r,b);v.setLayoutParams(p);}
}
