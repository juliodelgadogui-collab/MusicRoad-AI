package com.estradaplay.comunista;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Creates the company profile from an already authenticated Estrada Play account. */
public final class CompanySetupActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private EditText companyName;
    private Button create;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (CompanyAccount.shouldUseCompanyCentral(this)) {
            startActivity(new Intent(this, CompanyHomeActivity.class));
            finish();
            return;
        }
        build();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(theme.background);
        LinearLayout page = PremiumUi.col(this);
        page.setPadding(dp(20), dp(20), dp(20), dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(-1,-2));
        setContentView(scroll);

        Button back = PremiumUi.button(this, "‹ VOLTAR", false);
        back.setOnClickListener(v -> finish());
        page.addView(back, new LinearLayout.LayoutParams(dp(105), dp(46)));

        TextView over = PremiumUi.overline(this, "EMPRESA", theme.secondary);
        page.addView(over); margins(over,0,26,0,5);
        page.addView(PremiumUi.text(this, "Estrada Play Frotas", 30, theme.text, true));
        TextView intro = PremiumUi.text(this,
                "Crie a área da sua empresa para cadastrar veículos, vincular motoristas, acompanhar quilômetros e organizar comboios.",
                13, theme.muted, false);
        page.addView(intro); margins(intro,0,8,0,18);

        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(16),dp(16),dp(16),dp(16));
        card.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp+2));
        card.addView(PremiumUi.overline(this, "DADOS DA EMPRESA", theme.muted));

        companyName = new EditText(this);
        companyName.setSingleLine(true);
        companyName.setHint("Nome da empresa");
        companyName.setTextColor(theme.text);
        companyName.setHintTextColor(theme.muted);
        companyName.setPadding(dp(14),0,dp(14),0);
        companyName.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));
        card.addView(companyName,new LinearLayout.LayoutParams(-1,dp(56)));
        margins(companyName,0,10,0,0);

        create = PremiumUi.button(this,"CRIAR ÁREA DA EMPRESA",true);
        create.setOnClickListener(v -> createCompany());
        card.addView(create,new LinearLayout.LayoutParams(-1,dp(54)));
        margins(create,0,12,0,0);

        status = PremiumUi.text(this,"Sua conta atual será a proprietária da empresa.",11,theme.muted,false);
        status.setGravity(Gravity.CENTER);
        card.addView(status); margins(status,0,9,0,0);
        page.addView(card,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout privacy = PremiumUi.col(this);
        privacy.setPadding(dp(14),dp(13),dp(14),dp(13));
        privacy.setBackground(PremiumUi.panel(this,theme.surface,theme.border,theme.radiusDp));
        privacy.addView(PremiumUi.text(this,"Como funciona a localização",13,theme.text,true));
        privacy.addView(PremiumUi.text(this,
                "A empresa recebe presença e quilometragem somente quando o motorista vinculado estiver usando a Estrada. O módulo não cria um segundo GPS nem rastreia depois que o aplicativo sai de uso.",
                10,theme.muted,false));
        page.addView(privacy,lp(-1,-2,0,12,0,0));
    }

    private void createCompany() {
        String name = companyName.getText().toString().trim();
        if (name.length() < 3) { Toast.makeText(this,"Informe o nome da empresa.",Toast.LENGTH_LONG).show(); return; }
        create.setEnabled(false); create.setText("CRIANDO…"); status.setText("Preparando sua frota…");
        io.execute(() -> {
            try {
                JSONObject response = new CompanyApi(this).createCompany(name);
                JSONObject company = response.optJSONObject("company");
                if (company == null) throw new Exception("Empresa não retornada pelo servidor.");
                CompanyAccount.saveCompany(this, company);
                CompanyBootstrapProvider.markRouted();
                runOnUiThread(() -> {
                    Intent i = new Intent(this, CompanyHomeActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i); finish();
                });
            } catch (Throwable e) {
                String message = e.getMessage()==null ? "Não consegui criar a empresa agora." : e.getMessage();
                runOnUiThread(() -> { create.setEnabled(true); create.setText("CRIAR ÁREA DA EMPRESA"); status.setText(message); });
            }
        });
    }

    private int dp(float v){return PremiumUi.dp(this,v);}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
