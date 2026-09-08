package com.estradaplay.comunista;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/** Estrada Play Premium shared shell with no fixed bottom navigation. */
final class UnifiedAppShell {
    private UnifiedAppShell() {}

    static View wrap(Activity a,String section,View content){
        if(a==null||content==null)return content;EstradaTheme theme=EstradaTheme.get(a);a.getWindow().setStatusBarColor(theme.background);a.getWindow().setNavigationBarColor(theme.background);polish(a,content,theme);String active=section==null?"central":section.toLowerCase(java.util.Locale.ROOT);FrameLayout host=new FrameLayout(a);host.setBackgroundColor(theme.background);LinearLayout page=PremiumUi.col(a);host.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bar=PremiumUi.row(a);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(a,10),dp(a,7),dp(a,12),dp(a,7));bar.setBackground(PremiumUi.panel(a,theme.glass,theme.border,0));Button menu=PremiumUi.button(a,"☰",false);menu.setTextSize(20);menu.setContentDescription("Abrir menu");bar.addView(menu,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));BrandMarkView mark=new BrandMarkView(a);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(a,42),dp(a,42));mp.setMargins(dp(a,8),0,0,0);bar.addView(mark,mp);LinearLayout words=PremiumUi.col(a);words.setGravity(Gravity.CENTER_VERTICAL);words.addView(PremiumUi.overline(a,"ESTRADA PLAY",theme.secondary));words.addView(PremiumUi.text(a,title(active),16,theme.text,true));LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-1,1f);wp.setMargins(dp(a,10),0,0,0);bar.addView(words,wp);TextView state=PremiumUi.overline(a,"● ATIVO",theme.success);state.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);bar.addView(state,new LinearLayout.LayoutParams(-2,-1));page.addView(bar,new LinearLayout.LayoutParams(-1,dp(a,62)));
        FrameLayout body=new FrameLayout(a);body.setBackgroundColor(theme.background);body.addView(content,new FrameLayout.LayoutParams(-1,-1));page.addView(body,new LinearLayout.LayoutParams(-1,0,1f));menu.setOnClickListener(v->AppMenuOverlay.show(a,host,active,index->navigate(a,index)));return host;
    }

    private static void navigate(Activity a,int index){Class<?> cls;if(index==0)cls=PremiumHomeActivity.class;else if(index==1)cls=PremiumMusicActivity.class;else if(index==2)cls=MusicStorageActivity.class;else if(index==3)cls=RoadMapActivity.class;else cls=ThemeSettingsActivity.class;Intent i=new Intent(a,cls);i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);a.startActivity(i);}
    private static String title(String s){if(s.contains("road")||s.contains("map"))return "Estrada";if(s.contains("music"))return "Música";if(s.contains("radio"))return "Rádio";if(s.contains("trip")||s.contains("viagem"))return "Viagem";if(s.contains("storage")||s.contains("arquivo")||s.contains("download"))return "Biblioteca";if(s.contains("theme")||s.contains("tema")||s.contains("appearance"))return "Aparência";return "Central";}
    private static void polish(Activity a,View v,EstradaTheme theme){if(v instanceof Button){Button b=(Button)v;b.setAllCaps(false);b.setStateListAnimator(null);b.setTextColor(theme.text);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);String raw=String.valueOf(b.getText()).toUpperCase(java.util.Locale.ROOT);boolean primary=raw.contains("SALVAR")||raw.contains("REGISTRAR")||raw.contains("ATIVAR")||raw.contains("INICIAR")||raw.contains("CONECTAR")||raw.contains("FALAR")||raw.contains("CONFIRMAR");b.setBackground(PremiumUi.panel(a,primary?theme.primary:theme.surfaceAlt,primary?theme.primary:theme.border,theme.radiusDp));}else if(v instanceof EditText){EditText e=(EditText)v;e.setTextColor(theme.text);e.setHintTextColor(theme.muted);e.setPadding(dp(a,14),0,dp(a,14),0);e.setBackground(PremiumUi.panel(a,theme.surfaceAlt,theme.border,theme.radiusDp));}else if(v instanceof Spinner){Spinner s=(Spinner)v;s.setPadding(dp(a,10),0,dp(a,10),0);s.setBackground(PremiumUi.panel(a,theme.surfaceAlt,theme.border,theme.radiusDp));}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)polish(a,g.getChildAt(i),theme);}}
    private static int dp(android.content.Context c,float v){return PremiumUi.dp(c,v);}
}
