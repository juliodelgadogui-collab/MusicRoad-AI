package com.estradaplay.comunista;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Appearance screen for local JSON theme import. */
public final class ThemeSettingsActivity extends ComponentActivity {
    private static final int REQ_THEME = 8501;

    private FrameLayout root;
    private EstradaTheme theme;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);

        root = new FrameLayout(this);
        root.setBackground(PremiumUi.gradient(this, theme.background, darken(theme.surface, .22f), 0));
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = PremiumUi.col(this);
        page.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = PremiumUi.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = PremiumUi.button(this, "‹", false);
        back.setTextSize(24);
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout title = PremiumUi.col(this);
        title.addView(PremiumUi.overline(this, "ESTRADA PLAY", theme.secondary));
        title.addView(PremiumUi.text(this, "Aparência", 27, theme.text, true));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMargins(dp(12), 0, 0, 0);
        head.addView(title, tp);
        page.addView(head);

        TextView intro = PremiumUi.text(this,
                "O visual do app pode ser trocado por um arquivo de tema. O arquivo altera somente aparência; não executa código e não recebe acesso aos seus dados.",
                13, theme.muted, false);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(-1, -2);
        introLp.setMargins(0, dp(18), 0, dp(16));
        page.addView(intro, introLp);

        LinearLayout current = PremiumUi.col(this);
        current.setPadding(dp(16), dp(15), dp(16), dp(15));
        current.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
        current.addView(PremiumUi.overline(this, EstradaTheme.imported(this) ? "TEMA IMPORTADO" : "TEMA PADRÃO", theme.success));
        TextView currentName = PremiumUi.text(this, theme.name, 21, theme.text, true);
        LinearLayout.LayoutParams cn = new LinearLayout.LayoutParams(-1, -2);
        cn.setMargins(0, dp(4), 0, dp(12));
        current.addView(currentName, cn);

        LinearLayout swatches = PremiumUi.row(this);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        swatches.addView(swatch(theme.primary), new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(0, dp(42), 1f); s2.setMargins(dp(7),0,0,0); swatches.addView(swatch(theme.secondary), s2);
        LinearLayout.LayoutParams s3 = new LinearLayout.LayoutParams(0, dp(42), 1f); s3.setMargins(dp(7),0,0,0); swatches.addView(swatch(theme.success), s3);
        LinearLayout.LayoutParams s4 = new LinearLayout.LayoutParams(0, dp(42), 1f); s4.setMargins(dp(7),0,0,0); swatches.addView(swatch(theme.warning), s4);
        LinearLayout.LayoutParams s5 = new LinearLayout.LayoutParams(0, dp(42), 1f); s5.setMargins(dp(7),0,0,0); swatches.addView(swatch(theme.danger), s5);
        current.addView(swatches);
        page.addView(current);

        Button importTheme = PremiumUi.button(this, "IMPORTAR TEMA (.JSON)", true);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(56));
        ip.setMargins(0, dp(14), 0, 0);
        page.addView(importTheme, ip);
        importTheme.setOnClickListener(v -> openThemeFile());

        Button example = PremiumUi.button(this, "VER MODELO DO ARQUIVO", false);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, dp(52));
        ep.setMargins(0, dp(8), 0, 0);
        page.addView(example, ep);
        example.setOnClickListener(v -> showExample());

        if (EstradaTheme.imported(this)) {
            Button reset = PremiumUi.button(this, "VOLTAR AO TEMA PADRÃO", false);
            reset.setTextColor(theme.danger);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(52));
            rp.setMargins(0, dp(8), 0, 0);
            page.addView(reset, rp);
            reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Restaurar tema padrão?")
                    .setMessage("O tema importado será removido deste aparelho.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Restaurar", (d, w) -> {
                        EstradaTheme.reset(this);
                        Toast.makeText(this, "Tema padrão restaurado", Toast.LENGTH_SHORT).show();
                        recreate();
                    }).show());
        }

        LinearLayout info = PremiumUi.col(this);
        info.setPadding(dp(16), dp(14), dp(16), dp(14));
        info.setBackground(PremiumUi.panel(this, theme.surface, theme.border, theme.radiusDp));
        info.addView(PremiumUi.overline(this, "FORMATO SEGURO", theme.muted));
        info.addView(PremiumUi.text(this,
                "O tema aceita cores, transparência e arredondamento. Arquivos maiores que 64 KB ou cores inválidas são recusados.",
                12, theme.muted, false));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.setMargins(0, dp(18), 0, 0);
        page.addView(info, infoLp);
    }

    private View swatch(int color) {
        View v = new View(this);
        v.setBackground(PremiumUi.panel(this, color, PremiumUi.withAlpha(theme.text, 45), 12));
        return v;
    }

    private void openThemeFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain"});
            startActivityForResult(i, REQ_THEME);
        } catch (Throwable e) {
            Toast.makeText(this, "Não consegui abrir o seletor de arquivos.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_THEME || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            String json = readSmallText(uri);
            String name = EstradaTheme.install(this, json);
            Toast.makeText(this, "Tema “" + name + "” aplicado", Toast.LENGTH_SHORT).show();
            recreate();
        } catch (Throwable e) {
            String msg = e.getMessage();
            Toast.makeText(this, "Tema inválido" + (msg == null ? "" : ": " + msg), Toast.LENGTH_LONG).show();
        }
    }

    private String readSmallText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Arquivo indisponível");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 64 * 1024) throw new IllegalArgumentException("Arquivo maior que 64 KB");
                out.write(buf, 0, n);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }

    private void showExample() {
        TextView code = new TextView(this);
        code.setText(EstradaTheme.exampleJson());
        code.setTextColor(theme.text);
        code.setTextSize(11);
        code.setPadding(dp(16), dp(14), dp(16), dp(14));
        code.setTextIsSelectable(true);
        code.setBackgroundColor(theme.background);
        ScrollView sc = new ScrollView(this);
        sc.addView(code);
        new AlertDialog.Builder(this)
                .setTitle("Modelo de tema")
                .setView(sc)
                .setNegativeButton("Fechar", null)
                .setPositiveButton("Copiar", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Tema Estrada Play", EstradaTheme.exampleJson()));
                    Toast.makeText(this, "Modelo copiado", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private static int darken(int color, float amount) {
        float k = Math.max(0f, Math.min(1f, 1f - amount));
        return Color.rgb(Math.round(Color.red(color)*k), Math.round(Color.green(color)*k), Math.round(Color.blue(color)*k));
    }

    private int dp(float v) { return PremiumUi.dp(this, v); }
}
