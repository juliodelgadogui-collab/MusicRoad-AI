package com.estradaplay.patriota;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DestinationActivity extends ComponentActivity {
    private static final int BG = Color.rgb(7, 7, 9);
    private static final int SURFACE = Color.rgb(18, 14, 17);
    private static final int BORDER = Color.rgb(65, 32, 38);
    private static final int TEXT = Color.rgb(248, 246, 247);
    private static final int MUTED = Color.rgb(161, 145, 149);
    private static final int RED = Color.rgb(224, 30, 47);
    private static final int RED_DARK = Color.rgb(80, 12, 21);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText query;
    private Button search;
    private ProgressBar progress;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(26), dp(22), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(UnifiedAppShell.wrap(this,"trip",scroll));

        TextView over = label("ESTRADA PLAY PATRIOTA", 11, RED, true);
        over.setLetterSpacing(0.12f);
        page.addView(over);
        TextView title = label("Para onde vamos?", 31, TEXT, true);
        page.addView(title); margin(title, 0, 8, 0, 4);
        TextView body = label("O destino é opcional. Sem endereço, radares, lombadas, limites e proteção continuam funcionando normalmente.", 14, MUTED, false);
        page.addView(body); margin(body, 0, 0, 0, 20);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(panel(20, SURFACE, BORDER));
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));

        query = new EditText(this);
        query.setHint("Rua, número, cidade ou lugar");
        query.setHintTextColor(MUTED);
        query.setTextColor(TEXT);
        query.setTextSize(16);
        query.setSingleLine(true);
        query.setPadding(dp(14), 0, dp(14), 0);
        query.setBackground(panel(15, Color.rgb(27, 20, 23), BORDER));
        card.addView(query, new LinearLayout.LayoutParams(-1, dp(58)));

        search = button("BUSCAR DESTINO", true);
        card.addView(search, new LinearLayout.LayoutParams(-1, dp(58))); margin(search, 0, 12, 0, 0);
        search.setOnClickListener(v -> search());

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(RED));
        progress.setVisibility(View.GONE);
        card.addView(progress, new LinearLayout.LayoutParams(-1, dp(36))); margin(progress, 0, 10, 0, 0);

        status = label("Busca de endereço disponível quando houver internet.", 12, MUTED, false);
        status.setGravity(Gravity.CENTER);
        card.addView(status); margin(status, 0, 8, 0, 0);

        Button passive = button("DIRIGIR SEM DESTINO", false);
        page.addView(passive, new LinearLayout.LayoutParams(-1, dp(58))); margin(passive, 0, 16, 0, 0);
        passive.setOnClickListener(v -> {
            DestinationStore.clear(this);
            openMap();
        });

        DestinationStore.Destination current = DestinationStore.read(this);
        if (current != null) {
            TextView currentLabel = label("DESTINO ATUAL", 10, RED, true);
            page.addView(currentLabel); margin(currentLabel, 0, 22, 0, 6);
            TextView currentText = label(current.label, 14, TEXT, true);
            currentText.setPadding(dp(14), dp(14), dp(14), dp(14));
            currentText.setBackground(panel(16, RED_DARK, BORDER));
            page.addView(currentText);
        }
    }

    private void search() {
        String q = query.getText().toString().trim();
        if (q.length() < 3) {
            status.setText("Digite pelo menos 3 caracteres.");
            return;
        }
        search.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Procurando no mapa…");
        io.execute(() -> {
            try {
                List<DestinationStore.Destination> results = DestinationResolver.search(q);
                ui.post(() -> showResults(results));
            } catch (Exception e) {
                ui.post(() -> {
                    search.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    status.setText("Não consegui buscar agora. Verifique a internet e tente novamente.");
                });
            }
        });
    }

    private void showResults(List<DestinationStore.Destination> results) {
        search.setEnabled(true);
        progress.setVisibility(View.GONE);
        if (results == null || results.isEmpty()) {
            status.setText("Nenhum endereço encontrado.");
            return;
        }
        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) labels[i] = results.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Escolha o destino")
                .setItems(labels, (dialog, which) -> {
                    DestinationStore.Destination d = results.get(which);
                    DestinationStore.save(this, d);
                    status.setText("Destino definido. Preparando a rota…");
                    openMap();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openMap() {
        Intent i = new Intent(this, RoadMapActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setLetterSpacing(0.06f);
        b.setStateListAnimator(null);
        b.setBackground(panel(16, primary ? RED : Color.rgb(31, 22, 25), primary ? 0 : BORDER));
        return b;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable panel(int radiusDp, int color, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private void margin(View view, int l, int t, int r, int b) {
        if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        view.setLayoutParams(p);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
