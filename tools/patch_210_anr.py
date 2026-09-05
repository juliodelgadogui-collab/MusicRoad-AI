from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

# LibraryStore: cache large JSON and avoid parsing downloaded index once per catalog track.
p = ROOT / 'estradaplay-app-v2/app/src/main/java/com/estradaplay/app/LibraryStore.java'
s = p.read_text(encoding='utf-8')

if 'ANR_LIBRARY_INDEX_V210' not in s:
    s = s.replace(
        '    private final File catalogFile;\n',
        '    private final File catalogFile;\n'
        '    // ANR_LIBRARY_INDEX_V210: large catalog/download indexes are cached in memory.\n'
        '    private volatile List<Track> catalogCache;\n'
        '    private volatile List<Track> downloadedCache;\n'
        '    private volatile String downloadedCacheRaw;\n',
        1,
    )

    s = s.replace(
        '        if (!tmp.renameTo(catalogFile)) { tmp.delete(); return; }\n        prefs.edit().remove(KEY_CATALOG).apply();',
        '        if (!tmp.renameTo(catalogFile)) { tmp.delete(); return; }\n'
        '        catalogCache = tracks == null ? new ArrayList<>() : new ArrayList<>(tracks);\n'
        '        prefs.edit().remove(KEY_CATALOG).apply();',
        1,
    )

    s = s.replace(
        '    synchronized List<Track> catalog() {\n        ArrayList<Track> out = new ArrayList<>();',
        '    synchronized List<Track> catalog() {\n'
        '        List<Track> cached = catalogCache;\n'
        '        if (cached != null) return new ArrayList<>(cached);\n'
        '        ArrayList<Track> out = new ArrayList<>();',
        1,
    )

    old_tail = '''        } catch (Exception ignored) {}\n        return out;\n    }\n\n    private String readCatalogFile()'''
    new_tail = '''        } catch (Exception ignored) {}\n        catalogCache = new ArrayList<>(out);\n        return out;\n    }\n\n    private String readCatalogFile()'''
    if old_tail not in s:
        raise SystemExit('catalog() tail anchor not found')
    s = s.replace(old_tail, new_tail, 1)

    s = s.replace(
        '            prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();\n        } catch (Exception ignored) {}\n    }\n\n    Track localFor',
        '            String raw = all.toString();\n'
        '            prefs.edit().putString(KEY_DOWNLOADED, raw).apply();\n'
        '            downloadedCache = null; downloadedCacheRaw = null;\n'
        '        } catch (Exception ignored) {}\n'
        '    }\n\n    Track localFor',
        1,
    )
    s = s.replace(
        '            all.remove(track.key());\n            prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();',
        '            all.remove(track.key());\n'
        '            prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();\n'
        '            downloadedCache = null; downloadedCacheRaw = null;',
        1,
    )

    pattern = re.compile(r'    List<Track> downloadedTracks\(\) \{.*?\n    \}\n\n    boolean hasSetupDone', re.S)
    replacement = r'''    List<Track> downloadedTracks() {
        String raw = prefs.getString(KEY_DOWNLOADED, "{}");
        if (raw == null) raw = "{}";
        List<Track> cached = downloadedCache;
        String cachedRaw = downloadedCacheRaw;
        if (cached != null && raw.equals(cachedRaw)) return new ArrayList<>(cached);

        ArrayList<Track> out = new ArrayList<>();
        JSONObject all;
        try { all = new JSONObject(raw); } catch (Exception e) { all = new JSONObject(); }
        ArrayList<String> stale = new ArrayList<>();
        java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONObject o = all.optJSONObject(key);
            if (o == null) continue;
            Track t = Track.fromStored(o);
            File f = t.localPath.isEmpty() ? null : new File(t.localPath);
            if (f != null && f.isFile() && f.length() > 0) out.add(t); else stale.add(key);
        }
        if (!stale.isEmpty()) {
            for (String key : stale) all.remove(key);
            raw = all.toString();
            prefs.edit().putString(KEY_DOWNLOADED, raw).apply();
        }
        downloadedCacheRaw = raw;
        downloadedCache = new ArrayList<>(out);
        return out;
    }

    boolean hasDownloadedHint() {
        String raw = prefs.getString(KEY_DOWNLOADED, "{}");
        return raw != null && raw.length() > 2;
    }

    boolean hasSetupDone'''
    s, count = pattern.subn(replacement, s, count=1)
    if count != 1:
        raise SystemExit('downloadedTracks block not found')

    pattern = re.compile(
        r'    Map<String, FolderStat> folderStats\(List<Track> tracks\) \{.*?\n    \}\n\n'
        r'    List<Track> tracksForFolders\(Set<String> folders\) \{.*?\n    \}',
        re.S,
    )
    replacement = r'''    Map<String, FolderStat> folderStats(List<Track> tracks) {
        LinkedHashMap<String, FolderStat> out = new LinkedHashMap<>();
        if (tracks == null) return out;
        LinkedHashSet<String> offlineKeys = new LinkedHashSet<>();
        for (Track local : downloadedTracks()) if (local != null) offlineKeys.add(local.key());
        for (Track t : tracks) {
            if (t == null) continue;
            String key = folderKey(t);
            FolderStat stat = out.get(key);
            if (stat == null) { stat = new FolderStat(key); out.put(key, stat); }
            stat.total++;
            if (t.size > 0) stat.knownBytes += t.size;
            if (offlineKeys.contains(t.key())) stat.downloaded++;
        }
        return out;
    }

    List<Track> tracksForFolders(Set<String> folders) {
        ArrayList<Track> out = new ArrayList<>();
        if (folders == null || folders.isEmpty()) return out;
        LinkedHashSet<String> offlineKeys = new LinkedHashSet<>();
        for (Track local : downloadedTracks()) if (local != null) offlineKeys.add(local.key());
        for (Track t : catalog()) {
            if (t == null) continue;
            if (folders.contains(folderKey(t)) && !offlineKeys.contains(t.key())) out.add(t);
        }
        return out;
    }'''
    s, count = pattern.subn(replacement, s, count=1)
    if count != 1:
        raise SystemExit('folderStats/tracksForFolders block not found')

    s = s.replace(
        '        prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();\n        return removed;\n    }',
        '        prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();\n'
        '        downloadedCache = null; downloadedCacheRaw = null;\n'
        '        return removed;\n'
        '    }',
        1,
    )

p.write_text(s, encoding='utf-8')

# MainActivity: compute folder stats off-main-thread, virtualize folder rows, and open player asynchronously.
p = ROOT / 'estradaplay-app-v2/app/src/main/java/com/estradaplay/app/MainActivity.java'
s = p.read_text(encoding='utf-8')

if 'ANR_FOLDER_UI_V210' not in s:
    pattern = re.compile(r'    private void showFolderChooser\(boolean initial\) \{.*?\n    \}\n\n    private void startFolderDownload', re.S)
    replacement = r'''    // ANR_FOLDER_UI_V210: parse/index the large catalog off the UI thread and virtualize folder rows.
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
            close.setOnClickListener(v -> showMusic());
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

    private void startFolderDownload'''
    s, count = pattern.subn(replacement, s, count=1)
    if count != 1:
        raise SystemExit('showFolderChooser block not found')

    s = s.replace(
        '        List<Track> downloaded = library.downloadedTracks();\n        Button music = button(downloaded.isEmpty() ? "ESCOLHER MÚSICAS" : "ABRIR MÚSICA OFFLINE", false);',
        '        boolean hasDownloaded = library.hasDownloadedHint();\n'
        '        Button music = button(!hasDownloaded ? "ESCOLHER MÚSICAS" : "ABRIR MÚSICA OFFLINE", false);',
        1,
    )
    s = s.replace(
        '            if (downloaded.isEmpty()) {\n                if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para escolher músicas.");\n            } else showMusic();',
        '            if (!hasDownloaded) {\n'
        '                if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para escolher músicas.");\n'
        '            } else showMusic();',
        1,
    )

    old = '''    private void showMusic() {\n        clearDownloadViews();\n        roadLiveState = null; roadLiveDetail = null;\n        List<Track> tracks = library.downloadedTracks();\n        if (tracks.isEmpty()) { loadCatalogAndOpenChooser(false); return; }'''
    new = '''    private void showMusic() {\n        clearDownloadViews();\n        roadLiveState = null; roadLiveDetail = null;\n        showLoading("Abrindo sua música offline…");\n        io.execute(() -> {\n            List<Track> tracks = library.downloadedTracks();\n            ui.post(() -> renderMusic(tracks));\n        });\n    }\n\n    private void renderMusic(List<Track> tracks) {\n        clearDownloadViews();\n        roadLiveState = null; roadLiveDetail = null;\n        if (tracks == null || tracks.isEmpty()) { loadCatalogAndOpenChooser(false); return; }'''
    if old not in s:
        raise SystemExit('showMusic anchor not found')
    s = s.replace(old, new, 1)

    pattern = re.compile(
        r'            if \(!active\) \{\n                if \(downloadInitialFlow\) \{\n                    boolean hasOfflineMusic = !library\.downloadedTracks\(\)\.isEmpty\(\);.*?\n                \} else ui\.postDelayed\(MainActivity\.this::showMusic, 650\);\n            \}',
        re.S,
    )
    replacement = r'''            if (!active) {
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
            }'''
    s, count = pattern.subn(replacement, s, count=1)
    if count != 1:
        raise SystemExit('download receiver completion block not found')

p.write_text(s, encoding='utf-8')

# Version bump.
p = ROOT / 'estradaplay-app-v2/app/build.gradle'
s = p.read_text(encoding='utf-8')
s = re.sub(r'versionCode\s+\d+', 'versionCode 210', s, count=1)
s = re.sub(r"versionName\s+'[^']+'", "versionName '2.0.10'", s, count=1)
p.write_text(s, encoding='utf-8')

print('EstradaPlay 2.0.10 ANR patch applied')
