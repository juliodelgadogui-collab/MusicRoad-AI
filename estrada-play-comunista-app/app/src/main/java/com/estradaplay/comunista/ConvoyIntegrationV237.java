package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * COMBOIO_LINK_MAP_V237 / EPC_CONVOY_PUBLIC_SOURCE_V239
 *
 * Integra o Comboio ao app principal sem duplicar GPS:
 * - compartilha o link publico configurado em BuildConfig.CONVOY_PUBLIC_URL;
 * - aceita o retorno estradaplay://comboio/CODIGO e links web do dominio publico;
 * - guarda convites recebidos ate a sessao estar pronta;
 * - entra automaticamente no comboio ao abrir o convite;
 * - projeta os membros do comboio no RoadMapView principal usando o snapshot
 *   produzido pelo ConvoyLiveBridge/RoadSafetyService existente.
 */
final class ConvoyIntegrationV237 implements Application.ActivityLifecycleCallbacks {
    private static final String PREF = "epc_convoy_invite_v237";
    private static final String KEY_PENDING = "pending_code";
    private static final String SHARE_MARKER = "EPC_CONVOY_SHARE_LINK_V237";
    private static final long JOIN_RETRY_MS = 12_000L;
    private static volatile ConvoyIntegrationV237 instance;

    private final Application app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean joining = new AtomicBoolean(false);
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private long lastJoinAttemptAt;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ConvoyLiveBridge.ACTION_STATE.equals(intent.getAction())) return;
            String raw = intent.getStringExtra("state_json");
            if (raw == null || raw.trim().isEmpty()) return;
            try {
                JSONObject state = new JSONObject(raw);
                if (state.optBoolean("ok", false)) applyStateToVisibleMap(state);
                else clearVisibleMapIfMembershipEnded();
            } catch (Throwable ignored) {}
        }
    };

    private final Runnable visiblePulse = new Runnable() {
        @Override public void run() {
            Activity activity = resumed.get();
            if (activity == null || activity.isFinishing()) return;
            try {
                if (activity instanceof ConvoyActivity) installShareButton(activity);
                if (activity instanceof RoadMapActivity) applyCachedMembers(activity);
                if (activity instanceof MainActivity || activity instanceof AutomotiveActivity) maybeJoinPending(activity);
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 1200L);
        }
    };

    private ConvoyIntegrationV237(Application app) {
        this.app = app;
    }

    static synchronized void install(Application app) {
        if (app == null || instance != null) return;
        ConvoyIntegrationV237 bridge = new ConvoyIntegrationV237(app);
        instance = bridge;
        app.registerActivityLifecycleCallbacks(bridge);
        try {
            IntentFilter filter = new IntentFilter(ConvoyLiveBridge.ACTION_STATE);
            if (Build.VERSION.SDK_INT >= 33) InternalBroadcasts.register(app, bridge.stateReceiver, filter);
            else InternalBroadcasts.register(app, bridge.stateReceiver, filter);
        } catch (Throwable ignored) {}
    }

    static void captureInvite(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String code = extractCode(intent);
        if (code.length() >= 4) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_PENDING, code).apply();
        }
    }

    static String inviteLink(String rawCode) {
        String code = ConvoyStore.normalize(rawCode);
        if (code.isEmpty()) return "";
        String base = BuildConfig.CONVOY_PUBLIC_URL == null ? "" : BuildConfig.CONVOY_PUBLIC_URL.trim();
        if (base.isEmpty()) return "estradaplay://comboio/" + Uri.encode(code);
        if (!base.endsWith("/")) base += "/";
        return base + Uri.encode(code);
    }

    private static String extractCode(Intent intent) {
        try {
            Uri data = intent.getData();
            if (data != null) {
                String scheme = lower(data.getScheme());
                String host = lower(data.getHost());
                if ("estradaplay".equals(scheme) && "comboio".equals(host)) {
                    String code = data.getQueryParameter("code");
                    if (code == null || code.trim().isEmpty()) {
                        List<String> segments = data.getPathSegments();
                        if (segments != null && !segments.isEmpty()) code = segments.get(0);
                    }
                    return ConvoyStore.normalize(code);
                }

                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String code = "";
                    List<String> segments = data.getPathSegments();
                    if (isConfiguredPublicHost(host) && segments != null && segments.size() >= 2
                            && "c".equalsIgnoreCase(segments.get(0))) {
                        code = segments.get(1);
                    }
                    if (code == null || code.trim().isEmpty()) code = data.getQueryParameter("comboio");
                    if (code == null || code.trim().isEmpty()) code = data.getQueryParameter("code");
                    if (code != null && !code.trim().isEmpty()
                            && (isConfiguredPublicHost(host) || host.contains("gestao2.store"))) {
                        return ConvoyStore.normalize(code);
                    }
                }
            }
            String extra = intent.getStringExtra("convoy_code");
            return ConvoyStore.normalize(extra);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isConfiguredPublicHost(String host) {
        if (host == null || host.isEmpty()) return false;
        try {
            String configured = lower(Uri.parse(BuildConfig.CONVOY_PUBLIC_URL).getHost());
            if (configured.isEmpty()) return false;
            if (host.equals(configured)) return true;
            String a = host.startsWith("www.") ? host.substring(4) : host;
            String b = configured.startsWith("www.") ? configured.substring(4) : configured;
            return a.equals(b);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void installShareButton(Activity activity) {
        Button share = findButton(activity.getWindow().getDecorView(), "COMPARTILHAR");
        if (share == null) return;
        CharSequence description = share.getContentDescription();
        if (description != null && SHARE_MARKER.contentEquals(description)) return;
        share.setContentDescription(SHARE_MARKER);
        share.setOnClickListener(v -> shareConvoy(activity));
    }

    private void shareConvoy(Activity activity) {
        ConvoyStore store = new ConvoyStore(activity);
        String code = store.code();
        if (code.isEmpty()) {
            Toast.makeText(activity, "Crie ou entre em um comboio primeiro.", Toast.LENGTH_LONG).show();
            return;
        }
        String link = inviteLink(code);
        ConvoyStore.SharedDestination destination = store.destination(store.lastSnapshot());
        StringBuilder message = new StringBuilder();
        message.append("Entre no meu Comboio Estrada Play\n\n");
        message.append("Abrir no app: ").append(link).append("\n");
        message.append("Codigo: ").append(code);
        if (destination != null && destination.label != null && !destination.label.trim().isEmpty()) {
            message.append("\nDestino do grupo: ").append(destination.label.trim());
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Comboio Estrada Play");
        send.putExtra(Intent.EXTRA_TEXT, message.toString());
        try {
            activity.startActivity(Intent.createChooser(send, "Compartilhar comboio"));
        } catch (Throwable ignored) {}
    }

    private void applyStateToVisibleMap(JSONObject state) {
        Activity activity = resumed.get();
        if (!(activity instanceof RoadMapActivity)) return;
        RoadMapView map = findMap(activity.getWindow().getDecorView());
        if (map == null) return;
        ConvoyStore store = new ConvoyStore(activity);
        map.setConvoyMembers(store.members(state));
    }

    private void applyCachedMembers(Activity activity) {
        RoadMapView map = findMap(activity.getWindow().getDecorView());
        if (map == null) return;
        ConvoyStore store = new ConvoyStore(activity);
        if (store.code().isEmpty()) {
            map.setConvoyMembers(new ArrayList<>());
            return;
        }
        JSONObject state = store.lastSnapshot();
        if (state.optBoolean("ok", false)) map.setConvoyMembers(store.members(state));
    }

    private void clearVisibleMapIfMembershipEnded() {
        Activity activity = resumed.get();
        if (!(activity instanceof RoadMapActivity)) return;
        ConvoyStore store = new ConvoyStore(activity);
        if (!store.code().isEmpty()) return;
        RoadMapView map = findMap(activity.getWindow().getDecorView());
        if (map != null) map.setConvoyMembers(new ArrayList<>());
    }

    private void maybeJoinPending(Activity activity) {
        String code = pendingCode(activity);
        if (code.length() < 4) return;
        if (!hasAuthenticatedAccount(activity)) return;

        ApiClient api = new ApiClient(activity);
        if (!api.hasSecureSession()) return;

        ConvoyStore store = new ConvoyStore(activity);
        String active = store.code();
        if (code.equals(active)) {
            clearPending(activity);
            openConvoy(activity);
            return;
        }
        if (!active.isEmpty()) {
            clearPending(activity);
            Toast.makeText(activity, "Voce ja participa de outro comboio. Saia dele e toque no convite novamente.", Toast.LENGTH_LONG).show();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastJoinAttemptAt < JOIN_RETRY_MS || !joining.compareAndSet(false, true)) return;
        lastJoinAttemptAt = now;
        io.execute(() -> {
            try {
                JSONObject result = store.join(code, null);
                if (result.optBoolean("ok", false)) {
                    clearPending(activity);
                    ui.post(() -> {
                        Activity current = resumed.get();
                        if (current == activity && !activity.isFinishing()) {
                            Toast.makeText(activity, "Comboio " + code + " conectado.", Toast.LENGTH_SHORT).show();
                            openConvoy(activity);
                        }
                    });
                } else {
                    String error = result.optString("error", "Nao consegui entrar no comboio.");
                    String low = lower(error);
                    if (low.contains("nao encontrado") || low.contains("não encontrado") || low.contains("expir") || low.contains("bloque")) {
                        clearPending(activity);
                    }
                    ui.post(() -> {
                        Activity current = resumed.get();
                        if (current == activity && !activity.isFinishing()) Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Throwable ignored) {
                // Mantem o convite pendente; quando a conexao voltar o app tenta novamente.
            } finally {
                joining.set(false);
            }
        });
    }

    private static boolean hasAuthenticatedAccount(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("estradaplay_ui_v1", Context.MODE_PRIVATE);
            String raw = prefs.getString("account", "{}");
            if (raw == null || raw.trim().isEmpty() || "{}".equals(raw.trim())) return false;
            JSONObject account = new JSONObject(raw);
            return account.optBoolean("authenticated", false) || account.optJSONObject("user") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String pendingCode(Context context) {
        return ConvoyStore.normalize(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_PENDING, ""));
    }

    private static void clearPending(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_PENDING).apply();
    }

    private static void openConvoy(Activity activity) {
        try {
            Intent open = new Intent(activity, ConvoyActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(open);
        } catch (Throwable ignored) {}
    }

    private static RoadMapView findMap(View root) {
        if (root instanceof RoadMapView) return (RoadMapView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                RoadMapView found = findMap(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Button findButton(View root, String expected) {
        if (root instanceof Button) {
            Button button = (Button) root;
            if (expected.equalsIgnoreCase(String.valueOf(button.getText()).trim())) return button;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), expected);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        captureInvite(activity, activity.getIntent());
    }

    @Override public void onActivityStarted(Activity activity) {}

    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        captureInvite(activity, activity.getIntent());
        ui.removeCallbacks(visiblePulse);
        ui.post(visiblePulse);
    }

    @Override public void onActivityPaused(Activity activity) {
        Activity current = resumed.get();
        if (current == activity) {
            resumed = new WeakReference<>(null);
            ui.removeCallbacks(visiblePulse);
        }
    }

    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
