package com.musicroad.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uses the MusicRoad authenticated route endpoint as the single source of truth.
 * The endpoint geocodes and calculates the route with Mapbox and returns the
 * route-filtered radar / speed-bump set in the same response.
 */
final class ServerMapboxRouteEngine {
    interface Listener {
        void onRoute(JSONArray coordinates, JSONArray hazards,
                     double distanceMeters, double durationSeconds,
                     String destinationLabel, double destinationLat, double destinationLon,
                     boolean reroute);
        void onError(String message);
    }

    private final NativeApiClient api;
    private final String server;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    ServerMapboxRouteEngine(Context context, NativeApiClient api, String server, Listener listener) {
        this.api = api;
        this.server = NativeApiClient.normalizeBase(server);
        this.listener = listener;
    }

    boolean isBusy() { return inFlight.get(); }

    void requestRoute(double originLat, double originLon,
                      String destinationQuery, String displayDestination,
                      boolean reroute) {
        if (!validCoordinate(originLat, originLon)) {
            fail("GPS inválido para calcular a rota.");
            return;
        }
        if (destinationQuery == null || destinationQuery.trim().length() < 2) {
            fail("Informe um destino válido.");
            return;
        }
        if (!inFlight.compareAndSet(false, true)) return;

        io.execute(() -> {
            try {
                String origin = String.format(Locale.US, "%.7f,%.7f", originLat, originLon);
                String path = "api/route.php?origin=" + enc(origin)
                        + "&destination=" + enc(destinationQuery.trim());
                NativeApiClient.Response response = api.getLarge(server, path);
                JSONObject json = response.json();
                if (!response.ok() || !json.optBoolean("ok")) {
                    throw new IllegalStateException(json.optString("error", "Falha ao calcular rota Mapbox no servidor."));
                }

                String router = json.optString("router", "");
                if (!router.startsWith("mapbox-directions")) {
                    throw new IllegalStateException("O servidor ainda não está usando Mapbox Directions.");
                }

                JSONObject route = json.optJSONObject("route");
                JSONObject geometry = route == null ? null : route.optJSONObject("geometry");
                JSONArray coords = geometry == null ? null : geometry.optJSONArray("coordinates");
                if (coords == null || coords.length() < 2) {
                    throw new IllegalStateException("A rota retornou sem geometria.");
                }

                JSONArray hazards = json.optJSONArray("radars");
                if (hazards == null) hazards = new JSONArray();

                JSONObject dest = json.optJSONObject("destination");
                double dlat = dest == null ? Double.NaN : dest.optDouble("lat", Double.NaN);
                double dlon = dest == null ? Double.NaN : dest.optDouble("lon", Double.NaN);
                String label = dest == null ? "" : dest.optString("display_name", "").trim();
                if (label.isEmpty()) label = displayDestination == null ? destinationQuery : displayDestination;

                double distance = route.optDouble("distance", 0d);
                double duration = route.optDouble("duration", 0d);
                JSONArray finalCoords = coords;
                JSONArray finalHazards = hazards;
                String finalLabel = label;
                main.post(() -> {
                    inFlight.set(false);
                    if (listener != null) {
                        listener.onRoute(finalCoords, finalHazards, distance, duration,
                                finalLabel, dlat, dlon, reroute);
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null || msg.trim().isEmpty()) msg = "Falha ao calcular a rota Mapbox.";
                final String out = msg;
                main.post(() -> {
                    inFlight.set(false);
                    if (listener != null) listener.onError(out);
                });
            }
        });
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception e) { return value; }
    }

    private static boolean validCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private void fail(String message) {
        main.post(() -> { if (listener != null) listener.onError(message); });
    }

    void shutdown() { io.shutdownNow(); }
}
