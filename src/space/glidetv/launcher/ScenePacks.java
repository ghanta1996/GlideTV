package space.glidetv.launcher;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Downloadable, checksummed artwork. Each pack version is cached independently. */
final class ScenePacks {
    private static final String CATALOG="https://raw.githubusercontent.com/ghanta1996/GlideTV/main/scenes/index.json";
    private static final String PREFIX="https://github.com/ghanta1996/GlideTV/releases/download/";
    private final Context context;
    private final android.content.SharedPreferences prefs;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile int generation;
    private volatile boolean closed;
    ScenePacks(Context context){this.context=context;prefs=context.getSharedPreferences("home",0);}
    void ensure(double lat,double lon,boolean force,Runnable ready){
        String location=String.format(Locale.US,"%.4f,%.4f",lat,lon);
        if(!force&&location.equals(prefs.getString("scene_location",""))&&new File(prefs.getString("scene_path",""),"pack.json").isFile()&&System.currentTimeMillis()-prefs.getLong("scene_checked",0)<86400000)return;
        final int run=++generation;
        io.execute(()->{
            File temp=null;
            try{
                JSONObject catalog=HomeActivity.readJson(CATALOG);if(catalog.optInt("schemaVersion")!=1)throw new IOException("Unsupported scene catalogue");
                JSONArray packs=catalog.getJSONArray("packs");JSONObject selected=null;double nearest=Double.MAX_VALUE;
                for(int n=0;n<packs.length();n++){JSONObject p=packs.getJSONObject(n);if(p.optBoolean("fallback"))if(selected==null)selected=p;JSONObject match=p.optJSONObject("location");if(match!=null){double distance=distanceKm(lat,lon,match.getDouble("latitude"),match.getDouble("longitude"));if(distance<=match.getDouble("radiusKm")&&distance<nearest){selected=p;nearest=distance;}}}
                if(selected==null)throw new IOException("No matching scene pack");
                String id=selected.getString("id"),version=selected.getString("version"),sha=selected.getString("sha256"),url=selected.getString("url");
                if(!id.matches("[a-z0-9-]+")||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")||!sha.matches("[a-f0-9]{64}")||!url.startsWith(PREFIX))throw new IOException("Invalid scene metadata");
                long size=selected.getLong("size");if(size<=0||size>32L*1024*1024)throw new IOException("Scene download is too large");
                File root=new File(context.getFilesDir(),"scenes");root.mkdirs();File target=new File(root,id+"-"+version+"-"+sha.substring(0,12));
                if(!new File(target,"pack.json").isFile()){
                    temp=File.createTempFile("scene-",".zip",context.getCacheDir());MessageDigest hash=MessageDigest.getInstance("SHA-256");HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);
                    try{if(c.getResponseCode()!=200)throw new IOException("Scene download unavailable");try(InputStream input=c.getInputStream();OutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[65536];int n;long total=0;while((n=input.read(buffer))!=-1){if(closed||run!=generation)throw new IOException("Scene selection changed");total+=n;if(total>size)throw new IOException("Unexpected scene size");hash.update(buffer,0,n);out.write(buffer,0,n);}if(total!=size)throw new IOException("Incomplete scene download");}}finally{c.disconnect();}
                    if(!hex(hash.digest()).equals(sha))throw new IOException("Scene checksum mismatch");
                    File staging=new File(root,"pending-"+UUID.randomUUID());staging.mkdirs();
                    try{extract(temp,staging);JSONObject info=readPack(staging);if(!id.equals(info.getString("id"))||!version.equals(info.getString("version")))throw new IOException("Pack identity mismatch");if(!new File(staging,"cloud.png").isFile())throw new IOException("Pack cloud asset is missing");if("city".equals(info.optString("kind"))&&(!new File(staging,"city.png").isFile()||!new File(staging,"city-night.png").isFile()))throw new IOException("Pack city assets are missing");if(!staging.renameTo(target))throw new IOException("Couldn't cache the scene pack");}finally{deleteTree(staging);}
                }
                JSONObject info=readPack(target);String kind=info.getString("kind");if(!kind.equals("city")&&!kind.equals("landscape"))throw new IOException("Unsupported scenery type");
                if(closed||run!=generation)return;
                prefs.edit().putString("scene_path",target.getAbsolutePath()).putString("scene_id",id).putString("scene_version",version).putString("scene_kind",kind).putString("scene_location",location).putLong("scene_checked",System.currentTimeMillis()).apply();
                File[] cached=root.listFiles();if(cached!=null)for(File stale:cached){if(closed||run!=generation)break;if(stale.isDirectory()&&!stale.equals(target))deleteTree(stale);}
                ui.post(()->{if(!closed&&run==generation)ready.run();});
            }catch(Exception e){android.util.Log.w("GlideScenes","Keeping cached scenery: "+e.getMessage());}
            finally{if(temp!=null)temp.delete();}
        });
    }
    static double distanceKm(double lat,double lon,double otherLat,double otherLon){double a=Math.toRadians(otherLat-lat),b=Math.toRadians(otherLon-lon);double h=Math.sin(a/2)*Math.sin(a/2)+Math.cos(Math.toRadians(lat))*Math.cos(Math.toRadians(otherLat))*Math.sin(b/2)*Math.sin(b/2);return 6371*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private static JSONObject readPack(File dir)throws Exception{File f=new File(dir,"pack.json");if(f.length()>8192)throw new IOException("Invalid pack information");try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString("UTF-8"));}}
    private static void extract(File zip,File target)throws Exception{
        Set<String> allowed=new HashSet<>(Arrays.asList("pack.json","city.png","city-night.png","cloud.png")),seen=new HashSet<>();long expanded=0;
        try(ZipInputStream input=new ZipInputStream(new FileInputStream(zip))){ZipEntry entry;byte[] b=new byte[65536];while((entry=input.getNextEntry())!=null){String name=entry.getName();if(!allowed.contains(name)||!seen.add(name))throw new IOException("Unexpected pack entry");File file=new File(target,name);try(OutputStream out=new FileOutputStream(file)){int n;while((n=input.read(b))!=-1){expanded+=n;if(expanded>80L*1024*1024)throw new IOException("Expanded pack is too large");out.write(b,0,n);}}if(name.endsWith(".png")){BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(options.outWidth<=0||options.outHeight<=0||options.outWidth>8192||options.outHeight>4096||(long)options.outWidth*options.outHeight>12000000)throw new IOException("Invalid scene image");}}}
    }
    private static void deleteTree(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);}file.delete();}
    void close(){closed=true;++generation;io.shutdownNow();ui.removeCallbacksAndMessages(null);}
}
