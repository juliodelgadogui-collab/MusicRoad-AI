from pathlib import Path

root = Path('estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista')

def patch(path, replacements):
    text = path.read_text(encoding='utf-8')
    for label, old, new in replacements:
        if old not in text:
            raise SystemExit(f'missing {label} in {path.name}')
        text = text.replace(old, new, 1)
    path.write_text(text, encoding='utf-8')

p = root / 'RoadWeatherMonitor.java'
patch(p, [
    ('route 3h field',
     '        final double routeRainMm;\n        final String error;',
     '        final double routeRainMm;\n        final double routeRain3hMm;\n        final String error;'),
    ('ctor arg',
     '                 double next6hTotalMm, double routeRainMm, String error) {',
     '                 double next6hTotalMm, double routeRainMm, double routeRain3hMm, String error) {'),
    ('ctor assign',
     '            this.routeRainMm = routeRainMm;\n            this.error = error == null ? "" : error;',
     '            this.routeRainMm = routeRainMm;\n            this.routeRain3hMm = routeRain3hMm;\n            this.error = error == null ? "" : error;'),
    ('compact current',
     '            if (currentWet) return prefix + " · CHUVA AGORA" + mmText(currentPrecipMm) + chanceText(currentChance);',
     '            if (currentWet) return prefix + " · CHUVA AGORA" + chanceText(currentChance);'),
    ('compact route',
     '                return prefix + " · CHUVA EM " + distance(routeRainKm) + " (~" + routeRainMinutes + " min)" + mmText(routeRainMm) + chanceText(routeRainChance);',
     '                return prefix + " · CHUVA EM " + distance(routeRainKm) + " (~" + routeRainMinutes + " min)" + chanceText(routeRainChance);'),
    ('compact next',
     '            if (nextRainMinutes >= 0) return prefix + " · CHUVA EM ~" + nextRainMinutes + " min" + mmText(nextRainMm) + chanceText(nextRainChance);',
     '            if (nextRainMinutes >= 0) return prefix + " · CHUVA EM ~" + nextRainMinutes + " min" + chanceText(nextRainChance);'),
    ('spoken route block',
     '                String base = "Chuva prevista à frente, a aproximadamente " + spokenDistance(routeRainKm)\n                        + ", em cerca de " + routeRainMinutes + " minutos.";\n                base += spokenMm(routeRainMm);\n                if (routeRainChance > 0) base += " Probabilidade de " + routeRainChance + " por cento.";\n                return base + temp;',
     '                String base = "Chuva prevista na rota, a aproximadamente " + spokenDistance(routeRainKm)\n                        + ". Você deve alcançar essa área em cerca de " + routeRainMinutes + " minutos.";\n                if (routeRainChance > 0) base += " Chance de chuva de " + routeRainChance + " por cento.";\n                if (Double.isFinite(routeRain3hMm) && routeRain3hMm > 0.04)\n                    base += " O acumulado previsto na janela de três horas em torno da passagem é de cerca de "\n                            + String.format(Locale.getDefault(), "%.1f", routeRain3hMm) + " milímetros.";\n                else base += spokenMm(routeRainMm);\n                return base + temp;'),
    ('6h wording',
     '                out.append("~").append(String.format(Locale.getDefault(), "%.1f", next6hTotalMm)).append(" mm/6h");',
     '                out.append("acumulado ~").append(String.format(Locale.getDefault(), "%.1f", next6hTotalMm)).append(" mm em 6 h");'),
    ('snapshot pref',
     '                p.getFloat("route_rain_mm", Float.NaN),\n                p.getString("last_error", "")',
     '                p.getFloat("route_rain_mm", Float.NaN),\n                p.getFloat("route_rain_3h_mm", Float.NaN),\n                p.getString("last_error", "")'),
    ('clear active route',
     '                .putFloat("route_rain_mm", Float.NaN)\n                .apply();',
     '                .putFloat("route_rain_mm", Float.NaN)\n                .putFloat("route_rain_3h_mm", Float.NaN)\n                .apply();'),
    ('route vars',
     '            double riskMm = Double.NaN;\n            int count = Math.min(samples.size(), forecasts.size());',
     '            double riskMm = Double.NaN, risk3hMm = Double.NaN;\n            int count = Math.min(samples.size(), forecasts.size());'),
    ('route risk assignment',
     '                riskChance = risk.chance;\n                riskMm = risk.mm;\n                break;',
     '                riskChance = risk.chance;\n                riskMm = risk.mm;\n                risk3hMm = f.sumMmAround(hour);\n                break;'),
    ('route pref store',
     '                    .putFloat("route_rain_mm", finiteFloat(riskMm))\n                    .apply();',
     '                    .putFloat("route_rain_mm", finiteFloat(riskMm))\n                    .putFloat("route_rain_3h_mm", finiteFloat(risk3hMm))\n                    .apply();'),
    ('clear route risk',
     '                .putFloat("route_rain_mm", Float.NaN)\n                .apply();',
     '                .putFloat("route_rain_mm", Float.NaN)\n                .putFloat("route_rain_3h_mm", Float.NaN)\n                .apply();'),
    ('sum around method',
     '        Risk riskAround(int hour) {\n            boolean rain = false;\n            int chance = 0;\n            double mm = 0;\n            int from = Math.max(0, hour - 1), to = Math.min(Math.max(0, size() - 1), hour + 1);\n            for (int i = from; i <= to; i++) {\n                Risk r = riskAt(i);\n                rain = rain || r.rain;\n                chance = Math.max(chance, r.chance);\n                mm = Math.max(mm, r.mm);\n            }\n            return new Risk(rain, chance, mm);\n        }',
     '        Risk riskAround(int hour) {\n            boolean rain = false;\n            int chance = 0;\n            double mm = 0;\n            int from = Math.max(0, hour - 1), to = Math.min(Math.max(0, size() - 1), hour + 1);\n            for (int i = from; i <= to; i++) {\n                Risk r = riskAt(i);\n                rain = rain || r.rain;\n                chance = Math.max(chance, r.chance);\n                mm = Math.max(mm, r.mm);\n            }\n            return new Risk(rain, chance, mm);\n        }\n\n        double sumMmAround(int hour) {\n            double total = 0;\n            int from = Math.max(0, hour - 1), to = Math.min(Math.max(0, size() - 1), hour + 1);\n            for (int i = from; i <= to; i++) total += riskAt(i).mm;\n            return total;\n        }')
])

p = root / 'WeatherActivity.java'
patch(p, [
    ('current precip metric',
     '        local.addView(metric("CHANCE DE CHUVA AGORA",s.available?(s.currentChance+"%"):"—",s.currentChance>=40?GOLD:GREEN));',
     '        local.addView(metric("CHANCE DE CHUVA AGORA",s.available?(s.currentChance+"%"):"—",s.currentChance>=40?GOLD:GREEN));\n        local.addView(metric("PRECIPITAÇÃO ATUAL",s.available&&Double.isFinite(s.currentPrecipMm)?String.format(Locale.getDefault(),"%.1f mm",s.currentPrecipMm):"—",s.currentWet?GOLD:TEXT));'),
    ('6h accumulated metric',
     '        next.addView(metric("PRÓXIMA CHUVA",s.nextRainMinutes>=0?("~"+s.nextRainMinutes+" min · "+s.nextRainChance+"%"):"não prevista nas próximas horas",s.nextRainMinutes>=0?GOLD:GREEN));',
     '        next.addView(metric("PRÓXIMA CHUVA",s.nextRainMinutes>=0?("~"+s.nextRainMinutes+" min · "+s.nextRainChance+"%"):"não prevista nas próximas horas",s.nextRainMinutes>=0?GOLD:GREEN));\n        next.addView(metric("ACUMULADO PREVISTO · 6H",s.available&&Double.isFinite(s.next6hTotalMm)?String.format(Locale.getDefault(),"~%.1f mm",s.next6hTotalMm):"—",s.next6hTotalMm>=5?GOLD:TEXT));'),
    ('route wording',
     '            route.addView(text("Chegada estimada ao trecho em ~"+s.routeRainMinutes+" min · chance "+s.routeRainChance+"%",12,GOLD,true));',
     '            route.addView(text("Você deve alcançar essa área em ~"+s.routeRainMinutes+" min",12,GOLD,true));\n            route.addView(text("Chance de chuva: "+s.routeRainChance+"%",12,TEXT,true));\n            if(Double.isFinite(s.routeRain3hMm))route.addView(text(String.format(Locale.getDefault(),"Acumulado previsto na janela de 3 h: ~%.1f mm",s.routeRain3hMm),12,TEXT,true));\n            if(Double.isFinite(s.routeRainMm))route.addView(text(String.format(Locale.getDefault(),"Pico horário previsto: ~%.1f mm",s.routeRainMm),10,MUTED,false));')
])
