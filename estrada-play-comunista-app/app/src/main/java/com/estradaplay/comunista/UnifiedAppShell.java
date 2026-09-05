package com.estradaplay.comunista;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * UNIFIED_APP_SHELL_V172
 * One visual/navigation shell for the Universal app. Secondary screens keep their
 * existing business logic, but no longer look like unrelated Android applications.
 * There is deliberately no bottom navigation.
 */
final class UnifiedAppShell {
    private static final int BG=Color.rgb(8,5,7);
    private static final int PANEL=Color.rgb(18,9,12);
    private static final int SURFACE=Color.rgb(28,14,18);
    private static final int BORDER=Color.rgb(79,39,45);
    private static final int TEXT=Color.rgb(246,238,224);
    private static final int MUTED=Color.rgb(174,151,146);
    private static final int RED=Color.rgb(190,18,38);
    private static final int GOLD=Color.rgb(226,185,76);
    private static final int GREEN=Color.rgb(72,212,134);

    private UnifiedAppShell() {}

    static View wrap(Activity a, String section, View content) {
        if (a == null || content == null) return content;
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        polish(content);
        String active = section == null ? "central" : section.toLowerCase();

        // ANDROID16_ADAPTIVE_V280: choose navigation from the current window width instead
        // of the physical screen orientation. This behaves correctly on tablets, desktop/freeform
        // windows and automotive displays that can be resized without a configuration "landscape" flip.
        Configuration cfg = a.getResources().getConfiguration();
        boolean wideWindow = cfg.screenWidthDp >= 600;
        return wideWindow ? landscape(a, active, content) : portrait(a, active, content);
    }

    private static View landscape(Activity a, String active, View content) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        LinearLayout rail = new LinearLayout(a);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(a,8),dp(a,10),dp(a,8),dp(a,10));
        rail.setBackground(box(PANEL,22,BORDER));
        TextView brand = text(a,"EPC",22,TEXT,true);
        brand.setGravity(Gravity.CENTER);
        brand.setBackground(box(RED,16,0));
        rail.addView(brand,new LinearLayout.LayoutParams(-1,dp(a,62)));
        TextView drive = over(a,"DRIVE",GREEN);
        drive.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(-1,dp(a,28));dpv.setMargins(0,dp(a,5),0,dp(a,5));rail.addView(drive,dpv);
        addNav(a,rail,"ESTRADA","road",active,RoadMapActivity.class);
        addNav(a,rail,"MÚSICA","music",active,MusicPlayerActivity.class);
        addNav(a,rail,"RÁDIO","radio",active,RoadRadioActivity.class);
        addNav(a,rail,"VIAGEM","trip",active,TripPlannerActivity.class);
        addNav(a,rail,"CENTRAL","central",active,DriveToolsActivity.class);
        TextView ver=over(a,"v"+BuildConfig.VERSION_NAME,MUTED);ver.setGravity(Gravity.CENTER);
        rail.addView(ver,new LinearLayout.LayoutParams(-1,dp(a,28)));

        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(a,96),-1);
        rp.setMargins(dp(a,10),dp(a,10),dp(a,8),dp(a,10));
        root.addView(rail,rp);

        FrameLayout body=new FrameLayout(a);body.setBackgroundColor(BG);
        body.addView(content,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-1,1f);bp.setMargins(0,dp(a,10),dp(a,10),dp(a,10));root.addView(body,bp);
        return root;
    }

    private static View portrait(Activity a, String active, View content) {
        LinearLayout root=new LinearLayout(a);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        LinearLayout head=new LinearLayout(a);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(a,14),dp(a,8),dp(a,12),dp(a,8));head.setBackground(box(PANEL,0,BORDER));
        TextView mark=text(a,"EPC",17,Color.WHITE,true);mark.setGravity(Gravity.CENTER);mark.setBackground(box(RED,12,0));head.addView(mark,new LinearLayout.LayoutParams(dp(a,48),dp(a,42)));
        LinearLayout words=new LinearLayout(a);words.setOrientation(LinearLayout.VERTICAL);words.addView(over(a,"ESTRADA PLAY",RED));words.addView(text(a,title(active),16,TEXT,true));LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(dp(a,10),0,0,0);head.addView(words,wp);
        TextView state=over(a,"● SISTEMA ATIVO",GREEN);state.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);head.addView(state);
        root.addView(head,new LinearLayout.LayoutParams(-1,dp(a,62)));

        // PORTRAIT_NAV_FIT_V174: all five sectors remain visible at once.
        LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(dp(a,7),dp(a,6),dp(a,7),dp(a,6));row.setBackgroundColor(BG);
        addNavChipFit(a,row,"ESTRADA","road",active,RoadMapActivity.class);
        addNavChipFit(a,row,"MÚSICA","music",active,MusicPlayerActivity.class);
        addNavChipFit(a,row,"RÁDIO","radio",active,RoadRadioActivity.class);
        addNavChipFit(a,row,"VIAGEM","trip",active,TripPlannerActivity.class);
        addNavChipFit(a,row,"CENTRAL","central",active,DriveToolsActivity.class);
        root.addView(row,new LinearLayout.LayoutParams(-1,dp(a,52)));

        FrameLayout body=new FrameLayout(a);body.setBackgroundColor(BG);body.addView(content,new FrameLayout.LayoutParams(-1,-1));root.addView(body,new LinearLayout.LayoutParams(-1,0,1f));
        return root;
    }

    private static void addNav(Activity a,LinearLayout rail,String label,String key,String active,Class<?> cls){
        TextView v=navText(a,label,key.equals(active));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,0,1f);p.setMargins(0,dp(a,3),0,dp(a,3));rail.addView(v,p);v.setOnClickListener(x->go(a,key,active,cls));
    }
    private static void addNavChipFit(Activity a,LinearLayout row,String label,String key,String active,Class<?> cls){
        TextView v=navText(a,label,key.equals(active));v.setTextSize(8.2f);v.setMinWidth(0);v.setSingleLine(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(a,40),1f);p.setMargins(dp(a,2),0,dp(a,2),0);row.addView(v,p);
        v.setOnClickListener(x->go(a,key,active,cls));
    }
    private static TextView navText(Activity a,String label,boolean selected){TextView v=text(a,label,9,selected?Color.WHITE:MUTED,true);v.setGravity(Gravity.CENTER);v.setLetterSpacing(.07f);v.setBackground(box(selected?Color.rgb(79,10,23):SURFACE,13,selected?RED:BORDER));v.setClickable(true);v.setFocusable(true);return v;}
    private static void go(Activity a,String key,String active,Class<?> cls){if(key.equals(active))return;Intent i=new Intent(a,cls);i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);a.startActivity(i);}
    private static String title(String s){if("road".equals(s))return "Estrada";if("music".equals(s))return "Música";if("radio".equals(s))return "Rádio";if("trip".equals(s))return "Viagem";return "Central";}

    // Gives legacy secondary controls the same material language without changing logic.
    private static void polish(View v){
        if(v instanceof Button){Button b=(Button)v;b.setAllCaps(false);b.setStateListAnimator(null);b.setTextColor(TEXT);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);String s=String.valueOf(b.getText()).toUpperCase();boolean primary=s.contains("SALVAR")||s.contains("REGISTRAR")||s.contains("ATIVAR")||s.contains("INICIAR")||s.contains("CONECTAR")||s.contains("FALAR");b.setBackground(box(primary?RED:SURFACE,13,primary?0:BORDER));}
        else if(v instanceof EditText){EditText e=(EditText)v;e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setPadding(dp(e.getContext(),14),0,dp(e.getContext(),14),0);e.setBackground(box(SURFACE,12,BORDER));}
        else if(v instanceof Spinner){Spinner s=(Spinner)v;s.setPadding(dp(s.getContext(),10),0,dp(s.getContext(),10),0);s.setBackground(box(SURFACE,12,BORDER));}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)polish(g.getChildAt(i));}
    }

    private static TextView text(Activity a,String value,float size,int color,boolean bold){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setLineSpacing(0,1.05f);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static TextView over(Activity a,String value,int color){TextView t=text(a,value,8.5f,color,true);t.setLetterSpacing(.12f);return t;}
    private static GradientDrawable box(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);if(stroke!=0)g.setStroke(1,stroke);return g;}
    private static int dp(android.content.Context c,float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
}
