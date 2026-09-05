package com.estradaplay.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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

    // EstradaPlay Design System 1.3
    private final int BG = Color.rgb(6, 8, 12);
    private final int SURFACE = Color.rgb(13, 17, 23);
    private final int SURFACE_2 = Color.rgb(20, 26, 34);
    private final int SURFACE_3 = Color.rgb(27, 35, 45);
    private final int BORDER = Color.rgb(36, 46, 57);
    private final int TEXT = Color.rgb(244, 247, 251);
    private final int MUTED = Color.rgb(143, 154, 167);
    private final int SUBTLE = Color.rgb(99, 112, 126);
    private final int ACCENT = Color.rgb(255, 107, 44);
    private final int ACCENT_SOFT = Color.rgb(67, 31, 20);
    private final int GREEN = Color.rgb(69, 212, 131);
    private final int GREEN_SOFT = Color.rgb(20, 57, 42);
    private final int BLUE = Color.rgb(93, 169, 255);
    private final int BLUE_SOFT = Color.rgb(20, 43, 67);
    private final int PURPLE = Color.rgb(160, 120, 255);
    private final int RED = Color.rgb(255, 100, 107);

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
            if (downloadTitle != null) downloadTitle.setText(active ? "Preparando sua biblioteca" : "Tudo pronto");
            if (downloadState != null) {
                String detail = total > 0 ? (done + failed) + " de " + total + " músicas" : "Preparando download…";
                if (failed > 0) detail += " · " + failed + " falha(s)";
                if (current != null && !current.trim().isEmpty()) detail += "\n" + current.trim();
                downloadState.setText(detail);
            }
            if (!active) {
                if (downloadInitialFlow) {
                    library.setSetupDone(true);
                    ui.postDelayed(() -> { downloadInitialFlow = false; showHome(); }, 900);
                } else ui.postDelayed(MainActivity.this::showMusic, 650);
            }
        }
    };

    private final BroadcastReceiver playerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String title = intent.getStringExtra("title");
            String artist = intent.getStringExtra("artist");
            String state = intent.getStringExtra("state");
            if (nowTitle != null) nowTitle.setText(title == null || title.isEmpty() ? "Escolha uma música" : title);
            if (nowArtist != null) nowArtist.setText(artist == null || artist.isEmpty() ? "Biblioteca offline" : artist);
            if (nowState != null) nowState.setText(state == null || state.isEmpty() ? "OFFLINE" : state.toUpperCase(Locale.ROOT));
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
                if (hazard != null && !hazard.trim().isEmpty()) {
                    roadLiveState.setText(hazard + (distance > 0 ? " · " + Math.round(distance) + " m" : ""));
                    roadLiveState.setTextColor(ACCENT);
                } else {
                    roadLiveState.setText("Proteção ativa");
                    roadLiveState.setTextColor(TEXT);
                }
            }
            if (roadLiveDetail != null) {
                StringBuilder d = new StringBuilder();
                if (road != null && !road.trim().isEmpty()) d.append(road.trim()).append(" · ");
                d.append(Math.round(speed)).append(" km/h");
                if (packs > 0 || points > 0) d.append(" · ").append(points).append(" alertas offline");
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
        if (online()) attemptDeviceLogin();
        else showAuth(false, "Conecte-se para entrar pela primeira vez. Depois, música e alertas preparados continuam disponíveis offline.");
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
                } else ui.post(() -> showAuth(false, "Entre uma vez. Nas próximas vezes este aparelho poderá ser reconhecido automaticamente."));
            } catch (Exception e) {
                ui.post(() -> showAuth(false, "Não consegui reconhecer o aparelho agora. Entre com sua conta."));
            }
        });
    }

    private void showAuth(boolean register, String note) {
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout outer = column();
        outer.setPadding(dp(22), dp(30), dp(22), dp(36));
        sv.addView(outer, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout brandRow = row();
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = badge("EP", ACCENT, ACCENT_SOFT); brandRow.addView(mark, lp(48, 48));
        LinearLayout brandText = column();
        TextView brand = text("EstradaPlay", 27, TEXT, true); brandText.addView(brand);
        TextView tagline = overline("PLAY NA ESTRADA", ACCENT); brandText.addView(tagline);
        brandRow.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1)); margins(brandText, 12, 0, 0, 0);
        outer.addView(brandRow);

        TextView hero = text(register ? "Crie sua conta" : "Bem-vindo de volta", 32, TEXT, true);
        outer.addView(hero); margins(hero, 0, 34, 0, 5);
        TextView desc = text(register ? "Uma conta. Suas músicas e sua proteção de estrada preparadas no aparelho." : "Entre para preparar sua biblioteca e seguir viagem sem depender de sinal o tempo todo.", 14, MUTED, false);
        outer.addView(desc); margins(desc, 0, 0, 0, 20);

        LinearLayout benefits = row();
        TextView b1 = chip("MÚSICA OFFLINE", GREEN, GREEN_SOFT); benefits.addView(b1);
        TextView b2 = chip("GPS PASSIVO", BLUE, BLUE_SOFT); benefits.addView(b2); margins(b2, 7, 0, 0, 0);
        outer.addView(benefits); margins(benefits, 0, 0, 0, 18);

        LinearLayout form = card(); outer.addView(form, new LinearLayout.LayoutParams(-1, -2));
        form.addView(overline(register ? "NOVA CONTA" : "ACESSO", MUTED));
        TextView formTitle = text(register ? "Começar agora" : "Sua conta EstradaPlay", 21, TEXT, true); form.addView(formTitle); margins(formTitle, 0, 4, 0, 12);
        if (note != null && !note.isEmpty()) {
            TextView n = text(note, 12, MUTED, false); n.setBackground(bg(SURFACE_2, 12, 0)); n.setPadding(dp(12), dp(10), dp(12), dp(10));
            form.addView(n); margins(n, 0, 0, 0, 12);
        }

        EditText name = register ? field("Nome") : null;
        EditText email = register ? field("E-mail") : null;
        EditText login = field(register ? "Nome de usuário" : "Usuário ou e-mail");
        EditText pass = field("Senha");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (register) {
            form.addView(name, lp(-1, 56));
            form.addView(email, lp(-1, 56)); margins(email, 0, 9, 0, 0);
        }
        form.addView(login, lp(-1, 56)); margins(login, 0, register ? 9 : 0, 0, 0);
        form.addView(pass, lp(-1, 56)); margins(pass, 0, 9, 0, 0);

        Button submit = button(register ? "CRIAR CONTA" : "ENTRAR", true);
        form.addView(submit, lp(-1, 58)); margins(submit, 0, 14, 0, 0);
        Button toggle = textButton(register ? "Já tenho uma conta" : "Ainda não tenho conta");
        form.addView(toggle, lp(-1, 48)); margins(toggle, 0, 6, 0, 0);
        toggle.setOnClickListener(v -> showAuth(!register, null));

        submit.setOnClickListener(v -> {
            String user = login.getText().toString().trim();
            String password = pass.getText().toString();
            if (user.isEmpty() || password.isEmpty()) { toast("Preencha usuário e senha."); return; }
            submit.setEnabled(false);
            submit.setText("AGUARDE…");
            io.execute(() -> {
                try {
                    JSONObject d = devicePayload();
                    d.put(register ? "username" : "login", user);
                    d.put("password", password);
                    if (register) {
                        d.put("name", name.getText().toString().trim());
                        d.put("email", email.getText().toString().trim());
                    }
                    ApiClient.Response r = api.post("api/native_app.php?action=" + (register ? "register" : "login"), d);
                    JSONObject j = r.json();
                    if (!r.ok() || !j.optBoolean("ok") || j.optJSONObject("account") == null) {
                        String err = j.optString("error", "Não foi possível entrar.");
                        ui.post(() -> { submit.setEnabled(true); submit.setText(register ? "CRIAR CONTA" : "ENTRAR"); alert("EstradaPlay", err); });
                        return;
                    }
                    saveAccount(j.optJSONObject("account"));
                    ui.post(() -> loadCatalogAndOpenChooser(true));
                } catch (Exception e) {
                    ui.post(() -> { submit.setEnabled(true); submit.setText(register ? "CRIAR CONTA" : "ENTRAR"); alert("Sem conexão", "Não consegui falar com o servidor agora."); });
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
        showLoading("Organizando suas pastas…");
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
                ui.post(() -> { if (tracks.isEmpty()) showCatalogEmpty(initial); else showFolderChooser(initial); });
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
        page.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = column();
        titles.addView(overline(initial ? "PRIMEIRO ACESSO · 1 DE 1" : "BIBLIOTECA OFFLINE", ACCENT));
        titles.addView(text(initial ? "Escolha suas pastas" : "Gerenciar downloads", 27, TEXT, true));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        if (!initial) {
            Button close = compactButton("VOLTAR"); header.addView(close, lp(84, 44)); close.setOnClickListener(v -> showMusic());
        }
        page.addView(header);

        TextView explanation = text(initial ?
                "Baixe agora o que quer levar na estrada. Depois o player usa apenas os arquivos do aparelho." :
                "Adicione novas pastas ou remova somente a cópia offline. O conteúdo original continua no Drive.", 13, MUTED, false);
        page.addView(explanation); margins(explanation, 0, 8, 0, 12);

        LinearLayout storageCard = row(); storageCard.setGravity(Gravity.CENTER_VERTICAL); storageCard.setPadding(dp(13), dp(10), dp(13), dp(10)); storageCard.setBackground(bg(SURFACE_2, 14, BORDER));
        TextView storageLabel = overline("ARMAZENAMENTO", MUTED); storageCard.addView(storageLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView storage = chip(bytes(library.freeBytes()) + " LIVRES", GREEN, GREEN_SOFT); storageCard.addView(storage);
        page.addView(storageCard);

        Button all = compactButton("SELECIONAR TODAS NÃO BAIXADAS"); page.addView(all, lp(-1, 46)); margins(all, 0, 10, 0, 10);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout list = column();
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        for (LibraryStore.FolderStat s : stats.values()) {
            LinearLayout box = card(); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
            TextView folderMark = badge("♪", s.complete() ? GREEN : ACCENT, s.complete() ? GREEN_SOFT : ACCENT_SOFT);
            box.addView(folderMark, lp(44, 44));
            LinearLayout meta = column();
            TextView folderName = text(shortFolder(s.name), 15, TEXT, true); meta.addView(folderName);
            String info = s.downloaded + " de " + s.total + " offline" + (s.knownBytes > 0 ? " · " + bytes(s.knownBytes) : "");
            meta.addView(text(info, 11, s.complete() ? GREEN : MUTED, false));
            box.addView(meta, new LinearLayout.LayoutParams(0, -2, 1)); margins(meta, 12, 0, 8, 0);
            if (s.complete()) {
                Button remove = compactButton("REMOVER"); remove.setTextColor(RED); box.addView(remove, lp(88, 42));
                remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Remover download?")
                        .setMessage(shortFolder(s.name) + "\n\nAs músicas continuam no Google Drive e podem ser baixadas novamente.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Remover", (d, w) -> io.execute(() -> {
                            int removed = library.removeFolder(s.name);
                            ui.post(() -> { toast(removed + " arquivo(s) removido(s)."); showFolderChooser(initial); });
                        })).show());
            } else {
                CheckBox cb = new CheckBox(this); cb.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{ACCENT, SUBTLE}));
                box.addView(cb, lp(48, 48)); checks.put(s.name, cb);
                cb.setOnCheckedChangeListener((b, checked) -> {
                    if (checked) selected.add(s.name); else selected.remove(s.name);
                    updateSelectedStorage(storage, selected, stats);
                    box.setBackground(bg(checked ? SURFACE_2 : SURFACE, 20, checked ? ACCENT : BORDER));
                });
                box.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            }
            list.addView(box, new LinearLayout.LayoutParams(-1, -2)); margins(box, 0, 0, 0, 8);
        }

        all.setOnClickListener(v -> {
            boolean shouldSelect = selected.size() < checks.size();
            for (CheckBox cb : checks.values()) cb.setChecked(shouldSelect);
        });

        Button go = button(initial ? "BAIXAR E ENTRAR NO ESTRADAPLAY" : "BAIXAR SELECIONADAS", true);
        page.addView(go, lp(-1, 60)); margins(go, 0, 12, 0, 0);
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
        LinearLayout page = column(); page.setGravity(Gravity.CENTER); page.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        TextView mark = badge("EP", ACCENT, ACCENT_SOFT); page.addView(mark, lp(62, 62));
        downloadTitle = text(initial ? "Preparando sua viagem" : "Atualizando sua biblioteca", 25, TEXT, true); downloadTitle.setGravity(Gravity.CENTER); page.addView(downloadTitle); margins(downloadTitle, 0, 20, 0, 6);
        TextView intro = text("As músicas estão indo direto para este aparelho.", 13, MUTED, false); intro.setGravity(Gravity.CENTER); page.addView(intro);
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); downloadProgress.setIndeterminate(true); downloadProgress.setProgressTintList(ColorStateList.valueOf(ACCENT)); page.addView(downloadProgress, lp(-1, 10)); margins(downloadProgress, 0, 24, 0, 0);
        downloadState = text("Conectando ao Google Drive…", 13, TEXT, true); downloadState.setGravity(Gravity.CENTER); page.addView(downloadState); margins(downloadState, 0, 12, 0, 0);
        TextView note = text("Depois do download, a reprodução não precisa acessar o Drive.", 11, MUTED, false); note.setGravity(Gravity.CENTER); page.addView(note); margins(note, 0, 8, 0, 0);
        if (!initial) {
            Button back = compactButton("CONTINUAR USANDO O APP"); page.addView(back, lp(-1, 50)); margins(back, 0, 24, 0, 0); back.setOnClickListener(v -> showHome());
        }
    }

    private void showHome() {
        clearDownloadViews();
        roadLiveState = null; roadLiveDetail = null;
        root.removeAllViews();

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = column(); page.setPadding(dp(18), dp(12), dp(18), dp(26));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Início"));

        JSONObject user = account.optJSONObject("user");
        String firstName = user == null ? "" : firstWord(user.optString("name", ""));
        TextView eyebrow = overline(firstName.isEmpty() ? "PRONTO PARA SAIR" : "OLÁ, " + firstName.toUpperCase(Locale.ROOT), MUTED); page.addView(eyebrow); margins(eyebrow, 0, 18, 0, 2);
        TextView hero = text("Tudo pronto para a estrada?", 30, TEXT, true); page.addView(hero);
        TextView sub = text("Música e proteção funcionam a partir do que já está preparado no seu aparelho.", 13, MUTED, false); page.addView(sub); margins(sub, 0, 5, 0, 16);

        List<Track> downloaded = library.downloadedTracks();
        Set<String> folders = library.folderNames(downloaded);
        RoadPackStore roadStore = new RoadPackStore(this);

        LinearLayout statusRail = row();
        TextView musicStatus = chip(downloaded.isEmpty() ? "MÚSICA PENDENTE" : "MÚSICA OFFLINE", downloaded.isEmpty() ? ACCENT : GREEN, downloaded.isEmpty() ? ACCENT_SOFT : GREEN_SOFT);
        statusRail.addView(musicStatus);
        TextView gpsStatus = chip(hasLocationPermission() ? "GPS ATIVO" : "GPS DESATIVADO", hasLocationPermission() ? BLUE : MUTED, hasLocationPermission() ? BLUE_SOFT : SURFACE_2);
        statusRail.addView(gpsStatus); margins(gpsStatus, 7, 0, 0, 0);
        page.addView(statusRail); margins(statusRail, 0, 0, 0, 16);

        LinearLayout music = featureCard(ACCENT);
        page.addView(music);
        LinearLayout musicHead = row(); musicHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView musicMark = badge("♪", ACCENT, ACCENT_SOFT); musicHead.addView(musicMark, lp(48, 48));
        LinearLayout musicMeta = column();
        musicMeta.addView(overline("SUA MÚSICA", ACCENT));
        musicMeta.addView(text(downloaded.isEmpty() ? "Escolha o que levar" : downloaded.size() + " músicas prontas", 22, TEXT, true));
        musicHead.addView(musicMeta, new LinearLayout.LayoutParams(0, -2, 1)); margins(musicMeta, 12, 0, 0, 0);
        music.addView(musicHead);
        TextView musicDesc = text(downloaded.isEmpty() ? "Baixe suas primeiras pastas para ouvir sem depender da internet." : folders.size() + " pasta(s) no aparelho · reprodução local e imediata.", 12, MUTED, false); music.addView(musicDesc); margins(musicDesc, 0, 14, 0, 0);
        Button openMusic = button(downloaded.isEmpty() ? "ESCOLHER MÚSICAS" : "ABRIR PLAYER", true); music.addView(openMusic, lp(-1, 54)); margins(openMusic, 0, 14, 0, 0);
        openMusic.setOnClickListener(v -> { if (downloaded.isEmpty()) loadCatalogAndOpenChooser(false); else showMusic(); });

        LinearLayout road = featureCard(GREEN); page.addView(road); margins(road, 0, 12, 0, 0);
        LinearLayout roadHead = row(); roadHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView roadMark = badge("◎", GREEN, GREEN_SOFT); roadHead.addView(roadMark, lp(48, 48));
        LinearLayout roadMeta = column();
        roadMeta.addView(overline("PROTEÇÃO NA ESTRADA", GREEN));
        roadMeta.addView(text(hasLocationPermission() ? "Monitoramento automático" : "Localização necessária", 21, TEXT, true));
        roadHead.addView(roadMeta, new LinearLayout.LayoutParams(0, -2, 1)); margins(roadMeta, 12, 0, 0, 0);
        road.addView(roadHead);
        String rs = roadStore.packCount() == 0 ? "A base offline será preparada automaticamente pela sua localização." : roadStore.hazardCount() + " pontos armazenados · base estadual + reserva de viagem.";
        TextView roadDesc = text(rs, 12, MUTED, false); road.addView(roadDesc); margins(roadDesc, 0, 14, 0, 0);
        Button roadButton = compactButton("VER PROTEÇÃO"); road.addView(roadButton, lp(-1, 48)); margins(roadButton, 0, 13, 0, 0); roadButton.setOnClickListener(v -> showRoad());

        TextView quickLabel = overline("ATALHOS", MUTED); page.addView(quickLabel); margins(quickLabel, 0, 20, 0, 8);
        LinearLayout quick = row();
        LinearLayout manage = miniCard("↓", "Biblioteca", "Adicionar pastas", PURPLE); quick.addView(manage, new LinearLayout.LayoutParams(0, dp(104), 1));
        LinearLayout accountCard = miniCard("•", "Conta", "Perfil e versão", BLUE); quick.addView(accountCard, new LinearLayout.LayoutParams(0, dp(104), 1)); margins(accountCard, 9, 0, 0, 0);
        page.addView(quick);
        manage.setOnClickListener(v -> { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para buscar novas pastas."); });
        accountCard.setOnClickListener(v -> showAccount());
    }

    private void showMusic() {
        clearDownloadViews();
        roadLiveState = null; roadLiveDetail = null;
        List<Track> tracks = library.downloadedTracks();
        if (tracks.isEmpty()) { loadCatalogAndOpenChooser(false); return; }
        root.removeAllViews();
        LinearLayout page = column(); page.setPadding(dp(14), dp(10), dp(14), dp(12)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Música"));

        LinearLayout now = featureCard(ACCENT); page.addView(now);
        LinearLayout nowTop = row(); nowTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView disc = badge("♪", ACCENT, ACCENT_SOFT); nowTop.addView(disc, lp(50, 50));
        LinearLayout meta = column();
        nowState = chip("OFFLINE", GREEN, GREEN_SOFT); meta.addView(nowState, new LinearLayout.LayoutParams(-2, dp(27)));
        nowTitle = text("Escolha uma música", 19, TEXT, true); meta.addView(nowTitle); margins(nowTitle, 0, 5, 0, 0);
        nowArtist = text("Biblioteca offline", 11, MUTED, false); meta.addView(nowArtist);
        nowTop.addView(meta, new LinearLayout.LayoutParams(0, -2, 1)); margins(meta, 12, 0, 0, 0);
        now.addView(nowTop);

        LinearLayout controls = row(); controls.setGravity(Gravity.CENTER);
        Button prev = playerButton("‹‹", false), toggle = playerButton("▶ Ⅱ", true), next = playerButton("››", false);
        controls.addView(prev, lp(58, 50)); controls.addView(toggle, lp(86, 50)); margins(toggle, 8, 0, 8, 0); controls.addView(next, lp(58, 50));
        now.addView(controls); margins(controls, 0, 14, 0, 0);
        prev.setOnClickListener(v -> startPlayer(PlayerService.ACTION_PREVIOUS, null, null));
        toggle.setOnClickListener(v -> startPlayer(PlayerService.ACTION_TOGGLE, null, null));
        next.setOnClickListener(v -> startPlayer(PlayerService.ACTION_NEXT, null, null));

        LinearLayout section = row(); section.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout sectionText = column(); sectionText.addView(overline("BIBLIOTECA OFFLINE", MUTED)); sectionText.addView(text(tracks.size() + " músicas no aparelho", 18, TEXT, true));
        section.addView(sectionText, new LinearLayout.LayoutParams(0, -2, 1));
        Button manage = compactButton("GERENCIAR"); section.addView(manage, lp(94, 44));
        page.addView(section); margins(section, 2, 14, 2, 8);
        manage.setOnClickListener(v -> { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para baixar novas pastas."); });

        EditText search = field("Buscar música, artista ou álbum"); page.addView(search, lp(-1, 50));

        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout rail = row(); rail.setPadding(0, dp(8), 0, dp(8)); hs.addView(rail); page.addView(hs, lp(-1, 54));
        Set<String> folders = library.folderNames(tracks);
        addFolderButton(rail, "Todas", "__ALL__");
        for (String f : folders) addFolderButton(rail, shortFolder(f), f);

        ListView list = new ListView(this); list.setDivider(null); list.setDividerHeight(dp(7)); list.setCacheColorHint(Color.TRANSPARENT); list.setPadding(0, 0, 0, dp(10)); list.setClipToPadding(false);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        trackAdapter = new TrackAdapter(tracks); list.setAdapter(trackAdapter);
        list.setOnItemClickListener((p, v, position, id) -> playTrack(trackAdapter.getItem(position)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { if (trackAdapter != null) trackAdapter.setQuery(s == null ? "" : s.toString()); }
            public void afterTextChanged(Editable e) {}
        });
    }

    private void addFolderButton(LinearLayout rail, String label, String folder) {
        Button b = chipButton(label, folder.equals(activeMusicFolder));
        rail.addView(b, new LinearLayout.LayoutParams(-2, dp(40))); margins(b, rail.getChildCount() == 1 ? 0 : 6, 0, 0, 0);
        b.setOnClickListener(v -> {
            activeMusicFolder = folder;
            if (trackAdapter != null) trackAdapter.setFolder(folder);
            for (int i = 0; i < rail.getChildCount(); i++) {
                View child = rail.getChildAt(i);
                if (child instanceof Button) styleChipButton((Button) child, child == b);
            }
        });
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
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = column(); page.setPadding(dp(18), dp(10), dp(18), dp(26)); scroll.addView(page, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Estrada"));

        RoadPackStore store = new RoadPackStore(this);
        LinearLayout live = featureCard(GREEN); page.addView(live); margins(live, 0, 8, 0, 0);
        LinearLayout liveTop = row(); liveTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView liveBadge = chip(hasLocationPermission() ? "PROTEÇÃO ATIVA" : "GPS DESATIVADO", hasLocationPermission() ? GREEN : RED, hasLocationPermission() ? GREEN_SOFT : Color.rgb(63, 27, 31)); liveTop.addView(liveBadge);
        TextView passive = overline("SEM ROTA", MUTED); liveTop.addView(passive, new LinearLayout.LayoutParams(0, -2, 1)); passive.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        live.addView(liveTop);
        roadLiveState = text(hasLocationPermission() ? "Monitorando sua direção" : "Ative a localização", 27, TEXT, true); live.addView(roadLiveState); margins(roadLiveState, 0, 16, 0, 4);
        String initialDetail = store.packCount() == 0 ? "Aguardando a primeira sincronização offline…" : store.hazardCount() + " pontos de segurança disponíveis no aparelho";
        roadLiveDetail = text(initialDetail, 13, MUTED, false); live.addView(roadLiveDetail);

        TextView coverageLabel = overline("COBERTURA OFFLINE", MUTED); page.addView(coverageLabel); margins(coverageLabel, 0, 20, 0, 8);
        LinearLayout coverage = card(); page.addView(coverage);
        LinearLayout coverageRow = row();
        LinearLayout stateMetric = metric("BASE", "ESTADUAL", GREEN); coverageRow.addView(stateMetric, new LinearLayout.LayoutParams(0, dp(76), 1));
        LinearLayout reserveMetric = metric("RESERVA", "250 KM", ACCENT); coverageRow.addView(reserveMetric, new LinearLayout.LayoutParams(0, dp(76), 1)); margins(reserveMetric, 8, 0, 0, 0);
        LinearLayout pointsMetric = metric("ALERTAS", String.valueOf(store.hazardCount()), BLUE); coverageRow.addView(pointsMetric, new LinearLayout.LayoutParams(0, dp(76), 1)); margins(pointsMetric, 8, 0, 0, 0);
        coverage.addView(coverageRow);
        TextView coverageInfo = text("Enquanto houver internet, o EstradaPlay recompõe a reserva à frente. Sem sinal, usa o que já está salvo.", 11, MUTED, false); coverage.addView(coverageInfo); margins(coverageInfo, 0, 12, 0, 0);

        TextView alertsLabel = overline("O QUE O APP OBSERVA", MUTED); page.addView(alertsLabel); margins(alertsLabel, 0, 20, 0, 8);
        LinearLayout types = card(); page.addView(types);
        LinearLayout r1 = row(); r1.addView(alertType("RADAR", "velocidade", ACCENT), new LinearLayout.LayoutParams(0, dp(72), 1)); r1.addView(alertType("SEMÁFORO", "sinalização", BLUE), new LinearLayout.LayoutParams(0, dp(72), 1)); margins(r1.getChildAt(1), 8, 0, 0, 0); types.addView(r1);
        LinearLayout r2 = row(); r2.addView(alertType("LOMBADA", "quebra-molas", PURPLE), new LinearLayout.LayoutParams(0, dp(72), 1)); r2.addView(alertType("PEDÁGIO", "e ferrovia", GREEN), new LinearLayout.LayoutParams(0, dp(72), 1)); margins(r2.getChildAt(1), 8, 0, 0, 0); types.addView(r2); margins(r2, 0, 8, 0, 0);

        Button action = button(hasLocationPermission() ? "GARANTIR PROTEÇÃO ATIVA" : "ATIVAR LOCALIZAÇÃO", true); page.addView(action, lp(-1, 58)); margins(action, 0, 14, 0, 0);
        action.setOnClickListener(v -> {
            if (!hasLocationPermission()) startActivity(new Intent(this, GateActivity.class));
            else { startRoadSafetyIfAllowed(); toast("Proteção da estrada ativa."); }
        });

        TextView note = text("Não é necessário informar destino. O app usa posição, sentido e distância lateral para reduzir alertas de vias paralelas ou do sentido contrário.", 11, MUTED, false); note.setGravity(Gravity.CENTER); page.addView(note); margins(note, 10, 12, 10, 0);
    }

    private void showAccount() {
        roadLiveState = null; roadLiveDetail = null;
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = column(); page.setPadding(dp(18), dp(10), dp(18), dp(26)); scroll.addView(page, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Conta"));
        JSONObject user = account.optJSONObject("user");
        String name = user == null ? "Conta EstradaPlay" : user.optString("name", "Conta EstradaPlay");
        String email = user == null ? "" : user.optString("email", "");
        String username = user == null ? "" : user.optString("username", "");

        LinearLayout profile = featureCard(BLUE); page.addView(profile); margins(profile, 0, 8, 0, 0);
        LinearLayout profileTop = row(); profileTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView avatar = badge(initials(name), BLUE, BLUE_SOFT); profileTop.addView(avatar, lp(58, 58));
        LinearLayout who = column(); who.addView(overline("SUA CONTA", BLUE)); who.addView(text(name, 23, TEXT, true)); if (!username.isEmpty()) who.addView(text("@" + username, 12, MUTED, false));
        profileTop.addView(who, new LinearLayout.LayoutParams(0, -2, 1)); margins(who, 13, 0, 0, 0); profile.addView(profileTop);
        if (!email.isEmpty()) { TextView em = text(email, 12, MUTED, false); profile.addView(em); margins(em, 0, 14, 0, 0); }

        TextView appLabel = overline("ESTE APARELHO", MUTED); page.addView(appLabel); margins(appLabel, 0, 20, 0, 8);
        LinearLayout device = card(); page.addView(device);
        device.addView(infoRow("Músicas offline", library.downloadedTracks().size() + " arquivos"));
        device.addView(divider());
        RoadPackStore store = new RoadPackStore(this);
        device.addView(infoRow("Proteção da estrada", store.hazardCount() + " pontos salvos"));
        device.addView(divider());
        device.addView(infoRow("Versão do app", BuildConfig.VERSION_NAME));

        Button logout = dangerButton("SAIR DA CONTA"); page.addView(logout, lp(-1, 54)); margins(logout, 0, 18, 0, 0);
        logout.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Sair da conta?").setMessage("Músicas e dados de proteção já baixados permanecem neste aparelho.").setNegativeButton("Cancelar", null).setPositiveButton("Sair", (d,w) -> {
            api.clearSession(); account = new JSONObject(); prefs.edit().remove(KEY_ACCOUNT).apply(); showAuth(false, null);
        }).show());
    }

    private View topBar(String screen) {
        LinearLayout bar = row(); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(0, 0, 0, dp(2));
        LinearLayout brand = column();
        TextView logo = text("EstradaPlay", 20, TEXT, true); brand.addView(logo);
        TextView section = overline(screen.toUpperCase(Locale.ROOT), ACCENT); brand.addView(section);
        bar.addView(brand, new LinearLayout.LayoutParams(0, dp(62), 1));
        Button menu = menuButton(); bar.addView(menu, lp(52, 48)); menu.setOnClickListener(v -> openMenu());
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
        TextView mark = badge("EP", ACCENT, ACCENT_SOFT); page.addView(mark, lp(62, 62));
        TextView title = text("Só falta preparar o aparelho", 26, TEXT, true); title.setGravity(Gravity.CENTER); page.addView(title); margins(title, 0, 20, 0, 5);
        TextView msg = text("Conecte-se uma vez para escolher e baixar suas pastas. Depois a reprodução funciona offline.", 14, MUTED, false); msg.setGravity(Gravity.CENTER); page.addView(msg); margins(msg, 0, 0, 0, 20);
        Button retry = button("TENTAR NOVAMENTE", true); page.addView(retry, lp(-1, 58)); retry.setOnClickListener(v -> boot());
    }

    private void showCatalogEmpty(boolean initial) {
        alertWithRetry("Nenhuma pasta encontrada", "A conta entrou, mas o servidor não retornou músicas. Verifique a biblioteca do Google Drive no painel.", () -> loadCatalogAndOpenChooser(initial));
    }

    private void showLoading(String message) {
        root.removeAllViews();
        LinearLayout box = column(); box.setGravity(Gravity.CENTER); box.setPadding(dp(30), dp(30), dp(30), dp(30)); root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        TextView mark = badge("EP", ACCENT, ACCENT_SOFT); box.addView(mark, lp(58, 58));
        ProgressBar p = new ProgressBar(this); if (Build.VERSION.SDK_INT >= 21) p.setIndeterminateTintList(ColorStateList.valueOf(ACCENT)); box.addView(p, lp(42, 42)); margins(p, 0, 24, 0, 0);
        TextView brand = text("EstradaPlay", 22, TEXT, true); brand.setGravity(Gravity.CENTER); box.addView(brand); margins(brand, 0, 14, 0, 3);
        TextView m = text(message, 12, MUTED, false); m.setGravity(Gravity.CENTER); box.addView(m);
    }

    private void updateSelectedStorage(TextView target, Set<String> selected, Map<String, LibraryStore.FolderStat> stats) {
        long value = selectedBytes(selected, stats);
        String s = value > 0 ? bytes(value) + " SELECIONADOS" : bytes(library.freeBytes()) + " LIVRES";
        target.setText(s);
        target.setTextColor(value > library.freeBytes() * 0.92 ? RED : GREEN);
        target.setBackground(bg(value > library.freeBytes() * 0.92 ? Color.rgb(63, 27, 31) : GREEN_SOFT, 100, 0));
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
        return v.length() > 26 ? v.substring(0, 26) + "…" : v;
    }

    private String firstWord(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return "";
        int p = v.indexOf(' '); return p > 0 ? v.substring(0, p) : v;
    }

    private String initials(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return "EP";
        String[] parts = v.split("\\s+");
        String out = parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        if (parts.length > 1) out += parts[parts.length - 1].substring(0, 1).toUpperCase(Locale.ROOT);
        return out;
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

    // ---- UI primitives ----

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout card() {
        LinearLayout l = column(); l.setPadding(dp(17), dp(16), dp(17), dp(16)); l.setBackground(bg(SURFACE, 20, BORDER)); l.setElevation(dp(1)); return l;
    }

    private LinearLayout featureCard(int accent) {
        LinearLayout l = column(); l.setPadding(dp(18), dp(18), dp(18), dp(18)); l.setBackground(bg(SURFACE, 22, accent)); l.setElevation(dp(2)); return l;
    }

    private LinearLayout miniCard(String mark, String title, String subtitle, int accent) {
        LinearLayout l = column(); l.setPadding(dp(14), dp(12), dp(14), dp(12)); l.setBackground(bg(SURFACE, 18, BORDER));
        TextView m = text(mark, 20, accent, true); l.addView(m);
        TextView t = text(title, 15, TEXT, true); l.addView(t); margins(t, 0, 5, 0, 0);
        l.addView(text(subtitle, 10, MUTED, false));
        return l;
    }

    private LinearLayout metric(String label, String value, int accent) {
        LinearLayout l = column(); l.setGravity(Gravity.CENTER); l.setBackground(bg(SURFACE_2, 14, BORDER));
        TextView v = text(value, 16, accent, true); v.setGravity(Gravity.CENTER); l.addView(v);
        TextView k = overline(label, MUTED); k.setGravity(Gravity.CENTER); l.addView(k);
        return l;
    }

    private LinearLayout alertType(String title, String subtitle, int accent) {
        LinearLayout l = column(); l.setGravity(Gravity.CENTER_VERTICAL); l.setPadding(dp(12), dp(10), dp(12), dp(10)); l.setBackground(bg(SURFACE_2, 14, BORDER));
        l.addView(overline(title, accent)); l.addView(text(subtitle, 11, MUTED, false)); return l;
    }

    private GradientDrawable bg(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); t.setLineSpacing(0, 1.08f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private TextView overline(String value, int color) {
        TextView t = text(value, 9.5f, color, true); t.setLetterSpacing(0.13f); return t;
    }

    private TextView chip(String value, int color, int fill) {
        TextView t = text(value, 9, color, true); t.setLetterSpacing(0.08f); t.setGravity(Gravity.CENTER); t.setPadding(dp(10), 0, dp(10), 0); t.setMinHeight(dp(27)); t.setBackground(bg(fill, 100, 0)); return t;
    }

    private TextView badge(String value, int color, int fill) {
        TextView t = text(value, 15, color, true); t.setGravity(Gravity.CENTER); t.setBackground(bg(fill, 100, color)); return t;
    }

    private Button button(String value, boolean primary) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setLetterSpacing(0.08f); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(primary ? Color.WHITE : TEXT); b.setBackground(bg(primary ? ACCENT : SURFACE_2, 15, primary ? 0 : BORDER)); b.setStateListAnimator(null); return b;
    }

    private Button compactButton(String value) {
        Button b = button(value, false); b.setTextSize(9.5f); b.setLetterSpacing(0.06f); return b;
    }

    private Button textButton(String value) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(MUTED); b.setBackgroundColor(Color.TRANSPARENT); b.setStateListAnimator(null); return b;
    }

    private Button dangerButton(String value) {
        Button b = compactButton(value); b.setTextColor(RED); b.setBackground(bg(SURFACE, 15, Color.rgb(80, 39, 43))); return b;
    }

    private Button playerButton(String value, boolean primary) {
        Button b = button(value, primary); b.setTextSize(primary ? 12 : 18); return b;
    }

    private Button menuButton() {
        Button b = compactButton("MENU"); b.setTextColor(MUTED); return b;
    }

    private Button chipButton(String value, boolean selected) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(value); b.setTextSize(10); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setStateListAnimator(null); styleChipButton(b, selected); return b;
    }

    private void styleChipButton(Button b, boolean selected) {
        b.setTextColor(selected ? Color.WHITE : MUTED); b.setBackground(bg(selected ? ACCENT_SOFT : SURFACE_2, 100, selected ? ACCENT : BORDER));
    }

    private EditText field(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(SUBTLE); e.setTextColor(TEXT); e.setSingleLine(true); e.setTextSize(14); e.setPadding(dp(15), 0, dp(15), 0); e.setBackground(bg(Color.rgb(9, 13, 18), 14, BORDER)); e.setSelectAllOnFocus(false); return e;
    }

    private View divider() {
        View v = new View(this); v.setBackgroundColor(BORDER); v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return v;
    }

    private View infoRow(String label, String value) {
        LinearLayout r = row(); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(11), 0, dp(11));
        r.addView(text(label, 12, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1)); TextView v = text(value, 12, TEXT, true); v.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); r.addView(v); return r;
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
                LinearLayout r = new LinearLayout(MainActivity.this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(11), dp(8), dp(9), dp(8)); r.setBackground(bg(SURFACE, 16, BORDER));
                TextView mark = badge("♪", MUTED, SURFACE_2); r.addView(mark, lp(42, 42));
                LinearLayout meta = column(); TextView title = text("", 14, TEXT, true); TextView artist = text("", 10.5f, MUTED, false); meta.addView(title); meta.addView(artist); r.addView(meta, new LinearLayout.LayoutParams(0, dp(56), 1)); margins(meta, 11, 0, 6, 0);
                Button play = playerButton("▶", true); r.addView(play, lp(46, 44));
                h = new Holder(title, artist, play, mark); r.setTag(h); convertView = r;
            } else h = (Holder) convertView.getTag();
            Track t = getItem(position);
            h.title.setText(t.title);
            String artistText = t.artist == null || t.artist.trim().isEmpty() ? "Artista desconhecido" : t.artist.trim();
            h.artist.setText(artistText + " · " + shortFolder(LibraryStore.folderKey(t)));
            h.mark.setText(String.valueOf((position + 1)));
            h.play.setOnClickListener(v -> playTrack(t));
            return convertView;
        }
    }

    private static final class Holder {
        final TextView title, artist, mark; final Button play;
        Holder(TextView title, TextView artist, Button play, TextView mark) { this.title = title; this.artist = artist; this.play = play; this.mark = mark; }
    }
}
