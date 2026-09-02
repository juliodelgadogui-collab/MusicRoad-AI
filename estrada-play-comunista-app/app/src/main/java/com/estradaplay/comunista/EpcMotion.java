package com.estradaplay.comunista;
import android.view.View;
import android.view.ViewGroup;
final class EpcMotion {
    private EpcMotion(){}
    static void fadeIn(View v){if(v==null)return;v.setAlpha(0.88f);v.animate().alpha(1f).setDuration(180L).start();}
    static void stagger(ViewGroup g){if(g==null)return;int count=Math.min(g.getChildCount(),18);for(int i=0;i<count;i++){View v=g.getChildAt(i);v.setAlpha(0f);v.setTranslationY(18f);v.animate().alpha(1f).translationY(0f).setStartDelay(i*22L).setDuration(190L).start();}}
}
