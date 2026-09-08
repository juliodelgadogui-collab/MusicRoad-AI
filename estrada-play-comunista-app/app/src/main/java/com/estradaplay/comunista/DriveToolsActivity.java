package com.estradaplay.comunista;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
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

/** Premium tools/settings hub. Keeps existing feature activities and settings logic. */
public final class DriveToolsActivity extends ComponentActivity {
    private EstradaTheme theme;
    private LinearLayout page;

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
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(theme.background);
        setContentView(UnifiedAppShell.wrap(this, "central", content));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page = PremiumUi.col(this);
        page.setPadding(dp(16), dp(14), dp(16), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView over = PremiumUi.overline(this, "FERRAMENTAS", theme.secondary);
        page.addView(over);
        TextView title = PremiumUi.text(this, "Mais do Estrada Play", 27, theme.text, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.setMargins(0, dp(4), 0, dp(5));
        page.addView(title, titleLp);
        page.addView(PremiumUi.text(this,
                "Recursos de viagem, veículo, estrada e sistema ficam aqui para manter a tela de direção limpa.",
                12, theme.muted, false));

        addMotionWarning();
        addQuickRow();

        section("VIAGEM");
        grid(new Tool[]{
                new Tool("◎", "Planejar viagem", "Destino, distância e custo", TripPlannerActivity.class),
                new Tool("↺", "Histórico", "Viagens e replay", TripHistoryActivity.class),
                new Tool("☆", "Favoritos", "Casa, trabalho e paradas", RoadFavoritesActivity.class),
                new Tool("☂", "Clima", "Chuva aqui e na rota", WeatherActivity.class)
        });

        section("ESTRADA");
        grid(new Tool[]{
                new Tool("!", "SOS", "Localização e emergência", EmergencyActivity.class),
                new Tool("↓", "Offline", "Dados preparados no aparelho", OfflineCenterActivity.class),
                new Tool("✦", "Estrada Viva", "Alertas comunitários", EstradaVivaActivity.class),
                new Tool("◎", "Radares", "Base coletiva", CollectiveRoadActivity.class),
                new Tool("+", "Serviços", "Postos, oficinas e hospitais", NearbyServicesActivity.class),
                new Tool("⚑", "Reportar", "Correções da base rodoviária", RoadReportActivity.class)
        });

        section("VEÍCULO");
        grid(new Tool[]{
                new Tool("▣", "Consumo", "Custos e abastecimento", VehicleCostActivity.class),
                new Tool("⚙", "Manutenção", "Diário e próximos serviços", MaintenanceActivity.class),
                new Tool("$", "Combustível", "Preços informados na estrada", FuelCommunityActivity.class),
                new Tool("⌁", "OBD2", "ELM327 em modo leitura", Obd2Activity.class),
                new Tool("●", "Dashcam", "Gravação da viagem", CameraActivity.class),
                new Tool("◆", "Comboio", "Grupo e distância entre carros", ConvoyActivity.class)
        });

        section("BORDO");
        grid(new Tool[]{
                new Tool("◉", "Rádio PTT", "Canal de voz da rodovia", RoadRadioActivity.class),
                new Tool("◌", "Copiloto", "Comandos de voz", VoiceCommandActivity.class),
                new Tool("✓", "Diagnóstico", "Versão e permissões", SystemDiagnosticsActivity.class),
                new Tool("◐", "Aparência", "Importar ou restaurar tema", ThemeSettingsActivity.class),
                new Tool("♫", "Música", "Biblioteca Premium", PremiumMusicActivity.class),
                new Tool("⚙", "Servidor", "Ajustes avançados", ServerSettingsActivity.class)
        });

        section("PREFERÊNCIAS DE DIREÇÃO");
        toggle("Modo noturno automático", "Ajusta a leitura dos instrumentos", "auto_night", DriveSettings.autoNight(this));
        toggle("Chuva automática", "Antecipa avisos conforme clima e rota", "rain_auto", DriveSettings.autoRain(this));
        toggle("Proteger vídeo em impacto", "Preserva o trecho importante da dashcam", "protect_impact_video", DriveSettings.protectImpactVideo(this));
        toggle("Base coletiva", "Usa eventos leves compartilhados da estrada", "collective_enabled", DriveSettings.collectiveEnabled(this));
        toggle("Trânsito colaborativo", "Compartilhamento opcional para contexto de trânsito", "context_traffic_opt_in", DriveSettings.contextTrafficOptIn(this));

        section("CONTA E SISTEMA");
        LinearLayout last = PremiumUi.row(this);
        Button account = PremiumUi.button(this, "CONTA", false);
        account.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class).putExtra("open", "account")));
        last.addView(account, new LinearLayout.LayoutParams(0, dp(52), 1f));
        Button voice = PremiumUi.button(this, "DIAGNÓSTICO DE VOZ", false);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, dp(52), 1f); vp.setMargins(dp(8),0,0,0);
        last.addView(voice, vp);
        voice.setOnClickListener(v -> open(VoiceDiagnosticsActivity.class));
        page.addView(last);
    }

    private void addQuickRow() {
        LinearLayout row = PremiumUi.row(this);
        LinearLayout road = quick("↗", "ESTRADA", "Voltar ao mapa", theme.primary);
        road.setOnClickListener(v -> open(RoadMapActivity.class));
        row.addView(road, new LinearLayout.LayoutParams(0, dp(104), 1f));
        LinearLayout music = quick("♪", "MÚSICA", "Biblioteca local", theme.secondary);
        music.setOnClickListener(v -> open(PremiumMusicActivity.class));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(104), 1f); mp.setMargins(dp(8),0,0,0);
        row.addView(music, mp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.setMargins(0,dp(16),0,0);
        page.addView(row, rp);
    }

    private LinearLayout quick(String glyph, String title, String sub, int accent) {
        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(PremiumUi.gradient(this, theme.surfaceAlt,
                PremiumUi.withAlpha(accent, 55), theme.radiusDp));
        card.addView(PremiumUi.text(this, glyph, 22, accent, true));
        TextView t = PremiumUi.text(this, title, 13, theme.text, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1,-2); tp.setMargins(0,dp(5),0,0); card.addView(t,tp);
        card.addView(PremiumUi.text(this, sub, 10, theme.muted, false));
        card.setClickable(true); card.setFocusable(true);
        return card;
    }

    private void addMotionWarning() {
        double speed = lastKnownSpeed();
        if (speed < 8) return;
        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(14), dp(11), dp(14), dp(11));
        card.setBackground(PremiumUi.panel(this, PremiumUi.withAlpha(theme.warning, 28),
                PremiumUi.withAlpha(theme.warning, 140), theme.radiusDp));
        card.addView(PremiumUi.overline(this, "VEÍCULO EM MOVIMENTO · " + Math.round(speed) + " KM/H", theme.warning));
        card.addView(PremiumUi.text(this,
                "Prefira os atalhos essenciais enquanto dirige e deixe ajustes detalhados para quando estiver parado.",
                10, theme.text, true));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(14),0,0); page.addView(card,p);
    }

    private double lastKnownSpeed() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return 0;
        try {
            LocationManager lm = (LocationManager)getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location l = lm == null ? null : lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null && best.hasSpeed() && System.currentTimeMillis()-best.getTime() < 15000L)
                return Math.max(0, best.getSpeed()*3.6);
        } catch (Throwable ignored) {}
        return 0;
    }

    private void section(String name) {
        TextView s = PremiumUi.overline(this, name, theme.muted);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(dp(3),dp(22),0,dp(8));
        page.addView(s,p);
    }

    private void grid(Tool[] tools) {
        int cols = getResources().getConfiguration().screenWidthDp >= 700 ? 3 : 2;
        for (int i=0;i<tools.length;i+=cols) {
            LinearLayout row = PremiumUi.row(this);
            for (int c=0;c<cols;c++) {
                int idx=i+c;
                if (idx>=tools.length) { row.addView(new View(this), new LinearLayout.LayoutParams(0,1,1f)); continue; }
                View card = tool(tools[idx]);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(112), 1f);
                if (c>0) p.setMargins(dp(8),0,0,0);
                row.addView(card,p);
            }
            page.addView(row);
            if (i+cols<tools.length) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams)row.getLayoutParams();
                p.setMargins(0,0,0,dp(8)); row.setLayoutParams(p);
            }
        }
    }

    private View tool(Tool tool) {
        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(13),dp(11),dp(13),dp(11));
        card.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
        TextView icon = PremiumUi.text(this, tool.icon, 20, theme.secondary, true);
        card.addView(icon);
        TextView title = PremiumUi.text(this, tool.title, 13, theme.text, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1,-2); tp.setMargins(0,dp(5),0,0); card.addView(title,tp);
        TextView sub = PremiumUi.text(this, tool.sub, 9.5f, theme.muted, false); sub.setMaxLines(2); card.addView(sub);
        card.setClickable(true); card.setFocusable(true); card.setOnClickListener(v -> open(tool.cls));
        return card;
    }

    private void toggle(String title, String sub, String key, boolean initial) {
        LinearLayout row = PremiumUi.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14),dp(10),dp(10),dp(10));
        row.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
        LinearLayout words = PremiumUi.col(this);
        words.addView(PremiumUi.text(this,title,13,theme.text,true));
        words.addView(PremiumUi.text(this,sub,9.5f,theme.muted,false));
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1f));
        final boolean[] on={initial};
        Button switcher = PremiumUi.button(this, initial?"ATIVO":"DESLIGADO", initial);
        row.addView(switcher,new LinearLayout.LayoutParams(dp(112),dp(44)));
        switcher.setOnClickListener(v->{on[0]=!on[0];DriveSettings.toggle(this,key,on[0]);switcher.setText(on[0]?"ATIVO":"DESLIGADO");switcher.setBackground(PremiumUi.panel(this,on[0]?theme.primary:theme.surfaceAlt,on[0]?theme.primary:theme.border,theme.radiusDp));});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));page.addView(row,p);
    }

    private void open(Class<?> cls) {
        try { startActivity(new Intent(this, cls)); }
        catch (Throwable e) { Toast.makeText(this,"Essa função não conseguiu abrir agora.",Toast.LENGTH_SHORT).show(); }
    }

    private int dp(float v){return PremiumUi.dp(this,v);}

    private static final class Tool {
        final String icon,title,sub; final Class<?> cls;
        Tool(String icon,String title,String sub,Class<?> cls){this.icon=icon;this.title=title;this.sub=sub;this.cls=cls;}
    }
}
