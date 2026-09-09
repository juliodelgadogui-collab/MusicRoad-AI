package com.estradaplay.comunista;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

/** Native Premium account/login screen. It intentionally does not reuse MainActivity. */
public final class PremiumAccountActivity extends ComponentActivity {
    private static final String PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private ApiClient api;
    private EstradaTheme theme;
    private FrameLayout frame;
    private boolean registerMode;
    private boolean triedDeviceLogin;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        api = new ApiClient(this);
        theme = EstradaTheme.get(this);
        if (hasAccount()) showAccount();
        else tryDeviceLogin();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void tryDeviceLogin() {
        if (triedDeviceLogin) { showAuth(false, null); return; }
        triedDeviceLogin = true;
        showLoading("Reconhecendo este aparelho…");
        io.execute(() -> {
            try {
                ApiClient.Response r = api.post("api/native_app.php?action=device_login", devicePayload());
                JSONObject j = r.json();
                JSONObject account = j.optJSONObject("account");
                if (r.ok() && j.optBoolean("ok", false) && account != null) {
                    saveAccount(account);
                    runOnUiThread(this::openCentral);
                    return;
                }
            } catch (Throwable ignored) {}
            runOnUiThread(() -> showAuth(false, "Entre uma vez para vincular este aparelho ao Estrada Play."));
        });
    }

    private void showAuth(boolean register, String note) {
        registerMode = register;
        frame = base("CONTA", register ? "Criar sua conta" : "Entrar no Estrada Play",
                register ? "Cadastre-se para preparar seu aparelho." : "Sua conta libera biblioteca, proteção e sincronização.");
        LinearLayout page = content(frame);

        LinearLayout form = PremiumUi.col(this);
        form.setPadding(dp(16), dp(15), dp(16), dp(16));
        form.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp + 2));
        if (note != null && !note.trim().isEmpty()) {
            TextView n = PremiumUi.text(this, note.trim(), 11, theme.muted, false);
            form.addView(n);
            margins(n, 0, 0, 0, 12);
        }

        EditText name = register ? field("Nome") : null;
        EditText email = register ? field("E-mail") : null;
        EditText login = field(register ? "Nome de usuário" : "Usuário ou e-mail");
        EditText password = field("Senha");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (register) {
            form.addView(name, lp(-1, 54));
            margins(name, 0, 0, 0, 8);
            form.addView(email, lp(-1, 54));
            margins(email, 0, 0, 0, 8);
        }
        form.addView(login, lp(-1, 54));
        margins(login, 0, 0, 0, 8);
        form.addView(password, lp(-1, 54));

        Button submit = PremiumUi.button(this, register ? "CRIAR CONTA" : "ENTRAR", true);
        form.addView(submit, lp(-1, 54));
        margins(submit, 0, 12, 0, 0);
        Button toggle = PremiumUi.button(this, register ? "JÁ TENHO CONTA" : "CRIAR CONTA", false);
        form.addView(toggle, lp(-1, 48));
        margins(toggle, 0, 8, 0, 0);
        toggle.setOnClickListener(v -> showAuth(!registerMode, null));

        page.addView(form);

        Button server = PremiumUi.button(this, "SERVIDOR", false);
        page.addView(server, lp(-1, 48));
        margins(server, 0, 10, 0, 0);
        server.setOnClickListener(v -> startActivity(new Intent(this, ServerSettingsActivity.class)));

        submit.setOnClickListener(v -> {
            String user = login.getText().toString().trim();
            String pass = password.getText().toString();
            if (user.isEmpty() || pass.isEmpty()) {
                toast("Preencha usuário e senha.");
                return;
            }
            submit.setEnabled(false);
            submit.setText("AGUARDE…");
            io.execute(() -> {
                try {
                    JSONObject d = devicePayload();
                    d.put(register ? "username" : "login", user);
                    d.put("password", pass);
                    if (register) {
                        d.put("name", name == null ? "" : name.getText().toString().trim());
                        d.put("email", email == null ? "" : email.getText().toString().trim());
                    }
                    ApiClient.Response r = api.post("api/native_app.php?action=" + (register ? "register" : "login"), d);
                    JSONObject j = r.json();
                    JSONObject account = j.optJSONObject("account");
                    if (!r.ok() || !j.optBoolean("ok", false) || account == null) {
                        String error = j.optString("error", "Não foi possível entrar.");
                        runOnUiThread(() -> {
                            submit.setEnabled(true);
                            submit.setText(register ? "CRIAR CONTA" : "ENTRAR");
                            toast(error);
                        });
                        return;
                    }
                    saveAccount(account);
                    new LibraryStore(this).setSetupDone(true);
                    runOnUiThread(this::openCentral);
                } catch (Throwable e) {
                    String detail = networkFailureMessage(e);
                    runOnUiThread(() -> {
                        submit.setEnabled(true);
                        submit.setText(register ? "CRIAR CONTA" : "ENTRAR");
                        toast(detail);
                    });
                }
            });
        });
    }

    private String networkFailureMessage(Throwable error) {
        Throwable e = error;
        for (int i = 0; i < 6 && e != null; i++, e = e.getCause()) {
            if (e instanceof UnknownHostException) {
                return "O Android não conseguiu resolver o endereço do servidor. A 5.0.5 tentou DNS seguro, mas a rede ainda bloqueou a resolução.";
            }
            if (e instanceof SSLException) {
                return "Falha no HTTPS do servidor. Verifique certificado e data/hora do aparelho.";
            }
            if (e instanceof SocketTimeoutException) {
                return "O servidor demorou demais para responder. Tente novamente com outra rede.";
            }
        }
        String type = error == null ? "rede" : error.getClass().getSimpleName();
        return "Falha de conexão com o servidor (" + type + ").";
    }

    private void showAccount() {
        JSONObject account = account();
        JSONObject user = account.optJSONObject("user");
        String name = user == null ? account.optString("name", "Motorista") : user.optString("name", "Motorista");
        String login = user == null ? account.optString("username", "") : user.optString("username", "");
        String email = user == null ? account.optString("email", "") : user.optString("email", "");
        String plan = account.optString("plan", account.optString("subscription", ""));

        frame = base("CONTA", "Seu Estrada Play", "Conta vinculada a este aparelho.");
        LinearLayout page = content(frame);
        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp + 2));
        card.addView(PremiumUi.overline(this, "PERFIL", theme.secondary));
        TextView title = PremiumUi.text(this, name == null || name.trim().isEmpty() ? "Motorista" : name.trim(), 22, theme.text, true);
        card.addView(title);
        margins(title, 0, 5, 0, 5);
        if (login != null && !login.trim().isEmpty()) card.addView(PremiumUi.text(this, "@" + login.trim(), 11, theme.muted, false));
        if (email != null && !email.trim().isEmpty()) card.addView(PremiumUi.text(this, email.trim(), 11, theme.muted, false));
        if (plan != null && !plan.trim().isEmpty()) {
            TextView p = PremiumUi.text(this, "Plano: " + plan.trim(), 11, theme.success, true);
            card.addView(p);
            margins(p, 0, 10, 0, 0);
        }
        page.addView(card);

        Button central = PremiumUi.button(this, "VOLTAR À CENTRAL", true);
        page.addView(central, lp(-1, 52));
        margins(central, 0, 12, 0, 0);
        central.setOnClickListener(v -> openCentral());

        Button server = PremiumUi.button(this, "SERVIDOR", false);
        page.addView(server, lp(-1, 48));
        margins(server, 0, 8, 0, 0);
        server.setOnClickListener(v -> startActivity(new Intent(this, ServerSettingsActivity.class)));

        Button logout = PremiumUi.button(this, "SAIR DESTA CONTA", false);
        logout.setTextColor(theme.danger);
        page.addView(logout, lp(-1, 48));
        margins(logout, 0, 8, 0, 0);
        logout.setOnClickListener(v -> logout());
    }

    private FrameLayout base(String overline, String title, String subtitle) {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(PremiumUi.gradient(this, theme.background,
                blend(theme.background, theme.primary, .08f), 0));
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = PremiumUi.col(this);
        page.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout brand = PremiumUi.row(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        BrandMarkView mark = new BrandMarkView(this);
        brand.addView(mark, lp(48, 48));
        LinearLayout words = PremiumUi.col(this);
        words.addView(PremiumUi.text(this, "ESTRADA PLAY", 18, theme.text, true));
        words.addView(PremiumUi.overline(this, "PREMIUM DRIVE", theme.secondary));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.setMargins(dp(11), 0, 0, 0);
        brand.addView(words, wp);
        page.addView(brand);

        TextView over = PremiumUi.overline(this, overline, theme.secondary);
        page.addView(over);
        margins(over, 0, 28, 0, 5);
        page.addView(PremiumUi.text(this, title, 29, theme.text, true));
        TextView sub = PremiumUi.text(this, subtitle, 12, theme.muted, false);
        page.addView(sub);
        margins(sub, 0, 7, 0, 18);
        root.setTag(page);
        return root;
    }

    private LinearLayout content(FrameLayout root) {
        Object tag = root.getTag();
        return tag instanceof LinearLayout ? (LinearLayout) tag : PremiumUi.col(this);
    }

    private void showLoading(String message) {
        theme = EstradaTheme.get(this);
        LinearLayout page = PremiumUi.col(this);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(28), dp(28), dp(28), dp(28));
        page.setBackgroundColor(theme.background);
        BrandMarkView mark = new BrandMarkView(this);
        page.addView(mark, lp(72, 72));
        TextView brand = PremiumUi.text(this, "ESTRADA PLAY", 23, theme.text, true);
        brand.setGravity(Gravity.CENTER);
        page.addView(brand);
        margins(brand, 0, 18, 0, 6);
        TextView state = PremiumUi.text(this, message, 12, theme.muted, false);
        state.setGravity(Gravity.CENTER);
        page.addView(state);
        setContentView(page);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setHintTextColor(theme.muted);
        e.setTextColor(theme.text);
        e.setTextSize(14);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
        return e;
    }

    private JSONObject devicePayload() {
        JSONObject d = new JSONObject();
        try {
            d.put("device_token", DeviceIdentity.token(this));
            d.put("device_label", DeviceIdentity.label());
            d.put("app_version", BuildConfig.VERSION_NAME);
        } catch (Throwable ignored) {}
        return d;
    }

    private JSONObject account() {
        try { return new JSONObject(prefs.getString(KEY_ACCOUNT, "{}")); }
        catch (Throwable ignored) { return new JSONObject(); }
    }

    private boolean hasAccount() {
        JSONObject a = account();
        return a.optBoolean("authenticated", false) || a.optJSONObject("user") != null;
    }

    private void saveAccount(JSONObject account) {
        prefs.edit().putString(KEY_ACCOUNT, account == null ? "{}" : account.toString()).apply();
    }

    private void logout() {
        prefs.edit().remove(KEY_ACCOUNT).apply();
        try { api.clearSession(); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, RoadSafetyService.class)); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, CopilotService.class)); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, RoadRadioService.class)); } catch (Throwable ignored) {}
        Intent i = new Intent(this, GateActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void openCentral() {
        Intent i = new Intent(this, PremiumHomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private int dp(float v) { return PremiumUi.dp(this, v); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private void margins(View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(p);
    }
    private static int blend(int a, int b, float amount) {
        float x = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(Math.round(Color.red(a) * (1f - x) + Color.red(b) * x),
                Math.round(Color.green(a) * (1f - x) + Color.green(b) * x),
                Math.round(Color.blue(a) * (1f - x) + Color.blue(b) * x));
    }
}
