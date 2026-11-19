package space.glidetv.launcher;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

public final class AmbientMusicActivity extends Activity {
    private MusicSessions sessions;
    private ImageView backdrop,cover;
    private TextView status,title,subtitle,app;
    private PlayingIndicator indicator;
    private boolean fixture;

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setIncludeFontPadding(false);return view;}
    private void marquee(TextView view){view.setSingleLine(true);view.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);view.setMarqueeRepeatLimit(-1);view.setSelected(true);}
    private void gap(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);fixture=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("visual_music_fixture",false);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff080d19);
        backdrop=new ImageView(this);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);backdrop.setAlpha(.28f);root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xf0080d19,0xb0080d19,0xe0080d19});shade.setBackground(gradient);root.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=new LinearLayout(this);content.setGravity(Gravity.CENTER_VERTICAL);content.setPadding(dp(82),dp(55),dp(82),dp(55));
        cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackgroundColor(0xff182335);cover.setClipToOutline(true);cover.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,android.graphics.Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),dp(24));}});cover.setElevation(dp(12));content.addView(cover,new LinearLayout.LayoutParams(dp(390),dp(390)));
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);words.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams wordsLp=new LinearLayout.LayoutParams(0,-1,1);wordsLp.setMarginStart(dp(58));content.addView(words,wordsLp);
        LinearLayout live=new LinearLayout(this);live.setGravity(Gravity.CENTER_VERTICAL);indicator=new PlayingIndicator(this);live.addView(indicator,new LinearLayout.LayoutParams(dp(22),dp(20)));status=text("PLAYING NOW",13,0xffbee9cf);status.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));LinearLayout.LayoutParams statusLp=new LinearLayout.LayoutParams(-2,-2);statusLp.setMarginStart(dp(12));live.addView(status,statusLp);words.addView(live);gap(words,20);
        title=text("Nothing is playing",40,Color.WHITE);title.setTypeface(android.graphics.Typeface.create("sans-serif-light",0));marquee(title);words.addView(title,new LinearLayout.LayoutParams(-1,-2));gap(words,18);
        subtitle=text("",21,0xffd2deea);marquee(subtitle);words.addView(subtitle,new LinearLayout.LayoutParams(-1,-2));gap(words,14);
        app=text("",14,0xff91a7ba);marquee(app);words.addView(app,new LinearLayout.LayoutParams(-1,-2));gap(words,40);
        TextView hint=text("Press Back to return Home",12,0xff8297aa);words.addView(hint);
        root.addView(content,new FrameLayout.LayoutParams(-1,-1));setContentView(root);sessions=new MusicSessions(this,this::showMusic);
    }

    @Override protected void onResume(){super.onResume();if(fixture)showMusic(fixtureTrack());else sessions.start();}
    @Override protected void onPause(){super.onPause();if(!fixture)sessions.stop();}
    @Override protected void onDestroy(){super.onDestroy();sessions.close();}

    private void showMusic(MusicSessions.Track track){
        if(track==null){status.setText("WAITING FOR MUSIC");indicator.setPlaying(false);title.setText("Nothing is playing");subtitle.setText("Start playback in a supported app");app.setText("");cover.setImageDrawable(null);backdrop.setImageDrawable(null);return;}
        boolean playing=track.state==MusicSelection.PLAYING;status.setText(playing?"PLAYING NOW":"PLAYBACK PAUSED");indicator.setPlaying(playing);title.setText(track.title);subtitle.setText(track.subtitle);app.setText(track.app);cover.setImageBitmap(track.artwork);backdrop.setImageBitmap(track.artwork);getWindow().getDecorView().setContentDescription((playing?"Playing ":"Paused ")+track.title+(track.subtitle.isEmpty()?"":" by "+track.subtitle));
    }

    private MusicSessions.Track fixtureTrack(){
        Bitmap art=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(art);Paint paint=new Paint(3);paint.setShader(new LinearGradient(0,0,512,512,0xffe35376,0xff443bd7,Shader.TileMode.CLAMP));canvas.drawRect(0,0,512,512,paint);paint.setShader(null);paint.setColor(0xe8ffffff);canvas.drawCircle(256,256,130,paint);paint.setColor(0xff241d52);canvas.drawCircle(256,256,36,paint);return new MusicSessions.Track("Midnight Drive Across the City Lights","Glide Sessions  ·  After Hours","Demo player",MusicSelection.PLAYING,art);
    }
}
