package space.glidetv.launcher;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** User-selected or Android-provided weather coordinates; no IP-location service. */
final class WeatherLocation {
    static final int PERMISSION=44;
    private final Activity activity;
    private final android.content.SharedPreferences prefs;
    private final Runnable changed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private CancellationSignal cancellation;
    private LocationListener listener;
    private boolean closed,locating,resolving;private int selection;
    WeatherLocation(Activity a,Runnable changed){activity=a;this.changed=changed;prefs=a.getSharedPreferences("home",0);migrate();}
    private void migrate(){
        if(prefs.contains("weather_latitude"))return;
        String old=prefs.getString("weather_city","");
        if(old.isEmpty()&&prefs.contains("weather_v3_Abu Dhabi"))old="Abu Dhabi";
        if(old.equals("Abu Dhabi")||old.equals("Dubai")||old.equals("Sharjah")){
            double lat=old.equals("Dubai")?25.2048:old.equals("Sharjah")?25.3463:24.4539;
            double lon=old.equals("Dubai")?55.2708:old.equals("Sharjah")?55.4209:54.3773;
            prefs.edit().putString("weather_city",old).putString("weather_latitude",Double.toString(lat)).putString("weather_longitude",Double.toString(lon)).putString("weather_timezone","Asia/Dubai").putString("weather_country","AE").putString("weather_location_mode","manual").apply();
        }
    }
    boolean configured(){return prefs.contains("weather_latitude")&&prefs.contains("weather_longitude");}
    String city(){return prefs.getString("weather_city","Your location");}
    double latitude(){return Double.parseDouble(prefs.getString("weather_latitude","0"));}
    double longitude(){return Double.parseDouble(prefs.getString("weather_longitude","0"));}
    String key(){return String.format(Locale.US,"%.4f,%.4f",latitude(),longitude());}
    String timezone(){return prefs.getString("weather_timezone",TimeZone.getDefault().getID());}
    void showChooser(){
        show(new AlertDialog.Builder(activity).setTitle("Make Glide yours").setMessage("Use this TV's location for local weather and scenery, or search for your city.").setPositiveButton("Use device location",(d,n)->useDevice(true)).setNeutralButton("Search city",(d,n)->search()).setNegativeButton("Later",null).create());
    }
    void onResume(){if(configured()&&"auto".equals(prefs.getString("weather_location_mode",""))&&System.currentTimeMillis()-prefs.getLong("weather_location_time",0)>21600000)useDevice(false);}
    void permissionResult(){if(permitted())useDevice(true);else unavailable(true);}
    private boolean permitted(){return activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void useDevice(boolean interactive){
        if(locating||resolving||closed)return;
        if(!permitted()){if(interactive)activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},PERMISSION);return;}
        LocationManager manager=(LocationManager)activity.getSystemService(Context.LOCATION_SERVICE);
        try{
            Location best=null;for(String provider:manager.getProviders(true)){try{Location l=manager.getLastKnownLocation(provider);if(l!=null&&(best==null||l.getTime()>best.getTime()))best=l;}catch(Exception ignored){}}
            if(best!=null&&System.currentTimeMillis()-best.getTime()<21600000){accept(best);return;}
            String provider=manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)?LocationManager.NETWORK_PROVIDER:manager.isProviderEnabled(LocationManager.GPS_PROVIDER)?LocationManager.GPS_PROVIDER:null;
            if(provider==null){unavailable(interactive);return;}
            locating=true;if(interactive)Toast.makeText(activity,"Finding your location…",Toast.LENGTH_SHORT).show();
            main.postDelayed(()->{if(locating){cancelLocation();unavailable(interactive);}},15000);
            if(Build.VERSION.SDK_INT>=30){cancellation=new CancellationSignal();manager.getCurrentLocation(provider,cancellation,activity.getMainExecutor(),l->{if(!locating||closed)return;cancelLocation();if(l!=null)accept(l);else unavailable(interactive);});}
            else{listener=new LocationListener(){public void onLocationChanged(Location l){cancelLocation();accept(l);}public void onStatusChanged(String p,int s,Bundle b){}public void onProviderEnabled(String p){}public void onProviderDisabled(String p){}};manager.requestSingleUpdate(provider,listener,Looper.getMainLooper());}
        }catch(Exception e){cancelLocation();unavailable(interactive);}
    }
    private void cancelLocation(){locating=false;if(cancellation!=null){cancellation.cancel();cancellation=null;}if(listener!=null){try{((LocationManager)activity.getSystemService(Context.LOCATION_SERVICE)).removeUpdates(listener);}catch(Exception ignored){}listener=null;}}
    private void accept(Location l){resolving=true;final int request=++selection;io.execute(()->{String label="Your location",country="";try{if(Geocoder.isPresent()){List<Address> matches=new Geocoder(activity,Locale.getDefault()).getFromLocation(l.getLatitude(),l.getLongitude(),1);if(matches!=null&&!matches.isEmpty()){Address a=matches.get(0);label=a.getLocality()!=null?a.getLocality():a.getAdminArea()!=null?a.getAdminArea():label;country=a.getCountryCode()==null?"":a.getCountryCode();}}}catch(Exception ignored){}String name=label,code=country;main.post(()->{if(request==selection){resolving=false;save(name,l.getLatitude(),l.getLongitude(),TimeZone.getDefault().getID(),code,"auto");}});});}
    private void unavailable(boolean interactive){if(interactive&&!closed)show(new AlertDialog.Builder(activity).setTitle("Choose your city").setMessage("This TV couldn't provide a location. Search for a city to set up local weather and scenery.").setPositiveButton("Search city",(d,n)->search()).setNegativeButton("Later",null).create());}
    private void search(){
        cancelLocation();resolving=false;++selection;
        EditText input=new EditText(activity);input.setSingleLine();input.setHint("City or postal code");input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        LinearLayout box=new LinearLayout(activity);int pad=(int)(24*activity.getResources().getDisplayMetrics().density);box.setPadding(pad,0,pad,0);box.addView(input,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Find your city").setView(box).setPositiveButton("Search",null).setNegativeButton("Cancel",null).create();show(dialog);input.requestFocus();
        dialog.getButton(-1).setOnClickListener(v->{String query=input.getText().toString().trim();if(query.length()<2){input.setError("Enter at least two characters");return;}dialog.getButton(-1).setEnabled(false);io.execute(()->{try{JSONObject result=HomeActivity.readJson("https://geocoding-api.open-meteo.com/v1/search?name="+java.net.URLEncoder.encode(query,"UTF-8")+"&count=10&language="+Locale.getDefault().getLanguage()+"&format=json");JSONArray locations=result.optJSONArray("results");main.post(()->{if(closed||!dialog.isShowing())return;dialog.getButton(-1).setEnabled(true);if(locations==null||locations.length()==0){input.setError("No matches. Try city and country.");return;}String[] labels=new String[locations.length()];for(int n=0;n<labels.length;n++){JSONObject item=locations.optJSONObject(n);labels[n]=item.optString("name")+" · "+item.optString("admin1","")+" · "+item.optString("country","");}dialog.dismiss();show(new AlertDialog.Builder(activity).setTitle("Choose a location").setItems(labels,(d,n)->{JSONObject item=locations.optJSONObject(n);save(item.optString("name"),item.optDouble("latitude"),item.optDouble("longitude"),item.optString("timezone",TimeZone.getDefault().getID()),item.optString("country_code",""),"manual");}).setNegativeButton("Cancel",null).create());});}catch(Exception e){main.post(()->{if(!closed&&dialog.isShowing()){dialog.getButton(-1).setEnabled(true);input.setError("Couldn't search. Check the connection and retry.");}});}});});
    }
    private void save(String city,double lat,double lon,String timezone,String country,String mode){if(closed)return;++selection;if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;prefs.edit().putString("weather_city",city).putString("weather_latitude",Double.toString(lat)).putString("weather_longitude",Double.toString(lon)).putString("weather_timezone",timezone).putString("weather_country",country).putString("weather_location_mode",mode).putLong("weather_location_time",System.currentTimeMillis()).apply();changed.run();}
    private void show(AlertDialog d){if(closed||activity.isFinishing()||activity.isDestroyed())return;d.show();if(d.getWindow()!=null){GradientDrawable bg=new GradientDrawable();bg.setColor(0xff0d1825);float scale=activity.getResources().getDisplayMetrics().density;bg.setCornerRadius(scale*25);bg.setStroke(Math.max(1,(int)scale),0xff31594f);d.getWindow().setBackgroundDrawable(bg);}for(int b:new int[]{-1,-2,-3})if(d.getButton(b)!=null)d.getButton(b).setTextColor(0xff8be7c0);}
    void close(){closed=true;cancelLocation();main.removeCallbacksAndMessages(null);io.shutdownNow();}
}
