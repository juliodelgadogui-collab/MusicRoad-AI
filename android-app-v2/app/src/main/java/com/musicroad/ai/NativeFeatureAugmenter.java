package com.musicroad.ai;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native feature completion layer for screens that are built programmatically by MainActivity.
 * It keeps the definitive source tree clean while allowing new native controls to be attached
 * without returning to the old build-time patch chain.
 */
final class NativeFeatureAugmenter {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private static final Map<MainActivity,Session> SESSIONS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final String FEATURE_PREFS="musicroad_native_feature_v1";
    private static final String MAIN_PREFS="musicroad_native_shell_v1";
    private static final String LAST_UF="last_offline_uf";
    private static final Pattern UF_END=Pattern.compile("\\b([A-Z]{2})\\s*$");
    private static final Pattern FM_NUMBER=Pattern.compile("(?<!\\d)(\\d{2,3}(?:[.,]\\d))(?!\\d)");

    static void install(MainActivity activity){
        if(activity==null||activity.isFinishing())return;
        Session session=SESSIONS.get(activity);
        if(session==null){session=new Session(activity);SESSIONS.put(activity,session);session.attach();}
        session.requestScan();
    }

    static void detach(MainActivity activity){
        Session s=SESSIONS.remove(activity);if(s!=null)s.detach();
    }

    private static final class Session implements ViewTreeObserver.OnGlobalLayoutListener {
        private final MainActivity activity;
        private final View root;
        private final NativeApiClient api;
        private final OfflineStore offline;
        private final Map<EditText,Boolean> radioInstalled=new WeakHashMap<>();
        private final Map<Spinner,Boolean> offlineInstalled=new WeakHashMap<>();
        private long lastScanAt=0L;
        private boolean scanQueued=false;

        Session(MainActivity a){activity=a;root=a.getWindow().getDecorView();api=new NativeApiClient(a);offline=new OfflineStore(a);}

        void attach(){try{root.getViewTreeObserver().addOnGlobalLayoutListener(this);}catch(Throwable ignored){}}
        void detach(){try{if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(this);}catch(Throwable ignored){}}
        @Override public void onGlobalLayout(){requestScan();}

        void requestScan(){
            long now=System.currentTimeMillis();
            if(scanQueued)return;
            long wait=Math.max(0L,260L-(now-lastScanAt));scanQueued=true;
            MAIN.postDelayed(()->{scanQueued=false;lastScanAt=System.currentTimeMillis();scan();},wait);
        }

        private void scan(){
            if(activity.isFinishing())return;
            EditText stream=findEditByHint(root,"URL do stream de áudio");
            if(stream!=null&&!radioInstalled.containsKey(stream)){radioInstalled.put(stream,true);installRadio(stream);}
            Spinner state=findOfflineStateSpinner(root);
            if(state!=null&&!offlineInstalled.containsKey(state)){offlineInstalled.put(state,true);installOffline(state);}
        }

        private void installRadio(EditText urlField){
            LinearLayout card=asLinear(urlField.getParent());if(card==null)return;
            EditText nameField=findEditByHint(card,"Nome da rádio");
            EditText frequencyField=findEditByHint(card,"Frequência · ex. 98.7 FM");
            Button save=findButton(card,"SALVAR ESTAÇÃO");if(save==null)return;

            TextView status=label("Cole a URL: o MusicRoad identifica a emissora automaticamente.",12,Color.rgb(139,156,177),false);
            Button identify=button("IDENTIFICAR ESTAÇÃO",false);
            int index=Math.max(0,card.indexOfChild(save));
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(50));ip.topMargin=dp(8);card.addView(identify,index,ip);
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(7);card.addView(status,index+1,sp);

            final String[] lastProbe={""};
            Runnable manual=()->startRadioProbe(urlField,nameField,frequencyField,status,identify,lastProbe,true);
            identify.setOnClickListener(v->manual.run());
            urlField.addTextChangedListener(new TextWatcher(){
                @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                @Override public void onTextChanged(CharSequence s,int start,int before,int count){}
                @Override public void afterTextChanged(Editable e){
                    String snapshot=e==null?"":e.toString().trim();
                    if(!isWebUrl(snapshot))return;
                    MAIN.postDelayed(()->{
                        if(snapshot.equals(urlField.getText().toString().trim())&&!snapshot.equals(lastProbe[0]))
                            startRadioProbe(urlField,nameField,frequencyField,status,identify,lastProbe,false);
                    },900);
                }
            });
            String initial=urlField.getText().toString().trim();
            if(isWebUrl(initial))MAIN.postDelayed(()->startRadioProbe(urlField,nameField,frequencyField,status,identify,lastProbe,false),450);
        }

        private void startRadioProbe(EditText urlField,EditText nameField,EditText frequencyField,TextView status,Button identify,String[] lastProbe,boolean manual){
            String raw=urlField.getText().toString().trim();
            if(!isWebUrl(raw)){if(manual)toast("Informe uma URL http ou https da rádio.");return;}
            if(!manual&&raw.equals(lastProbe[0]))return;
            lastProbe[0]=raw;identify.setEnabled(false);identify.setText("IDENTIFICANDO…");status.setText("Consultando os metadados da emissora…");
            IO.execute(()->{
                RadioInfo info;
                try{info=probeRadio(raw,0);}catch(Exception e){info=new RadioInfo(raw,"","","","",false,safeMessage(e));}
                RadioInfo result=info;
                MAIN.post(()->{
                    identify.setEnabled(true);identify.setText("IDENTIFICAR ESTAÇÃO");
                    if(result.ok){
                        if(!result.resolvedUrl.isEmpty()&&!result.resolvedUrl.equals(raw))urlField.setText(result.resolvedUrl);
                        if(!result.name.isEmpty()&&(nameField.getText().toString().trim().isEmpty()||manual))nameField.setText(result.name);
                        if(!result.frequency.isEmpty()&&(frequencyField.getText().toString().trim().isEmpty()||manual))frequencyField.setText(result.frequency);
                        String detail=result.name.isEmpty()?"Stream válido; a emissora não envia nome nos metadados.":"Identificada: "+result.name;
                        if(!result.frequency.isEmpty())detail+=" · "+result.frequency;
                        if(!result.genre.isEmpty())detail+=" · "+result.genre;
                        status.setText(detail);
                    }else{
                        status.setText("Não foi possível identificar: "+(result.error.isEmpty()?"stream não respondeu":result.error));
                    }
                });
            });
        }

        private void installOffline(Spinner stateSpinner){
            LinearLayout card=asLinear(stateSpinner.getParent());if(card==null)return;
            TextView stateStatus=findTextStarting(card,"Selecione o estado");
            if(stateStatus==null)stateStatus=findTextContaining(card,"Mapa ");
            if(stateStatus==null){stateStatus=label("Selecione o estado.",12,Color.rgb(139,156,177),false);card.addView(stateStatus);}
            final TextView status=stateStatus;

            TextView roadLabel=label("RODOVIA",10,Color.rgb(139,156,177),true);
            Spinner roadSpinner=new Spinner(activity);
            TextView roadStatus=label("Escolha o estado para carregar as rodovias.",12,Color.rgb(139,156,177),false);
            int stateIndex=card.indexOfChild(stateSpinner);
            LinearLayout.LayoutParams lpLabel=new LinearLayout.LayoutParams(-1,-2);lpLabel.topMargin=dp(12);card.addView(roadLabel,stateIndex+1,lpLabel);
            card.addView(roadSpinner,stateIndex+2,new LinearLayout.LayoutParams(-1,dp(58)));
            LinearLayout.LayoutParams lpRoadStatus=new LinearLayout.LayoutParams(-1,-2);lpRoadStatus.bottomMargin=dp(8);card.addView(roadStatus,stateIndex+3,lpRoadStatus);

            Button mapButton=findButton(card,"BAIXAR MAPA");
            Button radarButton=findButton(card,"BAIXAR RADARES");
            Button deleteButton=findButton(card,"EXCLUIR DADOS");
            if(mapButton==null||radarButton==null)return;

            stateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                    String uf=stateUf(stateSpinner);boolean supported=isSupportedUf(uf);
                    mapButton.setEnabled(supported);radarButton.setEnabled(supported);roadSpinner.setEnabled(supported);
                    activity.getSharedPreferences(MAIN_PREFS,MainActivity.MODE_PRIVATE).edit().putString(LAST_UF,uf).apply();
                    if(supported)loadRoads(uf,roadSpinner,roadStatus,status);else{
                        setRoadAdapter(roadSpinner,Collections.singletonList(new RoadOption("","Disponível em SP, MG, RJ e ES",0)),"");
                        roadStatus.setText("Os pacotes rodoviários de teste estão disponíveis em SP, MG, RJ e ES.");updatePackStatus(uf,roadSpinner,status);
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent){}
            });
            roadSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                    RoadOption road=selectedRoad(roadSpinner);String uf=stateUf(stateSpinner);
                    if(isSupportedUf(uf))activity.getSharedPreferences(FEATURE_PREFS,MainActivity.MODE_PRIVATE).edit().putString("road_"+uf,road==null?"":road.key).apply();
                    updatePackStatus(uf,roadSpinner,status);
                }
                @Override public void onNothingSelected(AdapterView<?> parent){}
            });

            mapButton.setOnClickListener(v->downloadPack("map",stateSpinner,roadSpinner,mapButton,status));
            radarButton.setOnClickListener(v->downloadPack("radars",stateSpinner,roadSpinner,radarButton,status));
            if(deleteButton!=null)deleteButton.setOnClickListener(v->{String uf=stateUf(stateSpinner);if(uf.isEmpty())return;offline.removePrefix("state/"+uf+"/");updatePackStatus(uf,roadSpinner,status);toast("Dados offline de "+uf+" removidos.");});

            MAIN.post(()->{
                String uf=stateUf(stateSpinner);boolean supported=isSupportedUf(uf);mapButton.setEnabled(supported);radarButton.setEnabled(supported);roadSpinner.setEnabled(supported);
                if(supported)loadRoads(uf,roadSpinner,roadStatus,status);else updatePackStatus(uf,roadSpinner,status);
            });
        }

        private void loadRoads(String uf,Spinner roadSpinner,TextView roadStatus,TextView packStatus){
            roadStatus.setText("Carregando rodovias de "+uf+"…");roadSpinner.setEnabled(false);
            String wanted=activity.getSharedPreferences(FEATURE_PREFS,MainActivity.MODE_PRIVATE).getString("road_"+uf,"");
            IO.execute(()->{
                ArrayList<RoadOption> roads=new ArrayList<>();roads.add(new RoadOption("","Estado inteiro",0));String error="";
                try{
                    NativeApiClient.Response r=api.get(server(),"api/road_catalog.php?uf="+enc(uf));JSONObject j=r.json();
                    if(!r.ok()||!j.optBoolean("ok"))throw new Exception(j.optString("error","Catálogo indisponível"));
                    JSONArray a=j.optJSONArray("roads");if(a!=null)for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String key=o.optString("key","").trim();if(key.isEmpty())continue;roads.add(new RoadOption(key,o.optString("label",key),o.optInt("count",0)));}
                    roads.subList(1,roads.size()).sort(Comparator.comparingInt((RoadOption x)->x.count).reversed().thenComparing(x->x.label));
                }catch(Exception e){error=safeMessage(e);}
                String err=error;MAIN.post(()->{
                    if(!uf.equals(stateUf(findOfflineStateSpinner(root))))return;
                    setRoadAdapter(roadSpinner,roads,wanted);roadSpinner.setEnabled(true);
                    roadStatus.setText(err.isEmpty()?(roads.size()-1)+" rodovias disponíveis · escolha uma ou o estado inteiro":"Lista básica disponível · servidor: "+err);
                    updatePackStatus(uf,roadSpinner,packStatus);
                });
            });
        }

        private void downloadPack(String kind,Spinner stateSpinner,Spinner roadSpinner,Button button,TextView status){
            String uf=stateUf(stateSpinner);if(!isSupportedUf(uf)){toast("Escolha SP, MG, RJ ou ES.");return;}
            RoadOption option=selectedRoad(roadSpinner);String road=option==null?"":option.key;
            button.setEnabled(false);String old=button.getText().toString();button.setText("PREPARANDO "+(road.isEmpty()?uf:road)+"…");status.setText("Baixando "+(kind.equals("map")?"mapa":"alertas")+" de "+(road.isEmpty()?uf:road)+"…");
            activity.getSharedPreferences(MAIN_PREFS,MainActivity.MODE_PRIVATE).edit().putString(LAST_UF,uf).apply();
            activity.getSharedPreferences(FEATURE_PREFS,MainActivity.MODE_PRIVATE).edit().putString("road_"+uf,road).apply();
            IO.execute(()->{
                try{
                    String path;
                    if(road.isEmpty())path=kind.equals("map")?"api/road_map_state.php?uf="+enc(uf):"api/offline_state.php?kind=radars&uf="+enc(uf);
                    else path="api/offline_road.php?kind="+enc(kind)+"&uf="+enc(uf)+"&road="+enc(road);
                    NativeApiClient.Response response=api.getLarge(server(),path);JSONObject json=response.json();
                    if(!response.ok()||!json.optBoolean("ok"))throw new Exception(json.optString("error",json.optString("message","Falha no download")));
                    JSONObject stored=json;
                    if(kind.equals("map")&&road.isEmpty()){
                        JSONObject roads=json.optJSONObject("roads");if(roads==null)throw new Exception("Servidor não retornou a malha viária");
                        stored=new JSONObject();stored.put("ok",true);stored.put("uf",uf);stored.put("scope","state");stored.put("map",new JSONObject().put("roads",roads));stored.put("generated_at",json.optString("generated_at",""));
                    }
                    boolean saved=offline.put("state/"+uf+"/"+kind,stored.toString());if(!saved)throw new Exception("Não foi possível salvar no aparelho");
                    MAIN.post(()->{button.setEnabled(true);button.setText(old);updatePackStatus(uf,roadSpinner,status);toast((kind.equals("map")?"Mapa":"Alertas")+" de "+(road.isEmpty()?uf:road)+" salvos offline.");});
                }catch(Exception e){String msg=safeMessage(e);MAIN.post(()->{button.setEnabled(true);button.setText(old);status.setText("Falha: "+msg);});}
            });
        }

        private void updatePackStatus(String uf,Spinner roadSpinner,TextView status){
            if(uf==null||uf.isEmpty()){status.setText("Selecione o estado.");return;}
            RoadOption o=selectedRoad(roadSpinner);String scope=o==null||o.key.isEmpty()?uf:o.key;
            boolean map=offline.has("state/"+uf+"/map"),radars=offline.has("state/"+uf+"/radars");
            status.setText(scope+" · "+(map?"Mapa ✓":"Mapa —")+" · "+(radars?"Radares ✓":"Radares —"));
        }

        private void setRoadAdapter(Spinner spinner,List<RoadOption> roads,String selected){
            ArrayAdapter<RoadOption> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,roads);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);
            int target=0;for(int i=0;i<roads.size();i++)if(roads.get(i).key.equals(selected)){target=i;break;}spinner.setSelection(target,false);
        }

        private String server(){return NativeApiClient.normalizeBase(BuildConfig.MUSICROAD_URL);}
        private void toast(String text){Toast.makeText(activity,text,Toast.LENGTH_SHORT).show();}
        private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}

        private TextView label(String text,float size,int color,boolean bold){TextView v=new TextView(activity);v.setText(text);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
        private Button button(String text,boolean primary){
            Button b=new Button(activity);b.setText(text);b.setAllCaps(false);b.setTextSize(12);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(primary?Color.rgb(25,13,5):Color.rgb(247,249,252));
            GradientDrawable d=new GradientDrawable();d.setColor(primary?Color.rgb(255,122,26):Color.rgb(14,25,39));d.setCornerRadius(dp(14));if(!primary)d.setStroke(dp(1),Color.rgb(37,55,72));b.setBackground(d);return b;
        }
    }

    private static final class RoadOption {
        final String key,label;final int count;
        RoadOption(String k,String l,int c){key=k==null?"":k;label=l==null?key:l;count=Math.max(0,c);}
        @Override public String toString(){return label;}
    }

    private static final class RadioInfo {
        final String resolvedUrl,name,frequency,genre,contentType;final boolean ok;final String error;
        RadioInfo(String u,String n,String f,String g,String c,boolean o,String e){resolvedUrl=nvl(u);name=nvl(n);frequency=nvl(f);genre=nvl(g);contentType=nvl(c);ok=o;error=nvl(e);}
    }

    private static RadioInfo probeRadio(String value,int depth) throws Exception {
        if(depth>3)throw new Exception("muitos redirecionamentos");
        URL url=new URL(value);String scheme=url.getProtocol();if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme))throw new Exception("URL inválida");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(9000);c.setReadTimeout(9000);c.setInstanceFollowRedirects(false);c.setRequestMethod("GET");c.setRequestProperty("Icy-MetaData","1");c.setRequestProperty("Accept","audio/*,application/vnd.apple.mpegurl,audio/x-mpegurl,*/*;q=0.5");c.setRequestProperty("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME+" RadioProbe");
        int code=c.getResponseCode();
        if(code>=300&&code<400){String location=c.getHeaderField("Location");c.disconnect();if(location==null||location.trim().isEmpty())throw new Exception("redirecionamento sem destino");return probeRadio(new URL(url,location).toString(),depth+1);}
        if(code<200||code>=300){c.disconnect();throw new Exception("HTTP "+code);}
        String contentType=nvl(c.getContentType()).toLowerCase(Locale.ROOT);String name=firstHeader(c,"icy-name","ice-name","x-audiocast-name","x-stream-name");String genre=firstHeader(c,"icy-genre","ice-genre");String description=firstHeader(c,"icy-description","ice-description");String stationUrl=firstHeader(c,"icy-url");
        boolean playlist=isPlaylist(value,contentType);
        if(playlist){
            String body=readSmall(c.getInputStream(),65536);c.disconnect();String next=firstPlaylistUrl(body);if(!next.isEmpty()){
                RadioInfo child=probeRadio(new URL(url,next).toString(),depth+1);
                String mergedName=child.name.isEmpty()?name:child.name;String mergedGenre=child.genre.isEmpty()?genre:child.genre;String freq=child.frequency.isEmpty()?frequencyFrom(mergedName+" "+description+" "+stationUrl):child.frequency;
                return new RadioInfo(child.resolvedUrl,mergedName,freq,mergedGenre,child.contentType,true,"");
            }
        }
        c.disconnect();String frequency=frequencyFrom(name+" "+description+" "+stationUrl+" "+url.getHost());return new RadioInfo(value,name,frequency,genre,contentType,true,"");
    }

    private static String readSmall(InputStream input,int limit) throws Exception {
        if(input==null)return "";try(BufferedInputStream in=new BufferedInputStream(input);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n,total=0;while((n=in.read(buf))!=-1){int take=Math.min(n,limit-total);if(take>0)out.write(buf,0,take);total+=take;if(total>=limit)break;}return out.toString(StandardCharsets.UTF_8.name());}
    }

    private static boolean isPlaylist(String url,String type){String u=url.toLowerCase(Locale.ROOT);String t=nvl(type).toLowerCase(Locale.ROOT);return u.endsWith(".m3u")||u.endsWith(".m3u8")||u.endsWith(".pls")||t.contains("mpegurl")||t.contains("scpls");}
    private static String firstPlaylistUrl(String body){if(body==null)return "";for(String line:body.split("\\r?\\n")){String s=line.trim();if(s.regionMatches(true,0,"File",0,4)){int eq=s.indexOf('=');if(eq>0)s=s.substring(eq+1).trim();}if(isWebUrl(s))return s;}return "";}
    private static String firstHeader(HttpURLConnection c,String... names){for(String name:names){String value=c.getHeaderField(name);if(value!=null&&!value.trim().isEmpty())return clean(value);}return "";}
    private static String frequencyFrom(String value){Matcher m=FM_NUMBER.matcher(nvl(value));while(m.find()){String raw=m.group(1).replace(',','.');try{double f=Double.parseDouble(raw);if(f>=87.0&&f<=108.5)return String.format(Locale.US,"%.1f FM",f);}catch(Exception ignored){}}return "";}
    private static String clean(String value){String s=nvl(value).replace('\r',' ').replace('\n',' ').trim();return s.length()>120?s.substring(0,120):s;}
    private static String safeMessage(Throwable e){String s=e==null?"":nvl(e.getMessage()).trim();if(s.isEmpty()&&e!=null)s=e.getClass().getSimpleName();s=s.replace('\r',' ').replace('\n',' ');return s.length()>100?s.substring(0,100):s;}
    private static String nvl(String s){return s==null?"":s;}
    private static boolean isWebUrl(String s){return s!=null&&(s.startsWith("https://")||s.startsWith("http://"));}
    private static String enc(String s){try{return URLEncoder.encode(nvl(s),"UTF-8");}catch(Exception e){return nvl(s);}}
    private static boolean isSupportedUf(String uf){return "SP".equals(uf)||"MG".equals(uf)||"RJ".equals(uf)||"ES".equals(uf);}

    private static String stateUf(Spinner spinner){if(spinner==null||spinner.getSelectedItem()==null)return "";Matcher m=UF_END.matcher(spinner.getSelectedItem().toString().trim().toUpperCase(Locale.ROOT));return m.find()?m.group(1):"";}
    private static RoadOption selectedRoad(Spinner spinner){Object o=spinner==null?null:spinner.getSelectedItem();return o instanceof RoadOption?(RoadOption)o:null;}

    private static Spinner findOfflineStateSpinner(View root){
        TextView title=findTextExact(root,"Baixar por estado");if(title==null)return null;ViewParent p=title.getParent();while(p instanceof View){Spinner s=findFirstSpinner((View)p);if(s!=null)return s;p=p.getParent();}return null;
    }
    private static EditText findEditByHint(View root,String hint){if(root instanceof EditText){CharSequence h=((EditText)root).getHint();if(h!=null&&hint.contentEquals(h))return (EditText)root;}if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){EditText r=findEditByHint(g.getChildAt(i),hint);if(r!=null)return r;}}return null;}
    private static TextView findTextExact(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){TextView r=findTextExact(g.getChildAt(i),text);if(r!=null)return r;}}return null;}
    private static TextView findTextStarting(View root,String text){if(root instanceof TextView&&((TextView)root).getText().toString().startsWith(text))return (TextView)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){TextView r=findTextStarting(g.getChildAt(i),text);if(r!=null)return r;}}return null;}
    private static TextView findTextContaining(View root,String text){if(root instanceof TextView&&((TextView)root).getText().toString().contains(text))return (TextView)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){TextView r=findTextContaining(g.getChildAt(i),text);if(r!=null)return r;}}return null;}
    private static Button findButton(View root,String text){if(root instanceof Button&&((Button)root).getText().toString().contains(text))return (Button)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){Button r=findButton(g.getChildAt(i),text);if(r!=null)return r;}}return null;}
    private static Spinner findFirstSpinner(View root){if(root instanceof Spinner)return (Spinner)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){Spinner r=findFirstSpinner(g.getChildAt(i));if(r!=null)return r;}}return null;}
    private static LinearLayout asLinear(Object p){return p instanceof LinearLayout?(LinearLayout)p:null;}
}
