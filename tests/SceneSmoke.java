package space.glidetv.launcher;
import android.app.Activity;import android.content.*;import android.os.*;import java.io.*;import java.util.*;import org.json.*;
public class SceneSmoke {
 static class Prefs implements SharedPreferences {
  Map<String,Object> values=new HashMap<>();public Map<String,?> getAll(){return new HashMap<>(values);}public String getString(String k,String d){Object v=values.get(k);return v==null?d:(String)v;}public Set<String> getStringSet(String k,Set<String>d){return d;}public int getInt(String k,int d){Object v=values.get(k);return v==null?d:(Integer)v;}public long getLong(String k,long d){Object v=values.get(k);return v==null?d:(Long)v;}public float getFloat(String k,float d){return d;}public boolean getBoolean(String k,boolean d){Object v=values.get(k);return v==null?d:(Boolean)v;}public boolean contains(String k){return values.containsKey(k);}public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
  public Editor edit(){return new Editor(){public Editor putString(String k,String v){values.put(k,v);return this;}public Editor putStringSet(String k,Set<String>v){values.put(k,v);return this;}public Editor putInt(String k,int v){values.put(k,v);return this;}public Editor putLong(String k,long v){values.put(k,v);return this;}public Editor putFloat(String k,float v){values.put(k,v);return this;}public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}public Editor remove(String k){values.remove(k);return this;}public Editor clear(){values.clear();return this;}public boolean commit(){return true;}public void apply(){}};}
 }
 static class TestActivity extends Activity {Prefs prefs;TestActivity(Prefs p){prefs=p;}@Override public SharedPreferences getSharedPreferences(String s,int m){return prefs;}}
 static class TestContext extends ContextWrapper {Prefs prefs=new Prefs();File root;TestContext(String path){super(null);root=new File(path);root.mkdirs();}public SharedPreferences getSharedPreferences(String n,int m){return prefs;}public File getFilesDir(){File f=new File(root,"files");f.mkdirs();return f;}public File getCacheDir(){File f=new File(root,"cache");f.mkdirs();return f;}}
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static void failed(Throwable e){e.printStackTrace();System.exit(1);}
 public static void main(String[] args){try{
  if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
  Prefs p=new Prefs();WeatherLocation fresh=new WeatherLocation(new TestActivity(p),()->{});check(!fresh.configured(),"Fresh installs must not default to a city");fresh.close();
  p=new Prefs();p.edit().putString("weather_city","Dubai").apply();WeatherLocation old=new WeatherLocation(new TestActivity(p),()->{});check(old.configured()&&Math.abs(old.latitude()-25.2048)<.0001&&old.timezone().equals("Asia/Dubai"),"Legacy selected city migration");old.close();
  p=new Prefs();p.edit().putString("weather_v3_Abu Dhabi","{}").apply();old=new WeatherLocation(new TestActivity(p),()->{});check(old.configured()&&old.city().equals("Abu Dhabi"),"Legacy default/cache migration");old.close();
  p=new Prefs();p.edit().putString("weather_city","Paris").putString("weather_latitude","48.8566").putString("weather_longitude","2.3522").putString("weather_timezone","Europe/Paris").apply();old=new WeatherLocation(new TestActivity(p),()->{});check(old.city().equals("Paris")&&old.timezone().equals("Europe/Paris")&&Math.abs(old.longitude()-2.3522)<.0001,"Preserve worldwide saved coordinates");old.close();
  check(ScenePacks.distanceKm(24.4539,54.3773,24.4539,54.3773)<.001,"Distance origin");check(ScenePacks.distanceKm(51.5074,-.1278,24.4539,54.3773)>5000,"Global pack coverage");
  JSONObject geo=HomeActivity.readJson("https://geocoding-api.open-meteo.com/v1/search?name=Tokyo&count=5&language=en&format=json");JSONArray matches=geo.getJSONArray("results");boolean japan=false;for(int n=0;n<matches.length();n++)if("JP".equals(matches.getJSONObject(n).optString("country_code")))japan=true;check(japan,"Worldwide geocoding endpoint");
  System.out.println("PASS fresh setup, saved-location migration, global coordinates, and live Tokyo search");
  TestContext context=new TestContext(args[0]);ScenePacks packs=new ScenePacks(context);
  new Handler(Looper.getMainLooper()).postDelayed(()->{System.err.println("Scene smoke timed out");packs.close();System.exit(2);},120000);
  packs.ensure(24.4539,54.3773,true,()->{try{check(context.prefs.getString("scene_id","").equals("abu-dhabi"),"Nearby city pack");File city=new File(context.prefs.getString("scene_path",""));check(new File(city,"city.png").isFile()&&new File(city,"city-night.png").isFile(),"Downloaded city images");System.out.println("PASS Android city-pack download, checksum, extraction, and cache");
   packs.ensure(0,0,true,()->{try{check(context.prefs.getString("scene_id","").equals("generic"),"Generic fallback outside coverage");File generic=new File(context.prefs.getString("scene_path",""));check(new File(generic,"cloud.png").isFile()&&!new File(generic,"city.png").exists(),"Generic has no wrong-city image");check(!city.exists(),"Unused city cache pruned");long modified=new File(generic,"cloud.png").lastModified();
    packs.ensure(0,-10,true,()->{try{check(context.prefs.getString("scene_id","").equals("generic"),"Uncovered-location fallback");check(new File(generic,"cloud.png").lastModified()==modified,"Reuse downloaded pack version");packs.close();System.out.println("PASS generic selection, cache reuse, and removal of unused artwork");System.out.println("ALL ANDROID SMOKE CHECKS PASSED");System.exit(0);}catch(Throwable e){failed(e);}});
   }catch(Throwable e){failed(e);}});
  }catch(Throwable e){failed(e);}});
  Looper.loop();
 }catch(Throwable e){failed(e);}}
}
