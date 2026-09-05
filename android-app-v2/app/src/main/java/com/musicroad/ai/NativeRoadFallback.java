package com.musicroad.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local road catalog + state-pack filter.
 *
 * The road selector must work even before the optional road-specific PHP endpoints are
 * deployed to the MusicRoad server.  This layer therefore ships the initial SP/MG/RJ/ES
 * catalog in the APK and derives a selected-road pack from the already existing state APIs.
 */
final class NativeRoadFallback {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private static final Map<MainActivity,Session> SESSIONS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final String FEATURE_PREFS="musicroad_native_feature_v1";
    private static final String MAIN_PREFS="musicroad_native_shell_v1";
    private static final String LAST_UF="last_offline_uf";
    private static final Pattern UF_END=Pattern.compile("\\b([A-Z]{2})\\s*$");
    private static final Pattern ROAD_REF=Pattern.compile("\\b(BR|SP|MG|RJ|ES)\\s*[- ]?\\s*(\\d{2,3})\\b",Pattern.CASE_INSENSITIVE);

    static void install(MainActivity activity){
        if(activity==null||activity.isFinishing())return;
        Session s=SESSIONS.get(activity);
        if(s==null){s=new Session(activity);SESSIONS.put(activity,s);s.attach();}
        s.requestScan(260);
    }

    static void detach(MainActivity activity){Session s=SESSIONS.remove(activity);if(s!=null)s.detach();}

    private static final class Session implements ViewTreeObserver.OnGlobalLayoutListener {
        final MainActivity activity;
        final View root;
        final NativeApiClient api;
        final OfflineStore offline;
        boolean queued=false;
        long lastScan=0L;
        String lastUf="";

        Session(MainActivity a){activity=a;root=a.getWindow().getDecorView();api=new NativeApiClient(a);offline=new OfflineStore(a);}
        void attach(){try{root.getViewTreeObserver().addOnGlobalLayoutListener(this);}catch(Throwable ignored){}}
        void detach(){try{if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(this);}catch(Throwable ignored){}}
        @Override public void onGlobalLayout(){requestScan(140);}

        void requestScan(long extra){
            if(queued)return;queued=true;
            long delay=Math.max(extra,260L-Math.max(0L,System.currentTimeMillis()-lastScan));
            MAIN.postDelayed(()->{queued=false;lastScan=System.currentTimeMillis();scan();},delay);
        }

        void scan(){
            if(activity.isFinishing())return;
            TextView label=findTextExact(root,"RODOVIA");if(label==null)return;
            ViewParent parent=label.getParent();if(!(parent instanceof ViewGroup))return;
            ViewGroup card=(ViewGroup)parent;
            List<Spinner> spinners=new ArrayList<>();collectSpinners(card,spinners);if(spinners.size()<2)return;
            Spinner state=spinners.get(0),road=spinners.get(1);
            String uf=stateUf(state);if(!supported(uf))return;

            if(!uf.equals(lastUf)||road.getAdapter()==null||road.getAdapter().getCount()<=1){
                lastUf=uf;installCatalog(uf,road);
            }

            Button map=findButton(card,"BAIXAR MAPA"),radars=findButton(card,"BAIXAR RADARES");
            if(map!=null)map.setOnClickListener(v->download("map",state,road,map));
            if(radars!=null)radars.setOnClickListener(v->download("radars",state,road,radars));
        }

        void installCatalog(String uf,Spinner road){
            String wanted=activity.getSharedPreferences(FEATURE_PREFS,Context.MODE_PRIVATE).getString("road_"+uf,"");
            List<Road> items=roads(uf);
            ArrayAdapter<Road> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,items);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);road.setAdapter(adapter);
            int target=0;for(int i=0;i<items.size();i++)if(items.get(i).key.equals(wanted)){target=i;break;}road.setSelection(target,false);
        }

        void download(String kind,Spinner state,Spinner roadSpinner,Button button){
            String uf=stateUf(state);if(!supported(uf)){toast("Escolha SP, MG, RJ ou ES.");return;}
            Road selected=selectedRoad(roadSpinner);String road=selected==null?"":selected.key;
            String old=button.getText().toString();button.setEnabled(false);button.setText("PREPARANDO "+(road.isEmpty()?uf:road)+"…");
            activity.getSharedPreferences(MAIN_PREFS,Context.MODE_PRIVATE).edit().putString(LAST_UF,uf).apply();
            activity.getSharedPreferences(FEATURE_PREFS,Context.MODE_PRIVATE).edit().putString("road_"+uf,road).apply();
            IO.execute(()->{
                try{
                    JSONObject stored=kind.equals("map")?stateMapPack(uf,road):stateRadarPack(uf,road);
                    if(!offline.put("state/"+uf+"/"+kind,stored.toString()))throw new Exception("não foi possível salvar no aparelho");
                    MAIN.post(()->{button.setEnabled(true);button.setText(old);toast((kind.equals("map")?"Mapa":"Alertas")+" de "+(road.isEmpty()?uf:road)+" salvos offline.");});
                }catch(Exception e){String msg=safe(e);MAIN.post(()->{button.setEnabled(true);button.setText(old);toast("Falha no download: "+msg);});}
            });
        }

        JSONObject stateMapPack(String uf,String road) throws Exception {
            NativeApiClient.Response r=api.getLarge(server(),"api/road_map_state.php?uf="+enc(uf));JSONObject j=r.json();
            if(!r.ok()||!j.optBoolean("ok"))throw new Exception(j.optString("error",j.optString("message","mapa indisponível")));
            JSONObject fc=j.optJSONObject("roads");if(fc==null)throw new Exception("servidor não retornou a malha viária");
            if(!road.isEmpty())fc=filterFeatures(fc,road);
            JSONObject pack=new JSONObject();pack.put("ok",true);pack.put("uf",uf);pack.put("scope",road.isEmpty()?"state":"road");pack.put("road",road);pack.put("generated_at",j.optString("generated_at",""));pack.put("map",new JSONObject().put("roads",fc));return pack;
        }

        JSONObject stateRadarPack(String uf,String road) throws Exception {
            NativeApiClient.Response r=api.getLarge(server(),"api/offline_state.php?kind=radars&uf="+enc(uf));JSONObject j=r.json();
            if(!r.ok()||!j.optBoolean("ok"))throw new Exception(j.optString("error",j.optString("message","radares indisponíveis")));
            if(road.isEmpty())return j;
            JSONArray source=j.optJSONArray("radars"),out=new JSONArray();
            if(source!=null)for(int i=0;i<source.length();i++){JSONObject h=source.optJSONObject(i);if(h==null)continue;String ref=h.optString("rodovia",h.optString("road",""));if(matchesRoad(ref,road))out.put(h);}
            JSONObject pack=new JSONObject();pack.put("ok",true);pack.put("kind","radars");pack.put("uf",uf);pack.put("scope","road");pack.put("road",road);pack.put("generated_at",j.optString("generated_at",""));pack.put("radars",out);pack.put("total",out.length());return pack;
        }

        JSONObject filterFeatures(JSONObject fc,String road) throws Exception {
            JSONArray source=fc.optJSONArray("features"),out=new JSONArray();
            if(source!=null)for(int i=0;i<source.length();i++){
                JSONObject f=source.optJSONObject(i);if(f==null)continue;JSONObject p=f.optJSONObject("properties");
                String ref=p==null?"":p.optString("ref","");String name=p==null?"":p.optString("name","");
                if(matchesRoad(ref,road)||matchesRoad(name,road))out.put(f);
            }
            JSONObject filtered=new JSONObject();filtered.put("type","FeatureCollection");filtered.put("features",out);return filtered;
        }

        String server(){return NativeApiClient.normalizeBase(BuildConfig.MUSICROAD_URL);}
        void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_SHORT).show();}
    }

    private static List<Road> roads(String uf){
        ArrayList<Road> out=new ArrayList<>();out.add(new Road("","Estado inteiro"));String[] values;
        switch(uf){
            case "SP": values=new String[]{"BR-050","BR-101","BR-116","BR-153","BR-381","SP-070","SP-075","SP-160","SP-270","SP-280","SP-300","SP-310","SP-330","SP-348"};break;
            case "MG": values=new String[]{"BR-040","BR-050","BR-116","BR-135","BR-153","BR-251","BR-262","BR-265","BR-267","BR-365","BR-381","BR-459","MG-010","MG-050","MG-290","MG-424"};break;
            case "RJ": values=new String[]{"BR-040","BR-101","BR-116","BR-393","BR-465","RJ-104","RJ-106","RJ-116","RJ-124"};break;
            case "ES": values=new String[]{"BR-101","BR-259","BR-262","ES-010","ES-060","ES-080","ES-164","ES-248"};break;
            default: values=new String[0];
        }
        for(String value:values)out.add(new Road(value,value));return out;
    }

    private static final class Road {
        final String key,label;Road(String k,String l){key=k==null?"":k;label=l==null?key:l;}
        @Override public String toString(){return label;}
    }

    private static Road selectedRoad(Spinner s){Object o=s==null?null:s.getSelectedItem();if(o instanceof Road)return (Road)o;if(o==null)return null;String text=o.toString().trim();if(text.equalsIgnoreCase("Estado inteiro"))return new Road("","Estado inteiro");Matcher m=ROAD_REF.matcher(text);return m.find()?new Road(canonical(m.group(1),m.group(2)),text):null;}
    private static boolean matchesRoad(String raw,String wanted){if(raw==null||wanted==null||wanted.isEmpty())return false;Matcher m=ROAD_REF.matcher(raw.toUpperCase(Locale.ROOT));while(m.find())if(wanted.equals(canonical(m.group(1),m.group(2))))return true;return false;}
    private static String canonical(String prefix,String digits){try{return prefix.toUpperCase(Locale.ROOT)+"-"+String.format(Locale.US,"%03d",Integer.parseInt(digits));}catch(Exception e){return "";}}
    private static String stateUf(Spinner s){if(s==null||s.getSelectedItem()==null)return "";Matcher m=UF_END.matcher(s.getSelectedItem().toString().trim().toUpperCase(Locale.ROOT));return m.find()?m.group(1):"";}
    private static boolean supported(String uf){return "SP".equals(uf)||"MG".equals(uf)||"RJ".equals(uf)||"ES".equals(uf);}
    private static String enc(String s){try{return URLEncoder.encode(s==null?"":s,"UTF-8");}catch(Exception e){return s==null?"":s;}}
    private static String safe(Throwable e){String s=e==null?"":String.valueOf(e.getMessage());if(s==null||s.trim().isEmpty())s=e==null?"erro":e.getClass().getSimpleName();s=s.replace('\r',' ').replace('\n',' ');return s.length()>90?s.substring(0,90):s;}

    private static TextView findTextExact(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){TextView r=findTextExact(g.getChildAt(i),text);if(r!=null)return r;}}return null;}
    private static Button findButton(View root,String text){if(root instanceof Button&&((Button)root).getText().toString().contains(text))return (Button)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){Button r=findButton(g.getChildAt(i),text);if(r!=null)return r;}}return null;}
    private static void collectSpinners(View root,List<Spinner> out){if(root instanceof Spinner){out.add((Spinner)root);return;}if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++)collectSpinners(g.getChildAt(i),out);}}
}
