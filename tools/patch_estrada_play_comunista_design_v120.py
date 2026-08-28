#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path('estrada-play-comunista-app')
if not ROOT.exists():
    raise SystemExit('estrada-play-comunista-app not generated')


def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')


def write(rel, value):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding='utf-8')


def between(text, start, end, replacement, label):
    a = text.find(start)
    if a < 0:
        raise SystemExit(f'start anchor missing: {label}')
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f'end anchor missing: {label}')
    return text[:a] + replacement + text[b:]

# Version 1.2.0: visual architecture is now independent from the regular EstradaPlay.
build = read('app/build.gradle')
build = re.sub(r'versionCode\s+\d+', 'versionCode 120', build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.2.0'", build, count=1)
write('app/build.gradle', build)

# ---------- HOME / GLOBAL NAVIGATION ----------
main_rel = 'app/src/main/java/com/estradaplay/comunista/MainActivity.java'
main = read(main_rel)
main = main.replace('private final int BG = Color.rgb(6, 8, 12);', 'private final int BG = Color.rgb(9, 5, 7);')
main = main.replace('private final int SURFACE = Color.rgb(13, 17, 23);', 'private final int SURFACE = Color.rgb(20, 10, 13);')
main = main.replace('private final int SURFACE_2 = Color.rgb(20, 26, 34);', 'private final int SURFACE_2 = Color.rgb(30, 15, 19);')
main = main.replace('private final int SURFACE_3 = Color.rgb(27, 35, 45);', 'private final int SURFACE_3 = Color.rgb(42, 20, 25);')
main = main.replace('private final int BORDER = Color.rgb(36, 46, 57);', 'private final int BORDER = Color.rgb(76, 38, 43);')
main = main.replace('private final int TEXT = Color.rgb(244, 247, 251);', 'private final int TEXT = Color.rgb(246, 238, 224);')
main = main.replace('private final int MUTED = Color.rgb(143, 154, 167);', 'private final int MUTED = Color.rgb(174, 151, 146);')
main = main.replace('private final int SUBTLE = Color.rgb(99, 112, 126);', 'private final int SUBTLE = Color.rgb(121, 91, 91);')
main = main.replace('private final int ACCENT = Color.rgb(224, 30, 47);', 'private final int ACCENT = Color.rgb(190, 18, 38);')
main = main.replace('private final int ACCENT_SOFT = Color.rgb(72, 12, 20);', 'private final int ACCENT_SOFT = Color.rgb(79, 10, 23);')

home = r'''    private void showHome() {
        clearDownloadViews();
        roadLiveState = null;
        roadLiveDetail = null;
        root.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(12), dp(18), dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Central"));

        // DESIGN_V120: a constructivist command surface, not the regular EstradaPlay card stack.
        LinearLayout mast = row();
        mast.setGravity(Gravity.CENTER_VERTICAL);
        BrandMarkView mark = new BrandMarkView(this);
        mast.addView(mark, lp(58, 58));
        LinearLayout mastText = column();
        TextView code = overline("CENTRAL 01  ·  SISTEMA DE BORDO", ACCENT); mastText.addView(code);
        TextView name = text("Estrada Play Comunista", 22, TEXT, true); mastText.addView(name); margins(name, 0, 2, 0, 0);
        mast.addView(mastText, new LinearLayout.LayoutParams(0, -2, 1)); margins(mastText, 13, 0, 8, 0);
        TextView live = chip(hasLocationPermission() ? "● EM GUARDA" : "○ GPS", hasLocationPermission() ? GREEN : ACCENT,
                hasLocationPermission() ? GREEN_SOFT : ACCENT_SOFT);
        mast.addView(live);
        page.addView(mast); margins(mast, 0, 6, 0, 14);

        LinearLayout manifesto = column();
        manifesto.setPadding(dp(18), dp(18), dp(18), dp(18));
        manifesto.setBackground(bg(ACCENT, 4, 0));
        TextView mOver = overline("ORDEM DE MARCHA", Color.rgb(255, 216, 145)); manifesto.addView(mOver);
        TextView mTitle = text("CENTRAL DE VIAGEM", 31, Color.WHITE, true); manifesto.addView(mTitle); margins(mTitle, 0, 5, 0, 2);
        TextView mSub = text("ROTA  /  SOM  /  PROTEÇÃO  /  COPILOTO", 10, Color.rgb(255, 224, 196), true);
        mSub.setLetterSpacing(0.08f); manifesto.addView(mSub);
        page.addView(manifesto);

        LinearLayout stateLine = row();
        stateLine.setGravity(Gravity.CENTER_VERTICAL);
        stateLine.setPadding(dp(12), dp(9), dp(12), dp(9));
        stateLine.setBackground(bg(Color.rgb(16, 9, 11), 2, BORDER));
        TextView stateLeft = overline(hasLocationPermission() ? "PROTEÇÃO RODOVIÁRIA ATIVA" : "LOCALIZAÇÃO PENDENTE", hasLocationPermission() ? GREEN : ACCENT);
        stateLine.addView(stateLeft, new LinearLayout.LayoutParams(0, -2, 1));
        TextView stateRight = overline(online() ? "REDE ATIVA" : "MODO OFFLINE", online() ? TEXT : GREEN);
        stateRight.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); stateLine.addView(stateRight);
        page.addView(stateLine); margins(stateLine, 0, 8, 0, 18);

        TextView startLabel = overline("ESCOLHA O MODO", MUTED); page.addView(startLabel); margins(startLabel, 2, 0, 0, 8);
        LinearLayout selector = row();

        LinearLayout free = column();
        free.setPadding(dp(15), dp(15), dp(15), dp(15));
        free.setBackground(bg(Color.rgb(116, 13, 27), 4, ACCENT));
        TextView fCode = overline("01  ·  LIVRE", Color.rgb(255, 203, 126)); free.addView(fCode);
        TextView fTitle = text("RODAR\nLIVRE", 24, Color.WHITE, true); free.addView(fTitle); margins(fTitle, 0, 8, 0, 4);
        TextView fBody = text("Sem destino. O sistema observa a estrada e mantém os alertas ativos.", 11, Color.rgb(230, 198, 193), false); free.addView(fBody);
        View fSpace = new View(this); free.addView(fSpace, new LinearLayout.LayoutParams(1, 0, 1));
        Button freeGo = button("INICIAR", true); free.addView(freeGo, lp(-1, 50));
        freeGo.setOnClickListener(v -> { DestinationStore.clear(this); openCockpit(); });
        selector.addView(free, new LinearLayout.LayoutParams(0, dp(214), 1));

        LinearLayout routed = column();
        routed.setPadding(dp(15), dp(15), dp(15), dp(15));
        routed.setBackground(bg(SURFACE, 4, Color.rgb(214, 186, 143)));
        TextView rCode = overline("02  ·  ROTA", Color.rgb(214, 186, 143)); routed.addView(rCode);
        TextView rTitle = text("IR\nPARA...", 24, TEXT, true); routed.addView(rTitle); margins(rTitle, 0, 8, 0, 4);
        TextView rBody = text("Informe um endereço quando quiser orientação completa de percurso.", 11, MUTED, false); routed.addView(rBody);
        View rSpace = new View(this); routed.addView(rSpace, new LinearLayout.LayoutParams(1, 0, 1));
        Button routeGo = button("BUSCAR ENDEREÇO", false); routed.addView(routeGo, lp(-1, 50));
        routeGo.setOnClickListener(v -> startActivity(new Intent(this, DestinationActivity.class)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(214), 1); rp.setMargins(dp(9), 0, 0, 0); selector.addView(routed, rp);
        page.addView(selector);

        LinearLayout copilot = row();
        copilot.setGravity(Gravity.CENTER_VERTICAL);
        copilot.setPadding(dp(15), dp(12), dp(15), dp(12));
        copilot.setBackground(bg(Color.rgb(26, 13, 16), 2, BORDER));
        TextView star = text("★", 24, Color.rgb(226, 185, 76), true); star.setGravity(Gravity.CENTER); copilot.addView(star, lp(42, 42));
        LinearLayout voice = column();
        voice.addView(overline("CENTRAL DE VOZ", Color.rgb(226, 185, 76)));
        voice.addView(text("Copiloto contextual", 16, TEXT, true));
        voice.addView(text("Fala menos, entende o momento e prioriza segurança.", 10, MUTED, false));
        copilot.addView(voice, new LinearLayout.LayoutParams(0, -2, 1)); margins(voice, 10, 0, 0, 0);
        page.addView(copilot); margins(copilot, 0, 14, 0, 0);

        boolean hasDownloaded = library.hasDownloadedHint();
        LinearLayout media = row();
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.setPadding(dp(15), dp(13), dp(13), dp(13));
        media.setBackground(bg(Color.rgb(15, 9, 11), 2, BORDER));
        LinearLayout mediaInfo = column();
        mediaInfo.addView(overline("ARQUIVO SONORO", ACCENT));
        mediaInfo.addView(text("Música offline", 17, TEXT, true));
        mediaInfo.addView(text(hasDownloaded ? "Biblioteca preparada neste aparelho" : "Escolha o que levar para a viagem", 10, MUTED, false));
        media.addView(mediaInfo, new LinearLayout.LayoutParams(0, -2, 1));
        Button music = compactButton(hasDownloaded ? "ABRIR" : "PREPARAR"); media.addView(music, lp(96, 48));
        music.setOnClickListener(v -> {
            if (!hasDownloaded) { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para escolher músicas."); }
            else showMusic();
        });
        page.addView(media); margins(media, 0, 9, 0, 0);

        LinearLayout tools = row();
        Button libraryButton = compactButton("ARQUIVO");
        Button accountButton = compactButton("IDENTIDADE");
        tools.addView(libraryButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(48), 1); ap.setMargins(dp(8), 0, 0, 0); tools.addView(accountButton, ap);
        page.addView(tools); margins(tools, 0, 9, 0, 0);
        libraryButton.setOnClickListener(v -> { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para sincronizar pastas."); });
        accountButton.setOnClickListener(v -> showAccount());
    }

'''
main = between(main, '    private void showHome() {', '    private void showMusic() {', home, 'home v120')

navigation = r'''    private View topBar(String screen) {
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

'''
main = between(main, '    private View topBar(String screen) {', '    private void showOfflineSetupBlocked() {', navigation, 'top central')
write(main_rel, main)

# ---------- FULL-SCREEN CENTRAL INSTEAD OF A DRAWER ----------
write('app/src/main/java/com/estradaplay/comunista/AppMenuOverlay.java', r'''package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AppMenuOverlay {
    interface Listener { void onSelect(int index); }

    private static final int BG = Color.rgb(8, 5, 7);
    private static final int PANEL = Color.rgb(18, 9, 12);
    private static final int SURFACE = Color.rgb(28, 14, 18);
    private static final int BORDER = Color.rgb(82, 39, 45);
    private static final int TEXT = Color.rgb(246, 238, 224);
    private static final int MUTED = Color.rgb(174, 151, 146);
    private static final int RED = Color.rgb(190, 18, 38);
    private static final int GOLD = Color.rgb(226, 185, 76);
    private static final int GREEN = Color.rgb(72, 212, 134);

    private AppMenuOverlay() {}

    static void show(Context context, FrameLayout host, String currentScreen, Listener listener) {
        if (context == null || host == null) return;
        View old = host.findViewWithTag("epc-central-overlay");
        if (old != null) host.removeView(old);

        FrameLayout overlay = new FrameLayout(context);
        overlay.setTag("epc-central-overlay");
        overlay.setBackgroundColor(Color.argb(238, 3, 2, 3));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 14));
        panel.setBackground(box(PANEL, 8, BORDER));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -1);
        pp.setMargins(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        overlay.addView(panel, pp);

        LinearLayout banner = new LinearLayout(context);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(context, 14), dp(context, 12), dp(context, 12), dp(context, 12));
        banner.setBackground(box(RED, 3, 0));
        BrandMarkView mark = new BrandMarkView(context);
        banner.addView(mark, new LinearLayout.LayoutParams(dp(context, 50), dp(context, 50)));
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(context, "PAINEL CENTRAL", 19, Color.WHITE, true));
        TextView code = text(context, "EPC  /  COMANDO DE BORDO", 9, Color.rgb(255, 222, 180), true);
        code.setLetterSpacing(0.10f); words.addView(code);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.setMargins(dp(context, 12), 0, 0, 0); banner.addView(words, wp);
        TextView close = text(context, "FECHAR", 9, Color.WHITE, true);
        close.setGravity(Gravity.CENTER); close.setBackground(box(Color.rgb(106, 12, 25), 2, Color.rgb(255, 126, 136)));
        banner.addView(close, new LinearLayout.LayoutParams(dp(context, 70), dp(context, 42)));
        panel.addView(banner);

        TextView prompt = text(context, "ESCOLHA UM SETOR", 10, MUTED, true);
        prompt.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams prp = new LinearLayout.LayoutParams(-1, -2); prp.setMargins(dp(context, 2), dp(context, 18), 0, dp(context, 8)); panel.addView(prompt, prp);

        String[][] items = {
                {"01", "CENTRAL", "Resumo e partida"},
                {"02", "SOM", "Música offline"},
                {"03", "ARQUIVO", "Gerenciar downloads"},
                {"04", "ESTRADA", "Mapa e proteção"},
                {"05", "IDENTIDADE", "Conta e aparelho"}
        };
        String current = currentScreen == null ? "" : currentScreen.toLowerCase();

        LinearLayout row1 = row(context);
        LinearLayout a = module(context, items[0], selected(0, current));
        LinearLayout b = module(context, items[1], selected(1, current));
        row1.addView(a, new LinearLayout.LayoutParams(0, dp(context, 112), 1));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(context, 112), 1); bp.setMargins(dp(context, 8), 0, 0, 0); row1.addView(b, bp);
        panel.addView(row1);

        LinearLayout row2 = row(context);
        LinearLayout c = module(context, items[2], selected(2, current));
        LinearLayout d = module(context, items[3], selected(3, current));
        row2.addView(c, new LinearLayout.LayoutParams(0, dp(context, 112), 1));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(0, dp(context, 112), 1); dp2.setMargins(dp(context, 8), 0, 0, 0); row2.addView(d, dp2);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(-1, -2); r2p.setMargins(0, dp(context, 8), 0, 0); panel.addView(row2, r2p);

        LinearLayout e = module(context, items[4], selected(4, current));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, dp(context, 94)); ep.setMargins(0, dp(context, 8), 0, 0); panel.addView(e, ep);

        View spacer = new View(context); panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout footer = row(context); footer.setGravity(Gravity.CENTER_VERTICAL); footer.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10)); footer.setBackground(box(BG, 2, BORDER));
        TextView offline = text(context, "●  OFFLINE-FIRST", 9, GREEN, true); offline.setLetterSpacing(0.08f); footer.addView(offline, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ver = text(context, "v" + BuildConfig.VERSION_NAME, 10, GOLD, true); footer.addView(ver); panel.addView(footer);

        View[] modules = {a,b,c,d,e};
        for (int i = 0; i < modules.length; i++) {
            final int index = i;
            modules[i].setOnClickListener(v -> close(host, overlay, panel, () -> { if (listener != null) listener.onSelect(index); }));
        }
        close.setOnClickListener(v -> close(host, overlay, panel, null));

        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        panel.setAlpha(0f); panel.setScaleX(0.96f); panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170L).start();
    }

    private static LinearLayout module(Context c, String[] data, boolean active) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 11));
        box.setBackground(box(active ? Color.rgb(91, 12, 25) : SURFACE, 3, active ? RED : BORDER));
        TextView code = text(c, data[0], 10, active ? GOLD : RED, true); code.setLetterSpacing(0.12f); box.addView(code);
        TextView title = text(c, data[1], 18, TEXT, true); box.addView(title);
        TextView sub = text(c, data[2], 10, active ? Color.rgb(231, 198, 190) : MUTED, false); box.addView(sub);
        View spacer = new View(c); box.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        TextView go = text(c, active ? "SETOR ATUAL  ■" : "ABRIR  →", 9, active ? GOLD : TEXT, true); go.setGravity(Gravity.RIGHT); box.addView(go);
        box.setClickable(true); box.setFocusable(true);
        return box;
    }

    private static boolean selected(int index, String current) {
        if (index == 0) return current.contains("central") || current.contains("início") || current.contains("inicio");
        if (index == 1) return current.contains("música") || current.contains("musica");
        if (index == 2) return current.contains("biblioteca") || current.contains("download") || current.contains("arquivo");
        if (index == 3) return current.contains("estrada") || current.contains("mapa") || current.contains("proteção") || current.contains("protecao");
        return current.contains("conta") || current.contains("identidade");
    }

    private static void close(FrameLayout host, FrameLayout overlay, View panel, Runnable after) {
        if (overlay.getParent() == null) return;
        panel.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(120L).withEndAction(() -> {
            try { host.removeView(overlay); } catch (Throwable ignored) {}
            if (after != null) after.run();
        }).start();
    }

    private static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); t.setLineSpacing(0, 1.04f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private static GradientDrawable box(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); if (stroke != 0) d.setStroke(1, stroke); return d;
    }
    private static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
''')

# ---------- MAP: FULL-SCREEN INSTRUMENT SURFACE IN PORTRAIT ----------
road_rel = 'app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java'
road = read(road_rel)
road = road.replace('private final int BG = Color.rgb(5, 8, 12);', 'private final int BG = Color.rgb(8, 5, 7);')
road = road.replace('private final int SURFACE = Color.rgb(12, 17, 23);', 'private final int SURFACE = Color.rgb(18, 9, 12);')
road = road.replace('private final int SURFACE_2 = Color.rgb(18, 24, 32);', 'private final int SURFACE_2 = Color.rgb(28, 14, 18);')
road = road.replace('private final int BORDER = Color.rgb(40, 51, 64);', 'private final int BORDER = Color.rgb(79, 39, 45);')
road = road.replace('private final int TEXT = Color.rgb(244, 247, 250);', 'private final int TEXT = Color.rgb(246, 238, 224);')
road = road.replace('private final int MUTED = Color.rgb(145, 157, 170);', 'private final int MUTED = Color.rgb(174, 151, 146);')
road = road.replace('private final int ACCENT = Color.rgb(224, 30, 47);', 'private final int ACCENT = Color.rgb(190, 18, 38);')
road = road.replace('private final int ACCENT_SOFT = Color.rgb(72, 12, 20);', 'private final int ACCENT_SOFT = Color.rgb(79, 10, 23);')
road = road.replace('"  ESTRADA PLAY COMUNISTA  ·  CONDUÇÃO"', '"  CENTRAL DA ESTRADA  ·  CONDUÇÃO"')

portrait = r'''    private void buildPortraitUi(int width, int height) {
        int outer = clamp(Math.round(width * 0.020f), dp(8), dp(13));

        // V1.2: map is the cockpit. No separate EstradaPlay-style bottom card.
        FrameLayout mapPane = buildMapPane(height - outer * 2, true);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -1);
        mp.setMargins(outer, outer, outer, outer);
        root.addView(mapPane, mp);

        LinearLayout brandPlate = new LinearLayout(this);
        brandPlate.setOrientation(LinearLayout.VERTICAL);
        brandPlate.setPadding(dp(10), dp(8), dp(10), dp(8));
        brandPlate.setBackground(panel(3, Color.argb(236, 42, 7, 14), Color.rgb(151, 28, 45)));
        TextView central = label("CENTRAL 04", 8, Color.rgb(226, 185, 76), true); central.setLetterSpacing(0.12f); brandPlate.addView(central);
        brandPlate.addView(label(destination == null ? "RODAGEM LIVRE" : shortDestination(destination.label), 13, TEXT, true));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(150), -2, Gravity.LEFT | Gravity.TOP);
        bp.setMargins(outer + dp(10), outer + dp(92), 0, 0); root.addView(brandPlate, bp);
        if (Build.VERSION.SDK_INT >= 21) brandPlate.setElevation(dp(55));

        if (destination != null) {
            LinearLayout route = new LinearLayout(this);
            route.setOrientation(LinearLayout.VERTICAL);
            route.setPadding(dp(10), dp(8), dp(10), dp(8));
            route.setBackground(panel(3, Color.argb(238, 15, 8, 10), Color.rgb(214, 186, 143)));
            TextView over = label("ROTA ATIVA", 7, Color.rgb(226, 185, 76), true); over.setLetterSpacing(0.12f); route.addView(over);
            destinationText = label("Calculando percurso…", 9, TEXT, true); route.addView(destinationText);
            FrameLayout.LayoutParams rtp = new FrameLayout.LayoutParams(dp(210), -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            rtp.setMargins(0, outer + dp(96), 0, 0); root.addView(route, rtp);
            if (Build.VERSION.SDK_INT >= 21) route.setElevation(dp(55));
        }

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(7), dp(7), dp(7), dp(7));
        controls.setBackground(panel(4, Color.argb(238, 12, 7, 9), BORDER));
        Button recenter = action("CENTRO", false);
        Button destinationButton = action(destination == null ? "ROTA" : "MUDAR", false);
        Button music = action("SOM", true);
        Button centralButton = action("CENTRAL", false);
        controls.addView(recenter, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout.LayoutParams dpp = new LinearLayout.LayoutParams(-1, dp(48)); dpp.setMargins(0, dp(6), 0, 0); controls.addView(destinationButton, dpp);
        LinearLayout.LayoutParams mpp = new LinearLayout.LayoutParams(-1, dp(48)); mpp.setMargins(0, dp(6), 0, 0); controls.addView(music, mpp);
        LinearLayout.LayoutParams cpp = new LinearLayout.LayoutParams(-1, dp(48)); cpp.setMargins(0, dp(6), 0, 0); controls.addView(centralButton, cpp);
        recenter.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });
        destinationButton.setOnClickListener(v -> startActivity(new Intent(this, DestinationActivity.class)));
        music.setOnClickListener(v -> openMain("music"));
        centralButton.setOnClickListener(v -> openMain("home"));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(94), -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        cp.setMargins(0, 0, outer + dp(9), 0); root.addView(controls, cp);
        if (Build.VERSION.SDK_INT >= 21) controls.setElevation(dp(70));
        controls.bringToFront();

        TextView signature = label("EPC / MAPA LIVRE / PROTEÇÃO ATIVA", 7, Color.rgb(226, 185, 76), true);
        signature.setLetterSpacing(0.08f); signature.setGravity(Gravity.CENTER); signature.setBackground(panel(2, Color.argb(220, 12, 7, 9), BORDER));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(218), dp(30), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        sp.setMargins(0, 0, 0, outer + dp(8)); root.addView(signature, sp);
        if (Build.VERSION.SDK_INT >= 21) signature.setElevation(dp(60));
    }

'''
road = between(road, '    private void buildPortraitUi(int width, int height) {', '    private void addTopLevelRecenter', portrait, 'portrait cockpit v120')
write(road_rel, road)

write('README-DESIGN-1.2.0.md', '''# Estrada Play Comunista 1.2.0 — identidade independente\n\n- Home reconstruída como Central de Viagem.\n- Navegação por Painel Central em tela cheia, sem drawer lateral.\n- Mapa vertical convertido em cockpit de mapa integral com instrumentos flutuantes.\n- Paleta vinho, preto e marfim, com dourado de sinalização.\n- Elementos mais retos e modulares; menos cartões arredondados.\n- Nenhum PNG/JPG/WebP criado para a identidade.\n- Destino opcional, radares, offline, música e copiloto preservados.\n''')

print('Estrada Play Comunista design v1.2.0 applied')
