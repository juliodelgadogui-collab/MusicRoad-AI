package com.estradaplay.comunista;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

/** Copilot configuration. Conversation itself now lives in CopilotService. */
public final class VoiceCommandActivity extends ComponentActivity {
    private static final int REQ_AUDIO = 2201;
    private final int BG = Color.rgb(9, 5, 7), TEXT = Color.rgb(246, 238, 224), MUTED = Color.rgb(174, 151, 146),
            RED = Color.rgb(184, 20, 38), GREEN = Color.rgb(69, 205, 126), GOLD = Color.rgb(224, 184, 76), BORDER = Color.rgb(73, 35, 40);

    private TextView state, localStatus;
    private Button power;
    private EditText wakeWord;
    private boolean registered;
    private BroadcastReceiver receiver;
    private int pendingPermissionAction;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(32));
        page.setBackgroundColor(BG);
        scroll.addView(page);
        setContentView(UnifiedAppShell.wrap(this, "central", scroll));

        page.addView(text("COPILOTO", 30, TEXT, true));
        page.addView(text("Disponível durante mapa, música, alertas, comboio e demais telas.", 12, MUTED, false));

        LinearLayout card = panel();
        card.addView(text("ASSISTENTE EM SEGUNDO PLANO", 11, RED, true));
        state = text("Carregando estado…", 18, TEXT, true);
        card.addView(state);
        localStatus = text(CopilotSettings.localWakeWordStatus(this), 11,
                CopilotSettings.localWakeWordAvailable(this) ? GREEN : GOLD, true);
        card.addView(localStatus);
        card.addView(text("A palavra de ativação só fica em escuta contínua quando o Android oferece reconhecimento local no aparelho. O Estrada Play não envia esse áudio continuamente ao servidor.", 11, MUTED, false));
        page.addView(card);

        page.addView(section("ATIVAÇÃO"));
        power = button(CopilotSettings.enabled(this) ? "DESATIVAR COPILOTO" : "ATIVAR COPILOTO", CopilotSettings.enabled(this));
        power.setOnClickListener(v -> togglePower());
        page.addView(power);

        page.addView(section("PALAVRA DE ATIVAÇÃO"));
        wakeWord = new EditText(this);
        wakeWord.setText(CopilotSettings.wakeWord(this));
        wakeWord.setTextColor(TEXT);
        wakeWord.setHintTextColor(MUTED);
        wakeWord.setHint("Copiloto");
        wakeWord.setSingleLine(true);
        wakeWord.setTextSize(16f);
        wakeWord.setPadding(dp(14), 0, dp(14), 0);
        wakeWord.setBackground(panelDrawable(Color.rgb(22, 11, 14), 13, BORDER));
        page.addView(wakeWord, new LinearLayout.LayoutParams(-1, dp(54)));
        Button saveWake = button("SALVAR PALAVRA DE ATIVAÇÃO", false);
        saveWake.setOnClickListener(v -> {
            CopilotSettings.setWakeWord(this, wakeWord.getText() == null ? "" : wakeWord.getText().toString());
            wakeWord.setText(CopilotSettings.wakeWord(this));
            Toast.makeText(this, "Palavra de ativação salva.", Toast.LENGTH_SHORT).show();
            if (CopilotSettings.enabled(this)) CopilotService.requestStart(this);
            refreshLocalState();
        });
        page.addView(saveWake);

        page.addView(section("TESTE"));
        Button listen = button("FALAR AGORA", true);
        listen.setOnClickListener(v -> requestAudioThen(2));
        page.addView(listen);
        page.addView(text("Use este botão em aparelhos sem wake word local. Os mesmos comandos continuam funcionando.", 10, MUTED, false));

        page.addView(section("EXEMPLOS"));
        page.addView(text("“Quanto falta para chegar?”  ·  “Qual o limite?”  ·  “Tem radar?”  ·  “O que vem pela frente?”  ·  “Vai chover?”  ·  “Próxima música”  ·  “Abrir comboio”  ·  “SOS”", 12, TEXT, false));

        page.addView(section("VOZ"));
        Button voice = button("VOZ E DIAGNÓSTICO", false);
        voice.setOnClickListener(v -> startActivity(new Intent(this, VoiceDiagnosticsActivity.class)));
        page.addView(voice);
    }

    private void togglePower() {
        if (CopilotSettings.enabled(this)) {
            CopilotSettings.setEnabled(this, false);
            CopilotService.requestStop(this);
            refreshLocalState();
        } else requestAudioThen(1);
    }

    private void requestAudioThen(int action) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runPermissionAction(action);
            return;
        }
        pendingPermissionAction = action;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    private void runPermissionAction(int action) {
        if (action == 1) {
            CopilotSettings.setEnabled(this, true);
            CopilotService.requestStart(this);
        } else if (action == 2) {
            CopilotService.requestListenNow(this);
        }
        refreshLocalState();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            int action = pendingPermissionAction;
            pendingPermissionAction = 0;
            runPermissionAction(action);
        } else {
            pendingPermissionAction = 0;
            Toast.makeText(this, "O microfone é necessário para o Copiloto.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String value = intent.getStringExtra("state");
                String detail = intent.getStringExtra("text");
                renderState(value, detail);
            }
        };
        IntentFilter filter = new IntentFilter(CopilotService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) InternalBroadcasts.register(this, receiver, filter);
            else InternalBroadcasts.register(this, receiver, filter);
            registered = true;
        } catch (Throwable ignored) {}
        refreshLocalState();
        if (CopilotSettings.enabled(this)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CopilotService.requestStart(this);
        }
    }

    @Override protected void onStop() {
        if (registered && receiver != null) try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        registered = false;
        receiver = null;
        super.onStop();
    }

    private void refreshLocalState() {
        boolean enabled = CopilotSettings.enabled(this);
        if (power != null) {
            power.setText(enabled ? "DESATIVAR COPILOTO" : "ATIVAR COPILOTO");
            styleButton(power, enabled);
        }
        if (localStatus != null) {
            localStatus.setText(CopilotSettings.localWakeWordStatus(this));
            localStatus.setTextColor(CopilotSettings.localWakeWordAvailable(this) ? GREEN : GOLD);
        }
        if (state != null && !enabled) state.setText("DESLIGADO");
        else if (state != null) state.setText(CopilotSettings.localWakeWordAvailable(this)
                ? "EM ESPERA · DIGA “" + CopilotSettings.wakeWord(this).toUpperCase() + "”"
                : "ATIVO · USE FALAR AGORA");
    }

    private void renderState(String value, String detail) {
        if (state == null) return;
        if (CopilotService.STATE_LISTENING.equals(value)) state.setText("OUVINDO O COMANDO…");
        else if (CopilotService.STATE_PROCESSING.equals(value)) state.setText("PROCESSANDO…");
        else if (CopilotService.STATE_SPEAKING.equals(value)) state.setText("RESPONDENDO…");
        else if (CopilotService.STATE_WAITING.equals(value)) state.setText(detail == null || detail.isEmpty() ? "EM ESPERA" : detail.toUpperCase());
        else if (CopilotService.STATE_OFF.equals(value)) state.setText("DESLIGADO");
        refreshPowerOnly();
    }

    private void refreshPowerOnly() {
        if (power == null) return;
        boolean enabled = CopilotSettings.enabled(this);
        power.setText(enabled ? "DESATIVAR COPILOTO" : "ATIVAR COPILOTO");
        styleButton(power, enabled);
    }

    private LinearLayout panel() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(panelDrawable(Color.rgb(17, 10, 12), 16, BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView section(String value) {
        TextView text = text(value, 10, MUTED, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(18), 0, dp(6));
        text.setLayoutParams(lp);
        return text;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setStateListAnimator(null);
        styleButton(button, primary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.setMargins(0, dp(7), 0, 0);
        button.setLayoutParams(lp);
        return button;
    }

    private void styleButton(Button button, boolean primary) {
        button.setTextColor(Color.WHITE);
        button.setBackground(panelDrawable(primary ? RED : Color.rgb(36, 17, 21), 13, primary ? RED : BORDER));
    }

    private GradientDrawable panelDrawable(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setPadding(0, dp(4), 0, dp(4));
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
