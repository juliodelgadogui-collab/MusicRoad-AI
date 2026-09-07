package com.estradaplay.comunista;

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
    private static final int REQ_LOCATION = 702;

    // EstradaPlay Design System 1.3
    private final int BG = Color.rgb(9, 5, 7);
    private final int SURFACE = Color.rgb(20, 10, 13);
    private final int SURFACE_2 = Color.rgb(30, 15, 19);
    private final int SURFACE_3 = Color.rgb(42, 20, 25);
    private final int BORDER = Color.rgb(76, 38, 43);
    private final int TEXT = Color.rgb(246, 238, 224);
    private final int MUTED = Color.rgb(174, 151, 146);
    private final int SUBTLE = Color.rgb(121, 91, 91);
    private final int ACCENT = Color.rgb(190, 18, 38);
    private final int ACCENT_SOFT = Color.rgb(79, 10, 23);
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
    private boolean pendingOpenCockpitAfterPermission;
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
                    downloadInitialFlow = false;
                    io.execute(() -> {
                        boolean hasOfflineMusic = !library.downloadedTracks().isEmpty();
                        library.setSetupDone(true);
                        ui.postDelayed(() -> {
                            if (hasOfflineMusic) showHome();
                            else {
                                toast("Nenhuma música foi baixada. Você pode tentar novamente depois em Gerenciar Biblioteca.");
                                showHome();
                            }
                        }, hasOfflineMusic ? 900 : 650);
                    });
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
            SafetyAlertOverlay.show(MainActivity.this, root, intent);
            RoadThoughtOverlay.show(MainActivity.this, root, intent);
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
        ensureInteractiveWindow176();
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
        // TOUCH_INPUT_V171: no automatic runtime permission dialog during boot.
        // Permissions are requested from inside the automotive cockpit.
        boot();
    }


// BOOT_NONBLOCKING_V206: biblioteca nunca bloqueia a abertura do EstradaPlay.
private void boot() {
    if (hasAccount()) {
        startRoadSafetyIfAllowed();
        library.setSetupDone(true);
        openConfiguredTarget();
        if (online()) syncCatalogInBackground();
        return;
    }
    if (online()) attemptDeviceLogin();
    else showAuth(false, "Conecte-se para entrar pela primeira vez. Depois, mapa, proteção e músicas já baixadas continuam disponíveis offline.");
}

private void openConfiguredTarget() {
        String target = getIntent() == null ? "" : getIntent().getStringExtra("open");
        target = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        if ("music".equals(target)) { startActivity(new Intent(this, MusicPlayerActivity.class)); finish(); }
        else if ("library".equals(target)) {
            if (online()) loadCatalogAndOpenChooser(false); else showMusic();
        }
        else if ("account".equals(target)) showAccount();
        else showHome();
    }

    private void openCockpit() {
        if (!hasLocationPermission()) {
            showLocationPermissionGate();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingOpenCockpitAfterPermission = true;
            requestNotifications();
            return;
        }
        launchCockpitNow();
    }

    private void launchCockpitNow() {
        Intent i = new Intent(this, RoadMapActivity.class);
        startActivity(i);
        finish();
    }

    private void showLocationPermissionGate() {
        root.removeAllViews();
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(34), dp(24), dp(34), dp(24));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        BrandMarkView mark = new BrandMarkView(this);
        page.addView(mark, lp(62, 62));
        TextView over = overline("CONFIGURAÇÃO DO VEÍCULO", ACCENT);
        over.setGravity(Gravity.CENTER);
        page.addView(over); margins(over, 0, 18, 0, 4);
        TextView title = text("Ativar proteção da estrada", 26, TEXT, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title);
        TextView body = text("O EstradaPlay usa sua localização para identificar a estrada, o sentido do veículo e os alertas que estão à frente. A autorização só é solicitada depois do seu toque.", 13, MUTED, false);
        body.setGravity(Gravity.CENTER);
        page.addView(body); margins(body, 0, 8, 0, 18);

        Button allow = button("ATIVAR LOCALIZAÇÃO", true);
        page.addView(allow, lp(-1, 58));
        allow.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION));

        Button music = compactButton("CONTINUAR NA MÚSICA SEM GPS");
        page.addView(music, lp(-1, 50)); margins(music, 0, 8, 0, 0);
        music.setOnClickListener(v -> showMusic());
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
library.setSetupDone(true);
ui.post(() -> { showHome(); syncCatalogInBackground(); });
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
        BrandMarkView mark = new BrandMarkView(this); brandRow.addView(mark, lp(48, 48));
        LinearLayout brandText = column();
        TextView brand = text("Estrada Play Comunista", 27, TEXT, true); brandText.addView(brand);
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
        Button server = textButton("TROCAR SERVIDOR");
        form.addView(server, lp(-1, 44));
        server.setOnClickListener(v -> startActivity(new Intent(this, ServerSettingsActivity.class)));

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
                        ui.post(() -> { submit.setEnabled(true); submit.setText(register ? "CRIAR CONTA" : "ENTRAR"); alert("Estrada Play Comunista", err); });
                        return;
                    }
                    
saveAccount(j.optJSONObject("account"));
library.setSetupDone(true);
ui.post(() -> { showHome(); syncCatalogInBackground(); });
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



// LIBRARY_FAILSAFE_V209: paged catalog first, bounded legacy catalog on any server-side page failure.
// LIBRARY_PAGED_V208: fetch the catalog in bounded pages so library size cannot break the app.
private static final int LIBRARY_PAGE_SIZE = 500;
private static final int LIBRARY_MAX_PAGES = 100;

private ArrayList<Track> decodeCatalog(JSONObject j) {
    ArrayList<Track> tracks = new ArrayList<>();
    JSONArray arr = j == null ? null : j.optJSONArray("tracks");
    if (arr == null) return tracks;
    for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) continue;
        Track t = Track.fromServer(o, api);
        if (!t.remoteSource.isEmpty()) tracks.add(t);
    }
    return tracks;
}

private boolean restoreLibrarySession() {
    try {
        ApiClient.Response r = api.post("api/native_app.php?action=device_login", devicePayload());
        JSONObject j = r.json();
        if (r.ok() && j.optBoolean("ok") && j.optJSONObject("account") != null) {
            saveAccount(j.optJSONObject("account"));
            return true;
        }
    } catch (Exception ignored) {}
    return false;
}

private ApiClient.Response libraryPageRequest(int page) throws Exception {
    String path = "api/native_app.php?action=library_page&page=" + page + "&limit=" + LIBRARY_PAGE_SIZE;
    ApiClient.Response r = api.getFast(path);
    if (r.code == 401 && restoreLibrarySession()) r = api.getFast(path);
    return r;
}

private ArrayList<Track> fetchLegacyCatalog() throws Exception {
    ApiClient.Response r = api.getCatalogLegacy("api/native_app.php?action=library_fast");
    if (r.code == 401 && restoreLibrarySession()) r = api.getCatalogLegacy("api/native_app.php?action=library_fast");
    JSONObject j = r.json();
    if (!r.ok() || j.optJSONArray("tracks") == null) {
        throw new Exception("HTTP " + r.code + " · " + j.optString("error", "Biblioteca indisponível"));
    }
    return decodeCatalog(j);
}

private ArrayList<Track> fetchCatalogFast() throws Exception {
    ArrayList<Track> all = new ArrayList<>();
    int page = 1;
    while (page <= LIBRARY_MAX_PAGES) {
        ApiClient.Response r = libraryPageRequest(page);
        JSONObject j = r.json();
        if (!r.ok()) {
            // Server 2.0.7 compatibility until the matching server ZIP is installed.
            if (r.code >= 400) return fetchLegacyCatalog();
            throw new Exception("HTTP " + r.code + " · " + j.optString("error", "Falha ao carregar biblioteca"));
        }
        JSONArray raw = j.optJSONArray("tracks");
        if (raw == null) return fetchLegacyCatalog();
        all.addAll(decodeCatalog(j));

        JSONObject paging = j.optJSONObject("paging");
        boolean hasMore = paging != null && paging.optBoolean("has_more", false);
        if (!hasMore) return all;
        int next = paging.optInt("next_page", page + 1);
        if (next <= page) next = page + 1;
        page = next;
    }
    throw new Exception("Biblioteca excedeu o limite de páginas de segurança");
}

private ArrayList<Track> forceCatalogSync() throws Exception {
    ApiClient.Response r = api.getLong("api/native_app.php?action=library_sync_only");
    if (r.code == 401 && restoreLibrarySession()) r = api.getLong("api/native_app.php?action=library_sync_only");
    JSONObject j = r.json();
    if (r.ok() && j.optBoolean("ok", false)) return fetchCatalogFast();

    // Server 2.0.7 compatibility: old sync endpoint returns the whole catalog.
    if (!r.ok()) {
        r = api.getLong("api/native_app.php?action=library_sync");
        if (r.code == 401 && restoreLibrarySession()) r = api.getLong("api/native_app.php?action=library_sync");
        j = r.json();
        if (r.ok() && j.optJSONArray("tracks") != null) return decodeCatalog(j);
        return fetchLegacyCatalog();
    }
    throw new Exception("HTTP " + r.code + " · " + j.optString("error", "Falha ao sincronizar biblioteca"));
}

private boolean librarySyncDue() {
    long last = prefs.getLong("library_sync_v207_ms", 0L);
    return System.currentTimeMillis() - last > 10L * 60L * 1000L;
}

private void rememberLibrarySync() {
    prefs.edit().putLong("library_sync_v207_ms", System.currentTimeMillis()).apply();
}

private void saveFreshCatalog(List<Track> tracks, boolean notifyNewFolders) {
    if (tracks == null || tracks.isEmpty()) return;
    int beforeFolders = library.folderNames(library.catalog()).size();
    library.saveCatalog(tracks);
    int afterFolders = library.folderNames(tracks).size();
    if (notifyNewFolders && afterFolders > beforeFolders && beforeFolders > 0) {
        int added = afterFolders - beforeFolders;
        ui.post(() -> toast(added + (added == 1 ? " nova pasta encontrada." : " novas pastas encontradas.")));
    }
}

private void syncCatalogInBackground() {
    if (!online()) return;
    io.execute(() -> {
        try {
            ArrayList<Track> fast = fetchCatalogFast();
            if (!fast.isEmpty()) saveFreshCatalog(fast, true);
            if (fast.isEmpty() || librarySyncDue()) {
                ArrayList<Track> fresh = forceCatalogSync();
                if (!fresh.isEmpty()) {
                    saveFreshCatalog(fresh, true);
                    rememberLibrarySync();
                }
            }
        } catch (Exception ignored) {
            // Library is optional for boot. Map, GPS and downloaded music stay available.
        }
    });
}

// ANR_CATALOG_BOOT_V211: disk/JSON work never runs on Android's main thread.
private void loadCatalogAndOpenChooser(boolean initial) {
    showLoading("Abrindo sua biblioteca…");
    io.execute(() -> {
        List<Track> local = library.catalog();
        if (!local.isEmpty()) {
            library.setSetupDone(true);
            ui.post(() -> {
                showFolderChooser(initial);
                syncCatalogInBackground();
            });
            return;
        }
        ui.post(() -> showLoading("Carregando sua biblioteca…"));
        try {
            ArrayList<Track> tracks = fetchCatalogFast();
            if (tracks.isEmpty()) {
                ui.post(() -> showLoading("Sincronizando suas músicas…"));
                tracks = forceCatalogSync();
                if (!tracks.isEmpty()) rememberLibrarySync();
            }
            ArrayList<Track> result = tracks;
            if (!result.isEmpty()) library.saveCatalog(result);
            library.setSetupDone(true);
            ui.post(() -> {
                if (!result.isEmpty()) {
                    showFolderChooser(initial);
                } else {
                    showHome();
                    alert("Biblioteca", "O servidor respondeu, mas ainda não há músicas disponíveis. Verifique as pastas do Google Drive no painel e toque em Gerenciar Biblioteca novamente.");
                }
            });
        } catch (Exception e) {
            ui.post(() -> {
                library.setSetupDone(true);
                showHome();
                alert("Biblioteca", "Não consegui atualizar as músicas agora. O restante do EstradaPlay continua funcionando. Tente novamente em Gerenciar Biblioteca.");
            });
        }
    });
}

private void refreshLibraryAndOpenChooser() {
    if (!online()) {
        toast("Sem internet para atualizar. Mostrando a biblioteca salva.");
        showFolderChooser(false);
        return;
    }
    showLoading("Atualizando suas pastas…");
    io.execute(() -> {
        try {
            ArrayList<Track> tracks = forceCatalogSync();
            if (!tracks.isEmpty()) {
                library.saveCatalog(tracks);
                rememberLibrarySync();
            }
            ui.post(() -> {
                showFolderChooser(false);
                toast(tracks.isEmpty() ? "Nenhuma música nova encontrada." : tracks.size() + " músicas disponíveis.");
            });
        } catch (Exception e) {
            ui.post(() -> {
                showFolderChooser(false);
                toast("Não consegui atualizar agora. Mantive a biblioteca salva.");
            });
        }
    });
}

    // ANR_FOLDER_UI_V210: parse/index the large catalog off the UI thread and virtualize folder rows.
    private void showFolderChooser(boolean initial) {
        showLoading("Organizando suas pastas…");
        io.execute(() -> {
            List<Track> catalog = library.catalog();
            Map<String, LibraryStore.FolderStat> stats = library.folderStats(catalog);
            ui.post(() -> renderFolderChooser(initial, stats));
        });
    }

    private void renderFolderChooser(boolean initial, Map<String, LibraryStore.FolderStat> stats) {
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
            Button close = compactButton("VOLTAR");
            header.addView(close, lp(84, 44));
            close.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        }
        page.addView(header);

        TextView explanation = text(initial ?
                "Baixe agora o que quer levar na estrada. Depois o player usa apenas os arquivos do aparelho." :
                "Adicione novas pastas ou remova somente a cópia offline. O conteúdo original continua no Drive.", 13, MUTED, false);
        page.addView(explanation); margins(explanation, 0, 8, 0, 12);

        LinearLayout storageCard = row();
        storageCard.setGravity(Gravity.CENTER_VERTICAL);
        storageCard.setPadding(dp(13), dp(10), dp(13), dp(10));
        storageCard.setBackground(bg(SURFACE_2, 14, BORDER));
        TextView storageLabel = overline("ARMAZENAMENTO", MUTED);
        storageCard.addView(storageLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView storage = chip(bytes(library.freeBytes()) + " LIVRES", GREEN, GREEN_SOFT);
        storageCard.addView(storage);
        page.addView(storageCard);

        Button all = compactButton("SELECIONAR TODAS NÃO BAIXADAS");
        page.addView(all, lp(-1, 46)); margins(all, 0, 10, 0, 6);
        Button refresh = compactButton("ATUALIZAR DO SERVIDOR");
        page.addView(refresh, lp(-1, 46)); margins(refresh, 0, 0, 0, 10);
        refresh.setEnabled(online());
        refresh.setOnClickListener(v -> refreshLibraryAndOpenChooser());

        // LIBRARY_REPAIR_ENTRY_V203: recovery must be visible from Gerenciar downloads.
        Button repair = compactButton("PROCURAR / LIMPAR MÚSICAS DO CELULAR");
        page.addView(repair, lp(-1, 46)); margins(repair, 0, 0, 0, 10);
        repair.setOnClickListener(v -> startActivity(new Intent(this, MusicStorageActivity.class)));

        FolderDownloadAdapter adapter = new FolderDownloadAdapter(stats, storage, initial);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(8));
        list.setAdapter(adapter);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        all.setOnClickListener(v -> adapter.toggleSelectAll());

        Button go = button(initial ? "BAIXAR E ENTRAR NO ESTRADAPLAY" : "BAIXAR SELECIONADAS", true);
        page.addView(go, lp(-1, 60)); margins(go, 0, 12, 0, 0);
        go.setOnClickListener(v -> {
            Set<String> selected = adapter.selectedFolders();
            if (selected.isEmpty()) {
                if (initial && library.hasDownloadedHint()) { library.setSetupDone(true); showHome(); }
                else toast(stats.isEmpty() ? "Nenhuma pasta disponível ainda." : "Escolha pelo menos uma pasta.");
                return;
            }
            long known = selectedBytes(selected, stats);
            long free = library.freeBytes();
            if (known > 0 && known > free * 0.92) {
                alert("Espaço insuficiente", "Selecionado: " + bytes(known) + "\nLivre: " + bytes(free));
                return;
            }
            startFolderDownload(selected, initial);
        });
    }

    private final class FolderDownloadAdapter extends BaseAdapter {
        private final ArrayList<LibraryStore.FolderStat> items = new ArrayList<>();
        private final LinkedHashMap<String, LibraryStore.FolderStat> stats = new LinkedHashMap<>();
        private final LinkedHashSet<String> selected = new LinkedHashSet<>();
        private final TextView storage;
        private final boolean initial;

        FolderDownloadAdapter(Map<String, LibraryStore.FolderStat> source, TextView storage, boolean initial) {
            if (source != null) {
                stats.putAll(source);
                items.addAll(source.values());
            }
            this.storage = storage;
            this.initial = initial;
        }

        Set<String> selectedFolders() { return new LinkedHashSet<>(selected); }

        void toggleSelectAll() {
            int incomplete = 0;
            for (LibraryStore.FolderStat stat : items) if (!stat.complete()) incomplete++;
            boolean select = selected.size() < incomplete;
            selected.clear();
            if (select) for (LibraryStore.FolderStat stat : items) if (!stat.complete()) selected.add(stat.name);
            notifyDataSetChanged();
            updateSelectedStorage(storage, selected, stats);
        }

        @Override public int getCount() { return items.size(); }
        @Override public LibraryStore.FolderStat getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LibraryStore.FolderStat stat = getItem(position);
            LinearLayout outer = column();
            outer.setPadding(0, 0, 0, dp(8));
            LinearLayout box = card();
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);

            boolean checked = selected.contains(stat.name);
            box.setBackground(bg(checked ? SURFACE_2 : SURFACE, 20, checked ? ACCENT : BORDER));
            TextView folderMark = badge("♪", stat.complete() ? GREEN : ACCENT, stat.complete() ? GREEN_SOFT : ACCENT_SOFT);
            box.addView(folderMark, lp(44, 44));

            LinearLayout meta = column();
            meta.addView(text(shortFolder(stat.name), 15, TEXT, true));
            String info = stat.downloaded + " de " + stat.total + " offline" + (stat.knownBytes > 0 ? " · " + bytes(stat.knownBytes) : "");
            meta.addView(text(info, 11, stat.complete() ? GREEN : MUTED, false));
            box.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
            margins(meta, 12, 0, 8, 0);

            if (stat.complete()) {
                Button remove = compactButton("REMOVER");
                remove.setTextColor(RED);
                box.addView(remove, lp(88, 42));
                remove.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Remover download?")
                        .setMessage(shortFolder(stat.name) + "\n\nAs músicas continuam no Google Drive e podem ser baixadas novamente.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Remover", (d, w) -> {
                            showLoading("Removendo cópia offline…");
                            io.execute(() -> {
                                int removed = library.removeFolder(stat.name);
                                ui.post(() -> {
                                    toast(removed + " arquivo(s) removido(s).");
                                    showFolderChooser(initial);
                                });
                            });
                        }).show());
            } else {
                CheckBox cb = new CheckBox(MainActivity.this);
                cb.setButtonTintList(new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                        new int[]{ACCENT, SUBTLE}));
                cb.setChecked(checked);
                box.addView(cb, lp(48, 48));
                cb.setOnCheckedChangeListener((b, value) -> {
                    if (value) selected.add(stat.name); else selected.remove(stat.name);
                    updateSelectedStorage(storage, selected, stats);
                    notifyDataSetChanged();
                });
                box.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            }

            outer.addView(box, new LinearLayout.LayoutParams(-1, -2));
            return outer;
        }
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
        BrandMarkView mark = new BrandMarkView(this); page.addView(mark, lp(62, 62));
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
        roadLiveState = null;
        roadLiveDetail = null;
        nowTitle = null;
        nowArtist = null;
        nowState = null;
        root.removeAllViews();

        // AUTOMOTIVE_RED_GOLD_V200_HOME: visual hierarchy follows an in-dash system,
        // not a generic Android dashboard. Decorative atmosphere is drawn natively.
        EpcBackdropView backdrop = new EpcBackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(12), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout system = row();
        system.setGravity(Gravity.CENTER_VERTICAL);
        Button menu = compactButton("☰");
        menu.setTextSize(20);
        system.addView(menu, lp(54, 48));
        menu.setOnClickListener(v -> startActivity(new Intent(this, DriveToolsActivity.class)));
        TextView clock = text(new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date()), 14, TEXT, true);
        clock.setGravity(Gravity.CENTER);
        system.addView(clock, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView active = chip(hasLocationPermission() ? "● SISTEMA ATIVO" : "○ GPS PENDENTE",
                hasLocationPermission() ? GREEN : ACCENT,
                hasLocationPermission() ? GREEN_SOFT : ACCENT_SOFT);
        system.addView(active);
        page.addView(system);

        LinearLayout brand = column();
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        BrandMarkView mark = new BrandMarkView(this);
        brand.addView(mark, lp(68, 68));
        TextView epc = text("EPC", 38, Color.rgb(247, 235, 211), true);
        epc.setGravity(Gravity.CENTER); epc.setLetterSpacing(.10f); brand.addView(epc);
        TextView full = overline("ESTRADA PLAY COMUNISTA", ACCENT); full.setGravity(Gravity.CENTER); brand.addView(full);
        page.addView(brand); margins(brand, 0, 12, 0, 0);

        LinearLayout hero = column();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView heroTitle = text("CENTRAL\nde VIAGEM", 38, Color.rgb(250, 241, 224), true);
        heroTitle.setGravity(Gravity.CENTER); heroTitle.setLineSpacing(0, .90f); hero.addView(heroTitle);
        TextView heroSub = overline("ROTA  ·  SOM  ·  PROTEÇÃO  ·  COPILOTO", Color.rgb(226,185,76));
        heroSub.setGravity(Gravity.CENTER); hero.addView(heroSub);
        page.addView(hero); margins(hero, 0, 18, 0, 16);

        LinearLayout selector = row();
        selector.setBackground(bg(Color.argb(185, 13, 7, 9), 22, Color.rgb(92, 39, 44)));
        selector.setPadding(dp(2), dp(2), dp(2), dp(2));

        LinearLayout free = column();
        free.setPadding(dp(16), dp(18), dp(16), dp(16));
        free.setGravity(Gravity.CENTER_HORIZONTAL);
        free.setBackground(bg(Color.rgb(112, 12, 27), 20, ACCENT));
        TextView roadGlyph = text("╱╲", 25, Color.rgb(241, 213, 164), true); roadGlyph.setGravity(Gravity.CENTER); free.addView(roadGlyph);
        TextView freeTitle = text("RODAR\nLIVRE", 22, Color.WHITE, true); freeTitle.setGravity(Gravity.CENTER); free.addView(freeTitle); margins(freeTitle,0,8,0,3);
        TextView freeSub = text("Explore sem destino", 10, Color.rgb(220,185,184), false); freeSub.setGravity(Gravity.CENTER); free.addView(freeSub);
        View fsp = new View(this); free.addView(fsp, new LinearLayout.LayoutParams(1,0,1));
        Button freeGo = button("→", true); freeGo.setTextSize(20); free.addView(freeGo, lp(58,48));
        freeGo.setOnClickListener(v -> { DestinationStore.clear(this); openCockpit(); });
        selector.addView(free, new LinearLayout.LayoutParams(0, dp(212), 1));

        LinearLayout route = column();
        route.setPadding(dp(16), dp(18), dp(16), dp(16));
        route.setGravity(Gravity.CENTER_HORIZONTAL);
        route.setBackground(bg(Color.argb(230, 14, 12, 13), 20, Color.rgb(91, 67, 58)));
        TextView target = text("◎", 31, Color.rgb(226,185,76), true); target.setGravity(Gravity.CENTER); route.addView(target);
        TextView routeTitle = text("DEFINIR\nDESTINO", 21, TEXT, true); routeTitle.setGravity(Gravity.CENTER); route.addView(routeTitle); margins(routeTitle,0,7,0,3);
        TextView routeSub = text("Informe um endereço", 10, MUTED, false); routeSub.setGravity(Gravity.CENTER); route.addView(routeSub);
        View rsp = new View(this); route.addView(rsp, new LinearLayout.LayoutParams(1,0,1));
        Button routeGo = button("→", false); routeGo.setTextSize(20); route.addView(routeGo, lp(58,48));
        routeGo.setOnClickListener(v -> startActivity(new Intent(this, DestinationActivity.class)));
        LinearLayout.LayoutParams routeLp = new LinearLayout.LayoutParams(0, dp(212), 1); routeLp.setMargins(dp(3),0,0,0); selector.addView(route, routeLp);
        page.addView(selector);

        LinearLayout protection = row();
        protection.setGravity(Gravity.CENTER_VERTICAL);
        protection.setPadding(dp(14), dp(12), dp(14), dp(12));
        protection.setBackground(bg(Color.argb(226, 14, 11, 12), 15, Color.rgb(54, 91, 65)));
        TextView shield = text("◈", 25, GREEN, true); shield.setGravity(Gravity.CENTER); protection.addView(shield, lp(44,44));
        LinearLayout protectText = column();
        roadLiveState = overline(hasLocationPermission() ? "PROTEÇÃO RODOVIÁRIA ATIVA" : "LOCALIZAÇÃO PENDENTE", hasLocationPermission()?GREEN:ACCENT);
        roadLiveDetail = text(RoadWeatherMonitor.compactStatus(this), 10, MUTED, false);
        protectText.addView(roadLiveState); protectText.addView(roadLiveDetail);
        protection.addView(protectText, new LinearLayout.LayoutParams(0,-2,1)); margins(protectText,10,0,4,0);
        TextView goProtect = text("›", 26, TEXT, true); goProtect.setGravity(Gravity.CENTER); protection.addView(goProtect, lp(38,44));
        protection.setClickable(true); protection.setOnClickListener(v -> openCockpit());
        page.addView(protection); margins(protection,0,12,0,0);

        LinearLayout media = row();
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.setPadding(dp(13), dp(12), dp(13), dp(12));
        media.setBackground(bg(Color.argb(232, 17, 11, 13), 15, BORDER));
        TextView cover = text("★", 24, Color.rgb(226,185,76), true); cover.setGravity(Gravity.CENTER); cover.setBackground(bg(Color.rgb(78,12,24),10,Color.rgb(130,31,45))); media.addView(cover, lp(54,54));
        LinearLayout song = column();
        nowState = overline("MÚSICA OFFLINE", ACCENT); song.addView(nowState);
        nowTitle = text("Biblioteca local", 16, TEXT, true); nowTitle.setMaxLines(1); song.addView(nowTitle);
        nowArtist = text("Toque para abrir o player", 10, MUTED, false); nowArtist.setMaxLines(1); song.addView(nowArtist);
        media.addView(song, new LinearLayout.LayoutParams(0,-2,1)); margins(song,10,0,8,0);
        Button player = button("▶", true); player.setTextSize(19); media.addView(player, lp(54,54));
        player.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        media.setClickable(true); media.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        page.addView(media); margins(media,0,9,0,0);

        LinearLayout quick = row();
        Button climate = compactButton("CLIMA  ·  " + shortWeather());
        Button copilot = compactButton("COPILOTO  ·  FALAR");
        quick.addView(climate, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams qcp = new LinearLayout.LayoutParams(0, dp(52), 1); qcp.setMargins(dp(8),0,0,0); quick.addView(copilot, qcp);
        climate.setOnClickListener(v -> startActivity(new Intent(this, WeatherActivity.class)));
        copilot.setOnClickListener(v -> startActivity(new Intent(this, VoiceCommandActivity.class)));
        page.addView(quick); margins(quick,0,9,0,0);

        TextView foot = overline("EPC 2.0  ·  CENTRAL AUTOMOTIVA", Color.rgb(118,84,77));
        foot.setGravity(Gravity.CENTER); page.addView(foot); margins(foot,0,18,0,0);
        page.postDelayed(() -> EpcMotion.stagger(page), 55L);
    }

    private String shortWeather() {
        String s = RoadWeatherMonitor.compactStatus(this);
        if (s == null || s.trim().isEmpty()) return "AGUARDANDO";
        s = s.replace('\n',' ').trim();
        return s.length() > 26 ? s.substring(0,26) + "…" : s;
    }

    private void showMusic() {
        clearDownloadViews();
        roadLiveState = null; roadLiveDetail = null;
        showLoading("Abrindo sua música offline…");
        io.execute(() -> {
            List<Track> tracks = library.downloadedTracks();
            ui.post(() -> renderMusic(tracks));
        });
    }

    private void renderMusic(List<Track> tracks) {
        clearDownloadViews();
        roadLiveState = null; roadLiveDetail = null;
        if (tracks == null || tracks.isEmpty()) { loadCatalogAndOpenChooser(false); return; }
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

    // STABILITY_V152_ASYNC_ROAD_SCREEN: state-pack JSON never opens on the UI thread.
    private void showRoad() {
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = column(); page.setPadding(dp(18), dp(10), dp(18), dp(26));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Estrada"));

        LinearLayout live = featureCard(GREEN); page.addView(live); margins(live, 0, 8, 0, 0);
        LinearLayout liveTop = row(); liveTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView liveBadge = chip(hasLocationPermission() ? "PROTEÇÃO ATIVA" : "GPS DESATIVADO",
                hasLocationPermission() ? GREEN : RED,
                hasLocationPermission() ? GREEN_SOFT : Color.rgb(63, 27, 31));
        liveTop.addView(liveBadge);
        TextView passive = overline("SEM ROTA", MUTED); liveTop.addView(passive, new LinearLayout.LayoutParams(0, -2, 1));
        passive.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        live.addView(liveTop);
        roadLiveState = text(hasLocationPermission() ? "Monitorando sua direção" : "Ative a localização", 27, TEXT, true);
        live.addView(roadLiveState); margins(roadLiveState, 0, 16, 0, 4);
        roadLiveDetail = text("Carregando resumo offline sem bloquear a tela…", 13, MUTED, false);
        live.addView(roadLiveDetail);

        TextView coverageLabel = overline("COBERTURA OFFLINE", MUTED); page.addView(coverageLabel); margins(coverageLabel, 0, 20, 0, 8);
        LinearLayout coverage = card(); page.addView(coverage);
        TextView coverageInfo = text("RJ · MG · ES · lendo base local…", 13, TEXT, true);
        coverage.addView(coverageInfo);
        TextView coverageDetail = text("A proteção usa os arquivos já salvos no aparelho. A leitura detalhada acontece fora da interface.", 11, MUTED, false);
        coverage.addView(coverageDetail); margins(coverageDetail, 0, 8, 0, 0);

        TextView alertsLabel = overline("O QUE O APP OBSERVA", MUTED); page.addView(alertsLabel); margins(alertsLabel, 0, 20, 0, 8);
        LinearLayout types = card(); page.addView(types);
        LinearLayout r1 = row();
        r1.addView(alertType("RADAR", "velocidade", ACCENT), new LinearLayout.LayoutParams(0, dp(72), 1));
        r1.addView(alertType("SEMÁFORO", "sinalização", BLUE), new LinearLayout.LayoutParams(0, dp(72), 1));
        margins(r1.getChildAt(1), 8, 0, 0, 0); types.addView(r1);
        LinearLayout r2 = row();
        r2.addView(alertType("LOMBADA", "quebra-molas", PURPLE), new LinearLayout.LayoutParams(0, dp(72), 1));
        r2.addView(alertType("PEDÁGIO", "e ferrovia", GREEN), new LinearLayout.LayoutParams(0, dp(72), 1));
        margins(r2.getChildAt(1), 8, 0, 0, 0); types.addView(r2); margins(r2, 0, 8, 0, 0);

        Button action = button(hasLocationPermission() ? "GARANTIR PROTEÇÃO ATIVA" : "ATIVAR LOCALIZAÇÃO", true);
        page.addView(action, lp(-1, 58)); margins(action, 0, 14, 0, 0);
        action.setOnClickListener(v -> {
            if (!hasLocationPermission()) startActivity(new Intent(this, GateActivity.class));
            else { startRoadSafetyIfAllowed(); toast("Proteção da estrada ativa."); }
        });

        io.execute(() -> {
            try {
                RoadPackStore store = new RoadPackStore(getApplicationContext());
                final int points = store.hazardCount();
                final int states = store.coreStatePackCount();
                final String core = store.coreStatesStatus();
                ui.post(() -> {
                    if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    coverageInfo.setText(core + " · " + points + " pontos");
                    coverageDetail.setText(states == 3
                            ? "Núcleo offline completo. Radares e perigos permanecem disponíveis sem internet."
                            : "Núcleo offline " + states + "/3. Conecte-se para completar os estados restantes.");
                    if (roadLiveDetail != null) roadLiveDetail.setText(points + " pontos de segurança disponíveis no aparelho");
                });
            } catch (Throwable ignored) {
                ui.post(() -> coverageInfo.setText("Base offline temporariamente indisponível"));
            }
        });
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
        device.addView(infoRow("Músicas offline", library.hasDownloadedHint() ? "Biblioteca preparada" : "Nenhuma"));
        device.addView(divider());
        device.addView(infoRow("Proteção da estrada", hasLocationPermission() ? "Ativa neste aparelho" : "GPS desativado"));
        device.addView(divider());
        device.addView(infoRow("Versão do app", BuildConfig.VERSION_NAME));

        Button logout = dangerButton("SAIR DA CONTA"); page.addView(logout, lp(-1, 54)); margins(logout, 0, 18, 0, 0);
        logout.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Sair da conta?").setMessage("Músicas e dados de proteção já baixados permanecem neste aparelho.").setNegativeButton("Cancelar", null).setPositiveButton("Sair", (d,w) -> {
            api.clearSession(); account = new JSONObject(); prefs.edit().remove(KEY_ACCOUNT).apply(); showAuth(false, null);
        }).show());
    }

    private View topBar(String screen) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(7));
        BrandMarkView mark = new BrandMarkView(this);
        bar.addView(mark, lp(40, 40));
        LinearLayout brand = column();
        TextView section = overline("CENTRAL  /  " + screen.toUpperCase(Locale.ROOT), ACCENT); brand.addView(section);
        TextView logo = text("EPC", 17, TEXT, true); brand.addView(logo);
        bar.addView(brand, new LinearLayout.LayoutParams(0, dp(54), 1)); margins(brand, 10, 0, 0, 0);
        Button command = compactButton("PAINEL");
        command.setTextColor(Color.rgb(226, 185, 76));
        command.setBackground(bg(Color.rgb(24, 12, 15), 3, Color.rgb(108, 65, 57)));
        bar.addView(command, lp(90, 44));
        command.setOnClickListener(v -> openMenu(screen));
        return bar;
    }

    private void openMenu(String screen) {
        AppMenuOverlay.show(this, root, screen, which -> {
            if (which == 0) showHome();
            else if (which == 1) showMusic();
            else if (which == 2) { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para sincronizar pastas."); }
            else if (which == 3) startActivity(new Intent(this, RoadMapActivity.class));
            else showAccount();
        });
    }

    private void showOfflineSetupBlocked() {
        root.removeAllViews();
        LinearLayout page = column(); page.setGravity(Gravity.CENTER); page.setPadding(dp(28), dp(28), dp(28), dp(28)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        BrandMarkView mark = new BrandMarkView(this); page.addView(mark, lp(62, 62));
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
        BrandMarkView mark = new BrandMarkView(this); box.addView(mark, lp(58, 58));
        ProgressBar p = new ProgressBar(this); if (Build.VERSION.SDK_INT >= 21) p.setIndeterminateTintList(ColorStateList.valueOf(ACCENT)); box.addView(p, lp(42, 42)); margins(p, 0, 24, 0, 0);
        TextView brand = text("Estrada Play Comunista", 22, TEXT, true); brand.setGravity(Gravity.CENTER); box.addView(brand); margins(brand, 0, 14, 0, 3);
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
            InternalBroadcasts.register(this, downloadReceiver, d);
            InternalBroadcasts.register(this, playerReceiver, p);
            InternalBroadcasts.register(this, roadReceiver, r);
        } else {
            InternalBroadcasts.register(this, downloadReceiver, d);
            InternalBroadcasts.register(this, playerReceiver, p);
            InternalBroadcasts.register(this, roadReceiver, r);
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

    // AUTOMOTIVE_INPUT_ALL_SCREENS_V177
    private void automotiveInput(View view) {
        if (view == null) return;
        view.setEnabled(true);
        view.setClickable(true);
        view.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.performClick();
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE) return true;
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                return true;
            }
            return false;
        });
        view.setOnGenericMotionListener((v, event) -> {
            if (event != null && event.getActionMasked() == android.view.MotionEvent.ACTION_BUTTON_PRESS) {
                v.performClick();
                return true;
            }
            return false;
        });
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event == null || event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                v.performClick();
                return true;
            }
            return false;
        });
    }

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
        automotiveInput(l);
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
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setLetterSpacing(0.08f); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(primary ? Color.WHITE : TEXT); b.setBackground(bg(primary ? ACCENT : SURFACE_2, 15, primary ? 0 : BORDER)); b.setStateListAnimator(null); automotiveInput(b); return b;
    }

    private Button compactButton(String value) {
        Button b = button(value, false); b.setTextSize(9.5f); b.setLetterSpacing(0.06f); return b;
    }

    private Button textButton(String value) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(MUTED); b.setBackgroundColor(Color.TRANSPARENT); b.setStateListAnimator(null); automotiveInput(b); return b;
    }

    private Button dangerButton(String value) {
        Button b = compactButton(value); b.setTextColor(RED); b.setBackground(bg(SURFACE, 15, Color.rgb(80, 39, 43))); return b;
    }

    private Button playerButton(String value, boolean primary) {
        Button b = button(value, primary); b.setTextSize(primary ? 12 : 18); return b;
    }

    private Button menuButton() {
        Button b = compactButton("☰");
        b.setTextColor(TEXT);
        b.setTextSize(19);
        b.setLetterSpacing(0f);
        return b;
    }

    private Button chipButton(String value, boolean selected) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(value); b.setTextSize(10); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setStateListAnimator(null); styleChipButton(b, selected); automotiveInput(b); return b;
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
    private void alertWithRetry(String title, String message, Runnable retry) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Voltar", (d,w) -> { if (hasAccount()) showHome(); else showAuth(false, null); }).setPositiveButton("Tentar novamente", (d,w) -> retry.run()).show(); }

    private void clearDownloadViews() { downloadTitle = null; downloadState = null; downloadProgress = null; }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                startRoadSafetyIfAllowed();
                openCockpit();
            } else {
                pendingOpenCockpitAfterPermission = false;
                showLocationPermissionGate();
            }
            return;
        }
        if (requestCode == REQ_NOTIFICATIONS) {
            if (pendingOpenCockpitAfterPermission) {
                pendingOpenCockpitAfterPermission = false;
                launchCockpitNow();
            }
        }
    }

    // NATIVE_TOUCH_ALL_SCREENS_V176
    private void ensureInteractiveWindow176() {
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            View decor = getWindow().getDecorView();
            if (decor != null) {
                decor.setEnabled(true);
                decor.setClickable(false);
                decor.setFocusable(false);
            }
            if (root != null) {
                root.setEnabled(true);
                root.setClickable(false);
                root.setFocusable(false);
            }
        } catch (Throwable ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        ensureInteractiveWindow176();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ensureInteractiveWindow176();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (hasAccount() && library != null) openConfiguredTarget();
    }

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
