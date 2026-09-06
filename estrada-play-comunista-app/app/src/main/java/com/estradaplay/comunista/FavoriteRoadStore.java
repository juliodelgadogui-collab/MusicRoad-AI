package com.estradaplay.comunista;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

/** Typed road favorites: home/work/fuel/food/rest/route plus generic points. */
final class FavoriteRoadStore {
    static final String TYPE_HOME="home",TYPE_WORK="work",TYPE_FUEL="fuel",TYPE_FOOD="food",TYPE_REST="rest",TYPE_ROUTE="route",TYPE_POINT="point";
    private static final String P="epc_road_favorites_v160",K="rows";

    static final class Item {
        final String name,type;final double lat,lon;final long usedAt;
        Item(String n,String t,double a,double b){this(n,t,a,b,System.currentTimeMillis());}
        Item(String n,String t,double a,double b,long u){name=n==null?"Ponto":n;type=normalizeType(t);lat=a;lon=b;usedAt=u;}
        String categoryLabel(){switch(type){case TYPE_HOME:return"CASA";case TYPE_WORK:return"TRABALHO";case TYPE_FUEL:return"POSTO";case TYPE_FOOD:return"COMIDA";case TYPE_REST:return"PARADA";case TYPE_ROUTE:return"ROTA";default:return"FAVORITO";}}
    }

    static void add(Context c,Item x){if(x==null||!Double.isFinite(x.lat)||!Double.isFinite(x.lon))return;try{JSONArray old=raw(c),out=new JSONArray();JSONObject n=json(x);out.put(n);for(int i=0;i<old.length()&&out.length()<60;i++){JSONObject o=old.optJSONObject(i);if(o==null)continue;String type=normalizeType(o.optString("type"));if((TYPE_HOME.equals(x.type)||TYPE_WORK.equals(x.type))&&x.type.equals(type))continue;if(Math.abs(o.optDouble("lat")-x.lat)<.00001&&Math.abs(o.optDouble("lon")-x.lon)<.00001)continue;out.put(o);}save(c,out);}catch(Throwable ignored){}}
    static void setHome(Context c,String name,double lat,double lon){add(c,new Item(name==null||name.trim().isEmpty()?"Casa":name,TYPE_HOME,lat,lon));}
    static void setWork(Context c,String name,double lat,double lon){add(c,new Item(name==null||name.trim().isEmpty()?"Trabalho":name,TYPE_WORK,lat,lon));}
    static Item firstOfType(Context c,String type){String wanted=normalizeType(type);for(Item i:list(c))if(wanted.equals(i.type))return i;return null;}
    static ArrayList<Item> list(Context c){ArrayList<Item> out=new ArrayList<>();JSONArray a=raw(c);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)out.add(new Item(o.optString("name"),o.optString("type"),o.optDouble("lat"),o.optDouble("lon"),o.optLong("used_at",0)));}return out;}
    static ArrayList<Item> listByType(Context c,String type){ArrayList<Item> out=new ArrayList<>();String wanted=normalizeType(type);for(Item i:list(c))if(wanted.equals(i.type))out.add(i);return out;}
    static void touch(Context c,Item item){if(item==null)return;add(c,new Item(item.name,item.type,item.lat,item.lon,System.currentTimeMillis()));}
    static void remove(Context c,double lat,double lon){JSONArray a=raw(c),out=new JSONArray();for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if(Math.abs(o.optDouble("lat")-lat)<.00001&&Math.abs(o.optDouble("lon")-lon)<.00001)continue;out.put(o);}save(c,out);}
    static String normalizeType(String raw){String t=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);switch(t){case TYPE_HOME:case TYPE_WORK:case TYPE_FUEL:case TYPE_FOOD:case TYPE_REST:case TYPE_ROUTE:case TYPE_POINT:return t;case"gas":case"station":return TYPE_FUEL;case"restaurant":return TYPE_FOOD;case"stop":return TYPE_REST;default:return TYPE_POINT;}}
    private static JSONObject json(Item x)throws Exception{JSONObject n=new JSONObject();n.put("name",x.name);n.put("type",x.type);n.put("lat",x.lat);n.put("lon",x.lon);n.put("used_at",x.usedAt);return n;}
    private static void save(Context c,JSONArray a){c.getSharedPreferences(P,Context.MODE_PRIVATE).edit().putString(K,a.toString()).apply();}
    private static JSONArray raw(Context c){try{return new JSONArray(c.getSharedPreferences(P,Context.MODE_PRIVATE).getString(K,"[]"));}catch(Throwable e){return new JSONArray();}}
}
