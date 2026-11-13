package space.glidetv.launcher;

import android.content.Context;
import android.graphics.*;
import android.media.MediaPlayer;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.FrameLayout;
import java.io.File;
import java.util.*;

/** Local aerial footage on a decoder surface, independently composed behind Home. */
public final class Atmosphere extends FrameLayout implements SurfaceHolder.Callback {
 private final File flight;
 private SurfaceView surface;
 private MediaPlayer player;
 private CityScene fallback;
 private FlightWeather weather;
 private boolean running,prepared,disposed;
 private int position,code;private boolean weatherDay=true;
 private float sunriseHour=6.4f,sunsetHour=17.7f;
 private java.util.TimeZone weatherZone=java.util.TimeZone.getDefault();private double savedWind,savedCover;private String savedSunrise="",savedSunset="";

 public Atmosphere(Context context){
  super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  flight=new File(context.getExternalFilesDir(null),"abu-dhabi-flight.mp4");
  if(!flight.isFile()||flight.length()<1024){showFallback();return;}
  setBackgroundColor(0xff0b1822);
  surface=new SurfaceView(context);surface.setFocusable(false);surface.getHolder().addCallback(this);
  addView(surface,new LayoutParams(-1,-1));
  weather=new FlightWeather(context);addView(weather,new LayoutParams(-1,-1));
 }
 private void showFallback(){
  releasePlayer();if(surface!=null){surface.getHolder().removeCallback(this);removeView(surface);surface=null;}
  if(weather!=null){weather.stop();removeView(weather);weather=null;}
  if(disposed)return;
  if(fallback==null){fallback=new CityScene(getContext());addView(fallback,new LayoutParams(-1,-1));fallback.setTimeZone(weatherZone.getID());fallback.setEnvironment(savedWind,savedCover,savedSunrise,savedSunset);fallback.setConditions(code,weatherDay,0);}
  fallback.setRunning(running);
 }
 private String isoTime(float hour){return String.format(Locale.US,"2000-01-01T%02d:%02d",(int)hour,(int)((hour%1)*60));}
 public void surfaceCreated(SurfaceHolder holder){openPlayer();}
 public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}
 public void surfaceDestroyed(SurfaceHolder holder){releasePlayer();}
 private void openPlayer(){
  if(!running||disposed||surface==null||!surface.getHolder().getSurface().isValid()||player!=null)return;
  try{
   final MediaPlayer next=new MediaPlayer();player=next;
   next.setDataSource(flight.getAbsolutePath());next.setDisplay(surface.getHolder());
   next.setVolume(0,0);next.setLooping(true);next.setScreenOnWhilePlaying(false);
   next.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
   next.setOnPreparedListener(mp->{
    if(player!=mp||disposed)return;prepared=true;
    if(position>0)mp.seekTo(position,MediaPlayer.SEEK_CLOSEST);
    if(running)mp.start();
    Log.i("GlideFlight","Prepared local aerial "+mp.getVideoWidth()+"x"+mp.getVideoHeight()+", duration="+mp.getDuration());
   });
   next.setOnErrorListener((mp,what,extra)->{Log.w("GlideFlight","Playback failed: "+what+"/"+extra);post(this::showFallback);return true;});
   next.prepareAsync();
  }catch(Exception e){Log.w("GlideFlight","Unable to open local aerial",e);showFallback();}
 }
 private void releasePlayer(){
  MediaPlayer old=player;player=null;
  if(old!=null){try{if(prepared)position=old.getCurrentPosition();}catch(Exception ignored){}old.setOnPreparedListener(null);old.setOnErrorListener(null);old.release();}
  prepared=false;
 }
 public void setRunning(boolean value){
  running=value&&!disposed;
  if(fallback!=null){fallback.setRunning(running);return;}
  if(weather!=null)weather.setRunning(running);
  if(running)openPlayer();else releasePlayer();
 }
 // Video stays on its own compositor surface; focus movement requires no pause/cache.
 public void pauseMotionFor(long ms){if(fallback!=null)fallback.pauseMotionFor(ms);}
 public void dispose(){disposed=true;setRunning(false);if(fallback!=null)fallback.dispose();if(surface!=null)surface.getHolder().removeCallback(this);}
 private float parseHour(String value,float previous){try{return Integer.parseInt(value.substring(11,13))+Integer.parseInt(value.substring(14,16))/60f;}catch(Exception e){return previous;}}
 public void setTimeZone(String id){weatherZone=java.util.TimeZone.getTimeZone(id);if(fallback!=null)fallback.setTimeZone(id);}
 public void reloadScene(){if(disposed)return;if(fallback!=null){fallback.dispose();removeView(fallback);fallback=null;}showFallback();}
 public boolean isDaytime(){if(fallback!=null)return fallback.isDaytime();if(savedSunrise.length()<16||savedSunset.length()<16)return weatherDay;Calendar now=Calendar.getInstance(weatherZone);float hour=now.get(Calendar.HOUR_OF_DAY)+now.get(Calendar.MINUTE)/60f;return hour>=sunriseHour&&hour<sunsetHour;}
 public void setEnvironment(double wind,double cover,String sunrise,String sunset){
  savedWind=wind;savedCover=cover;savedSunrise=sunrise;savedSunset=sunset;sunriseHour=parseHour(sunrise,sunriseHour);sunsetHour=parseHour(sunset,sunsetHour);
  if(fallback!=null)fallback.setEnvironment(wind,cover,sunrise,sunset);
 }
 public void setConditions(int value,boolean day,int hour){code=value;weatherDay=day;if(fallback!=null)fallback.setConditions(value,day,hour);if(weather!=null)weather.update();}

 /** Weather cues are sparse. Clear weather adds no cloud, sun or rain layer. */
 private final class FlightWeather extends View {
  final Paint paint=new Paint(3);final Handler clock=new Handler(Looper.getMainLooper());
  final float[] drops=new float[96];final long epoch=SystemClock.uptimeMillis();
  boolean active;int shade=0x33040c17;boolean rain;
  FlightWeather(Context c){super(c);setFocusable(false);Random random=new Random(71);for(int i=0;i<drops.length;i++)drops[i]=random.nextFloat();update();}
  void update(){rain=(code>=51&&code<=67)||(code>=80&&code<=82)||code>=95;shade=isDaytime()?(code>=3?0x57091520:0x33040c17):0x9c050c20;invalidate();schedule();}
  void setRunning(boolean value){active=value;schedule();}
  void schedule(){clock.removeCallbacks(frame);if(active&&rain)clock.postDelayed(frame,33);}
  void stop(){active=false;clock.removeCallbacksAndMessages(null);}
  final Runnable frame=()->{invalidate();schedule();};
  @Override protected void onDraw(Canvas canvas){
   canvas.drawColor(shade);
   if(rain){float t=(SystemClock.uptimeMillis()-epoch)/1000f;paint.setColor(0x55d9efff);paint.setStrokeWidth(1.3f);for(int i=0;i<48;i++){float x=((drops[i]+t*.008f)%1)*getWidth(),y=((drops[i+48]+t*.23f)%1)*getHeight();canvas.drawLine(x,y,x-3,y+17,paint);}}
  }
 }
}
