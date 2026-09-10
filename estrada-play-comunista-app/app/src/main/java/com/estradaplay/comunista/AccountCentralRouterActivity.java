package com.estradaplay.comunista;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Chooses Personal or Company central after refreshing the account context. */
public final class AccountCentralRouterActivity extends ComponentActivity {
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
        page.setPadding(PremiumUi.dp(this, 24), PremiumUi.dp(this, 24), PremiumUi.dp(this, 24), PremiumUi.dp(this, 24));
        page.setBackgroundColor(theme.background);
        BrandMarkView mark = new BrandMarkView(this);
        page.addView(mark, new LinearLayout.LayoutParams(PremiumUi.dp(this, 76), PremiumUi.dp(this, 76)));
        TextView text = PremiumUi.text(this, "Preparando sua central…", 12, theme.muted, false);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(0, PremiumUi.dp(this, 16), 0, 0);
        page.addView(text, p);
        setContentView(page);
    }

    private void route() {
        if (routed || isFinishing()) return;
        routed = true;
        CompanyJourneyTracker.install(getApplicationContext());
        Class<?> target = CompanyAccount.shouldUseCompanyCentral(this)
                ? CompanyHomeActivity.class : PremiumHomeActivity.class;
        CompanyBootstrapProvider.markRouted();
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
