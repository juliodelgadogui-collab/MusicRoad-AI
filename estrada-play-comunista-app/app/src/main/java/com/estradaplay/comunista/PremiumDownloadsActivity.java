package com.estradaplay.comunista;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Premium replacement for the legacy MainActivity library/download screen. */
public final class PremiumDownloadsActivity extends ComponentActivity {
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 100;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private ApiClient api;
    private LibraryStore store;
    private FrameLayout content;
    private LinearLayout list;
    private TextView state;
    private Button refresh;
    private boolean receiverRegistered;

    private final BroadcastReceiver downloads = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean active = intent.getBooleanExtra("active", false);
            int total = intent.getIntExtra("total", 0);
            int done = intent.getIntExtra("done", 0);
            int failed = intent.getIntExtra("failed", 0);
            String current = intent.getStringExtra("current");
            if (state != null) {
                if (active) state.setText("Baixando " + (done + failed) + " de " + total + (current == null || current.trim().isEmpty() ? "" : " · " + current.trim()));
                else state.setText(failed > 0 ? "Concluído com " + failed + " falha(s)" : "Biblioteca offline atualizada");
            }
            if (!active) load(false);
        }
    };

    @Override protected void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        api = new ApiClient(this);
        store = new LibraryStore(this);
        build();
        registerDownloads();
        load(store.catalog().isEmpty());
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) try { unregisterReceiver(downloads); } catch (Throwable ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        content = new FrameLayout(this);
        content.setBackgroundColor(theme.background);
        setContentView(UnifiedAppShell.wrap(this, "music", content));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = PremiumUi.col(this);
        page.setPadding(dp(16), dp(14), dp(16), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        page.addView(PremiumUi.overline(this, "BIBLIOTECA OFFLINE", theme.secondary));
        TextView title = PremiumUi.text(this, "Downloads de música", 27, theme.text, true);
        page.addView(title);
        margins(title, 0, 4, 0, 4);
        page.addView(PremiumUi.text(this, "Escolha o que fica salvo no aparelho. A reprodução continua disponível sem internet.", 11, theme.muted, false));

        LinearLayout actions = PremiumUi.row(this);
        refresh = PremiumUi.button(this, "ATUALIZAR SERVIDOR", false);
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button files = PremiumUi.button(this, "ARQUIVOS", false);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        fp.setMargins(dp(8), 0, 0, 0);
        actions.addView(files, fp);
        page.addView(actions);
        margins(actions, 0, 16, 0, 0);
        refresh.setOnClickListener(v -> load(true));
        files.setOnClickListener(v -> startActivity(new Intent(this, MusicStorageActivity.class)));

        state = PremiumUi.text(this, "Carregando biblioteca…", 11, theme.muted, false);
        page.addView(state);
        margins(state, 0, 10, 0, 10);

        list = PremiumUi.col(this);
        page.addView(list);
    }

    private void load(boolean refreshServer) {
        if (io.isShutdown()) return;
        if (refresh != null) refresh.setEnabled(false);
        if (state != null) state.setText(refreshServer ? "Atualizando catálogo…" : "Lendo biblioteca…");
        io.execute(() -> {
            List<Track> tracks = store.catalog();
            String error = null;
            if (refreshServer || tracks.isEmpty()) {
                try {
                    tracks = fetchCatalog();
                    if (!tracks.isEmpty()) store.saveCatalog(tracks);
                } catch (Throwable e) {
                    error = "Não consegui atualizar o servidor agora.";
                    tracks = store.catalog();
                }
            }
            final List<Track> result = tracks == null ? Collections.emptyList() : new ArrayList<>(tracks);
            final String message = error;
            runOnUiThread(() -> render(result, message));
        });
    }

    private List<Track> fetchCatalog() throws Exception {
        ArrayList<Track> all = new ArrayList<>();
        int page = 1;
        while (page <= MAX_PAGES) {
            ApiClient.Response r = api.getFast("api/native_app.php?action=library_page&page=" + page + "&limit=" + PAGE_SIZE);
            JSONObject j = r.json();
            JSONArray arr = j.optJSONArray("tracks");
            if (!r.ok() || arr == null) return fetchLegacy();
            decode(arr, all);
            JSONObject paging = j.optJSONObject("paging");
            boolean more = paging != null && paging.optBoolean("has_more", false);
            if (!more) return all;
            int next = paging.optInt("next_page", page + 1);
            page = next <= page ? page + 1 : next;
        }
        return all;
    }

    private List<Track> fetchLegacy() throws Exception {
        ApiClient.Response r = api.getCatalogLegacy("api/native_app.php?action=library_fast");
        JSONObject j = r.json();
        JSONArray arr = j.optJSONArray("tracks");
        if (!r.ok() || arr == null) throw new Exception("Biblioteca indisponível");
        ArrayList<Track> out = new ArrayList<>();
        decode(arr, out);
        return out;
    }

    private void decode(JSONArray arr, List<Track> out) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Track t = Track.fromServer(o, api);
            if (t != null && t.remoteSource != null && !t.remoteSource.trim().isEmpty()) out.add(t);
        }
    }

    private void render(List<Track> tracks, String error) {
        if (refresh != null) refresh.setEnabled(true);
        if (list == null) return;
        list.removeAllViews();
        Map<String, LibraryStore.FolderStat> stats = store.folderStats(tracks);
        if (error != null && state != null) state.setText(error + " Mostrando a biblioteca salva.");
        else if (state != null) state.setText(tracks.size() + " músicas · " + stats.size() + " pastas");

        if (stats.isEmpty()) {
            LinearLayout empty = PremiumUi.col(this);
            empty.setPadding(dp(16), dp(18), dp(16), dp(18));
            empty.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
            empty.addView(PremiumUi.text(this, "Nenhuma pasta disponível", 16, theme.text, true));
            empty.addView(PremiumUi.text(this, "Toque em Atualizar servidor ou use Arquivos para músicas já salvas no celular.", 10, theme.muted, false));
            list.addView(empty);
            return;
        }

        for (LibraryStore.FolderStat stat : stats.values()) addFolder(stat);
    }

    private void addFolder(LibraryStore.FolderStat stat) {
        LinearLayout card = PremiumUi.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));

        TextView icon = PremiumUi.text(this, "♪", 20, stat.complete() ? theme.success : theme.secondary, true);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout words = PremiumUi.col(this);
        TextView name = PremiumUi.text(this, shortFolder(stat.name), 14, theme.text, true);
        name.setSingleLine(true);
        words.addView(name);
        String detail = stat.downloaded + " de " + stat.total + " offline";
        words.addView(PremiumUi.text(this, detail, 10, stat.complete() ? theme.success : theme.muted, false));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.setMargins(dp(8), 0, dp(8), 0);
        card.addView(words, wp);

        Button action = PremiumUi.button(this, stat.complete() ? "REMOVER" : "BAIXAR", !stat.complete());
        action.setTextSize(9);
        card.addView(action, new LinearLayout.LayoutParams(dp(92), dp(44)));
        if (stat.complete()) action.setTextColor(theme.danger);
        action.setOnClickListener(v -> {
            action.setEnabled(false);
            if (stat.complete()) remove(stat.name);
            else download(stat.name);
        });

        list.addView(card);
        margins(card, 0, 0, 0, 8);
    }

    private void download(String folder) {
        try {
            JSONArray folders = new JSONArray();
            folders.put(folder);
            Intent i = new Intent(this, DownloadService.class)
                    .setAction(DownloadService.ACTION_START)
                    .putExtra(DownloadService.EXTRA_FOLDERS, folders.toString());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            toast("Download iniciado. Você pode continuar usando o Estrada Play.");
            if (state != null) state.setText("Preparando download…");
        } catch (Throwable e) {
            toast("Não consegui iniciar o download.");
            load(false);
        }
    }

    private void remove(String folder) {
        io.execute(() -> {
            int removed = 0;
            try { removed = store.removeFolder(folder); } catch (Throwable ignored) {}
            final int count = removed;
            runOnUiThread(() -> {
                toast(count + " arquivo(s) removido(s).");
                load(false);
            });
        });
    }

    private void registerDownloads() {
        if (receiverRegistered) return;
        InternalBroadcasts.register(this, downloads, new IntentFilter(DownloadService.ACTION_STATE));
        receiverRegistered = true;
    }

    private static String shortFolder(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "Sem pasta";
        String[] parts = s.replace('\\', '/').split("/");
        return parts.length == 0 ? s : parts[parts.length - 1];
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private int dp(float v) { return PremiumUi.dp(this, v); }
    private void margins(View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(p);
    }
}
