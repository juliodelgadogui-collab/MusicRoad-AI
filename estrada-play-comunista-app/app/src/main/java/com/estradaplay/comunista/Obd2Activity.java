package com.estradaplay.comunista;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressLint("MissingPermission")
public final class Obd2Activity extends ComponentActivity {
    private static final int REQ_BT = 7301;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private LinearLayout devices;
    private TextView state;
    private TextView rpm;
    private TextView coolant;
    private TextView vehicleSpeed;
    private TextView battery;
    private TextView fuel;
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean polling;
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

    @Override protected void onDestroy() {
        polling = false;
        closeSocket();
        io.shutdownNow();
        super.onDestroy();
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

        page.addView(PremiumUi.overline(this, "OBD2 BLUETOOTH", theme.secondary));
        TextView title = PremiumUi.text(this, "Painel do veículo", 27, theme.text, true);
        page.addView(title);
        margin(title, 0, 5, 0, 3);
        TextView subtitle = PremiumUi.text(this,
                "Compatível com adaptadores ELM327 Bluetooth Classic já pareados no Android.",
                11, theme.muted, false);
        page.addView(subtitle);

        LinearLayout status = card();
        state = PremiumUi.text(this, "DESCONECTADO", 13, theme.warning, true);
        status.addView(state);
        TextView help = PremiumUi.text(this,
                "Pareie primeiro o ELM327 nas configurações Bluetooth. O Estrada Play apenas consulta PIDs padrão; não altera parâmetros da ECU.",
                11, theme.muted, false);
        status.addView(help);
        margin(help, 0, 5, 0, 0);
        add(page, status, 0, 14, 0, 14, -1, -2);

        LinearLayout metrics = PremiumUi.row(this);
        rpm = metric("RPM", "--");
        coolant = metric("MOTOR °C", "--");
        metrics.addView(rpm, new LinearLayout.LayoutParams(0, dp(88), 1f));
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, dp(88), 1f);
        m2.setMargins(dp(7), 0, 0, 0);
        metrics.addView(coolant, m2);
        page.addView(metrics);

        LinearLayout metrics2 = PremiumUi.row(this);
        vehicleSpeed = metric("OBD KM/H", "--");
        battery = metric("BATERIA", "--");
        fuel = metric("TANQUE", "--");
        metrics2.addView(vehicleSpeed, new LinearLayout.LayoutParams(0, dp(88), 1f));
        LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, dp(88), 1f);
        q.setMargins(dp(7), 0, 0, 0);
        metrics2.addView(battery, q);
        LinearLayout.LayoutParams q2 = new LinearLayout.LayoutParams(0, dp(88), 1f);
        q2.setMargins(dp(7), 0, 0, 0);
        metrics2.addView(fuel, q2);
        add(page, metrics2, 0, 7, 0, 16, -1, -2);

        page.addView(PremiumUi.overline(this, "DISPOSITIVOS PAREADOS", theme.muted));
        devices = PremiumUi.col(this);
        page.addView(devices);
        margin(devices, 0, 7, 0, 0);
        loadBonded();
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadBonded() {
        devices.removeAllViews();
        if (!hasBluetoothConnectPermission()) {
            Button allow = PremiumUi.button(this, "AUTORIZAR BLUETOOTH", true);
            devices.addView(allow, new LinearLayout.LayoutParams(-1, dp(54)));
            allow.setOnClickListener(v -> requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT));
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            devices.addView(PremiumUi.text(this,
                    "Este aparelho não possui Bluetooth Classic.", 12, theme.muted, false));
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                devices.addView(PremiumUi.text(this,
                        "Ative o Bluetooth do Android e volte a esta tela.", 12, theme.warning, false));
                return;
            }
        } catch (SecurityException e) {
            devices.addView(PremiumUi.text(this,
                    "Autorize o Bluetooth para continuar.", 12, theme.warning, false));
            return;
        }

        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            devices.addView(PremiumUi.text(this,
                    "Autorize o Bluetooth para ler os dispositivos pareados.", 12, theme.muted, false));
            return;
        } catch (Throwable e) {
            devices.addView(PremiumUi.text(this,
                    "Não consegui ler os dispositivos pareados.", 12, theme.muted, false));
            return;
        }

        if (bonded == null || bonded.isEmpty()) {
            devices.addView(PremiumUi.text(this,
                    "Nenhum dispositivo pareado. Pareie o ELM327 nas configurações do Android.",
                    12, theme.muted, false));
            return;
        }

        boolean first = true;
        for (BluetoothDevice device : bonded) {
            Button b = PremiumUi.button(this, safeName(device) + "\n" + safeAddress(device), false);
            b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            devices.addView(b, new LinearLayout.LayoutParams(-1, dp(62)));
            if (!first) margin(b, 0, 7, 0, 0);
            first = false;
            b.setOnClickListener(v -> connect(device));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQ_BT) loadBonded();
    }

    private String safeAddress(BluetoothDevice device) {
        if (device == null || !hasBluetoothConnectPermission()) return "";
        try { return device.getAddress(); }
        catch (Throwable e) { return ""; }
    }

    private String safeName(BluetoothDevice device) {
        if (device == null || !hasBluetoothConnectPermission()) return "ELM327";
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "Dispositivo Bluetooth" : name;
        } catch (Throwable e) {
            return "ELM327";
        }
    }

    private void connect(BluetoothDevice device) {
        if (polling || device == null) return;
        if (!hasBluetoothConnectPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        state.setTextColor(theme.warning);
        state.setText("CONECTANDO…");
        io.execute(() -> {
            if (!hasBluetoothConnectPermission()) {
                ui.post(() -> state.setText("AUTORIZE O BLUETOOTH PARA CONECTAR"));
                return;
            }
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) adapter.cancelDiscovery();
                socket = device.createRfcommSocketToServiceRecord(SPP);
                socket.connect();
                in = socket.getInputStream();
                out = socket.getOutputStream();
                initElm();
                polling = true;
                ui.post(() -> {
                    state.setTextColor(theme.success);
                    state.setText("CONECTADO · " + safeName(device));
                });
                pollLoop();
            } catch (SecurityException e) {
                polling = false;
                closeSocket();
                ui.post(() -> state.setText("AUTORIZE O BLUETOOTH PARA CONECTAR"));
            } catch (Throwable e) {
                polling = false;
                closeSocket();
                ui.post(() -> {
                    state.setTextColor(theme.danger);
                    state.setText("FALHA AO CONECTAR · verifique se o ELM327 está livre");
                });
            }
        });
    }

    private void initElm() throws Exception {
        command("ATZ", 3000);
        command("ATE0", 1200);
        command("ATL0", 1200);
        command("ATS0", 1200);
        command("ATH0", 1200);
        command("ATSP0", 2200);
    }

    private void pollLoop() {
        while (polling && !Thread.currentThread().isInterrupted()) {
            try {
                Double r = parsePid(command("010C", 1400), "410C", 2, 4.0);
                Double c = parsePid(command("0105", 1400), "4105", 1, 1.0);
                Double s = parsePid(command("010D", 1400), "410D", 1, 1.0);
                Double f = parsePid(command("012F", 1400), "412F", 1, 255.0 / 100.0);
                Double volts = parseVoltage(command("ATRV", 1400));
                ui.post(() -> {
                    if (r != null) rpm.setText(String.valueOf(Math.round(r)));
                    if (c != null) coolant.setText(String.valueOf(Math.round(c - 40)));
                    if (s != null) vehicleSpeed.setText(String.valueOf(Math.round(s)));
                    if (volts != null) battery.setText(String.format(Locale.getDefault(), "%.1f V", volts));
                    if (f != null) fuel.setText(Math.round(f) + "%");
                });
                Thread.sleep(900);
            } catch (Throwable e) {
                polling = false;
                closeSocket();
                ui.post(() -> {
                    state.setTextColor(theme.warning);
                    state.setText("CONEXÃO OBD ENCERRADA");
                });
            }
        }
    }

    private synchronized String command(String cmd, long timeout) throws Exception {
        if (out == null || in == null) throw new IOException("sem conexão");
        while (in.available() > 0) in.read();
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        long end = System.currentTimeMillis() + timeout;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (System.currentTimeMillis() < end) {
            int available = in.available();
            if (available > 0) {
                byte[] bytes = new byte[Math.min(256, available)];
                int count = in.read(bytes);
                if (count > 0) {
                    buffer.write(bytes, 0, count);
                    String text = buffer.toString("US-ASCII");
                    if (text.contains(">")) break;
                }
            } else {
                Thread.sleep(35);
            }
        }
        return buffer.toString("US-ASCII");
    }

    private Double parsePid(String raw, String marker, int bytes, double divisor) {
        if (raw == null) return null;
        String clean = raw.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-F]", "");
        int at = clean.indexOf(marker);
        if (at < 0) return null;
        int start = at + marker.length();
        if (clean.length() < start + bytes * 2) return null;
        try {
            if (bytes == 1) {
                int a = Integer.parseInt(clean.substring(start, start + 2), 16);
                return divisor == 1.0 ? (double) a : a / divisor;
            }
            int a = Integer.parseInt(clean.substring(start, start + 2), 16);
            int b = Integer.parseInt(clean.substring(start + 2, start + 4), 16);
            return (a * 256.0 + b) / divisor;
        } catch (Throwable e) {
            return null;
        }
    }

    private Double parseVoltage(String raw) {
        if (raw == null) return null;
        Matcher matcher = Pattern.compile("([0-9]{1,2}(?:\\.[0-9]+)?)\\s*V", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (!matcher.find()) return null;
        try { return Double.parseDouble(matcher.group(1)); }
        catch (Throwable e) { return null; }
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
        in = null;
        out = null;
    }

    private TextView metric(String key, String value) {
        TextView t = PremiumUi.text(this, value + "\n" + key, 18, theme.success, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
        return t;
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
