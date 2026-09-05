from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/estradaplay/app/MainActivity.java'
s = p.read_text(encoding='utf-8')

replacements = {
    'roadButton.setOnClickListener(v -> showRoad());':
        'roadButton.setOnClickListener(v -> startActivity(new Intent(this, RoadMapActivity.class)));',
    'menu.setOnClickListener(v -> openMenu());':
        'menu.setOnClickListener(v -> openMenu(screen));',
    '''    private void openMenu() {
        String[] items = {"Início", "Música offline", "Gerenciar músicas", "Proteção na estrada", "Conta"};
        new AlertDialog.Builder(this).setTitle("EstradaPlay").setItems(items, (d, which) -> {
            if (which == 0) showHome();
            else if (which == 1) showMusic();
            else if (which == 2) { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para sincronizar pastas."); }
            else if (which == 3) showRoad();
            else showAccount();
        }).show();
    }''':
        '''    private void openMenu(String screen) {
        AppMenuOverlay.show(this, root, screen, which -> {
            if (which == 0) showHome();
            else if (which == 1) showMusic();
            else if (which == 2) { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para sincronizar pastas."); }
            else if (which == 3) startActivity(new Intent(this, RoadMapActivity.class));
            else showAccount();
        });
    }''',
    '''    private Button menuButton() {
        Button b = compactButton("MENU"); b.setTextColor(MUTED); return b;
    }''':
        '''    private Button menuButton() {
        Button b = compactButton("☰");
        b.setTextColor(TEXT);
        b.setTextSize(19);
        b.setLetterSpacing(0f);
        return b;
    }'''
}

for old, new in replacements.items():
    if old not in s:
        raise SystemExit('EstradaPlay 1.5.1 anchor not found: ' + old.splitlines()[0])
    s = s.replace(old, new)

p.write_text(s, encoding='utf-8')
print('EstradaPlay 1.5.1 navigation shell applied')
