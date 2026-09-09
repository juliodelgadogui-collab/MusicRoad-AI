package com.estradaplay.comunista;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class MaintenanceActivity extends ComponentActivity {
    private LinearLayout page;
    private EstradaTheme theme;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        EstradaTheme current = EstradaTheme.get(this);
        if (theme != null && !current.name.equals(theme.name)) build();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = PremiumUi.col(this);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        page.setBackgroundColor(theme.background);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(UnifiedAppShell.wrap(this, "central", scroll));

        VehicleProfileStore.Profile vehicle = VehicleProfileStore.active(this);
        double current = MaintenanceStore.currentOdometer(this, vehicle.id);

        page.addView(PremiumUi.overline(this, "DIÁRIO DO CARRO", theme.secondary));
        TextView title = PremiumUi.text(this, "Manutenção", 27, theme.text, true);
        page.addView(title);
        margin(title, 0, 5, 0, 3);
        TextView vehicleInfo = PremiumUi.text(this,
                vehicle.name + "  ·  " + (current > 0
                        ? String.format(Locale.getDefault(), "%.0f km", current)
                        : "odômetro ainda não informado"),
                11, theme.muted, false);
        page.addView(vehicleInfo);

        int due = MaintenanceStore.dueCount(this, vehicle.id);
        LinearLayout alert = card();
        alert.addView(PremiumUi.overline(this, due > 0 ? "ATENÇÃO" : "EM DIA",
                due > 0 ? theme.warning : theme.success));
        alert.addView(PremiumUi.text(this,
                due > 0 ? due + " item(ns) chegando ao prazo" : "Nenhuma manutenção próxima do prazo",
                17, theme.text, true));
        TextView hint = PremiumUi.text(this,
                "Os lembretes ficam somente neste aparelho e usam o odômetro registrado nos abastecimentos ou revisões.",
                10, theme.muted, false);
        alert.addView(hint);
        margin(hint, 0, 5, 0, 0);
        add(page, alert, 0, 14, 0, 16, -1, -2);

        page.addView(PremiumUi.overline(this, "REGISTRAR SERVIÇO", theme.muted));
        LinearLayout form = card();
        Spinner type = new Spinner(this);
        String[] types = {"Óleo e filtro", "Filtro de ar", "Pneus", "Freios", "Correia dentada", "Bateria", "Revisão geral", "Outro"};
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, types));
        type.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
        form.addView(type, new LinearLayout.LayoutParams(-1, dp(52)));

        EditText odo = numberField("ODÔMETRO AGORA · km", current > 0 ? String.format(Locale.US, "%.0f", current) : "");
        EditText interval = numberField("PRÓXIMA EM · intervalo em km", "10000");
        EditText months = numberField("OU EM · meses", "12");
        EditText note = textField("OBSERVAÇÃO · Ex.: óleo 5W30, filtro trocado");
        form.addView(odo);
        form.addView(interval);
        form.addView(months);
        form.addView(note);

        Button save = PremiumUi.button(this, "SALVAR MANUTENÇÃO", true);
        add(form, save, 0, 12, 0, 0, -1, dp(54));
        save.setOnClickListener(v -> {
            try {
                double km = num(odo);
                double iv = num(interval);
                int mo = (int) num(months);
                MaintenanceStore.add(this, vehicle.id, String.valueOf(type.getSelectedItem()),
                        km, iv, mo, note.getText().toString().trim());
                Toast.makeText(this, "Manutenção registrada.", Toast.LENGTH_SHORT).show();
                build();
            } catch (Throwable e) {
                Toast.makeText(this, "Confira os valores informados.", Toast.LENGTH_LONG).show();
            }
        });
        add(page, form, 0, 8, 0, 18, -1, -2);

        page.addView(PremiumUi.overline(this, "HISTÓRICO", theme.muted));
        ArrayList<MaintenanceStore.Entry> rows = MaintenanceStore.list(this, vehicle.id);
        if (rows.isEmpty()) {
            TextView empty = PremiumUi.text(this, "Nenhum serviço registrado ainda.", 12, theme.muted, false);
            page.addView(empty);
            margin(empty, 0, 7, 0, 0);
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(16, rows.size()); i++) {
            MaintenanceStore.Entry entry = rows.get(i);
            boolean soon = entry.soon(current, now);
            LinearLayout item = card();
            item.addView(PremiumUi.text(this, entry.type, 16,
                    soon ? theme.warning : theme.text, true));
            String when = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date(entry.at));
            StringBuilder detail = new StringBuilder(when);
            if (entry.odometer > 0) detail.append(" · ").append(Math.round(entry.odometer)).append(" km");
            if (entry.nextKm > 0) detail.append("\nPróxima: ").append(Math.round(entry.nextKm)).append(" km");
            if (entry.nextAt > 0) detail.append(" · ")
                    .append(new SimpleDateFormat("MM/yyyy", Locale.getDefault()).format(new Date(entry.nextAt)));
            TextView meta = PremiumUi.text(this, detail.toString(), 11,
                    soon ? theme.warning : theme.muted, false);
            item.addView(meta);
            if (!entry.note.isEmpty()) {
                TextView noteView = PremiumUi.text(this, entry.note, 11, theme.text, false);
                item.addView(noteView);
                margin(noteView, 0, 5, 0, 0);
            }
            add(page, item, 0, 8, 0, 0, -1, -2);
        }
    }

    private EditText numberField(String hint, String value) {
        EditText e = baseField(hint);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    private EditText textField(String hint) {
        return baseField(hint);
    }

    private EditText baseField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(theme.text);
        e.setHintTextColor(theme.muted);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(0, dp(8), 0, 0);
        e.setLayoutParams(p);
        return e;
    }

    private double num(EditText e) {
        String value = e.getText().toString().trim().replace(',', '.');
        return value.isEmpty() ? 0 : Double.parseDouble(value);
    }

    private LinearLayout card() {
        LinearLayout c = PremiumUi.col(this);
        c.setPadding(dp(15), dp(14), dp(15), dp(14));
        c.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
        return c;
    }

    private void add(LinearLayout parent, View view, int l, int t, int r, int b, int w, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        parent.addView(view, p);
    }

    private void margin(View view, int l, int t, int r, int b) {
        if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        view.setLayoutParams(p);
    }

    private int dp(float value) { return PremiumUi.dp(this, value); }
}
