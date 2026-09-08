package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Premium side drawer shared by native Estrada Play screens. */
final class AppMenuOverlay {
    interface Listener { void onSelect(int index); }
    private AppMenuOverlay() {}

    static void show(Context context, FrameLayout host, String currentScreen, Listener listener) {
        if (context == null || host == null) return;
        EstradaTheme theme = EstradaTheme.get(context);
        View old = host.findViewWithTag("estrada-play-premium-menu");
        if (old != null) host.removeView(old);

        FrameLayout overlay = new FrameLayout(context);
        overlay.setTag("estrada-play-premium-menu");
        overlay.setBackgroundColor(Color.argb(178, 0, 0, 0));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        int sw = context.getResources().getDisplayMetrics().widthPixels;
        int sh = context.getResources().getDisplayMetrics().heightPixels;
        boolean landscape = sw > sh;
        int width = landscape ? Math.min(dp(context, 390), Math.round(sw * .38f)) : Math.min(dp(context, 360), Math.round(sw * .90f));

        LinearLayout drawer = PremiumUi.col(context);
        drawer.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 14));
        drawer.setBackground(PremiumUi.panel(context, theme.glass, theme.border, 0));
        overlay.addView(drawer, new FrameLayout.LayoutParams(width, -1, Gravity.LEFT));

        LinearLayout head = PremiumUi.row(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        BrandMarkView mark = new BrandMarkView(context);
        head.addView(mark, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        LinearLayout words = PremiumUi.col(context);
        words.addView(PremiumUi.text(context, "ESTRADA PLAY", 18, theme.text, true));
        words.addView(PremiumUi.overline(context, "PREMIUM DRIVE", theme.secondary));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, dp(context, 52), 1f); wp.setMargins(dp(context, 11),0,0,0); head.addView(words, wp);
        TextView close = PremiumUi.text(context, "×", 29, theme.text, false); close.setGravity(Gravity.CENTER);
        close.setBackground(PremiumUi.panel(context, theme.surfaceAlt, theme.border, 100));
        head.addView(close, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
        drawer.addView(head, new LinearLayout.LayoutParams(-1, dp(context, 58)));

        TextView status = PremiumUi.overline(context, "●  SISTEMA ATIVO", theme.success);
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(-1, dp(context, 38)); st.setMargins(dp(context,3),dp(context,6),0,dp(context,6)); drawer.addView(status, st);

        String[][] items = {
                {"⌂", "Central", "Visão geral e partida"},
                {"♪", "Música", "Biblioteca local"},
                {"▤", "Arquivos", "Downloads e armazenamento"},
                {"↗", "Estrada", "Mapa e navegação"},
                {"◐", "Aparência", "Tema importado"}
        };
        String current = currentScreen == null ? "" : currentScreen.toLowerCase(java.util.Locale.ROOT);
        View[] rows = new View[items.length];
        for (int i=0;i<items.length;i++) {
            rows[i] = item(context, theme, items[i], selected(i,current));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(context, 70));
            p.setMargins(0, i==0?dp(context,8):dp(context,5),0,0);
            drawer.addView(rows[i],p);
        }

        View spacer = new View(context); drawer.addView(spacer, new LinearLayout.LayoutParams(1,0,1f));
        LinearLayout footer = PremiumUi.row(context); footer.setGravity(Gravity.CENTER_VERTICAL); footer.setPadding(dp(context,12),0,dp(context,12),0);
        footer.setBackground(PremiumUi.panel(context, theme.surface, theme.border, 14));
        footer.addView(PremiumUi.text(context, theme.name, 9, theme.secondary, true), new LinearLayout.LayoutParams(0,-1,1f));
        footer.addView(PremiumUi.text(context, "v"+BuildConfig.VERSION_NAME, 9, theme.muted, true));
        drawer.addView(footer, new LinearLayout.LayoutParams(-1,dp(context,42)));

        for(int i=0;i<rows.length;i++){
            final int index=i;
            rows[i].setOnClickListener(v->close(host,overlay,drawer,width,()->{if(listener!=null)listener.onSelect(index);}));
        }
        close.setOnClickListener(v->close(host,overlay,drawer,width,null));
        overlay.setOnClickListener(v->{if(v==overlay)close(host,overlay,drawer,width,null);});
        drawer.setOnClickListener(v->{});

        host.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        drawer.setTranslationX(-width); overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(130L).start();
        drawer.animate().translationX(0f).setDuration(190L).start();
    }

    private static View item(Context c,EstradaTheme theme,String[] data,boolean active){
        LinearLayout row=PremiumUi.row(c); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(c,11),dp(c,7),dp(c,10),dp(c,7));
        row.setBackground(PremiumUi.panel(c,active?PremiumUi.withAlpha(theme.primary,34):theme.surface,active?theme.primary:theme.border,theme.radiusDp));
        TextView icon=PremiumUi.text(c,data[0],20,active?theme.secondary:theme.muted,true); icon.setGravity(Gravity.CENTER); row.addView(icon,new LinearLayout.LayoutParams(dp(c,42),-1));
        LinearLayout words=PremiumUi.col(c); words.setGravity(Gravity.CENTER_VERTICAL); words.addView(PremiumUi.text(c,data[1],14,theme.text,true)); words.addView(PremiumUi.text(c,data[2],9,theme.muted,false));
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-1,1f); wp.setMargins(dp(c,9),0,0,0); row.addView(words,wp);
        TextView arrow=PremiumUi.text(c,active?"●":"›",active?9:24,active?theme.success:theme.text,true); arrow.setGravity(Gravity.CENTER); row.addView(arrow,new LinearLayout.LayoutParams(dp(c,30),-1));
        row.setClickable(true); row.setFocusable(true); return row;
    }

    private static boolean selected(int index,String current){
        if(index==0)return current.contains("central")||current.contains("inicio")||current.contains("home");
        if(index==1)return current.contains("music")||current.contains("musica")||current.contains("música");
        if(index==2)return current.contains("arquivo")||current.contains("biblioteca")||current.contains("storage")||current.contains("download");
        if(index==3)return current.contains("estrada")||current.contains("road")||current.contains("mapa");
        return current.contains("tema")||current.contains("aparencia")||current.contains("aparência");
    }

    private static void close(FrameLayout host,FrameLayout overlay,View drawer,int width,Runnable after){
        if(overlay.getParent()==null)return;
        overlay.animate().alpha(0f).setDuration(110L).start();
        drawer.animate().translationX(-width).setDuration(150L).withEndAction(()->{try{host.removeView(overlay);}catch(Throwable ignored){}if(after!=null)after.run();}).start();
    }

    private static int dp(Context c,float v){return PremiumUi.dp(c,v);}
}
