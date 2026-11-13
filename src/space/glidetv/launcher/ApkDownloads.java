package space.glidetv.launcher;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;

/** One streamed download at a time; installation always goes through Android's confirmation. */
final class ApkDownloads {
    private final Activity activity;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final File directory;
    private Job job;
    private Future<?> task;
    private boolean closed;
    private static final class Job {String id,state="downloading",message="Resolving download…",label="",pkg="",version="";long bytes,total=-1;File file;volatile boolean cancelled;}
    ApkDownloads(Activity activity){this.activity=activity;directory=new File(activity.getCacheDir(),"apk-downloads");directory.mkdirs();File[] old=directory.listFiles();if(old!=null)for(File file:old)file.delete();}
    synchronized JSONObject start(String source)throws Exception {
        if(closed)throw new IOException("Mobile control has closed.");
        if(job!=null&&("downloading".equals(job.state)||"installing".equals(job.state)))throw new IOException("A download or installation is already in progress.");
        ApkSource.input(source);
        if(job!=null)job.file.delete();
        Job j=new Job();j.id=UUID.randomUUID().toString();j.file=new File(directory,j.id+".apk");job=j;
        task=worker.submit(()->{
            try {
                new ApkSource().download(source,j.file,(bytes,total)->{synchronized(this){if(j.cancelled)throw new CancellationException("Download cancelled.");j.bytes=bytes;j.total=total;j.message="Downloading APK…";}});
                if(j.cancelled||Thread.currentThread().isInterrupted())throw new IOException("Download cancelled.");
                PackageInfo archive=activity.getPackageManager().getPackageArchiveInfo(j.file.getAbsolutePath(),0);
                if(archive==null||archive.applicationInfo==null)throw new IOException("The downloaded file is not a valid APK. Use a direct APK link, not a web page or app bundle.");
                archive.applicationInfo.sourceDir=j.file.getAbsolutePath();archive.applicationInfo.publicSourceDir=j.file.getAbsolutePath();
                String label=archive.applicationInfo.loadLabel(activity.getPackageManager()).toString();
                synchronized(this){if(j.cancelled)throw new IOException("Download cancelled.");j.pkg=archive.packageName;j.label=label;j.version=archive.versionName==null?"":archive.versionName;j.state="ready";j.message="Ready to install on the TV.";}
            }catch(Exception e){synchronized(this){j.state="error";j.message=j.cancelled?"Download cancelled.":e.getMessage()==null?"Download failed. Please retry.":e.getMessage();}j.file.delete();}
        });
        return snapshot(j.id);
    }
    synchronized JSONObject snapshot(String id)throws Exception {
        Job j=find(id);JSONObject r=new JSONObject();r.put("id",j.id);r.put("state",j.state);r.put("message",j.message);r.put("bytes",j.bytes);r.put("total",j.total);r.put("label",j.label);r.put("package",j.pkg);r.put("version",j.version);return r;
    }
    private Job find(String id)throws IOException {if(job==null||!job.id.equals(id))throw new IOException("This download has expired. Start it again.");return job;}
    synchronized void install(String id)throws Exception {
        Job j=find(id);if(!"ready".equals(j.state)&&!"confirmation".equals(j.state))throw new IOException("Wait for the APK download to finish.");
        if(!activity.getPackageManager().canRequestPackageInstalls()){
            main.post(()->{try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}catch(Exception ignored){}});
            throw new IOException("Allow Glide TV to install apps in TV settings, then tap Install on TV again.");
        }
        j.state="installing";j.message="Opening the TV installer…";
        worker.execute(()->{
            PackageInstaller installer=activity.getPackageManager().getPackageInstaller();int sessionId=-1;boolean committed=false;
            try {
                PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(j.pkg);params.setSize(j.file.length());
                if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                sessionId=installer.createSession(params);
                try(PackageInstaller.Session session=installer.openSession(sessionId);InputStream in=new FileInputStream(j.file)){
                    try(OutputStream out=session.openWrite("base.apk",0,j.file.length())){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);session.fsync(out);}
                    Intent result=new Intent(activity,InstallResultReceiver.class).setAction("space.glidetv.launcher.INSTALL_RESULT");
                    PendingIntent callback=PendingIntent.getBroadcast(activity,sessionId,result,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
                    session.commit(callback.getIntentSender());committed=true;
                }
                synchronized(this){j.state="confirmation";j.message="Confirm installation on the TV. If dismissed, tap Install on TV to retry.";}
            }catch(Exception e){synchronized(this){j.state="ready";j.message=e.getMessage()==null?"Could not open the installer. Please retry.":e.getMessage();}}
            finally{if(sessionId>=0&&!committed)try{installer.abandonSession(sessionId);}catch(Exception ignored){}}
        });
    }
    synchronized void cancel(String id)throws Exception {Job j=find(id);if(!"downloading".equals(j.state))throw new IOException("This download is no longer running.");j.cancelled=true;if(task!=null)task.cancel(true);j.state="error";j.message="Download cancelled.";}
    synchronized void close(){closed=true;if(job!=null)job.cancelled=true;if(task!=null)task.cancel(true);worker.shutdownNow();}
}
