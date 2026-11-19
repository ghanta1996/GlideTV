package space.glidetv.launcher;

import android.content.*;
import android.graphics.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.LruCache;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Reads active media metadata locally while Home is visible. It never exposes playback controls. */
public final class MusicSessions implements AutoCloseable {
    public interface Listener { void onMusicChanged(Track track); }
    public static final class Track {
        public final String title,subtitle,app;
        public final Bitmap artwork;
        Track(String title,String subtitle,String app,Bitmap artwork){this.title=title;this.subtitle=subtitle;this.app=app;this.artwork=artwork;}
    }

    private static final int ART_EDGE=256, ART_BYTES=2*1024*1024, INPUT_BYTES=4*1024*1024;
    private final Context context;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService artwork=Executors.newSingleThreadExecutor();
    private final MediaSessionManager sessions;
    private final ComponentName accessComponent;
    private final ArrayList<MediaController> controllers=new ArrayList<>();
    private final IdentityHashMap<MediaController,MediaController.Callback> callbacks=new IdentityHashMap<>();
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(ART_BYTES){protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount();}};
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionChanges=this::replaceControllers;
    private boolean wanted,started,closed;
    private int generation;
    private final Runnable retry=this::connect;

    public MusicSessions(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        sessions=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        accessComponent=new ComponentName(context,MusicListenerService.class);
    }

    public static boolean accessGranted(Context context){
        String enabled=Settings.Secure.getString(context.getContentResolver(),"enabled_notification_listeners");
        if(enabled==null||enabled.isEmpty())return false;
        ComponentName expected=new ComponentName(context,MusicListenerService.class);
        for(String value:enabled.split(":")){ComponentName actual=ComponentName.unflattenFromString(value);if(expected.equals(actual))return true;}
        return false;
    }

    public void start(){
        wanted=true;connect();
    }

    private void connect(){
        if(closed||!wanted)return;
        handler.removeCallbacks(retry);
        if(!accessGranted(context)){stopInternal();listener.onMusicChanged(null);return;}
        if(started){refresh();return;}
        started=true;
        try{sessions.addOnActiveSessionsChangedListener(sessionChanges,accessComponent,handler);replaceControllers(sessions.getActiveSessions(accessComponent));}
        catch(SecurityException|IllegalStateException e){stopInternal();listener.onMusicChanged(null);if(wanted)handler.postDelayed(retry,1000);}
    }

    public void stop(){
        wanted=false;handler.removeCallbacks(retry);stopInternal();
    }

    private void stopInternal(){
        generation++;
        if(started){try{sessions.removeOnActiveSessionsChangedListener(sessionChanges);}catch(Exception ignored){}}
        started=false;
        for(Map.Entry<MediaController,MediaController.Callback> entry:callbacks.entrySet())try{entry.getKey().unregisterCallback(entry.getValue());}catch(Exception ignored){}
        callbacks.clear();controllers.clear();
    }

    private void replaceControllers(List<MediaController> active){
        if(!started)return;
        for(Map.Entry<MediaController,MediaController.Callback> entry:callbacks.entrySet())try{entry.getKey().unregisterCallback(entry.getValue());}catch(Exception ignored){}
        callbacks.clear();controllers.clear();
        if(active!=null)for(MediaController controller:active){
            MediaController.Callback callback=new MediaController.Callback(){
                @Override public void onMetadataChanged(MediaMetadata metadata){refresh();}
                @Override public void onPlaybackStateChanged(PlaybackState state){refresh();}
                @Override public void onSessionDestroyed(){reload();}
            };
            try{controller.registerCallback(callback,handler);controllers.add(controller);callbacks.put(controller,callback);}catch(Exception ignored){}
        }
        refresh();
    }

    private void reload(){if(!started)return;try{replaceControllers(sessions.getActiveSessions(accessComponent));}catch(Exception ignored){listener.onMusicChanged(null);}}

    private void refresh(){
        if(!started)return;
        ArrayList<MusicSelection.Candidate> candidates=new ArrayList<>();
        HashMap<String,MediaController> owners=new HashMap<>();
        for(MediaController controller:controllers){
            MediaMetadata metadata=controller.getMetadata();PlaybackState playback=controller.getPlaybackState();
            if(metadata==null||playback==null)continue;
            String key=controller.getPackageName()+":"+System.identityHashCode(controller),title=first(metadata,MediaMetadata.METADATA_KEY_DISPLAY_TITLE,MediaMetadata.METADATA_KEY_TITLE),app=appLabel(controller.getPackageName());
            MusicSelection.Candidate candidate=new MusicSelection.Candidate(key,title,first(metadata,MediaMetadata.METADATA_KEY_ARTIST,MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),app,playback.getState(),playback.getLastPositionUpdateTime());
            candidates.add(candidate);owners.put(key,controller);
        }
        MusicSelection.Candidate selected=MusicSelection.choose(candidates);
        if(selected==null){generation++;listener.onMusicChanged(null);return;}
        MediaController controller=owners.get(selected.key);MediaMetadata metadata=controller==null?null:controller.getMetadata();
        if(metadata==null){listener.onMusicChanged(null);return;}
        String subtitle=MusicSelection.subtitle(selected.artist,selected.album,selected.app),uri=first(metadata,MediaMetadata.METADATA_KEY_ALBUM_ART_URI,MediaMetadata.METADATA_KEY_ART_URI,MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),key=selected.key+"\n"+selected.title+"\n"+subtitle+"\n"+uri;
        Bitmap cached=cache.get(key);if(cached!=null){listener.onMusicChanged(new Track(selected.title,subtitle,selected.app,cached));return;}
        Bitmap embedded=firstBitmap(metadata,MediaMetadata.METADATA_KEY_ALBUM_ART,MediaMetadata.METADATA_KEY_ART,MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if(embedded==null&&uri.isEmpty()){generation++;listener.onMusicChanged(new Track(selected.title,subtitle,selected.app,null));return;}
        final int request=++generation;
        listener.onMusicChanged(new Track(selected.title,subtitle,selected.app,null));
        try{artwork.execute(()->{
            Bitmap bitmap=embedded==null?loadUri(uri):bounded(embedded);
            handler.post(()->{if(closed||!started||request!=generation)return;if(bitmap!=null)cache.put(key,bitmap);listener.onMusicChanged(new Track(selected.title,subtitle,selected.app,bitmap));});
        });}catch(RejectedExecutionException ignored){}
    }

    private String appLabel(String packageName){try{return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(packageName,0)).toString();}catch(Exception e){return packageName;}}
    private static String first(MediaMetadata metadata,String... keys){for(String key:keys){String value=metadata.getString(key);if(value!=null&&!value.trim().isEmpty())return value.trim();}return "";}
    private static Bitmap firstBitmap(MediaMetadata metadata,String... keys){for(String key:keys){Bitmap value=metadata.getBitmap(key);if(value!=null)return value;}return null;}

    private Bitmap loadUri(String value){
        try{
            Uri uri=Uri.parse(value);String scheme=uri.getScheme();if(!"content".equals(scheme)&&!"android.resource".equals(scheme))return null;
            byte[] bytes;try(InputStream input=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream output=new ByteArrayOutputStream()){
                if(input==null)return null;byte[] buffer=new byte[32768];int total=0,read;
                while((read=input.read(buffer))!=-1){total+=read;if(total>INPUT_BYTES)return null;output.write(buffer,0,read);}bytes=output.toByteArray();
            }
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample>ART_EDGE*2)sample*=2;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;return bounded(BitmapFactory.decodeByteArray(bytes,0,bytes.length,options));
        }catch(Exception ignored){return null;}
    }

    private static Bitmap bounded(Bitmap source){
        if(source==null)return null;int width=source.getWidth(),height=source.getHeight();if(width<=0||height<=0)return null;
        float scale=Math.min(1f,ART_EDGE/(float)Math.max(width,height));int targetWidth=Math.max(1,Math.round(width*scale)),targetHeight=Math.max(1,Math.round(height*scale));
        try{return scale<1f?Bitmap.createScaledBitmap(source,targetWidth,targetHeight,true):source.copy(Bitmap.Config.ARGB_8888,false);}catch(Exception ignored){return null;}
    }

    @Override public void close(){if(closed)return;closed=true;stop();artwork.shutdownNow();cache.evictAll();}
}
