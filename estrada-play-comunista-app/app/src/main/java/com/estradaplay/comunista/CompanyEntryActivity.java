package com.estradaplay.comunista;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit entry point for the Empresa area. Refreshes server context before choosing the correct screen. */
public final class CompanyEntryActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean routed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        showLoading();
        io.execute(() -> {
            CompanyContextSync.refresh(this);
            runOnUiThread(this::route);
        });
    }

    private void showLoading() {
        EstradaTheme theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);
        LinearLayout page = PremiumUi.col(this);
        page.setGravity(Gravity.CENTER);
        page.setPadding(PremiumUi.dp(this, 26), PremiumUi.dp(this, 26), PremiumUi.dp(this, 26), PremiumUi.dp(this, 26));
        page.setBackgroundColor(theme.background);
        BrandMarkView mark = new BrandMarkView(this);
        page.addView(mark, new LinearLayout.LayoutParams(PremiumUi.dp(this, 72), PremiumUi.dp(this, 72)));
        TextView title = PremiumUi.text(this, "ESTRADA PLAY FROTAS", 18, theme.text, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMargins(0, PremiumUi.dp(this, 16), 0, 0);
        page.addView(title, tp);
        TextView state = PremiumUi.text(this, "Carregando seu vínculo com a empresa…", 11, theme.muted, false);
        state.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.setMargins(0, PremiumUi.dp(this, 7), 0, 0);
        page.addView(state, sp);
        setContentView(page);
    }

    private void route() {
        if (routed || isFinishing()) return;
        routed = true;
        Class<?> target;
        if (CompanyAccount.shouldUseCompanyCentral(this)) target = CompanyHomeActivity.class;
        else if (CompanyAccount.hasCompany(this)) target = CompanyDriverActivity.class;
        else target = CompanySetupActivity.class;
        Intent i = new Intent(this, target);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
