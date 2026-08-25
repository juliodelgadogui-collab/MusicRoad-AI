package com.estradaplay.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final String PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";
    private static final int REQ_NOTIFICATIONS = 701;

    private final int BG = Color.rgb(4, 9, 15);
    private final int PANEL = Color.rgb(10, 18, 28);
    private final int PANEL2 = Color.rgb(15, 27, 40);
    private final int LINE = Color.rgb(38, 55, 72);
    private final int TEXT = Color.rgb(247, 249, 252);
    private final int MUTED = Color.rgb(143, 160, 178);
    private final int ACCENT = Color.rgb(255, 116, 24);
    private final int PURPLE = Color.rgb(142, 74, 255);
    private final int GREEN = Color.rgb(51, 220, 136);
    private final int RED = Color.rgb(255, 89, 94);

    private FrameLayout root;
    private ApiClient api;
    private LibraryStore library;
    private SharedPreferences prefs;
    private JSONObject account = new JSONObject();
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean downloadInitialFlow;
    private TextView downloadTitle, downloadState;
    private ProgressBar downloadProgress;
    private TextView nowTitle, nowArtist, nowState;
    private TextView roadLiveState, roadLiveDetail;
    private TrackAdapter trackAdapter;
    private String activeMusicFolder = "__ALL__";

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int total = intent.getIntExtra("total", 0);
            int done = intent.getIntExtra("done", 0);
            int failed = intent.getIntExtra("failed", 0);
            boolean active = intent.getBooleanExtra("active", false);
            String current = intent.getStringExtra("current");
            if (downloadProgress != null) {
                downloadProgress.setIndeterminate(total <= 0 && active);
                downloadProgress.setMax(Math.max(1, total));
                downloadProgress.setProgress(Math.min(total, done + failed));
            }
            if (downloadTitle != null) downloadTitle.setText(active ? "Preparando sua biblioteca" : "Biblioteca offline atualizada");
            if (downloadState != null) {
                String detail = (done + failed) + " de " + total;
                if (failed > 0) detail += " · " + failed + " falha(s)";
                if (current != null && !current.trim().isEmpty()) detail += "\n" + current;
                downloadState.setText(detail);
            }
            if (!active) {
                if (downloadInitialFlow) {
                    library.setSetupDone(true);
                    ui.postDelayed(() -> { downloadInitialFlow = false; showHome(); }, 900);
                } else ui.postDelayed(MainActivity.this::showMusic, 700);
            }
        }
    };

    private final BroadcastReceiver playerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String title = intent.getStringExtra("title");
            String artist = intent.getStringExtra("artist");
            String state = intent.getStringExtra("state");
            if (nowTitle != null) nowTitle.setText(title == null || title.isEmpty() ? "Nenhuma música" : title);
            if (nowArtist != null) nowArtist.setText(artist == null || artist.isEmpty() ? "EstradaPlay" : artist);
            if (nowState != null) nowState.setText(state == null || state.isEmpty() ? "OFFLINE" : state);
        }
    };

    private final BroadcastReceiver roadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String hazard = intent.getStringExtra("hazard_label");
            String road = intent.getStringExtra("road");
            String status = intent.getStringExtra("status");
            double distance = intent.getDoubleExtra("distance_m", 0);
            double speed = intent.getDoubleExtra("speed_kmh", 0);
            int packs = intent.getIntExtra("pack_count", 0);
            int points = intent.getIntExtra("hazard_count", 0);
            if (roadLiveState != null) {
                if (hazard != null && !hazard.trim().isEmpty()) roadLiveState.setText("⚠ " + hazard + (distance > 0 ? " · " + Math.round(distance) + " m" : ""));
                else roadLiveState.setText("Proteção GPS ativa");
            }
            if (roadLiveDetail != null) {
                StringBuilder d = new StringBuilder();
                if (road != null && !road.trim().isEmpty()) d.append(road.trim()).append(" · ");
                d.append(Math.round(speed)).append(" km/h");
                if (packs > 0 || points > 0) d.append(" · ").append(packs).append(" área(s) · ").append(points).append(" pontos");
                if ((hazard == null || hazard.trim().isEmpty()) && status != null && !status.trim().isEmpty()) d.append("\n").append(status.trim());
                roadLiveDetail.setText(d.toString());
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        api = new ApiClient(this);
        library = new LibraryStore(this);
        loadAccount();
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        registerAppReceivers();
        requestNotifications();
        boot();
    }

    private void boot() {
        if (hasAccount()) {
            startRoadSafetyIfAllowed();
            if (!library.hasSetupDone()) {
                if (online()) loadCatalogAndOpenChooser(true); else showOfflineSetupBlocked();
            } else showHome();
            return;
        }
        if (online()) attemptDeviceLogin(); else showAuth(false, "Entre quando houver internet. Depois das músicas baixadas, o player funciona offline.");
    }

    private void attemptDeviceLogin() {
        showLoading("Reconhecendo este aparelho…");
        io.execute(() -> {
            try {
                JSONObject d = devicePayload();
                ApiClient.Response r = api.post("api/native_app.php?action=device_login", d);
                JSONObject j = r.json();
                if (r.ok() && j.optBoolean("ok") && j.optJSONObject("account") != null) {
                    saveAccount(j.optJSONObject("account"));
                    ui.post(() -> loadCatalogAndOpenChooser(true));
                } else ui.post(() -> showAuth(false, "Entre uma vez. Depois o EstradaPlay reconhece este aparelho."));
            } catch (Exception e) {
                ui.post(() -> showAuth(false, "Não consegui reconhecer o aparelho. Entre com sua conta."));
            }
        });
    }

    private void showAuth(boolean register, String note) {
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout outer = column();
        outer.setPadding(dp(22), dp(34), dp(22), dp(40));
        sv.addView(outer, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView brand = text("EstradaPlay", 38, TEXT, true);
        outer.addView(brand);
        TextView tagline = text("PLAY NA ESTRADA", 12, ACCENT, true);
        outer.addView(tagline);
        TextView desc = text("Música pronta no aparelho antes de pegar a estrada.", 15, MUTED, false);
        outer.addView(desc); margins(desc, 0, 8, 0, 24);

        LinearLayout card = card(); outer.addView(card, new LinearLayout.LayoutParams(-1, -2));
        card.addView(text(register ? "Criar sua conta" : "Entrar", 25, TEXT, true));
        if (note != null && !note.isEmpty()) { TextView n = text(note, 12, MUTED, false); card.addView(n); margins(n, 0, 6, 0, 12); }

        EditText name = register ? field("Nome") : null;
        EditText email = register ? field("E-mail") : null;
        EditText login = field(register ? "Login" : "Usuário ou e-mail");
        EditText pass = field("Senha"); pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (register) { card.addView(name, lp(-1, 54)); margins(name, 0, 12, 0, 0); card.addView(email, lp(-1, 54)); margins(email, 0, 9, 0, 0); }
        card.addView(login, lp(-1, 54)); margins(login, 0, 9, 0, 0);
        card.addView(pass, lp(-1, 54)); margins(pass, 0, 9, 0, 0);
        Button submit = button(register ? "CRIAR CONTA" : "ENTRAR", true); card.addView(submit, lp(-1, 56)); margins(submit, 0, 14, 0, 0);
        Button toggle = button(register ? "Já tenho uma conta" : "Criar conta", false); card.addView(toggle, lp(-1, 50)); margins(toggle, 0, 8, 0, 0);
        toggle.setOnClickListener(v -> showAuth(!register, null));

        submit.setOnClickListener(v -> {
            String user = login.getText().toString().trim();
            String password = pass.getText().toString();
            if (user.isEmpty() || password.isEmpty()) { toast("Preencha login e senha."); return; }
            submit.setEnabled(false);
            io.execute(() -> {
                try {
                    JSONObject d = devicePayload();
                    d.put(register ? "username" : "login", user);
                    d.put("password", password);
                    if (register) { d.put("name", name.getText().toString().trim()); d.put("email", email.getText().toString().trim()); }
                    ApiClient.Response r = api.post("api/native_app.php?action=" + (register ? "register" : "login"), d);
                    JSONObject j = r.json();
                    if (!r.ok() || !j.optBoolean("ok") || j.optJSONObject("account") == null) {
                        String err = j.optString("error", "Não foi possível entrar.");
                        ui.post(() -> { submit.setEnabled(true); alert("EstradaPlay", err); });
                        return;
                    }
                    saveAccount(j.optJSONObject("account"));
                    ui.post(() -> loadCatalogAndOpenChooser(true));
                } catch (Exception e) {
                    ui.post(() -> { submit.setEnabled(true); alert("Sem conexão", "Não consegui falar com o servidor agora."); });
                }
            });
        });
    }

    private JSONObject devicePayload() {
        JSONObject d = new JSONObject();
        try {
            d.put("device_token", DeviceIdentity.token(this));
            d.put("device_label", DeviceIdentity.label());
            d.put("app_version", BuildConfig.VERSION_NAME);
        } catch (Exception ignored) {}
        return d;
    }

    private void loadCatalogAndOpenChooser(boolean initial) {
        showLoading("Carregando apenas a lista de pastas…");
        io.execute(() -> {
            try {
                ApiClient.Response r = api.get("api/library.php?action=list");
                JSONObject j = r.json();
                JSONArray arr = j.optJSONArray("tracks");
                if (!r.ok() || arr == null) {
                    ApiClient.Response fallback = api.get("api/native_app.php?action=library");
                    j = fallback.json(); arr = j.optJSONArray("tracks");
                    if (!fallback.ok() || arr == null) throw new Exception("Biblioteca indisponível");
                }
                ArrayList<Track> tracks = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Track t = Track.fromServer(o, api);
                    if (!t.remoteSource.isEmpty()) tracks.add(t);
                }
                library.saveCatalog(tracks);
                ui.post(() -> {
                    if (tracks.isEmpty()) showCatalogEmpty(initial); else showFolderChooser(initial);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (!library.catalog().isEmpty()) showFolderChooser(initial);
                    else alertWithRetry("Não consegui carregar as pastas", "Confira a internet e tente novamente.", () -> loadCatalogAndOpenChooser(initial));
                });
            }
        });
    }

    private void showFolderChooser(boolean initial) {
        List<Track> catalog = library.catalog();
        Map<String, LibraryStore.FolderStat> stats = library.folderStats(catalog);
        Set<String> selected = new LinkedHashSet<>();
        Map<String, CheckBox> checks = new LinkedHashMap<>();

        root.removeAllViews();
        LinearLayout page = column();
        page.setPadding(dp(18), dp(22), dp(18), dp(22));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = row();
        LinearLayout titles = column();
        titles.addView(text(initial ? "Primeiro acesso" : "Biblioteca offline", 11, ACCENT, true));
        titles.addView(text(initial ? "Escolha suas músicas" : "Gerenciar músicas", 28, TEXT, true));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        if (!initial) { Button close = button("VOLTAR", false); header.addView(close, lp(90, 48)); close.setOnClickListener(v -> showMusic()); }
        page.addView(header);
        TextView explanation = text(initial ?
                "Escolha as pastas que quer no aparelho. O download acontece uma vez; depois o player não consulta o Google Drive." :
                "Adicione novas pastas ou remova as que já estão no aparelho.", 13, MUTED, false);
        page.addView(explanation); margins(explanation, 0, 8, 0, 10);

        TextView storage = text("Espaço livre: " + bytes(library.freeBytes()), 12, GREEN, true);
        page.addView(storage);
        Button all = button("SELECIONAR TODAS NÃO BAIXADAS", false); page.addView(all, lp(-1, 48)); margins(all, 0, 10, 0, 10);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        for (LibraryStore.FolderStat s : stats.values()) {
            LinearLayout box = card(); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout meta = column();
            meta.addView(text(s.name, 15, TEXT, true));
            String info = s.downloaded + "/" + s.total + " offline" + (s.knownBytes > 0 ? " · " + bytes(s.knownBytes) : "");
            meta.addView(text(info, 11, s.complete() ? GREEN : MUTED, false));
            box.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
            if (s.complete()) {
                Button remove = button("REMOVER", false); remove.setTextColor(RED); box.addView(remove, lp(92, 46));
                remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Remover pasta offline?")
                        .setMessage(s.name + "\nAs músicas continuam no Google Drive e podem ser baixadas novamente.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Remover", (d, w) -> io.execute(() -> {
                            int removed = library.removeFolder(s.name);
                            ui.post(() -> { toast(removed + " arquivo(s) removido(s)."); showFolderChooser(initial); });
                        })).show());
            } else {
                CheckBox cb = new CheckBox(this); cb.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
                box.addView(cb, lp(54, 54)); checks.put(s.name, cb);
                cb.setOnCheckedChangeListener((b, checked) -> { if (checked) selected.add(s.name); else selected.remove(s.name); updateSelectedStorage(storage, selected, stats); });
                box.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            }
            list.addView(box, new LinearLayout.LayoutParams(-1, -2)); margins(box, 0, 0, 0, 8);
        }

        all.setOnClickListener(v -> {
            boolean shouldSelect = selected.size() < checks.size();
            for (CheckBox cb : checks.values()) cb.setChecked(shouldSelect);
        });

        Button go = button(initial ? "BAIXAR E CONTINUAR" : "BAIXAR SELECIONADAS", true);
        page.addView(go, lp(-1, 58)); margins(go, 0, 12, 0, 0);
        go.setOnClickListener(v -> {
            if (selected.isEmpty()) {
                if (initial && !library.downloadedTracks().isEmpty()) { library.setSetupDone(true); showHome(); }
                else toast("Escolha pelo menos uma pasta.");
                return;
            }
            long known = selectedBytes(selected, stats);
            long free = library.freeBytes();
            if (known > 0 && known > free * 0.92) { alert("Espaço insuficiente", "Selecionado: " + bytes(known) + "\nLivre: " + bytes(free)); return; }
            startFolderDownload(selected, initial);
        });
    }

    private void startFolderDownload(Set<String> folders, boolean initial) {
        downloadInitialFlow = initial;
        showDownloadProgress(initial);
        JSONArray a = new JSONArray(); for (String f : folders) a.put(f);
        Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START).putExtra(DownloadService.EXTRA_FOLDERS, a.toString());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void showDownloadProgress(boolean initial) {
        root.removeAllViews();
        LinearLayout page = column(); page.setGravity(Gravity.CENTER); page.setPadding(dp(30), dp(30), dp(30), dp(30));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        TextView logo = text("EstradaPlay", 34, TEXT, true); logo.setGravity(Gravity.CENTER); page.addView(logo);
        downloadTitle = text(initial ? "Preparando sua primeira biblioteca" : "Baixando novas músicas", 20, TEXT, true); downloadTitle.setGravity(Gravity.CENTER); page.addView(downloadTitle); margins(downloadTitle, 0, 18, 0, 8);
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); downloadProgress.setIndeterminate(true); page.addView(downloadProgress, lp(-1, 18));
        downloadState = text("Conectando ao Google Drive…", 13, MUTED, false); downloadState.setGravity(Gravity.CENTER); page.addView(downloadState); margins(downloadState, 0, 12, 0, 0);
        TextView note = text("Os arquivos vão direto do Google Drive para este aparelho. O servidor não armazena as músicas.", 11, MUTED, false); note.setGravity(Gravity.CENTER); page.addView(note); margins(note, 0, 16, 0, 0);
        if (!initial) { Button back = button("USAR O APP ENQUANTO BAIXA", false); page.addView(back, lp(-1, 52)); margins(back, 0, 22, 0, 0); back.setOnClickListener(v -> showHome()); }
    }

    private void showHome() {
        clearDownloadViews();
        roadLiveState = null; roadLiveDetail = null;
        root.removeAllViews();
        LinearLayout page = column(); page.setPadding(dp(18), dp(18), dp(18), dp(24)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Início"));
        TextView hero = text("Sua estrada.\nSua música.", 34, TEXT, true); page.addView(hero); margins(hero, 0, 18, 0, 4);
        TextView sub = text("Música local e alertas da estrada preparados no aparelho.", 13, MUTED, false); page.addView(sub); margins(sub, 0, 0, 0, 18);

        List<Track> downloaded = library.downloadedTracks();
        Set<String> folders = library.folderNames(downloaded);
        LinearLayout music = card(); page.addView(music);
        music.addView(text("♫  MÚSICA OFFLINE", 12, ACCENT, true));
        music.addView(text(downloaded.size() + " músicas · " + folders.size() + " pasta(s)", 23, TEXT, true));
        music.addView(text(downloaded.isEmpty() ? "Escolha uma pasta para começar." : "Pronta para tocar sem acessar o Google Drive.", 12, MUTED, false));
        Button openMusic = button(downloaded.isEmpty() ? "ESCOLHER MÚSICAS" : "ABRIR MÚSICA", true); music.addView(openMusic, lp(-1, 54)); margins(openMusic, 0, 14, 0, 0);
        openMusic.setOnClickListener(v -> { if (downloaded.isEmpty()) loadCatalogAndOpenChooser(false); else showMusic(); });

        LinearLayout manage = card(); page.addView(manage); margins(manage, 0, 12, 0, 0);
        manage.addView(text("⇩  GERENCIAR BIBLIOTECA", 12, PURPLE, true));
        manage.addView(text("Baixar outras pastas", 20, TEXT, true));
        manage.addView(text("O Drive só é acessado quando você escolhe sincronizar/baixar.", 12, MUTED, false));
        manage.setOnClickListener(v -> { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para buscar novas pastas."); });

        RoadPackStore roadStore = new RoadPackStore(this);
        LinearLayout road = card(); page.addView(road); margins(road, 0, 12, 0, 0);
        road.addView(text("◎  PROTEÇÃO NA ESTRADA", 12, GREEN, true));
        road.addView(text(hasLocationPermission() ? "GPS + alertas offline ativos" : "Ative a localização", 20, TEXT, true));
        String rs = roadStore.packCount() == 0 ? "O primeiro pacote será baixado automaticamente pela sua localização." : roadStore.packCount() + " área(s) offline · " + roadStore.hazardCount() + " pontos de alerta";
        road.addView(text(rs, 12, MUTED, false));
        road.setOnClickListener(v -> showRoad());
    }

    private void showMusic() {
        clearDownloadViews();
        roadLiveState = null; roadLiveDetail = null;
        List<Track> tracks = library.downloadedTracks();
        if (tracks.isEmpty()) { loadCatalogAndOpenChooser(false); return; }
        root.removeAllViews();
        LinearLayout page = column(); page.setPadding(dp(14), dp(14), dp(14), dp(14)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Música"));

        LinearLayout now = card(); now.setOrientation(LinearLayout.HORIZONTAL); now.setGravity(Gravity.CENTER_VERTICAL); page.addView(now);
        LinearLayout meta = column();
        nowTitle = text("Nenhuma música", 17, TEXT, true); nowArtist = text("EstradaPlay", 11, MUTED, false); nowState = text("OFFLINE", 10, GREEN, true);
        meta.addView(nowTitle); meta.addView(nowArtist); meta.addView(nowState); now.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        Button prev = button("⏮", false), toggle = button("▶/Ⅱ", true), next = button("⏭", false);
        now.addView(prev, lp(52, 50)); now.addView(toggle, lp(64, 50)); margins(toggle, 6, 0, 6, 0); now.addView(next, lp(52, 50));
        prev.setOnClickListener(v -> startPlayer(PlayerService.ACTION_PREVIOUS, null, null));
        toggle.setOnClickListener(v -> startPlayer(PlayerService.ACTION_TOGGLE, null, null));
        next.setOnClickListener(v -> startPlayer(PlayerService.ACTION_NEXT, null, null));

        Button manage = button("GERENCIAR MÚSICAS OFFLINE", false); page.addView(manage, lp(-1, 48)); margins(manage, 0, 10, 0, 8);
        manage.setOnClickListener(v -> { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para baixar novas pastas."); });

        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout rail = row(); hs.addView(rail); page.addView(hs, lp(-1, 48));
        Set<String> folders = library.folderNames(tracks);
        addFolderButton(rail, "Todas", "__ALL__");
        for (String f : folders) addFolderButton(rail, shortFolder(f), f);

        EditText search = field("Buscar música ou artista"); page.addView(search, lp(-1, 50)); margins(search, 0, 8, 0, 8);
        ListView list = new ListView(this); list.setDivider(null); list.setCacheColorHint(Color.TRANSPARENT); page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        trackAdapter = new TrackAdapter(tracks); list.setAdapter(trackAdapter);
        list.setOnItemClickListener((p, v, position, id) -> playTrack(trackAdapter.getItem(position)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { if (trackAdapter != null) trackAdapter.setQuery(s == null ? "" : s.toString()); }
            public void afterTextChanged(Editable e) {}
        });
    }

    private void addFolderButton(LinearLayout rail, String label, String folder) {
        Button b = button(label, false); rail.addView(b, new LinearLayout.LayoutParams(-2, dp(44))); margins(b, rail.getChildCount() == 1 ? 0 : 6, 0, 0, 0);
        b.setOnClickListener(v -> { activeMusicFolder = folder; if (trackAdapter != null) trackAdapter.setFolder(folder); });
    }

    private void playTrack(Track t) {
        if (t == null || t.localPath.isEmpty()) { toast("Esta música ainda não está offline."); return; }
        startPlayer(PlayerService.ACTION_PLAY_TRACK, t.key(), activeMusicFolder);
    }

    private void startPlayer(String action, String key, String folder) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        if (key != null) i.putExtra(PlayerService.EXTRA_KEY, key);
        if (folder != null) i.putExtra(PlayerService.EXTRA_FOLDER, folder);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void showRoad() {
        root.removeAllViews();
        LinearLayout page = column(); page.setPadding(dp(18), dp(18), dp(18), dp(24)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Estrada"));
        TextView icon = text("◎", 62, ACCENT, true); icon.setGravity(Gravity.CENTER); page.addView(icon, lp(-1, 92));
        TextView title = text("Proteção automática", 28, TEXT, true); title.setGravity(Gravity.CENTER); page.addView(title);
        TextView body = text("Não precisa escolher destino. O GPS acompanha posição, velocidade e sentido do carro. Os pontos de segurança são baixados para o aparelho e comparados localmente com o que está à frente.", 14, MUTED, false); body.setGravity(Gravity.CENTER); page.addView(body); margins(body, 10, 10, 10, 16);

        RoadPackStore store = new RoadPackStore(this);
        LinearLayout live = card(); page.addView(live);
        live.addView(text("PROTEÇÃO AO VIVO", 11, GREEN, true));
        roadLiveState = text(hasLocationPermission() ? "Proteção GPS ativa" : "Localização desativada", 21, TEXT, true); live.addView(roadLiveState); margins(roadLiveState, 0, 4, 0, 0);
        String initialDetail = store.packCount() == 0 ? "Aguardando a primeira área offline…" : store.packCount() + " área(s) · " + store.hazardCount() + " pontos armazenados no aparelho";
        roadLiveDetail = text(initialDetail, 12, MUTED, false); live.addView(roadLiveDetail); margins(roadLiveDetail, 0, 5, 0, 0);

        LinearLayout types = card(); page.addView(types); margins(types, 0, 12, 0, 0);
        types.addView(text("ALERTAS OFFLINE", 11, ACCENT, true));
        types.addView(text("Radar / fiscalização de velocidade", 14, TEXT, true));
        types.addView(text("Semáforos · quebra-molas · pedágios · passagens de nível", 12, MUTED, false));
        types.addView(text("O filtro usa direção de movimento e distância lateral para reduzir avisos de outra via ou de pontos que já ficaram para trás.", 11, MUTED, false)); margins(types.getChildAt(types.getChildCount()-1), 0, 8, 0, 0);

        Button action = button(hasLocationPermission() ? "MANTER PROTEÇÃO ATIVA" : "ATIVAR LOCALIZAÇÃO", true); page.addView(action, lp(-1, 56)); margins(action, 0, 14, 0, 0);
        action.setOnClickListener(v -> {
            if (!hasLocationPermission()) startActivity(new Intent(this, GateActivity.class));
            else { startRoadSafetyIfAllowed(); toast("Proteção da estrada ativa."); }
        });

        TextView note = text("Quando você entra em uma área ainda não salva e há internet, o EstradaPlay baixa o próximo pacote automaticamente. Depois o reconhecimento daquela área funciona sem consultar o servidor a cada alerta.", 11, MUTED, false); note.setGravity(Gravity.CENTER); page.addView(note); margins(note, 10, 12, 10, 0);
    }

    private void showAccount() {
        roadLiveState = null; roadLiveDetail = null;
        root.removeAllViews();
        LinearLayout page = column(); page.setPadding(dp(18), dp(18), dp(18), dp(24)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Conta"));
        JSONObject user = account.optJSONObject("user");
        LinearLayout c = card(); page.addView(c); margins(c, 0, 18, 0, 0);
        c.addView(text(user == null ? "Conta EstradaPlay" : user.optString("name", "Conta EstradaPlay"), 24, TEXT, true));
        if (user != null) { c.addView(text(user.optString("email", ""), 13, MUTED, false)); c.addView(text("@" + user.optString("username", ""), 12, ACCENT, true)); }
        c.addView(text("Versão " + BuildConfig.VERSION_NAME + " · app Android novo", 11, MUTED, false));
        Button logout = button("SAIR DA CONTA", false); logout.setTextColor(RED); c.addView(logout, lp(-1, 52)); margins(logout, 0, 18, 0, 0);
        logout.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Sair?").setMessage("As músicas e áreas de segurança já baixadas permanecem neste aparelho.").setNegativeButton("Cancelar", null).setPositiveButton("Sair", (d,w) -> {
            api.clearSession(); account = new JSONObject(); prefs.edit().remove(KEY_ACCOUNT).apply(); showAuth(false, null);
        }).show());
    }

    private View topBar(String screen) {
        LinearLayout bar = row(); bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("EstradaPlay", 24, TEXT, true); bar.addView(logo, new LinearLayout.LayoutParams(0, dp(60), 1));
        TextView section = text(screen.toUpperCase(Locale.ROOT), 10, ACCENT, true); bar.addView(section, new LinearLayout.LayoutParams(-2, dp(60)));
        Button menu = button("☰", false); menu.setTextSize(20); bar.addView(menu, lp(58, 52)); margins(menu, 10, 4, 0, 4); menu.setOnClickListener(v -> openMenu());
        return bar;
    }

    private void openMenu() {
        String[] items = {"Início", "Música offline", "Gerenciar músicas", "Proteção na estrada", "Conta"};
        new AlertDialog.Builder(this).setTitle("EstradaPlay").setItems(items, (d, which) -> {
            if (which == 0) showHome();
            else if (which == 1) showMusic();
            else if (which == 2) { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para sincronizar pastas."); }
            else if (which == 3) showRoad();
            else showAccount();
        }).show();
    }

    private void showOfflineSetupBlocked() {
        root.removeAllViews();
        LinearLayout page = column(); page.setGravity(Gravity.CENTER); page.setPadding(dp(28), dp(28), dp(28), dp(28)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        TextView title = text("Primeira preparação", 27, TEXT, true); title.setGravity(Gravity.CENTER); page.addView(title);
        TextView msg = text("Conecte-se uma vez para escolher e baixar as pastas. Depois o player funciona offline.", 14, MUTED, false); msg.setGravity(Gravity.CENTER); page.addView(msg); margins(msg, 0, 10, 0, 18);
        Button retry = button("TENTAR NOVAMENTE", true); page.addView(retry, lp(-1, 56)); retry.setOnClickListener(v -> boot());
    }

    private void showCatalogEmpty(boolean initial) {
        alertWithRetry("Nenhuma pasta encontrada", "A conta entrou, mas o servidor não retornou músicas. Verifique a biblioteca do Google Drive no painel.", () -> loadCatalogAndOpenChooser(initial));
    }

    private void showLoading(String message) {
        root.removeAllViews();
        LinearLayout box = column(); box.setGravity(Gravity.CENTER); box.setPadding(dp(30), dp(30), dp(30), dp(30)); root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        ProgressBar p = new ProgressBar(this); box.addView(p, lp(62, 62));
        TextView brand = text("EstradaPlay", 28, TEXT, true); brand.setGravity(Gravity.CENTER); box.addView(brand); margins(brand, 0, 18, 0, 6);
        TextView m = text(message, 13, MUTED, false); m.setGravity(Gravity.CENTER); box.addView(m);
    }

    private void updateSelectedStorage(TextView target, Set<String> selected, Map<String, LibraryStore.FolderStat> stats) {
        long bytes = selectedBytes(selected, stats);
        String s = "Espaço livre: " + bytes(library.freeBytes());
        if (bytes > 0) s += " · selecionado: " + bytes(bytes);
        target.setText(s);
        target.setTextColor(bytes > library.freeBytes() * 0.92 ? RED : GREEN);
    }

    private long selectedBytes(Set<String> selected, Map<String, LibraryStore.FolderStat> stats) {
        long total = 0;
        for (String f : selected) { LibraryStore.FolderStat s = stats.get(f); if (s != null) total += s.knownBytes; }
        return total;
    }

    private String bytes(long value) {
        if (value < 1024L * 1024L) return String.format(Locale.getDefault(), "%.1f MB", value / 1024d / 1024d);
        if (value < 1024L * 1024L * 1024L) return String.format(Locale.getDefault(), "%.0f MB", value / 1024d / 1024d);
        return String.format(Locale.getDefault(), "%.2f GB", value / 1024d / 1024d / 1024d);
    }

    private String shortFolder(String value) {
        if (value == null) return "Pasta";
        String v = value.replace('\\','/'); int slash = v.lastIndexOf('/'); if (slash >= 0 && slash < v.length() - 1) v = v.substring(slash + 1);
        return v.length() > 24 ? v.substring(0, 24) + "…" : v;
    }

    private void registerAppReceivers() {
        IntentFilter d = new IntentFilter(DownloadService.ACTION_STATE);
        IntentFilter p = new IntentFilter(PlayerService.ACTION_STATE);
        IntentFilter r = new IntentFilter(RoadSafetyService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, d, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(playerReceiver, p, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(roadReceiver, r, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, d);
            registerReceiver(playerReceiver, p);
            registerReceiver(roadReceiver, r);
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startRoadSafetyIfAllowed() {
        if (!hasLocationPermission()) return;
        try {
            Intent i = new Intent(this, RoadSafetyService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable ignored) {}
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork(); if (n == null) return false;
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo(); return info != null && info.isConnected();
        } catch (Exception e) { return false; }
    }

    private void loadAccount() {
        try { account = new JSONObject(prefs.getString(KEY_ACCOUNT, "{}")); }
        catch (Exception e) { account = new JSONObject(); }
    }

    private void saveAccount(JSONObject a) {
        account = a == null ? new JSONObject() : a;
        prefs.edit().putString(KEY_ACCOUNT, account.toString()).apply();
        startRoadSafetyIfAllowed();
    }

    private boolean hasAccount() { return account.optBoolean("authenticated", false) || account.optJSONObject("user") != null; }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout card() {
        LinearLayout l = column(); l.setPadding(dp(16), dp(15), dp(16), dp(15)); l.setBackground(bg(PANEL, 18, LINE)); return l;
    }

    private GradientDrawable bg(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); t.setLineSpacing(0, 1.08f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private Button button(String value, boolean primary) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(primary ? Color.rgb(25,12,4) : TEXT); b.setBackground(bg(primary ? ACCENT : PANEL2, 14, primary ? 0 : LINE)); return b;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(Color.rgb(96,116,135)); e.setTextColor(TEXT); e.setSingleLine(true); e.setTextSize(14); e.setPadding(dp(14), 0, dp(14), 0); e.setBackground(bg(Color.rgb(7,14,22), 13, LINE)); return e;
    }

    private void margins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        ViewGroup.MarginLayoutParams p = raw instanceof ViewGroup.MarginLayoutParams ? (ViewGroup.MarginLayoutParams) raw : new ViewGroup.MarginLayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); v.setLayoutParams(p);
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void alert(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show(); }
    private void alertWithRetry(String title, String message, Runnable retry) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Voltar", (d,w) -> { if (library.hasSetupDone()) showHome(); else showAuth(false, null); }).setPositiveButton("Tentar novamente", (d,w) -> retry.run()).show(); }

    private void clearDownloadViews() { downloadTitle = null; downloadState = null; downloadProgress = null; }

    @Override protected void onDestroy() {
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(playerReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(roadReceiver); } catch (Exception ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    private final class TrackAdapter extends BaseAdapter {
        private final List<Track> source;
        private final ArrayList<Track> shown = new ArrayList<>();
        private String folder = activeMusicFolder;
        private String query = "";
        TrackAdapter(List<Track> tracks) { source = new ArrayList<>(tracks); rebuild(); }
        void setFolder(String value) { folder = value == null ? "__ALL__" : value; rebuild(); }
        void setQuery(String value) { query = value == null ? "" : value.trim().toLowerCase(Locale.ROOT); rebuild(); }
        private void rebuild() {
            shown.clear();
            for (Track t : source) {
                if (!"__ALL__".equals(folder) && !folder.equals(LibraryStore.folderKey(t))) continue;
                if (!query.isEmpty()) {
                    String hay = (t.title + " " + t.artist + " " + t.album).toLowerCase(Locale.ROOT);
                    if (!hay.contains(query)) continue;
                }
                shown.add(t);
            }
            notifyDataSetChanged();
        }
        @Override public int getCount() { return shown.size(); }
        @Override public Track getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12), dp(8), dp(8), dp(8)); row.setBackground(bg(PANEL, 14, LINE));
                LinearLayout meta = column(); TextView title = text("", 14, TEXT, true); TextView artist = text("", 11, MUTED, false); meta.addView(title); meta.addView(artist); row.addView(meta, new LinearLayout.LayoutParams(0, dp(64), 1));
                Button play = button("▶", true); row.addView(play, lp(50, 46));
                h = new Holder(title, artist, play); row.setTag(h); convertView = row;
            } else h = (Holder) convertView.getTag();
            Track t = getItem(position); h.title.setText(t.title); h.artist.setText(t.artist + " · " + shortFolder(LibraryStore.folderKey(t))); h.play.setOnClickListener(v -> playTrack(t));
            return convertView;
        }
    }

    private static final class Holder {
        final TextView title, artist; final Button play;
        Holder(TextView title, TextView artist, Button play) { this.title = title; this.artist = artist; this.play = play; }
    }
}
