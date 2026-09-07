from pathlib import Path

root = Path('estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista')


def load(name):
    p = root / name
    return p, p.read_text(encoding='utf-8')


def rep(text, old, new, label):
    if old not in text:
        raise SystemExit(f'missing pattern: {label}')
    return text.replace(old, new, 1)


# CopilotService: Android 14+ microphone FGS only starts after permission and from a visible Activity.
p, s = load('CopilotService.java')
s = rep(s, 'import android.Manifest;\nimport android.app.Notification;',
        'import android.Manifest;\nimport android.app.Activity;\nimport android.app.Notification;', 'copilot activity import')
s = rep(s, '    private boolean registered;\n    private boolean oneShot;',
        '    private boolean registered;\n    private boolean foregroundStarted;\n    private boolean oneShot;', 'copilot foreground field')
s = rep(s,
'''    static void requestStart(Context context) {
        Intent intent = new Intent(context, CopilotService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable ignored) {}
    }
''',
'''    static void requestStart(Context context) {
        if (!canStartMicrophoneFgs(context)) return;
        Intent intent = new Intent(context, CopilotService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable ignored) {}
    }
''', 'copilot requestStart')
s = rep(s,
'''    static void requestListenNow(Context context) {
        Intent intent = new Intent(context, CopilotService.class).setAction(ACTION_LISTEN_NOW);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable ignored) {}
    }
''',
'''    static void requestListenNow(Context context) {
        if (!canStartMicrophoneFgs(context)) return;
        Intent intent = new Intent(context, CopilotService.class).setAction(ACTION_LISTEN_NOW);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable ignored) {}
    }
''', 'copilot requestListenNow')
marker = '''    static void requestQuery(Context context) {
        Intent intent = new Intent(context, CopilotService.class).setAction(ACTION_QUERY);
        try { context.startService(intent); } catch (Throwable ignored) {}
    }
'''
s = rep(s, marker, marker + '''
    private static boolean canStartMicrophoneFgs(Context context) {
        if (context == null) return false;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        // Android 14+ rejects creation of a microphone FGS from a background-only context.
        // If the process dies, ActivityLifecycleCallbacks re-arm it when the app is visible again.
        return Build.VERSION.SDK_INT < 34 || context instanceof Activity;
    }
''', 'copilot start guard helper')
s = rep(s,
'''    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Copiloto", "Preparando serviço de voz"));
        initTts();
        registerContextReceivers();
    }
''',
'''    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        // Do not call startForeground here. Android 14+ may recreate a service before the
        // while-in-use microphone eligibility exists; onStartCommand validates it safely.
        initTts();
        registerContextReceivers();
    }
''', 'copilot onCreate')
start = s.index('    @Override public int onStartCommand(Intent intent, int flags, int startId) {')
end = s.index('    @Override public IBinder onBind(Intent intent) { return null; }', start)
s = s[:start] + '''    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            CopilotSettings.setEnabled(this, false);
            setState(STATE_OFF, "Copiloto desligado");
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            publishCurrentState();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setState(STATE_OFF, "Autorize o microfone para usar o Copiloto");
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (!ensureForeground()) {
            // Never crash/restart-loop. The next visible Activity safely re-arms the service.
            setState(STATE_OFF, "Abra o Estrada Play para reativar o microfone do Copiloto");
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (ACTION_LISTEN_NOW.equals(action)) {
            oneShot = !CopilotSettings.enabled(this);
            beginCommandListening();
            return START_NOT_STICKY;
        }
        CopilotSettings.setEnabled(this, true);
        oneShot = false;
        enterWaiting("Diga “" + CopilotSettings.wakeWord(this) + "”");
        scheduleWake(180L);
        return START_NOT_STICKY;
    }

    private boolean ensureForeground() {
        if (foregroundStarted) return true;
        try {
            startForeground(NOTIFICATION_ID, notification("Copiloto", "Preparando serviço de voz"));
            foregroundStarted = true;
            return true;
        } catch (SecurityException | IllegalStateException denied) {
            foregroundStarted = false;
            return false;
        } catch (Throwable denied) {
            foregroundStarted = false;
            return false;
        }
    }

''' + s[end:]
s = rep(s, '        try { stopForeground(true); } catch (Throwable ignored) {}\n        stopSelf();',
        '        try { stopForeground(true); } catch (Throwable ignored) {}\n        foregroundStarted = false;\n        stopSelf();', 'copilot foreground reset')
p.write_text(s, encoding='utf-8')


# Map microphone button requests permission directly and automatically continues after grant.
p, s = load('RoadMapActivity.java')
s = rep(s, '    private static final String KEY_ACCOUNT = "account";\n',
        '    private static final String KEY_ACCOUNT = "account";\n    private static final int REQ_COPILOT_AUDIO = 2202;\n', 'roadmap copilot request code')
marker = '    private void startSafety(){try{Intent i=new Intent(this,RoadSafetyService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Throwable ignored){}}\n'
insert = '''    void requestCopilotNow(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){
            CopilotService.requestListenNow(this);return;
        }
        try{requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_COPILOT_AUDIO);}catch(Throwable ignored){
            Toast.makeText(this,"Não foi possível solicitar acesso ao microfone.",Toast.LENGTH_SHORT).show();
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode!=REQ_COPILOT_AUDIO)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)CopilotService.requestListenNow(this);
        else Toast.makeText(this,"Autorize o microfone para falar com o Copiloto.",Toast.LENGTH_LONG).show();
    }
''' + marker
s = rep(s, marker, insert, 'roadmap copilot permission gate')
p.write_text(s, encoding='utf-8')

p, s = load('RoadMapReferenceUiV360.java')
s = rep(s, '        mic.setOnClickListener(v -> CopilotService.requestListenNow(a));',
        '        mic.setOnClickListener(v -> a.requestCopilotNow());', 'cockpit mic permission')
p.write_text(s, encoding='utf-8')


# Weather: store and expose precipitation in millimeters without double-counting total precipitation.
p, s = load('RoadWeatherMonitor.java')
s = rep(s, '        final long updatedAt;\n        final String error;',
        '        final long updatedAt;\n        final double currentPrecipMm;\n        final double nextRainMm;\n        final double next6hTotalMm;\n        final double routeRainMm;\n        final String error;', 'weather snapshot mm fields')
s = rep(s, '                 String routeLabel, long updatedAt, String error) {',
        '                 String routeLabel, long updatedAt, double currentPrecipMm, double nextRainMm,\n                 double next6hTotalMm, double routeRainMm, String error) {', 'weather snapshot ctor args')
s = rep(s, '            this.updatedAt = updatedAt;\n            this.error = error == null ? "" : error;',
        '            this.updatedAt = updatedAt;\n            this.currentPrecipMm = currentPrecipMm;\n            this.nextRainMm = nextRainMm;\n            this.next6hTotalMm = next6hTotalMm;\n            this.routeRainMm = routeRainMm;\n            this.error = error == null ? "" : error;', 'weather snapshot ctor assigns')
s = rep(s, '            if (currentWet) return prefix + " · CHUVA AGORA" + chanceText(currentChance);',
        '            if (currentWet) return prefix + " · CHUVA AGORA" + mmText(currentPrecipMm) + chanceText(currentChance);', 'weather compact current mm')
s = rep(s, '                return prefix + " · CHUVA EM " + distance(routeRainKm) + " (~" + routeRainMinutes + " min)" + chanceText(routeRainChance);',
        '                return prefix + " · CHUVA EM " + distance(routeRainKm) + " (~" + routeRainMinutes + " min)" + mmText(routeRainMm) + chanceText(routeRainChance);', 'weather compact route mm')
s = rep(s, '            if (nextRainMinutes >= 0) return prefix + " · CHUVA EM ~" + nextRainMinutes + " min" + chanceText(nextRainChance);',
        '            if (nextRainMinutes >= 0) return prefix + " · CHUVA EM ~" + nextRainMinutes + " min" + mmText(nextRainMm) + chanceText(nextRainChance);', 'weather compact next mm')
s = rep(s, '                String base = "Chuva agora na sua região." + temp;\n                return currentChance > 0 ? base + " Probabilidade de " + currentChance + " por cento." : base;',
        '                String base = "Chuva agora na sua região." + spokenMm(currentPrecipMm) + temp;\n                return currentChance > 0 ? base + " Probabilidade de " + currentChance + " por cento." : base;', 'weather spoken current mm')
s = rep(s, '                if (routeRainChance > 0) base += " Probabilidade de " + routeRainChance + " por cento.";\n                return base + temp;',
        '                base += spokenMm(routeRainMm);\n                if (routeRainChance > 0) base += " Probabilidade de " + routeRainChance + " por cento.";\n                return base + temp;', 'weather spoken route mm')
s = rep(s, '                if (nextRainChance > 0) base += " Probabilidade de " + nextRainChance + " por cento.";\n                return base + temp;',
        '                base += spokenMm(nextRainMm);\n                if (nextRainChance > 0) base += " Probabilidade de " + nextRainChance + " por cento.";\n                return base + temp;', 'weather spoken next mm')
s = rep(s, '            return out.toString();\n        }\n\n        private static String chanceText',
        '            if (next6hTotalMm > 0.04) {\n                if (out.length() > 0) out.append(" · ");\n                out.append("~").append(String.format(Locale.getDefault(), "%.1f", next6hTotalMm)).append(" mm/6h");\n            }\n            return out.toString();\n        }\n\n        private static String mmText(double mm) { return Double.isFinite(mm) && mm > 0.04 ? " · " + String.format(Locale.getDefault(), "%.1f mm", mm) : ""; }\n        private static String spokenMm(double mm) { return Double.isFinite(mm) && mm > 0.04 ? " Previsão de cerca de " + String.format(Locale.getDefault(), "%.1f", mm) + " milímetros de precipitação." : ""; }\n        private static String chanceText', 'weather helpers and 6h mm')
s = rep(s, '                p.getString("route_label", ""),\n                at,\n                p.getString("last_error", "")',
        '                p.getString("route_label", ""),\n                at,\n                p.getFloat("current_precip_mm", Float.NaN),\n                p.getFloat("next_rain_mm", Float.NaN),\n                p.getFloat("next_6h_total_mm", Float.NaN),\n                p.getFloat("route_rain_mm", Float.NaN),\n                p.getString("last_error", "")', 'weather snapshot prefs mm')
s = rep(s, '            int nextMin = -1, nextChance = 0;\n            int maxChance6h = 0;',
        '            int nextMin = -1, nextChance = 0;\n            double nextRainMm = Double.NaN, totalMm6h = 0;\n            int maxChance6h = 0;', 'weather refresh mm vars')
s = rep(s, '                maxChance6h = Math.max(maxChance6h, r.chance);',
        '                maxChance6h = Math.max(maxChance6h, r.chance);\n                if (Double.isFinite(r.mm) && r.mm > 0) totalMm6h += r.mm;', 'weather sum mm')
s = rep(s, '                    nextMin = i * 60;\n                    nextChance = r.chance;',
        '                    nextMin = i * 60;\n                    nextChance = r.chance;\n                    nextRainMm = r.mm;', 'weather next rain mm')
s = rep(s, '                    .putInt("current_chance", f.currentChance)\n                    .putFloat("current_temp_c", finiteFloat(f.currentTempC))',
        '                    .putInt("current_chance", f.currentChance)\n                    .putFloat("current_precip_mm", finiteFloat(f.currentPrecipMm))\n                    .putFloat("current_temp_c", finiteFloat(f.currentTempC))', 'weather store current mm')
s = rep(s, '                    .putInt("next_rain_chance", nextChance)\n                    .putInt("next_6h_max_chance", maxChance6h)',
        '                    .putInt("next_rain_chance", nextChance)\n                    .putFloat("next_rain_mm", finiteFloat(nextRainMm))\n                    .putFloat("next_6h_total_mm", finiteFloat(totalMm6h))\n                    .putInt("next_6h_max_chance", maxChance6h)', 'weather store forecast mm')
# First route-clear block.
s = rep(s, '                .putInt("route_rain_chance", 0)\n                .apply();',
        '                .putInt("route_rain_chance", 0)\n                .putFloat("route_rain_mm", Float.NaN)\n                .apply();', 'weather clear active route mm')
s = rep(s, '            int riskMin = -1, riskChance = 0;',
        '            int riskMin = -1, riskChance = 0;\n            double riskMm = Double.NaN;', 'weather route mm var')
s = rep(s, '                riskChance = risk.chance;\n                break;',
        '                riskChance = risk.chance;\n                riskMm = risk.mm;\n                break;', 'weather route risk mm')
s = rep(s, '                    .putInt("route_rain_chance", riskChance)\n                    .apply();',
        '                    .putInt("route_rain_chance", riskChance)\n                    .putFloat("route_rain_mm", finiteFloat(riskMm))\n                    .apply();', 'weather store route mm')
s = rep(s, '                .putInt("route_rain_chance", 0)\n                .apply();\n    }\n\n    private static Forecast fetchSingle',
        '                .putInt("route_rain_chance", 0)\n                .putFloat("route_rain_mm", Float.NaN)\n                .apply();\n    }\n\n    private static Forecast fetchSingle', 'weather clear route risk mm')
s = rep(s, '        final int chance;\n        Risk(boolean rain, int chance) { this.rain = rain; this.chance = Math.max(0, Math.min(100, chance)); }',
        '        final int chance;\n        final double mm;\n        Risk(boolean rain, int chance, double mm) { this.rain = rain; this.chance = Math.max(0, Math.min(100, chance)); this.mm = Math.max(0, Double.isFinite(mm) ? mm : 0); }', 'weather Risk mm')
s = rep(s, '        final boolean currentWet;\n        final int currentChance;\n        final double currentTempC, feelsLikeC, windKmh, gustKmh;',
        '        final boolean currentWet;\n        final int currentChance;\n        final double currentPrecipMm;\n        final double currentTempC, feelsLikeC, windKmh, gustKmh;', 'weather Forecast current mm field')
s = rep(s, '        Forecast(boolean currentWet, int currentChance, double currentTempC, double feelsLikeC,',
        '        Forecast(boolean currentWet, int currentChance, double currentPrecipMm, double currentTempC, double feelsLikeC,', 'weather Forecast ctor arg')
s = rep(s, '            this.currentChance = currentChance;\n            this.currentTempC = currentTempC;',
        '            this.currentChance = currentChance;\n            this.currentPrecipMm = currentPrecipMm;\n            this.currentTempC = currentTempC;', 'weather Forecast ctor assign')
s = rep(s, '            double nowP = current == null ? 0 : current.optDouble("precipitation", 0) + current.optDouble("rain", 0) + current.optDouble("showers", 0);',
        '            double nowP = current == null ? 0 : current.optDouble("precipitation", Double.NaN);\n            if (!Double.isFinite(nowP)) nowP = current == null ? 0 : current.optDouble("rain", 0) + current.optDouble("showers", 0);', 'weather no double counting current')
s = rep(s, '                    wet, chance,\n                    current == null ? Double.NaN : current.optDouble("temperature_2m", Double.NaN),',
        '                    wet, chance, nowP,\n                    current == null ? Double.NaN : current.optDouble("temperature_2m", Double.NaN),', 'weather Forecast parse ctor')
s = rep(s, '            if (i < 0) return new Risk(false, 0);\n            int chance = probability.optInt(i, 0);\n            double mm = precipitation.optDouble(i, 0) + rain.optDouble(i, 0) + showers.optDouble(i, 0);',
        '            if (i < 0) return new Risk(false, 0, 0);\n            int chance = probability.optInt(i, 0);\n            double mm = precipitation.optDouble(i, Double.NaN);\n            if (!Double.isFinite(mm)) mm = rain.optDouble(i, 0) + showers.optDouble(i, 0);', 'weather no double counting hourly')
s = rep(s, '            return new Risk(risk, chance);',
        '            return new Risk(risk, chance, mm);', 'weather riskAt return mm')
s = rep(s, '            int chance = 0;\n            int from = Math.max(0, hour - 1), to = Math.min(Math.max(0, size() - 1), hour + 1);',
        '            int chance = 0;\n            double mm = 0;\n            int from = Math.max(0, hour - 1), to = Math.min(Math.max(0, size() - 1), hour + 1);', 'weather riskAround mm var')
s = rep(s, '                chance = Math.max(chance, r.chance);\n            }\n            return new Risk(rain, chance);',
        '                chance = Math.max(chance, r.chance);\n                mm = Math.max(mm, r.mm);\n            }\n            return new Risk(rain, chance, mm);', 'weather riskAround return mm')
p.write_text(s, encoding='utf-8')


# Weather screen: explicit millimeter metrics.
p, s = load('WeatherActivity.java')
s = rep(s, '        local.addView(metric("CHANCE DE CHUVA AGORA",s.available?(s.currentChance+"%"):"—",s.currentChance>=40?GOLD:GREEN));',
        '        local.addView(metric("CHANCE DE CHUVA AGORA",s.available?(s.currentChance+"%"):"—",s.currentChance>=40?GOLD:GREEN));\n        local.addView(metric("PRECIPITAÇÃO AGORA",s.available?mm(s.currentPrecipMm):"—",s.currentPrecipMm>=1?GOLD:TEXT));', 'weather UI current mm')
s = rep(s, '        next.addView(metric("TENDÊNCIA",s.available?s.nextHoursSummary():"SEM DADOS",s.next6hMaxChance>=40?GOLD:GREEN));\n        next.addView(metric("PRÓXIMA CHUVA",s.nextRainMinutes>=0?("~"+s.nextRainMinutes+" min · "+s.nextRainChance+"%"):"não prevista nas próximas horas",s.nextRainMinutes>=0?GOLD:GREEN));',
        '        next.addView(metric("TENDÊNCIA",s.available?s.nextHoursSummary():"SEM DADOS",s.next6hMaxChance>=40?GOLD:GREEN));\n        next.addView(metric("ACUMULADO PREVISTO · 6H",s.available?mm(s.next6hTotalMm):"—",s.next6hTotalMm>=5?RED:(s.next6hTotalMm>=1?GOLD:GREEN)));\n        next.addView(metric("PRÓXIMA CHUVA",s.nextRainMinutes>=0?("~"+s.nextRainMinutes+" min · "+mm(s.nextRainMm)+" · "+s.nextRainChance+"%"):"não prevista nas próximas horas",s.nextRainMinutes>=0?GOLD:GREEN));', 'weather UI 6h next mm')
s = rep(s, '            route.addView(text("Chegada estimada ao trecho em ~"+s.routeRainMinutes+" min · chance "+s.routeRainChance+"%",12,GOLD,true));',
        '            route.addView(text("Chegada estimada ao trecho em ~"+s.routeRainMinutes+" min · "+mm(s.routeRainMm)+" · chance "+s.routeRainChance+"%",12,GOLD,true));', 'weather UI route mm')
s = rep(s, '• condição atual: temperatura, sensação, chuva, vento e código meteorológico\\n• próximas horas: faixa de temperatura e maior chance de chuva\\n• rota: até seis pontos do percurso comparados com o horário previsto de chegada',
        '• condição atual: temperatura, sensação, chuva em milímetros, vento e código meteorológico\\n• próximas horas: faixa de temperatura, chance e acumulado de chuva em mm\\n• rota: até seis pontos do percurso comparados com o horário previsto de chegada e volume previsto no trecho', 'weather explain mm')
s = rep(s, '    private View section(String title,String sub){',
        '    private String mm(double value){if(!Double.isFinite(value)||value<0)return "—";return String.format(Locale.getDefault(),"%.1f mm",Math.max(0,value));}\n    private View section(String title,String sub){', 'weather UI mm helper')
p.write_text(s, encoding='utf-8')

# Clean up one-shot automation files in the resulting commit.
for tmp in [Path('.github/workflows/fix-copilot-weather-once.yml'), Path('.github/patch_copilot_weather.py')]:
    if tmp.exists():
        tmp.unlink()
