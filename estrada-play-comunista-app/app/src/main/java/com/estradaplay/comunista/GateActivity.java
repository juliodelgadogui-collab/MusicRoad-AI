package com.estradaplay.comunista;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import org.json.JSONObject;

/** Lightweight local gate: returning drivers go straight to the road cockpit. */
public final class GateActivity extends ComponentActivity {
    private static final String UI_PREFS="estradaplay_ui_v1",KEY_ACCOUNT="account";
    private final Handler ui=new Handler(Looper.getMainLooper());private boolean launched;

    @Override protected void onCreate(Bundle state){super.onCreate(state);ConvoyIntegrationV237.captureInvite(this,getIntent());getWindow().setStatusBarColor(Color.rgb(8,5,7));getWindow().setNavigationBarColor(Color.rgb(8,5,7));
        // FAST_START_V301: usuário configurado não precisa atravessar home/splash a cada viagem.
        if(canOpenDrive()){ui.postDelayed(this::launchDrive,60L);}else{showBrandIntro();ui.postDelayed(this::launchMain,450L);}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);ConvoyIntegrationV237.captureInvite(this,intent);launched=false;ui.removeCallbacksAndMessages(null);if(canOpenDrive())ui.postDelayed(this::launchDrive,40L);else ui.postDelayed(this::launchMain,80L);}

    private boolean canOpenDrive(){try{SharedPreferences p=getSharedPreferences(UI_PREFS,MODE_PRIVATE);JSONObject a=new JSONObject(p.getString(KEY_ACCOUNT,"{}"));boolean account=a.optBoolean("authenticated",false)||a.optJSONObject("user")!=null;boolean location=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;boolean setup=new LibraryStore(this).hasSetupDone();return account&&location&&setup;}catch(Throwable e){return false;}}

    private void showBrandIntro(){FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(8,5,7));setContentView(root);LinearLayout center=new LinearLayout(this);center.setOrientation(LinearLayout.VERTICAL);center.setGravity(Gravity.CENTER);center.setPadding(dp(30),dp(28),dp(30),dp(28));root.addView(center,new FrameLayout.LayoutParams(-1,-1));BrandMarkView mark=new BrandMarkView(this);center.addView(mark,new LinearLayout.LayoutParams(dp(104),dp(104)));TextView kicker=label("ESTRADA PLAY",12,Color.rgb(224,30,47),true);kicker.setLetterSpacing(.18f);center.addView(kicker,wrap());margins(kicker,0,24,0,3);TextView title=label("COMUNISTA",31,Color.rgb(247,245,246),true);title.setLetterSpacing(.05f);center.addView(title,wrap());TextView subtitle=label("NAVEGAÇÃO  ·  MÚSICA  ·  PROTEÇÃO",11,Color.rgb(148,139,144),true);subtitle.setLetterSpacing(.08f);center.addView(subtitle,wrap());margins(subtitle,0,11,0,0);TextView version=label("v"+BuildConfig.VERSION_NAME,10,Color.rgb(118,84,77),true);version.setLetterSpacing(.10f);center.addView(version,wrap());margins(version,0,12,0,0);View line=new View(this);line.setBackgroundColor(Color.rgb(224,30,47));center.addView(line,new LinearLayout.LayoutParams(dp(54),dp(3)));margins(line,0,20,0,0);}
    private void launchDrive(){if(launched||isFinishing())return;launched=true;startActivity(new Intent(this,RoadMapActivity.class));overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);finish();}
    private void launchMain(){if(launched||isFinishing())return;launched=true;startActivity(new Intent(this,MainActivity.class));overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);finish();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);super.onDestroy();}
    private TextView label(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setGravity(Gravity.CENTER);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private LinearLayout.LayoutParams wrap(){return new LinearLayout.LayoutParams(-2,-2);}private void margins(View v,int l,int t,int r,int b){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
