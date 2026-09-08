package com.estradaplay.comunista;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Music browser shown over RoadMapActivity. It never redirects the driver to Central. */
final class RoadMusicChooserOverlay {
    private static final String TAG = "estrada-play-road-music-chooser";
    private static final String ALL = "__ALL__";

    private RoadMusicChooserOverlay() {}

    static void show(Activity activity, FrameLayout host) {
        if (activity == null || host == null) return;
        View old = host.findViewWithTag(TAG);
        if (old != null) host.removeView(old);
        new Sheet(activity, host).show();
    }

    private static final class Sheet {
        final Activity a;
        final FrameLayout host;
        final EstradaTheme theme;
        final FrameLayout overlay;
        final LinearLayout panel;
        final ArrayList<Track> all = new ArrayList<>();
        final ArrayList<Track> visible = new ArrayList<>();
        final TrackAdapter adapter;
        final LinearLayout folders;
        final TextView count;
        final EditText search;
        String selectedFolder = ALL;

        Sheet(Activity a, FrameLayout host) {
            this.a = a;
            this.host = host;
            this.theme = EstradaTheme.get(a);
            this.overlay = new FrameLayout(a);
            overlay.setTag(TAG);
            overlay.setBackgroundColor(Color.argb(175, 0, 0, 0));
            overlay.setClickable(true);
            overlay.setFocusable(true);

            boolean landscape = a.getResources().getDisplayMetrics().widthPixels > a.getResources().getDisplayMetrics().heightPixels;
            panel = PremiumUi.col(a);
            panel.setPadding(dp(16), dp(14), dp(16), dp(14));
            panel.setBackground(PremiumUi.panel(a, theme.glass, theme.border, theme.radiusDp + 4));

            FrameLayout.LayoutParams pp;
            if (landscape) {
                int width = Math.min(dp(560), Math.round(a.getResources().getDisplayMetrics().widthPixels * .48f));
                pp = new FrameLayout.LayoutParams(width, -1, Gravity.RIGHT);
                pp.setMargins(0, dp(8), dp(8), dp(8));
            } else {
                int height = Math.min(dp(690), Math.round(a.getResources().getDisplayMetrics().heightPixels * .78f));
                pp = new FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM);
                pp.setMargins(dp(8), 0, dp(8), dp(8));
            }
            overlay.addView(panel, pp);

            LinearLayout head = PremiumUi.row(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout words = PremiumUi.col(a);
            words.addView(PremiumUi.overline(a, "MÚSICA NA ESTRADA", theme.secondary));
            words.addView(PremiumUi.text(a, "Escolher música", 22, theme.text, true));
            head.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
            Button close = PremiumUi.button(a, "×", false);
            close.setTextSize(22);
            close.setOnClickListener(v -> close());
            head.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
            panel.addView(head);

            search = new EditText(a);
            search.setSingleLine(true);
            search.setHint("Buscar música ou artista");
            search.setHintTextColor(theme.muted);
            search.setTextColor(theme.text);
            search.setTextSize(14);
            search.setPadding(dp(14), 0, dp(14), 0);
            search.setBackground(PremiumUi.panel(a, PremiumUi.withAlpha(theme.surfaceAlt, 248), theme.border, theme.radiusDp));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
            sp.setMargins(0, dp(12), 0, 0);
            panel.addView(search, sp);

            HorizontalScrollView folderScroll = new HorizontalScrollView(a);
            folderScroll.setHorizontalScrollBarEnabled(false);
            folders = PremiumUi.row(a);
            folders.setGravity(Gravity.CENTER_VERTICAL);
            folderScroll.addView(folders, new HorizontalScrollView.LayoutParams(-2, -1));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(50));
            fp.setMargins(0, dp(7), 0, 0);
            panel.addView(folderScroll, fp);

            count = PremiumUi.text(a, "Carregando biblioteca…", 10, theme.muted, false);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMargins(dp(3), dp(2), 0, dp(7));
            panel.addView(count, cp);

            ListView list = new ListView(a);
            list.setDividerHeight(0);
            list.setBackgroundColor(Color.TRANSPARENT);
            list.setCacheColorHint(Color.TRANSPARENT);
            list.setFastScrollEnabled(true);
            adapter = new TrackAdapter();
            list.setAdapter(adapter);
            list.setOnItemClickListener((p, v, pos, id) -> {
                Track t = pos >= 0 && pos < visible.size() ? visible.get(pos) : null;
                if (t != null) play(t);
            });
            panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int after) {}
                @Override public void onTextChanged(CharSequence s, int st, int before, int c) {}
                @Override public void afterTextChanged(Editable e) { filter(); }
            });
            overlay.setOnClickListener(v -> { if (v == overlay) close(); });
            panel.setOnClickListener(v -> {});
        }

        void show() {
            host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
            overlay.setAlpha(0f);
            panel.setTranslationY(dp(24));
            overlay.animate().alpha(1f).setDuration(150L).start();
            panel.animate().translationY(0f).setDuration(190L).start();
            load();
        }

        void load() {
            new Thread(() -> {
                List<Track> own = FastMusicLibrary.downloadedTracks(a);
                List<Track> merged = PhoneMp3Store.mergeCached(a, own);
                a.runOnUiThread(() -> {
                    all.clear();
                    if (merged != null) for (Track t : merged) if (t != null) all.add(t);
                    all.sort((x, y) -> {
                        int f = fold(LibraryStore.folderKey(x)).compareTo(fold(LibraryStore.folderKey(y)));
                        if (f != 0) return f;
                        int n = fold(x.title).compareTo(fold(y.title));
                        return n != 0 ? n : fold(x.artist).compareTo(fold(y.artist));
                    });
                    rebuildFolders();
                    filter();
                });
            }, "road-music-browser").start();
        }

        void rebuildFolders() {
            folders.removeAllViews();
            addFolder("Todas", ALL);
            TreeSet<String> names = new TreeSet<>((x,y) -> fold(x).compareTo(fold(y)));
            for (Track t : all) names.add(LibraryStore.folderKey(t));
            for (String f : names) addFolder(shortFolder(f), f);
        }

        void addFolder(String label, String key) {
            boolean active = key.equals(selectedFolder);
            Button b = PremiumUi.button(a, label, active);
            b.setTextSize(10);
            if (!active) b.setBackground(PremiumUi.panel(a, PremiumUi.withAlpha(theme.surfaceAlt, 245), theme.border, 13));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(38));
            p.setMargins(0, dp(5), dp(7), dp(5));
            folders.addView(b, p);
            b.setOnClickListener(v -> { selectedFolder = key; rebuildFolders(); filter(); });
        }

        void filter() {
            String q = fold(search.getText() == null ? "" : search.getText().toString());
            visible.clear();
            for (Track t : all) {
                if (t == null) continue;
                String folder = LibraryStore.folderKey(t);
                if (!ALL.equals(selectedFolder) && !selectedFolder.equals(folder)) continue;
                if (!q.isEmpty()) {
                    String hay = fold(t.title + " " + t.artist + " " + t.album + " " + folder);
                    if (!hay.contains(q)) continue;
                }
                visible.add(t);
            }
            adapter.notifyDataSetChanged();
            count.setText(visible.size() + (visible.size() == 1 ? " música" : " músicas") + " disponíveis");
        }

        void play(Track track) {
            try {
                String queueToken = PlayerService.stageQueue(a, new ArrayList<>(visible));
                Intent i = new Intent(a, PlayerService.class).setAction(PlayerService.ACTION_PLAY_TRACK);
                i.putExtra(PlayerService.EXTRA_KEY, track.key());
                i.putExtra(PlayerService.EXTRA_FOLDER, ALL.equals(selectedFolder) ? "__ALL__" : selectedFolder);
                if (queueToken != null && !queueToken.trim().isEmpty()) i.putExtra(PlayerService.EXTRA_QUEUE_TOKEN, queueToken);
                i.putExtra(PlayerService.EXTRA_TRACK_JSON, track.toStored().toString());
                if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
                Toast.makeText(a, "Tocando: " + track.title, Toast.LENGTH_SHORT).show();
                close();
            } catch (Throwable e) {
                Toast.makeText(a, "Não consegui tocar esta música agora.", Toast.LENGTH_SHORT).show();
            }
        }

        void close() {
            if (overlay.getParent() == null) return;
            overlay.animate().alpha(0f).setDuration(120L).withEndAction(() -> {
                try { host.removeView(overlay); } catch (Throwable ignored) {}
            }).start();
        }

        final class TrackAdapter extends BaseAdapter {
            @Override public int getCount() { return visible.size(); }
            @Override public Track getItem(int position) { return position >= 0 && position < visible.size() ? visible.get(position) : null; }
            @Override public long getItemId(int position) { Track t = getItem(position); return t == null ? position : t.key().hashCode(); }
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                Track t = getItem(position);
                LinearLayout row = PremiumUi.row(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(7), dp(6), dp(7));
                TextView art = PremiumUi.text(a, "♪", 19, theme.secondary, true);
                art.setGravity(Gravity.CENTER);
                art.setBackground(PremiumUi.panel(a, PremiumUi.withAlpha(theme.primary, 28), PremiumUi.withAlpha(theme.primary, 85), 14));
                row.addView(art, new LinearLayout.LayoutParams(dp(46), dp(46)));
                LinearLayout meta = PremiumUi.col(a);
                TextView tt = PremiumUi.text(a, t == null ? "" : t.title, 14, theme.text, true);
                tt.setSingleLine(true);
                meta.addView(tt);
                TextView aa = PremiumUi.text(a, t == null ? "" : (t.artist + " · " + shortFolder(LibraryStore.folderKey(t))), 10, theme.muted, false);
                aa.setSingleLine(true);
                meta.addView(aa);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f); mp.setMargins(dp(10),0,dp(6),0); row.addView(meta, mp);
                TextView play = PremiumUi.text(a, "▶", 15, theme.primary, true); play.setGravity(Gravity.CENTER); row.addView(play, new LinearLayout.LayoutParams(dp(42), dp(42)));
                return row;
            }
        }

        int dp(float v) { return PremiumUi.dp(a, v); }
    }

    private static String fold(String raw) {
        String n = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String shortFolder(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "Sem pasta";
        String[] parts = s.replace('\\', '/').split("/");
        return parts.length == 0 ? s : parts[parts.length - 1];
    }
}
