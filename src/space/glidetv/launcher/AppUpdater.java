package space.glidetv.launcher;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.*;

/** Quiet Home checks with explicit, user-confirmed installation from public GitHub releases. */
final class AppUpdater {
    private static final String API="https://api.github.com/repos/ghanta1996/GlideTV/releases/latest";
    private static final String DOWNLOADS="https://github.com/ghanta1996/GlideTV/releases/download/";
    private static final long MAX_APK=128L*1024*1024;
    private final Activity activity;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean busy,waitingPermission;
    private volatile boolean cancelled,closed;
    private Release pending,available;
    private long nextCheckAt;
    private Runnable availabilityChanged;
    void setAvailabilityChanged(Runnable callback){availabilityChanged=callback;}
    String availableVersion(){return available==null?null:available.name;}
    void showAvailable(){if(available==null){check();return;}try{offer(available,activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName);}catch(Exception e){error("Couldn't open update",e);}}
    private AlertDialog progress;
    private static final class Release {String name,url,sha,notes;long code,size;}
    AppUpdater(Activity activity){this.activity=activity;}

    void check(){check(true);}
    private void check(boolean interactive){
        if(busy||closed)return;
        busy=true;if(interactive)Toast.makeText(activity,"Checking for updates…",Toast.LENGTH_SHORT).show();
        worker.execute(()->{
            try{
                JSONObject release=json(API),metadata=null,apk=null;
                JSONArray assets=release.getJSONArray("assets");
                for(int n=0;n<assets.length();n++){JSONObject a=assets.getJSONObject(n);if("update.json".equals(a.optString("name")))metadata=json(assetUrl(a));}
                if(metadata==null)throw new IOException("This release does not include update information.");
                for(int n=0;n<assets.length();n++){JSONObject a=assets.getJSONObject(n);if(metadata.getString("apk").equals(a.optString("name")))apk=a;}
                if(apk==null)throw new IOException("The release APK is missing.");
                Release r=new Release();r.name=metadata.getString("versionName");r.code=metadata.getLong("versionCode");r.sha=metadata.getString("sha256");r.url=assetUrl(apk);r.size=apk.getLong("size");r.notes=release.optString("body","");
                if(!r.sha.matches("[a-fA-F0-9]{64}")||r.size<=0||r.size>MAX_APK)throw new IOException("Invalid release metadata.");
                PackageInfo current=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0);
                long installed=versionCode(current);
                ui.post(()->{busy=false;if(closed)return;nextCheckAt=SystemClock.elapsedRealtime()+6L*60*60*1000;available=r.code>installed?r:null;if(availabilityChanged!=null)availabilityChanged.run();if(interactive){if(available==null)show(new AlertDialog.Builder(activity).setTitle("You're up to date").setMessage("Glide TV "+current.versionName+" is installed.").setPositiveButton("Done",null).create());else offer(r,current.versionName);}});
            }catch(Exception e){ui.post(()->{busy=false;nextCheckAt=SystemClock.elapsedRealtime()+15L*60*1000;if(interactive)error("Couldn't check for updates",e);});}
        });
    }
    private void offer(Release r,String current){
        String notes=r.notes.length()>600?r.notes.substring(0,600)+"…":r.notes;
        show(new AlertDialog.Builder(activity).setTitle("Glide TV "+r.name+" is available").setMessage("Installed: "+current+" · Download: "+String.format(Locale.US,"%.1f MB",r.size/1048576d)+"\n\n"+notes).setPositiveButton("Download & install",(d,n)->install(r)).setNegativeButton("Later",null).create());
    }
    private void install(Release r){
        if(busy)return;
        if(!activity.getPackageManager().canRequestPackageInstalls()){
            show(new AlertDialog.Builder(activity).setTitle("Allow Glide updates").setMessage("Allow Glide TV to install apps in Android settings. Return here to continue the update.").setPositiveButton("Open settings",(d,n)->{pending=r;waitingPermission=true;try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}catch(Exception e){waitingPermission=false;error("Couldn't open settings",e);}}).setNegativeButton("Cancel",null).create());return;
        }
        busy=true;cancelled=false;
        progress=new AlertDialog.Builder(activity).setTitle("Downloading Glide TV "+r.name).setMessage("Starting download…").setNegativeButton("Cancel",(d,n)->cancelled=true).create();progress.setCanceledOnTouchOutside(false);progress.setOnCancelListener(d->cancelled=true);show(progress);
        worker.execute(()->downloadAndInstall(r));
    }
    void onResume(){if(waitingPermission){waitingPermission=false;if(pending!=null&&activity.getPackageManager().canRequestPackageInstalls())install(pending);}checkIfDue();}
    void checkIfDue(){if(!busy&&SystemClock.elapsedRealtime()>=nextCheckAt)check(false);}
    private void downloadAndInstall(Release r){
        File file=new File(activity.getCacheDir(),"glide-update.apk");HttpURLConnection connection=null;int sessionId=-1;boolean committed=false;
        PackageInstaller installer=activity.getPackageManager().getPackageInstaller();
        try{
            connection=connect(r.url);MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0,lastProgress=0;
            try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(file)){
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1){if(cancelled||closed)throw new IOException("Download cancelled.");total+=n;if(total>MAX_APK||total>r.size)throw new IOException("Unexpected APK size.");out.write(buffer,0,n);digest.update(buffer,0,n);long now=SystemClock.uptimeMillis();if(now-lastProgress>300){lastProgress=now;int percent=(int)(100*total/r.size);ui.post(()->{if(progress!=null&&progress.isShowing())progress.setMessage(percent+"% downloaded");});}}
            }
            if(total!=r.size||!hex(digest.digest()).equalsIgnoreCase(r.sha))throw new IOException("Download verification failed. Please retry.");
            PackageInfo archive=activity.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),0);
            if(archive==null||!activity.getPackageName().equals(archive.packageName)||versionCode(archive)!=r.code)throw new IOException("The APK does not match this Glide release.");
            if(cancelled||closed)throw new IOException("Download cancelled.");
            sessionId=installer.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
            try(PackageInstaller.Session session=installer.openSession(sessionId);InputStream in=new FileInputStream(file)){
                try(OutputStream out=session.openWrite("base.apk",0,file.length())){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);session.fsync(out);}
                if(cancelled||closed)throw new IOException("Download cancelled.");
                Intent result=new Intent(activity,InstallResultReceiver.class).setAction("space.glidetv.launcher.UPDATE_RESULT");
                PendingIntent callback=PendingIntent.getBroadcast(activity,sessionId,result,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
                session.commit(callback.getIntentSender());committed=true;
            }
            ui.post(()->{finishProgress();if(!closed)Toast.makeText(activity,"Confirm the update on the TV",Toast.LENGTH_LONG).show();});
        }catch(Exception e){ui.post(()->{finishProgress();if(!cancelled)error("Update couldn't be installed",e);});}
        finally{if(connection!=null)connection.disconnect();file.delete();if(sessionId>=0&&!committed)try{installer.abandonSession(sessionId);}catch(Exception ignored){}}
    }
    private static long versionCode(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
    private String assetUrl(JSONObject asset)throws Exception{String url=asset.getString("browser_download_url");if(!url.startsWith(DOWNLOADS))throw new IOException("Unexpected release download address.");return url;}
    private HttpURLConnection connect(String url)throws IOException{
        if(!url.startsWith("https://"))throw new IOException("Update downloads require HTTPS.");
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","GlideTV-Updater");int status=c.getResponseCode();
        if(status!=200){c.disconnect();throw new IOException(status==404?"No public release is available yet.":"GitHub returned HTTP "+status+". Please try again later.");}return c;
    }
    private JSONObject json(String url)throws Exception{HttpURLConnection c=connect(url);try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>1048576)throw new IOException("Update information is too large.");out.write(buffer,0,n);}return new JSONObject(out.toString("UTF-8"));}finally{c.disconnect();}}
    private void finishProgress(){busy=false;if(progress!=null){progress.dismiss();progress=null;}}
    private void error(String title,Exception e){if(!closed)show(new AlertDialog.Builder(activity).setTitle(title).setMessage(e.getMessage()==null?"Please try again later.":e.getMessage()).setPositiveButton("Done",null).create());}
    private void show(AlertDialog dialog){if(closed||activity.isFinishing()||activity.isDestroyed())return;dialog.show();if(dialog.getWindow()!=null){GradientDrawable glass=new GradientDrawable();glass.setColor(0xff0d1825);float scale=activity.getResources().getDisplayMetrics().density;glass.setCornerRadius(25*scale);glass.setStroke(Math.max(1,(int)scale),0xff31594f);dialog.getWindow().setBackgroundDrawable(glass);}for(int button:new int[]{-1,-2,-3})if(dialog.getButton(button)!=null)dialog.getButton(button).setTextColor(0xff8be7c0);}
    void close(){closed=true;cancelled=true;worker.shutdownNow();ui.removeCallbacksAndMessages(null);if(progress!=null)progress.dismiss();}
}
