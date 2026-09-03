#!/usr/bin/env python3
from pathlib import Path
import re

APP = Path('estrada-play-comunista-app')
GRADLE = APP / 'app/build.gradle'
ROAD = APP / 'app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java'

# Version bump.
g = GRADLE.read_text(encoding='utf-8')
g = re.sub(r'versionCode\s+207\b', 'versionCode 208', g, count=1)
g = re.sub(r"versionName\s+'2\.0\.7'", "versionName '2.0.8'", g, count=1)
if 'versionCode 208' not in g or "versionName '2.0.8'" not in g:
    raise SystemExit('Could not bump app version to 2.0.8')
GRADLE.write_text(g, encoding='utf-8')

s = ROAD.read_text(encoding='utf-8')

if 'NAVIGATION_REAL_ACTIVITY_V208' not in s:
    anchor = '    private double lastRouteLon = Double.NaN;\n'
    if anchor not in s:
        raise SystemExit('Route state anchor not found')
    s = s.replace(anchor, anchor + '''\n    // NAVIGATION_REAL_ACTIVITY_V208: progress follows the matched route polyline, not straight-line displacement.\n    private double routeProgressM;\n    private int routeSegmentHint = -1;\n    private int offRouteSamples;\n    private long lastRerouteAt;\n    private boolean routeArrived;\n''', 1)

    refresh_pattern = re.compile(
        r'    private void refreshDestinationRoute\(double lat, double lon\) \{.*?\n    \}\n\n    // NAV_POLISH_V201 helpers',
        re.S,
    )
    refresh_replacement = '''    private void refreshDestinationRoute(double lat, double lon) {\n        DestinationStore.Destination d = destination;\n        if (d == null || !Double.isFinite(lat) || !Double.isFinite(lon)) {\n            activeRoute = null;\n            routeProgressM = 0;\n            routeSegmentHint = -1;\n            routeArrived = false;\n            if (roadMap != null) roadMap.setRouteGeoJson(null);\n            return;\n        }\n        if (routeArrived || activeRoute != null || routeLoading.get()) return;\n        long now = System.currentTimeMillis();\n        if (lastRouteAt > 0 && now - lastRouteAt < 12_000L) return;\n        if (!routeLoading.compareAndSet(false, true)) return;\n        lastRouteAt = now;\n        if (destinationText != null) destinationText.setText("Calculando rota…");\n        routeIo.execute(() -> {\n            try {\n                RouteEngine.Route route = RouteEngine.fetch(RoadMapActivity.this, lat, lon, d.lat, d.lon);\n                activeRoute = route;\n                lastRouteAt = System.currentTimeMillis();\n                lastRouteLat = lat;\n                lastRouteLon = lon;\n                routeProgressM = 0;\n                routeSegmentHint = -1;\n                offRouteSamples = 0;\n                routeArrived = false;\n                ui.post(() -> {\n                    if (roadMap != null) roadMap.setRouteGeoJson(route.geoJson);\n                    if (universalRouteText != null) universalRouteText.setText("ROTA · " + route.summary());\n                    renderRouteUi(route, 0);\n                });\n            } catch (Throwable e) {\n                ui.post(() -> {\n                    if (navInstructionText != null) navInstructionText.setText("Rota online indisponível");\n                    if (navRoadText != null) navRoadText.setText("A proteção da estrada continua ativa");\n                    if (destinationText != null) destinationText.setText("Rota online indisponível. A proteção continua ativa.");\n                });\n            } finally {\n                routeLoading.set(false);\n            }\n        });\n    }\n\n    // NAV_POLISH_V201 helpers'''
    s, n = refresh_pattern.subn(refresh_replacement, s, count=1)
    if n != 1:
        raise SystemExit(f'Could not replace refreshDestinationRoute ({n})')

    glyph_pattern = re.compile(r'    private String maneuverGlyph\(RouteEngine\.Route r\)\{[^\n]*\}\n')
    glyph_replacement = '''    private String maneuverGlyph(RouteEngine.Step step) {\n        if (step == null) return "↑";\n        String m = step.modifier == null ? "" : step.modifier;\n        String type = step.type == null ? "" : step.type;\n        if (type.contains("roundabout") || type.contains("rotary")) return "↻";\n        if (type.contains("arrive")) return "★";\n        if (m.contains("right")) return "↱";\n        if (m.contains("left")) return "↰";\n        return "↑";\n    }\n'''
    s, n = glyph_pattern.subn(glyph_replacement, s, count=1)
    if n != 1:
        raise SystemExit(f'Could not replace maneuverGlyph ({n})')

    progress_pattern = re.compile(
        r'    private void renderRouteUi\(RouteEngine\.Route route,double moved\)\{[^\n]*\}\n'
        r'    private void updateRouteProgress\(double lat,double lon\)\{[^\n]*\}\n'
    )
    progress_replacement = '''    private void renderRouteUi(RouteEngine.Route route, double alongM) {\n        if (route == null) return;\n        double moved = Math.max(0, Math.min(route.distanceM, alongM));\n        double rem = route.remainingDistance(moved);\n        double dur = route.remainingDuration(moved);\n        RouteEngine.Step step = route.upcomingStep(moved);\n        double next = route.nextManeuverDistance(moved);\n        String instruction = step == null || step.instruction.isEmpty() ? "Siga na rota" : step.instruction;\n        String road = step == null || step.road.isEmpty()\n                ? shortDestination(destination == null ? "Destino" : destination.label) : step.road;\n        if (navTurnText != null) navTurnText.setText(maneuverGlyph(step));\n        if (navDistanceText != null) navDistanceText.setText(navDistance(next));\n        if (navInstructionText != null) navInstructionText.setText(instruction);\n        if (navRoadText != null) navRoadText.setText(road);\n        if (navEtaText != null) {\n            String eta = new SimpleDateFormat("HH:mm", Locale.getDefault())\n                    .format(new Date(System.currentTimeMillis() + (long) (dur * 1000)));\n            navEtaText.setText(eta + "\\nCHEGADA");\n        }\n        if (navRemainingText != null) navRemainingText.setText(remainDistance(rem) + "\\nRESTANTE");\n        if (navDurationText != null) navDurationText.setText(durationText(dur) + "\\nDURAÇÃO");\n        if (destinationText != null) destinationText.setText(remainDistance(rem) + " · " + durationText(dur) + "\\n" + instruction);\n    }\n\n    private void updateRouteProgress(double lat, double lon) {\n        RouteEngine.Route route = activeRoute;\n        if (route == null || routeArrived) return;\n        RouteEngine.Match match = route.match(lat, lon, lastHeading, routeSegmentHint, routeProgressM);\n        if (!match.valid) return;\n        routeSegmentHint = match.segmentIndex;\n\n        double offRouteThreshold = universalSpeed < 5 ? 95.0 : (universalSpeed < 35 ? 80.0 : 65.0);\n        boolean offRoute = match.lateralM > offRouteThreshold;\n        if (offRoute) {\n            offRouteSamples++;\n            if (navRoadText != null) navRoadText.setText("FORA DA ROTA · " + Math.round(match.lateralM) + " m");\n        } else {\n            offRouteSamples = 0;\n            // Ignore GPS noise that would move progress backwards. A real U-turn naturally triggers a reroute.\n            if (match.alongM >= routeProgressM - 35.0) routeProgressM = Math.max(routeProgressM, match.alongM);\n            routeProgressM = Math.min(route.distanceM, routeProgressM);\n        }\n\n        double remaining = route.remainingDistance(routeProgressM);\n        if (!offRoute && remaining <= 35.0) {\n            routeProgressM = route.distanceM;\n            routeArrived = true;\n            renderRouteUi(route, routeProgressM);\n            if (navTurnText != null) navTurnText.setText("★");\n            if (navDistanceText != null) navDistanceText.setText("CHEGOU");\n            if (navInstructionText != null) navInstructionText.setText("Chegada ao destino");\n            if (destinationText != null) destinationText.setText("Você chegou ao destino");\n            return;\n        }\n\n        if (offRouteSamples >= 3 && System.currentTimeMillis() - lastRerouteAt >= 20_000L) {\n            lastRerouteAt = System.currentTimeMillis();\n            offRouteSamples = 0;\n            activeRoute = null;\n            routeProgressM = 0;\n            routeSegmentHint = -1;\n            lastRouteAt = 0L;\n            if (navInstructionText != null) navInstructionText.setText("Recalculando rota…");\n            if (destinationText != null) destinationText.setText("Saída da rota detectada · recalculando…");\n            refreshDestinationRoute(lat, lon);\n            return;\n        }\n\n        if (!offRoute) renderRouteUi(route, routeProgressM);\n    }\n'''
    s, n = progress_pattern.subn(progress_replacement, s, count=1)
    if n != 1:
        raise SystemExit(f'Could not replace route progress helpers ({n})')

    intent_anchor = '''            activeRoute = null;\n            lastRouteAt = 0L;'''
    intent_replacement = '''            activeRoute = null;\n            routeProgressM = 0;\n            routeSegmentHint = -1;\n            offRouteSamples = 0;\n            routeArrived = false;\n            lastRouteAt = 0L;'''
    if intent_anchor not in s:
        raise SystemExit('Destination reset anchor not found')
    s = s.replace(intent_anchor, intent_replacement, 1)

if 'NAVIGATION_REAL_ACTIVITY_V208' not in s:
    raise SystemExit('2.0.8 marker missing after patch')
if 'RouteEngine.fetch(RoadMapActivity.this' not in s:
    raise SystemExit('Context-aware route fetch missing')
if 'route.match(lat, lon' not in s:
    raise SystemExit('Map matching integration missing')
if 'Recalculando rota' not in s:
    raise SystemExit('Off-route rerouting integration missing')

ROAD.write_text(s, encoding='utf-8')
print('Estrada Play 2.0.8 navigation real patch ready')
