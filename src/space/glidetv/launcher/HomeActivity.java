package space.glidetv.launcher;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Local, dependency-free TV home. Artwork and weather are fetched only while needed. */
public final class HomeActivity extends Activity implements RemoteControlServer.Host {
    private final int ink=Color.rgb(238,244,242), muted=Color.rgb(148,166,175), mint=Color.rgb(190,233,207);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<App> apps=new ArrayList<>();
    private final ArrayList<String> favorites=new ArrayList<>();
    private final HashMap<String,Drawable> icons=new HashMap<>();
    private LinearLayout page, favoriteRow, library, watchRow, watchSection;
    private final java.util.concurrent.ExecutorService artwork=Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String,Bitmap> artCache=new android.util.LruCache<String,Bitmap>(2*1024*1024){protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
    private volatile int artworkGeneration;
    private final android.database.ContentObserver watchChanges=new android.database.ContentObserver(handler){public void onChange(boolean self){if(active)loadWatchNext();}};
    private int watchGeneration=0;
    private HorizontalScrollView appStrip, watchStrip;
    private FrameLayout watchBubble, dockHost;
    private SpeechBubble watchBalloon;
    private float tailCentre;
    private android.animation.ValueAnimator dockMagnify;
    private int dockCentre=-1;
    private UsageMeters meters;
    private ConnectivityStatus connectivity;
    private RemoteControlServer mobileControl;
    private AppUpdater appUpdater;
    private TextView updateNotice;
    private LinearLayout musicCard;
    private ImageView musicArt;
    private TextView musicTitle,musicSubtitle;
    private MusicSessions musicSessions;
    private MusicSessions.Track currentMusic;
    private WeatherLocation weatherLocation;private ScenePacks scenePacks;private boolean locationPromptScheduled;
    private View remoteHit, libraryButton, settingsButton;
    private RemoteGlyph remoteGlyph;
    private TextView remoteLabel;
    private boolean remoteActive;
    private Atmosphere backdrop;
    private ReadabilityOverlay readabilityOverlay;
    private int weatherCode=0;
    private boolean weatherIsDay=true, visualNightFixture;
    private TextView weather, weatherDetail, weatherSymbol, weatherStats, weatherRain;
    private String selectedPkg, watchFingerprint="", renderedWatchKey="";
    private final ArrayList<WatchHolder> watchPool=new ArrayList<>();
    private final ArrayList<WatchItem> watchItems=new ArrayList<>();
    private AlertDialog libraryDialog;
    private AlertDialog mobileDialog;
    private String weatherCity="Your location";
    private long weatherFetched;
    private final Runnable selectWatch=()->renderSelectedWatch();
    /** Continue Watching stays out of the way until Home has been on screen for a moment. */
    private static final long WATCH_DWELL=3000;
    private final Runnable revealWatch=new Runnable(){public void run(){
        if(watchSection==null||watchRevealed||!active)return;watchRevealed=true;
        View focus=favoriteRow==null?null:favoriteRow.findFocus();if(focus==null)return;
        pointTail(focus);bubbleIn();
    }};
    /** Pops the thought out of the icon it belongs to. */
    void bubbleIn(){
        if(watchSection==null||!watchRevealed||!watchHasItems)return;
        if(watchSection.getVisibility()==View.VISIBLE&&watchSection.getAlpha()>.99f)return;
        watchSection.animate().cancel();watchSection.setVisibility(View.VISIBLE);
        watchSection.setPivotX(tailCentre);watchSection.setPivotY(watchSection.getHeight());
        watchSection.setScaleX(.84f);watchSection.setScaleY(.84f);watchSection.setTranslationY(dp(16));
        watchSection.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setStartDelay(130).setDuration(540)
            .setInterpolator(new android.view.animation.PathInterpolator(.22f,1.2f,.36f,1f)).start();
    }
    void bubbleOut(){
        if(watchSection==null||watchSection.getVisibility()!=View.VISIBLE)return;
        watchSection.animate().cancel();watchSection.setPivotX(tailCentre);watchSection.setPivotY(watchSection.getHeight());
        watchSection.animate().alpha(0f).scaleX(.84f).scaleY(.84f).setStartDelay(0).setDuration(330).withEndAction(()->watchSection.setVisibility(View.INVISIBLE)).start();
    }
    void hideWatch(){if(watchSection==null)return;watchRevealed=false;watchSection.animate().cancel();watchSection.setAlpha(0f);watchSection.setScaleX(1f);watchSection.setScaleY(1f);watchSection.setTranslationY(0);watchSection.setVisibility(View.INVISIBLE);}
    private TextView clock, date;
    private View firstApp;
    private boolean loaded=false, active=false, loading=false, watchRevealed=false, watchHasItems=false;
    private String restoreFocus, movingPkg;
    private ArrayList<String> moveBackup;
    private Runnable pendingSwap;
    private boolean consumeCenterUp;
    private android.content.SharedPreferences prefs;
    private static final String STOCK="com.google.android.apps.tv.launcherx";
    private static final int CARD_W=174, CARD_H=102, CARD_GAP=13, TITLE_GAP=7, TITLE_LINE=14,
            STRIP_PAD=5, BUBBLE_PAD_V=21, BUBBLE_PAD_H=20;
    /** Strip height follows the card: artwork, the gap, then one line of title. */
    private int stripHeight(){return CARD_H+TITLE_GAP+TITLE_LINE+STRIP_PAD*2;}
    /**
     * The balloon is a stadium, so its ends curve away over the cards' top corners. Side padding
     * has to clear that curve or the first and last card poke out of the glass.
     */
    private int bubbleSidePad(){
        float r=(stripHeight()+BUBBLE_PAD_V*2)*.5f, dy=r-BUBBLE_PAD_V-STRIP_PAD;
        return Math.max(BUBBLE_PAD_H,(int)Math.ceil(r-Math.sqrt(Math.max(0,r*r-dy*dy)))+2);
    }
    private static class App {
        String pkg,label; Intent intent; Drawable icon; int accent;
        App(String p,String l,Intent i,Drawable d,int c){pkg=p;label=l;intent=i;icon=d;accent=c;}
    }
    private final Runnable ticker=new Runnable(){public void run(){
        if(clock!=null){Date now=new Date();clock.setText(localTime("h:mm",now));date.setText(localTime("EEEE, d MMMM",now));}
        if(active){if(appUpdater!=null)appUpdater.checkIfDue();if(backdrop!=null){backdrop.setConditions(weatherCode,visualNightFixture?false:weatherIsDay,visualNightFixture?23:Calendar.getInstance().get(Calendar.HOUR_OF_DAY));readabilityOverlay.setSceneLuminance(backdrop.readabilityLuminance());if(weatherCode==0&&weatherSymbol!=null){boolean day=backdrop.isDaytime();weatherSymbol.setText(day?"☀":"☾");weatherSymbol.setTextColor(day?0xffffd18a:0xffd9cbff);}}refreshWeather();handler.postDelayed(this,60000-System.currentTimeMillis()%60000);}
    }};
    private final BroadcastReceiver changes=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(active)loadApps();else loaded=false;}};
    @Override public void onCreate(Bundle b){
        super.onCreate(b);prefs=getSharedPreferences("home",MODE_PRIVATE);weatherLocation=new WeatherLocation(this,this::locationChanged);scenePacks=new ScenePacks(this);visualNightFixture=(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("visual_night_fixture",false);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        String saved=prefs.getString("favorites",null);
        if(saved!=null){for(String p:saved.split(","))if(!p.isEmpty())favorites.add(p);}

        buildShell();musicSessions=new MusicSessions(this,this::showMusic);
        appUpdater=new AppUpdater(this);appUpdater.setAvailabilityChanged(()->{String version=appUpdater.availableVersion();updateNotice.setText(version==null?"":"↑  Glide "+version+" available");updateNotice.setVisibility(version==null?View.GONE:View.VISIBLE);});
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_PACKAGE_ADDED);filter.addAction(Intent.ACTION_PACKAGE_REMOVED);filter.addAction(Intent.ACTION_PACKAGE_CHANGED);filter.addDataScheme("package");registerReceiver(changes,filter,Context.RECEIVER_EXPORTED);
        getContentResolver().registerContentObserver(android.net.Uri.parse("content://android.media.tv/watch_next_program"),true,watchChanges);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((lost,gained)->{if(!isInside(gained,favoriteRow)&&!isInside(gained,watchRow))bubbleOut();});
        loadApps();
    }
    @Override public void onResume(){super.onResume();active=true;if(appUpdater!=null)appUpdater.onResume();weatherLocation.onResume();handler.removeCallbacks(ticker);ticker.run();meters.start();connectivity.start();if(weatherLocation.configured()&&!prefs.getBoolean("connection_permissions_prompted",false)){prefs.edit().putBoolean("connection_permissions_prompted",true).apply();connectivity.requestInitialStatusPermissions(43);}backdrop.setRunning(true);if(!loaded)loadApps();hideWatch();handler.removeCallbacks(revealWatch);handler.postDelayed(revealWatch,WATCH_DWELL);loadWatchNext();refreshWeather();maybeSetupLocation();if(visualMusicFixtureEnabled())showMusic(musicFixture());else musicSessions.start();}
    @Override public void onPause(){super.onPause();active=false;handler.removeCallbacks(revealWatch);hideWatch();if(dockMagnify!=null)dockMagnify.end();handler.removeCallbacks(ticker);meters.stop();connectivity.stop();backdrop.setRunning(false);musicSessions.stop();showMusic(null);}
    @Override public void onDestroy(){super.onDestroy();artworkGeneration++;if(appUpdater!=null)appUpdater.close();musicSessions.close();weatherLocation.close();scenePacks.close();handler.removeCallbacksAndMessages(null);unregisterReceiver(changes);backdrop.dispose();getContentResolver().unregisterContentObserver(watchChanges);if(mobileControl!=null)mobileControl.stop();worker.shutdownNow();artwork.shutdownNow();artCache.evictAll();icons.clear();}
    @Override public void onBackPressed(){if(movingPkg!=null){finishMove(false);return;}if(firstApp!=null)firstApp.requestFocus();}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);if(firstApp!=null)firstApp.requestFocus();}
    String localTime(String pattern,Date now){SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.getDefault());f.setTimeZone(TimeZone.getTimeZone(weatherLocation.timezone()));return f.format(now);}
    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");t.setIncludeFontPadding(false);return t;}
    GradientDrawable shape(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(13));if(stroke!=0)d.setStroke(dp(2),stroke);return d;}
    void gap(LinearLayout p,int h){p.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h)));}
    void styleFocus(View v,int normal, int focused){
        v.setFocusable(true);v.setClickable(true);v.setBackground(new GlassDrawable(dp(18),false));
        v.setOnFocusChangeListener((view,has)->{
            view.setBackground(new GlassDrawable(dp(18),has));
            view.animate().cancel();view.animate().scaleX(has?1.035f:1f).scaleY(has?1.035f:1f).translationZ(dp(has?4:0)).setDuration(190).setInterpolator(new android.view.animation.PathInterpolator(.2f,.8f,.2f,1f)).start();
        });
    }
    TextView button(String name,Runnable action){TextView b=text(name,13,ink);b.setGravity(Gravity.CENTER);b.setPadding(dp(17),0,dp(17),0);styleFocus(b,0xff192934,0xff304840);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(32));lp.setMarginStart(dp(8));b.setLayoutParams(lp);return b;}
    View remoteShortcut(){LinearLayout hit=row();remoteHit=hit;hit.setGravity(Gravity.CENTER);hit.setPadding(dp(9),0,dp(9),0);hit.setBackground(new GlassDrawable(dp(18),false));RemoteGlyph glyph=new RemoteGlyph(this);remoteGlyph=glyph;hit.addView(glyph,new LinearLayout.LayoutParams(dp(21),dp(27)));TextView label=text("Remote",12,0xffdce8f1);remoteLabel=label;label.setSingleLine(true);label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMarginStart(dp(6));hit.addView(label,lp);hit.setFocusable(true);hit.setClickable(true);hit.setContentDescription("Glide Remote · show mobile pairing code");hit.setOnClickListener(v->showMobileControl());hit.setOnFocusChangeListener((v,focused)->{int color=remoteActive?0xff65df9b:focused?mint:0xffdce8f1;glyph.setColor(color);label.setTextColor(color);hit.setBackground(new GlassDrawable(dp(18),focused||remoteActive));v.animate().cancel();v.animate().scaleX(focused?1.05f:1f).scaleY(focused?1.05f:1f).translationZ(dp(focused?4:0)).setDuration(190).setInterpolator(new android.view.animation.PathInterpolator(.2f,.8f,.2f,1f)).start();});return hit;}
    private void setRemoteActive(boolean value){remoteActive=value;if(remoteGlyph!=null){int color=value?0xff65df9b:0xffdce8f1;remoteGlyph.setColor(color);remoteLabel.setText(value?"Active":"Remote");remoteLabel.setTextColor(color);remoteHit.setBackground(new GlassDrawable(dp(18),value));remoteHit.setContentDescription(value?"Glide Remote · connected and active":"Glide Remote · show mobile pairing code");}}
    private final class RemoteGlyph extends View{private final android.graphics.Paint p=new android.graphics.Paint(3);private int color=0xffdce8f1;RemoteGlyph(Context c){super(c);}void setColor(int value){color=value;invalidate();}@Override protected void onDraw(Canvas c){float s=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight(),x=w*.5f,y=h*.51f;p.setColor(color);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(1.7f*s);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeJoin(android.graphics.Paint.Join.ROUND);c.drawRoundRect(w*.17f,h*.025f,w*.83f,h*.975f,6*s,6*s,p);c.drawLine(x,h*.12f,x,h*.22f,p);c.drawCircle(x,h*.29f,1.5f*s,p);c.drawCircle(x,y,6.7f*s,p);c.drawLine(x,y-3.3f*s,x,y+3.3f*s,p);c.drawLine(x-3.3f*s,y,x+3.3f*s,y,p);p.setStyle(android.graphics.Paint.Style.FILL);c.drawCircle(x-3.2f*s,h*.83f,1.25f*s,p);c.drawCircle(x+3.2f*s,h*.83f,1.25f*s,p);}}
    /** Centres the balloon on the dock icon it belongs to and puts the horn's point on that icon. */
    void pointTail(View item){
        if(watchBubble==null||item==null)return;
        if(watchBubble.getWidth()==0||item.getWidth()==0||favoriteRow.getWidth()==0){watchBubble.post(()->pointTail(item));return;}
        // Measure from layout, never getLocationOnScreen: the tile carries the magnification's
        // translationX and the section carries the pop's scale, both of which would skew the aim.
        int[] rowAt=new int[2],pageAt=new int[2];favoriteRow.getLocationOnScreen(rowAt);page.getLocationOnScreen(pageAt);
        tailCentre=rowAt[0]+item.getLeft()+item.getWidth()*.5f-pageAt[0]-watchSection.getLeft();
        placeBubble(watchSection.getVisibility()==View.VISIBLE);
    }
    void placeBubble(boolean animate){
        if(watchBubble==null||watchBubble.getWidth()==0||watchSection.getWidth()==0)return;
        float room=Math.max(0,watchSection.getWidth()-watchBubble.getWidth());
        float offset=Math.max(0,Math.min(room,tailCentre-watchBubble.getWidth()*.5f));
        watchBalloon.setTail(tailCentre-offset);
        watchBubble.animate().cancel();
        if(animate)watchBubble.animate().translationX(offset).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
        else watchBubble.setTranslationX(offset);
    }
    /** Re-aims once a new set of titles has been measured. */
    void reaimBubble(){if(watchSection==null||favoriteRow==null)return;watchSection.post(()->{View focus=favoriteRow.findFocus();if(focus==null)return;pointTail(focus);bubbleIn();});}
    void buildShell(){
        getWindow().setBackgroundDrawable(new ColorDrawable(0xff090f21));
        FrameLayout root=new FrameLayout(this);backdrop=new Atmosphere(this);root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));readabilityOverlay=new ReadabilityOverlay(this);root.addView(readabilityOverlay,new FrameLayout.LayoutParams(-1,-1));
        page=column();page.setPadding(dp(40),dp(18),dp(40),dp(12));page.setClipChildren(false);root.addView(page,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        LinearLayout top=row();
        meters=new UsageMeters(this);meters.setOnRamClickListener(v->new RamDialog(this,artCache::evictAll,icons,pkg->{App owner=app(pkg);return owner==null?pkg:owner.label;}));top.addView(meters);connectivity=new ConnectivityStatus(this,(permission,requestCode)->requestPermissions(new String[]{permission},requestCode));top.addView(connectivity);top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));LinearLayout.LayoutParams remoteLp=new LinearLayout.LayoutParams(dp(96),dp(32));remoteLp.setMarginEnd(dp(8));top.addView(remoteShortcut(),remoteLp);libraryButton=button("App library",()->showLibrary());settingsButton=button("Settings",()->options());top.addView(libraryButton);top.addView(settingsButton);page.addView(top,new LinearLayout.LayoutParams(-1,dp(32)));gap(page,12);
        LinearLayout hero=row();LinearLayout time=column();clock=text("",56,ink);clock.setTypeface(android.graphics.Typeface.create("sans-serif-light",0));date=text("",15,Color.WHITE);time.addView(clock);gap(time,3);time.addView(date);hero.addView(time,new LinearLayout.LayoutParams(dp(235),-2));
        weatherSymbol=text("◌",43,0xffffd695);hero.addView(weatherSymbol);LinearLayout weatherBox=column();weatherBox.setGravity(Gravity.START);weather=text("Set your location",28,ink);weatherDetail=text("Loading weather…",14,Color.WHITE);weatherBox.addView(weather);gap(weatherBox,5);weatherBox.addView(weatherDetail);gap(weatherBox,5);weatherStats=text("",13,Color.WHITE);weatherRain=text("",13,Color.WHITE);weatherBox.addView(weatherStats);gap(weatherBox,4);weatherBox.addView(weatherRain);for(TextView detail:new TextView[]{date,weatherDetail,weatherStats,weatherRain})detail.setShadowLayer(dp(.7f),0,dp(.5f),0x90000b19);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(dp(420),-2);wp.setMarginStart(dp(16));hero.addView(weatherBox,wp);page.addView(hero,new LinearLayout.LayoutParams(-1,dp(110)));gap(page,13);
        page.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        watchSection=column();watchSection.setClipChildren(false);watchSection.setClipToPadding(false);
        FrameLayout bubble=new FrameLayout(this);watchBubble=bubble;watchBalloon=new SpeechBubble(dp(28));bubble.setBackground(watchBalloon);
        bubble.setClipChildren(true);bubble.setClipToPadding(true);
        watchStrip=new HorizontalScrollView(this);watchStrip.setHorizontalScrollBarEnabled(false);watchStrip.setClipChildren(true);watchStrip.setClipToPadding(false);watchStrip.setPadding(0,dp(STRIP_PAD),0,dp(STRIP_PAD));watchStrip.setOverScrollMode(View.OVER_SCROLL_NEVER);watchStrip.setSmoothScrollingEnabled(true);watchRow=row();watchRow.setClipChildren(false);watchRow.setClipToPadding(false);watchStrip.addView(watchRow);
        FrameLayout.LayoutParams stripLp=new FrameLayout.LayoutParams(-1,dp(stripHeight()));stripLp.setMargins(dp(bubbleSidePad()),dp(BUBBLE_PAD_V),dp(bubbleSidePad()),dp(BUBBLE_PAD_V)+dp(28));bubble.addView(watchStrip,stripLp);bubble.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)placeBubble(false);});watchSection.addView(bubble,new LinearLayout.LayoutParams(-1,-2));
        watchSection.setAlpha(0f);watchSection.setVisibility(View.INVISIBLE);page.addView(watchSection,new LinearLayout.LayoutParams(-1,-2));gap(page,14);
        dockHost=new FrameLayout(this);dockHost.setClipChildren(false);dockHost.setClipToPadding(false);dockHost.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->sizeDockViewport());
        appStrip=new HorizontalScrollView(this);appStrip.setHorizontalScrollBarEnabled(false);appStrip.setClipToPadding(false);appStrip.setClipChildren(false);appStrip.setPadding(dp(20),dp(19),dp(20),dp(7));appStrip.setOverScrollMode(View.OVER_SCROLL_NEVER);appStrip.setSmoothScrollingEnabled(true);
        appStrip.setBackground(new DockDrawable(dp(32),false));appStrip.setElevation(dp(5));
        favoriteRow=row();favoriteRow.setGravity(Gravity.BOTTOM);favoriteRow.setClipChildren(false);favoriteRow.setClipToPadding(false);appStrip.addView(favoriteRow);
        dockHost.addView(appStrip,new FrameLayout.LayoutParams(-2,-1,Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM));page.addView(dockHost,new LinearLayout.LayoutParams(-1,dp(125)));
        updateNotice=button("",()->appUpdater.showAvailable());updateNotice.setVisibility(View.GONE);updateNotice.setTextColor(mint);updateNotice.setContentDescription("Glide update available. Select to review and install.");FrameLayout.LayoutParams noticeLp=new FrameLayout.LayoutParams(-2,dp(34),Gravity.BOTTOM|Gravity.END);noticeLp.setMargins(0,0,dp(40),dp(12));root.addView(updateNotice,noticeLp);
        musicCard=row();musicCard.setGravity(Gravity.CENTER_VERTICAL);musicCard.setPadding(dp(10),dp(10),dp(13),dp(10));musicCard.setBackground(new GlassDrawable(dp(20),false));musicCard.setElevation(dp(5));musicCard.setFocusable(false);musicCard.setClickable(false);musicCard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);musicCard.setVisibility(View.GONE);
        musicArt=new ImageView(this);musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);musicArt.setBackground(shape(0x38101d2d,0));musicCard.addView(musicArt,new LinearLayout.LayoutParams(dp(64),dp(64)));LinearLayout musicWords=column();TextView musicLabel=text("NOW PLAYING",10,mint);musicLabel.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));musicWords.addView(musicLabel);gap(musicWords,5);musicTitle=text("",15,ink);musicTitle.setSingleLine(true);musicTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);musicTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));musicWords.addView(musicTitle);gap(musicWords,4);musicSubtitle=text("",11,muted);musicSubtitle.setSingleLine(true);musicSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);musicWords.addView(musicSubtitle);LinearLayout.LayoutParams wordsLp=new LinearLayout.LayoutParams(0,-2,1);wordsLp.setMarginStart(dp(12));musicCard.addView(musicWords,wordsLp);FrameLayout.LayoutParams musicLp=new FrameLayout.LayoutParams(dp(310),dp(84),Gravity.END|Gravity.TOP);musicLp.setMargins(0,dp(76),dp(40),0);root.addView(musicCard,musicLp);
        library=column();
    }
    void showLibrary(){
        if(libraryDialog!=null&&libraryDialog.isShowing()){libraryDialog.getWindow().getDecorView().requestFocus();return;}
        ScrollView container=new ScrollView(this);container.setPadding(dp(18),dp(12),dp(18),dp(12));LinearLayout grid=column();grid.setClipChildren(false);container.addView(grid);LinearLayout line=null;
        for(int n=0;n<apps.size();n++){if(n%7==0){line=row();line.setClipChildren(false);grid.addView(line,new LinearLayout.LayoutParams(-1,dp(105)));}View icon=tile(apps.get(n),false);LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-1,1);line.addView(icon,cell);}
        if(line!=null){int extra=(7-apps.size()%7)%7;for(int n=0;n<extra;n++)line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));}
        libraryDialog=new AlertDialog.Builder(this).setTitle("App library").setView(container).setNegativeButton("Close",null).create();libraryDialog.show();libraryDialog.getWindow().setLayout(dp(850),dp(460));
    }
    void loadApps(){
        if(loading)return;loading=true;
        worker.execute(()->{
            ArrayList<App> found=new ArrayList<>();PackageManager pm=getPackageManager();LinkedHashMap<String,ResolveInfo> available=new LinkedHashMap<>();
            for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}){
                Intent query=new Intent(Intent.ACTION_MAIN).addCategory(category);
                for(ResolveInfo r:pm.queryIntentActivities(query,0))if(!available.containsKey(r.activityInfo.packageName))available.put(r.activityInfo.packageName,r);
            }
            for(Map.Entry<String,ResolveInfo> e:available.entrySet()){
                String pkg=e.getKey();if(pkg.equals(getPackageName())||pkg.equals(STOCK))continue;ResolveInfo r=e.getValue();
                String revision=r.activityInfo.applicationInfo.sourceDir;String label=prefs.getString("app-label:"+pkg,null);if(!revision.equals(prefs.getString("app-revision:"+pkg,""))||label==null){icons.remove(pkg);label=r.loadLabel(pm).toString();prefs.edit().putString("app-label:"+pkg,label).putString("app-revision:"+pkg,revision).apply();}
                Intent i=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(pkg,r.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Drawable icon=icons.get(pkg);
                if(icon==null){try{
                    java.io.File directory=new java.io.File(getCacheDir(),"icons");directory.mkdirs();java.io.File file=new java.io.File(directory,pkg+"-"+Integer.toHexString(revision.hashCode())+".png");Bitmap bmp=file.isFile()?BitmapFactory.decodeFile(file.getAbsolutePath()):null;
                    if(bmp==null){Drawable raw=r.loadIcon(pm);int edge=dp(favorites.contains(pkg)?76:48);bmp=Bitmap.createBitmap(edge,edge,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bmp);raw.setBounds(0,0,edge,edge);raw.draw(canvas);try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){bmp.compress(Bitmap.CompressFormat.PNG,100,out);}}
                    icon=new BitmapDrawable(getResources(),bmp);icons.put(pkg,icon);
                }catch(Exception ignored){}}
                int accent=IconPalette.NEUTRAL;
                if(icon instanceof BitmapDrawable){Bitmap source=((BitmapDrawable)icon).getBitmap();Bitmap sample=Bitmap.createScaledBitmap(source,32,32,true);int[] pixels=new int[1024];sample.getPixels(pixels,0,32,0,0,32,32);accent=IconPalette.fromPixels(pixels);if(sample!=source)sample.recycle();}
                found.add(new App(pkg,label,i,icon,accent));
            }
            Collections.sort(found,(a,b)->a.label.compareToIgnoreCase(b.label));
            handler.post(()->{loading=false;if(isDestroyed())return;apps.clear();apps.addAll(found);if(!prefs.contains("favorites")){for(int n=0;n<Math.min(6,apps.size());n++)favorites.add(apps.get(n).pkg);prefs.edit().putString("favorites",String.join(",",favorites)).apply();}loaded=true;updateApps();});
        });
    }
    App app(String p){for(App a:apps)if(a.pkg.equals(p))return a;return null;}
    void updateApps(){
        renderFavorites();
    }
    void renderFavorites(){
        View old=getCurrentFocus();String focus=restoreFocus!=null?restoreFocus:old!=null&&old.getTag() instanceof String?(String)old.getTag():null;restoreFocus=null;
        favoriteRow.removeAllViews();firstApp=null;
        dockCentre=-1;for(String p:favorites){App a=app(p);if(a==null)continue;View item=tile(a,true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(112),dp(99));favoriteRow.addView(item,lp);if(firstApp==null)firstApp=item;}
        if(firstApp==null){selectedPkg=null;favoriteRow.addView(button("Choose favorites",()->showLibrary()));}
        sizeDockViewport();
        View target=focus==null?null:favoriteRow.findViewWithTag(focus);if(target!=null)target.requestFocus();else if(firstApp!=null){firstApp.requestFocus();selectApp(appsForFirstFavorite());}
    }
    void sizeDockViewport(){
        if(dockHost==null||appStrip==null||favoriteRow==null)return;
        dockHost.post(()->{
            int hostWidth=dockHost.getWidth(),contentWidth=favoriteRow.getMeasuredWidth()+appStrip.getPaddingLeft()+appStrip.getPaddingRight();
            if(hostWidth<=0||contentWidth<=0)return;
            android.widget.FrameLayout.LayoutParams layout=(android.widget.FrameLayout.LayoutParams)appStrip.getLayoutParams();
            int width=Math.min(hostWidth,contentWidth);if(layout.width==width)return;
            layout.width=width;appStrip.setLayoutParams(layout);
        });
    }
    App appsForFirstFavorite(){for(String p:favorites){App a=app(p);if(a!=null)return a;}return null;}
    /** Dock magnification: the focused icon grows and its neighbours step aside so nothing overlaps. */
    void magnifyDock(View focused){
        if(favoriteRow==null)return;
        int centre=focused==null?-1:favoriteRow.indexOfChild(focused);if(centre==dockCentre)return;dockCentre=centre;
        final int total=favoriteRow.getChildCount();final View[] icons=new View[total];
        final float[] fromScale=new float[total],toScale=new float[total],fromShift=new float[total],toShift=new float[total];
        float half=dp(80)*.5f,slot=dp(112),gap=dp(21);
        for(int n=0;n<total;n++){
            View child=favoriteRow.getChildAt(n);if(!(child instanceof LinearLayout))continue;
            icons[n]=((LinearLayout)child).getChildAt(0);if(icons[n]==null)continue;
            int distance=centre<0?9:Math.abs(n-centre);
            toScale[n]=distance==0?1.46f:1f;
            fromScale[n]=icons[n].getScaleX()<=0?1f:icons[n].getScaleX();
            fromShift[n]=icons[n].getTranslationX();
        }
        for(int n=centre+1;n>0&&n<total;n++)toShift[n]=toShift[n-1]+Math.max(0,gap+half*(toScale[n-1]+toScale[n])-slot);
        for(int n=centre-1;n>=0;n--)toShift[n]=toShift[n+1]-Math.max(0,gap+half*(toScale[n+1]+toScale[n])-slot);
        if(dockMagnify!=null)dockMagnify.cancel();
        dockMagnify=android.animation.ValueAnimator.ofFloat(0,1);dockMagnify.setDuration(580);
        dockMagnify.setInterpolator(new android.view.animation.PathInterpolator(.19f,1f,.3f,1f));
        dockMagnify.addUpdateListener(a->{float t=(Float)a.getAnimatedValue();
            for(int n=0;n<total;n++){
                View icon=icons[n];if(icon==null)continue;
                float scale=fromScale[n]+(toScale[n]-fromScale[n])*t;
                icon.setPivotX(icon.getWidth()*.5f);icon.setPivotY(icon.getHeight());
                icon.setScaleX(scale);icon.setScaleY(scale);
                icon.setTranslationX(fromShift[n]+(toShift[n]-fromShift[n])*t);
            }});
        dockMagnify.start();
    }
    void selectApp(App a){if(a==null)return;if(a.pkg.equals(selectedPkg)){bubbleIn();return;}selectedPkg=a.pkg;bubbleOut();handler.removeCallbacks(selectWatch);handler.postDelayed(selectWatch,16);}
    View tile(App a,boolean favorite){
        LinearLayout item=column();item.setGravity(Gravity.CENTER_HORIZONTAL);item.setClipChildren(false);item.setClipToPadding(false);item.setPadding(dp(5),dp(4),dp(5),favorite?0:dp(4));item.setTag((favorite?"fav:":"app:")+a.pkg);item.setContentDescription(a.label+(favorite?", favorite. Hold OK to move.":". Hold OK to pin."));
        FrameLayout halo=new FrameLayout(this);ImageView logo=new ImageView(this);logo.setImageDrawable(a.pkg.equals("com.netflix.ninja")?new NetflixIcon():a.icon);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setClipToOutline(true);logo.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,android.graphics.Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(favorite?18:12));}});
        DockDrawable cardGlass=new DockDrawable(dp(16),true);
        if(!favorite){halo.setPadding(dp(4),dp(4),dp(4),dp(4));logo.setBackgroundColor(0xff090d14);halo.setBackground(cardGlass);}
        halo.addView(logo,new FrameLayout.LayoutParams(-1,-1));item.addView(halo,new LinearLayout.LayoutParams(dp(favorite?80:70),dp(favorite?80:70)));
        TextView name=text(a.label,11,ink);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER);
        View dot=new View(this);
        if(favorite){GradientDrawable mark=new GradientDrawable();mark.setShape(GradientDrawable.OVAL);mark.setColor(0xd8e8eef7);dot.setBackground(mark);dot.setAlpha(0f);gap(item,10);item.addView(dot,new LinearLayout.LayoutParams(dp(5),dp(5)));}
        else{gap(item,8);item.addView(name,new LinearLayout.LayoutParams(-1,-2));}
        item.setFocusable(true);item.setClickable(true);
        item.setOnFocusChangeListener((v,f)->{
            if(favorite){dot.animate().cancel();dot.animate().alpha(f?1f:0f).setDuration(160).start();
                if(f){selectApp(a);magnifyDock(v);pointTail(v);}else v.post(()->{if(favoriteRow.findFocus()==null)magnifyDock(null);});}
            else{cardGlass.setFocused(f);halo.animate().cancel();halo.animate().scaleX(f?1.16f:1f).scaleY(f?1.16f:1f).translationZ(dp(f?5:0)).setDuration(210).setInterpolator(new android.view.animation.PathInterpolator(.2f,.8f,.2f,1f)).start();name.setTextColor(f?Color.WHITE:0xffcbd7e9);}});
        item.setOnClickListener(v->{if(!favorite&&libraryDialog!=null)libraryDialog.dismiss();open(a.intent);});item.setOnLongClickListener(v->{if(favorite)startMove(a);else appOptions(a,false);return true;});
        item.setOnKeyListener((v,k,e)->{if(k==KeyEvent.KEYCODE_MENU&&e.getAction()==KeyEvent.ACTION_DOWN){appOptions(a,favorite);return true;}return false;});
        return item;
    }
    void startMove(App a){
        if(movingPkg!=null)return;View v=favoriteRow.findViewWithTag("fav:"+a.pkg);
        if(!(v instanceof LinearLayout))return;movingPkg=a.pkg;moveBackup=new ArrayList<>(favorites);View icon=((LinearLayout)v).getChildAt(0);
        if(icon!=null)icon.animate().translationY(-dp(14)).setDuration(170).setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
    }
    private void settlePendingSwap(){if(pendingSwap!=null)pendingSwap.run();}
    private void finishMove(boolean save){
        String pkg=movingPkg;if(pkg==null)return;settlePendingSwap();movingPkg=null;
        if(save){consumeCenterUp=true;saveFavorites();}
        else{favorites.clear();if(moveBackup!=null)favorites.addAll(moveBackup);restoreFocus="fav:"+pkg;renderFavorites();}
        moveBackup=null;
    }
    /** Slides the two tiles past each other, then commits the new order with no visible jump. */
    void swapTiles(View current,View neighbor){
        settlePendingSwap();
        final int target=favoriteRow.indexOfChild(neighbor);final float delta=neighbor.getX()-current.getX();
        final Runnable settle=new Runnable(){public void run(){
            if(pendingSwap!=this)return;pendingSwap=null;
            current.animate().cancel();neighbor.animate().cancel();current.setTranslationX(0);neighbor.setTranslationX(0);
            favoriteRow.removeView(current);favoriteRow.addView(current,target);dockCentre=-1;current.requestFocus();
        }};
        pendingSwap=settle;
        neighbor.animate().translationX(-delta).setDuration(165).setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
        current.animate().translationX(delta).setDuration(165).setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).withEndAction(settle).start();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        int k=e.getKeyCode();if(e.getAction()==KeyEvent.ACTION_DOWN&&backdrop!=null)backdrop.pauseMotionFor(280);if(consumeCenterUp&&e.getAction()==KeyEvent.ACTION_UP&&(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER)){consumeCenterUp=false;return true;}
        if(movingPkg!=null){
            if(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT){if(e.getAction()==KeyEvent.ACTION_DOWN){int old=favorites.indexOf(movingPkg),next=old+(k==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);if(next>=0&&next<favorites.size()){String other=favorites.get(next);Collections.swap(favorites,old,next);View current=favoriteRow.findViewWithTag("fav:"+movingPkg),neighbor=favoriteRow.findViewWithTag("fav:"+other);if(current!=null&&neighbor!=null){playRemoteSound(k==KeyEvent.KEYCODE_DPAD_LEFT?"left":"right");swapTiles(current,neighbor);}}}return true;}
            if(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)finishMove(true);return true;}
            if(k==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_DOWN)finishMove(false);return true;}
            return true;
        }
        if(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT){
            View focus=getCurrentFocus();android.view.ViewGroup region=isInside(focus,favoriteRow)?favoriteRow:isInside(focus,watchRow)?watchRow:null;
            if(region!=null){if(e.getAction()==KeyEvent.ACTION_DOWN){ArrayList<View> choices=region.getFocusables(View.FOCUS_FORWARD);int index=choices.indexOf(focus),next=index+(k==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);if(index>=0&&next>=0&&next<choices.size()){playRemoteSound(k==KeyEvent.KEYCODE_DPAD_LEFT?"left":"right");choices.get(next).requestFocus();}}return true;}
        }
        if(e.getAction()==KeyEvent.ACTION_DOWN&&(k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_DPAD_DOWN)&&moveFocusVertically(k==KeyEvent.KEYCODE_DPAD_UP?"up":"down"))return true;
        return super.dispatchKeyEvent(e);
    }
    private View firstFocusable(ViewGroup region){if(region==null||!region.isShown())return null;for(View item:region.getFocusables(View.FOCUS_FORWARD))if(item.isShown())return item;return null;}
    private View selectedFavorite(){if(selectedPkg!=null){View selected=favoriteRow.findViewWithTag("fav:"+selectedPkg);if(selected!=null)return selected;}return firstApp;}
    private View topControl(){if(remoteHit!=null&&remoteHit.isShown())return remoteHit;if(libraryButton!=null&&libraryButton.isShown())return libraryButton;return settingsButton;}
    private boolean moveTo(View next,String direction){if(next==null||next==getCurrentFocus())return false;next.setFocusableInTouchMode(true);playRemoteSound(direction);if(!next.requestFocus()){next.post(next::requestFocus);}if(next.getTag() instanceof String&&((String)next.getTag()).startsWith("fav:"))selectApp(app(((String)next.getTag()).substring(4)));return true;}
    private boolean moveFocusVertically(String direction){
        View current=getCurrentFocus();if(current==null)return moveTo(firstApp,direction);
        boolean up="up".equals(direction);View next=null;
        if(isInside(current,favoriteRow))next=up?firstFocusable(watchRow):null;
        else if(isInside(current,watchRow))next=up?topControl():selectedFavorite();
        else if(current==remoteHit||current==libraryButton||current==settingsButton)next=up?null:firstFocusable(watchRow);
        if(next==null&&isInside(current,favoriteRow)&&up)next=topControl();
        if(next==null&&!isInside(current,favoriteRow)&&!isInside(current,watchRow)&&(current==remoteHit||current==libraryButton||current==settingsButton)&&!up)next=selectedFavorite();
        if(next!=null)return moveTo(next,direction);
        return moveTo(current.focusSearch(up?View.FOCUS_UP:View.FOCUS_DOWN),direction);
    }
    boolean isInside(View view,android.view.ViewGroup container){if(view==null||container==null)return false;View current=view;while(current!=null){if(current==container)return true;android.view.ViewParent parent=current.getParent();current=parent instanceof View?(View)parent:null;}return false;}
    void saveFavorites(){prefs.edit().putString("favorites",android.text.TextUtils.join(",",favorites)).apply();renderFavorites();}
    void appOptions(App a,boolean fromFavorite){
        boolean pinned=favorites.contains(a.pkg);ArrayList<String> entries=new ArrayList<>();entries.add("Open "+a.label);entries.add(pinned?"Unpin from favorites":"Pin to favorites");if(pinned)entries.add("Move to first favorite");entries.add("App information");
        new AlertDialog.Builder(this).setTitle(a.label).setItems(entries.toArray(new String[0]),(d,which)->{
            if(which==0)open(a.intent);
            else if(which==1){if(pinned)favorites.remove(a.pkg);else favorites.add(a.pkg);restoreFocus=(fromFavorite&&!pinned?"fav:":"app:")+a.pkg;saveFavorites();}
            else if(pinned&&which==2){favorites.remove(a.pkg);favorites.add(0,a.pkg);restoreFocus="fav:"+a.pkg;saveFavorites();}
            else open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+a.pkg)));
        }).setNegativeButton("Close",null).show();
    }
    void options(){new AlertDialog.Builder(this).setTitle("Glide TV").setItems(new String[]{"TV settings","Location & scenery","Music playback info","Refresh apps and watch history","Mobile control","Check for updates","About Glide TV"},(d,n)->{
        if(n==0)open(new Intent(Settings.ACTION_SETTINGS));
        else if(n==1)chooseWeatherCity();
        else if(n==2)showMusicAccess();
        else if(n==3){loadApps();loadWatchNext();}
        else if(n==4)showMobileControl();
        else if(n==5){if(appUpdater==null)appUpdater=new AppUpdater(this);appUpdater.check();}
        else new AlertDialog.Builder(this).setTitle("Glide Glass").setMessage("Glide TV "+appVersion()+"\n\nHold OK on a favorite to move it, then use left/right. OK saves, Back cancels. Press Menu for other app options. Pin apps from the library with a long press.\n\nContinue Watching appears a few seconds after Home opens and shows only history shared by the highlighted app. Optional music information shows metadata shared by the active media app and never controls playback. RAM and storage update every five seconds while Home is visible.\n\nWeather: Open-Meteo (open-meteo.com). City search: Open-Meteo / GeoNames. Air quality: CAMS ENSEMBLE via Open-Meteo. CC BY 4.0. AQI uses the US scale; UV and rain chance are hourly forecasts. No ads or analytics.").setPositiveButton("Done",null).show();
    }).setNegativeButton("Close",null).show();}
    void showMusicAccess(){
        boolean granted=MusicSessions.accessGranted(this);String status=!granted?"Access is off.":currentMusic==null?"Access is on. Nothing is playing right now.":"Access is on. Showing “"+currentMusic.title+"”.";
        new AlertDialog.Builder(this).setTitle("Music playback info").setMessage(status+"\n\nGlide reads only the active title, artist, album and artwork shared by media apps while Home is visible. Metadata stays on this TV. Glide does not control playback, keep a listening history or upload this information.\n\nAndroid notification access is required to discover media sessions. Some apps and TV firmware do not expose them. You can turn access off at any time.").setPositiveButton(granted?"Manage access":"Open notification access",(dialog,which)->{Intent access=new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);if(access.resolveActivity(getPackageManager())==null)access=new Intent(Settings.ACTION_SETTINGS);open(access);}).setNegativeButton("Close",null).show();
    }
    String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "";}}
    void showMusic(MusicSessions.Track track){
        currentMusic=track;if(musicCard==null)return;
        if(track==null){musicCard.animate().cancel();musicCard.setVisibility(View.GONE);musicArt.setImageDrawable(null);return;}
        musicTitle.setText(track.title);musicSubtitle.setText(track.subtitle);if(track.artwork==null)musicArt.setImageDrawable(null);else musicArt.setImageBitmap(track.artwork);musicCard.setContentDescription("Now playing "+track.title+(track.subtitle.isEmpty()?"":" by "+track.subtitle));
        if(musicCard.getVisibility()!=View.VISIBLE){musicCard.setAlpha(0f);musicCard.setTranslationY(dp(8));musicCard.setVisibility(View.VISIBLE);musicCard.animate().alpha(1f).translationY(0).setDuration(280).setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();}
    }
    boolean visualMusicFixtureEnabled(){return (getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("visual_music_fixture",false);}
    MusicSessions.Track musicFixture(){
        Bitmap art=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(art);Paint paint=new Paint(3);paint.setShader(new LinearGradient(0,0,128,128,0xffe35376,0xff443bd7,Shader.TileMode.CLAMP));canvas.drawRect(0,0,128,128,paint);paint.setShader(null);paint.setColor(0xe8ffffff);canvas.drawCircle(64,64,32,paint);paint.setColor(0xff241d52);canvas.drawCircle(64,64,9,paint);return new MusicSessions.Track("Midnight Drive","Glide Sessions", "Demo player",art);
    }
    void showMobileControl(){
        try{
            if(mobileControl==null)mobileControl=new RemoteControlServer(this,this);
            if(!mobileControl.isRunning())mobileControl.start();
            String url="http://"+mobileControl.getAddress()+":"+mobileControl.getPort();
            String pin=mobileControl.getPin();
            LinearLayout content=column();content.setGravity(Gravity.CENTER_HORIZONTAL);content.setPadding(dp(20),dp(8),dp(20),dp(4));
            TextView instructions=text("Scan to pair with your TV",17,ink);instructions.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));instructions.setGravity(Gravity.CENTER);content.addView(instructions,new LinearLayout.LayoutParams(-1,-2));
            TextView sub=text("Use your phone camera · same Wi-Fi",12,muted);sub.setGravity(Gravity.CENTER);sub.setPadding(0,dp(5),0,0);content.addView(sub,new LinearLayout.LayoutParams(-1,-2));
            ImageView qr=new ImageView(this);qr.setImageBitmap(RemoteQr.create(mobileControl.getPairUrl(),dp(224)));qr.setScaleType(ImageView.ScaleType.FIT_CENTER);qr.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(dp(224),dp(224));qlp.topMargin=dp(16);qlp.bottomMargin=dp(4);content.addView(qr,qlp);
            TextView address=text(url,13,0xffdce7f1);address.setGravity(Gravity.CENTER);address.setPadding(0,dp(8),0,0);content.addView(address,new LinearLayout.LayoutParams(-1,-2));
            TextView code=text("Pairing code  ·  "+pin,18,mint);code.setGravity(Gravity.CENTER);code.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));code.setPadding(0,dp(6),0,dp(3));content.addView(code,new LinearLayout.LayoutParams(-1,-2));
            TextView note=text("Code expires in 10 min · auto-stops after 5 min idle",11,muted);note.setGravity(Gravity.CENTER);note.setPadding(0,dp(5),0,0);content.addView(note,new LinearLayout.LayoutParams(-1,-2));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Glide Remote").setView(content).setPositiveButton("Done",null).setNegativeButton("Turn off",(d,n)->{mobileControl.stop();Toast.makeText(this,"Mobile control is off",Toast.LENGTH_SHORT).show();}).setNeutralButton("New code",(d,n)->{try{mobileControl.start();handler.post(this::showMobileControl);}catch(Exception e){android.util.Log.e("GlideRemote","Could not restart remote",e);Toast.makeText(this,"Remote could not restart. Close another remote session and retry.",Toast.LENGTH_LONG).show();}}).create();
            mobileDialog=dialog;dialog.setOnDismissListener(d->{if(mobileDialog==dialog)mobileDialog=null;});dialog.show();if(dialog.getWindow()!=null){GradientDrawable glass=new GradientDrawable();glass.setColor(0xff0d1825);glass.setCornerRadius(dp(25));glass.setStroke(dp(1),0xff31594f);dialog.getWindow().setBackgroundDrawable(glass);dialog.getWindow().setLayout(dp(500),-2);}dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xff8be7c0);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(0xff8be7c0);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xff8be7c0);
        }catch(Exception e){android.util.Log.e("GlideRemote","Could not start mobile remote",e);new AlertDialog.Builder(this).setTitle("Mobile control unavailable").setMessage(e.getMessage()==null?"Could not start the local remote. Close another remote session and retry.":e.getMessage()).setPositiveButton("Done",null).show();}
    }
    @Override public org.json.JSONObject status() throws Exception {
        org.json.JSONObject j=new org.json.JSONObject();j.put("device",android.os.Build.MANUFACTURER+" TV");j.put("model",android.os.Build.MODEL);j.put("android",android.os.Build.VERSION.RELEASE+" (API "+android.os.Build.VERSION.SDK_INT+")");j.put("ip",mobileControl==null?"":mobileControl.getAddress());j.put("appCount",apps.size());
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);int ramPercent=(int)(100*(memory.totalMem-memory.availMem)/(double)memory.totalMem);j.put("ram",String.format(Locale.US,"%.1f GB used · %.1f GB free",(memory.totalMem-memory.availMem)/1073741824d,memory.availMem/1073741824d));j.put("ramTotal",memory.totalMem);j.put("ramUsed",memory.totalMem-memory.availMem);j.put("ramAvailable",memory.availMem);j.put("ramPercent",ramPercent);
        android.os.StatFs disk=new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());j.put("storage",String.format(Locale.US,"%.1f GB free · %d%% used",disk.getAvailableBytes()/1073741824d,(int)(100*(disk.getTotalBytes()-disk.getAvailableBytes())/(double)disk.getTotalBytes())));
        boolean wifiConnected=false,lanConnected=false;try{android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);for(android.net.Network network:cm.getAllNetworks()){android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(network);if(caps!=null){wifiConnected|=caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);lanConnected|=caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);}}}catch(Exception ignored){}
        String ssid="";if(wifiConnected&&checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")==PackageManager.PERMISSION_GRANTED)try{android.net.wifi.WifiManager wm=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);String value=wm==null?"":wm.getConnectionInfo().getSSID();if(value!=null&&!value.isEmpty()&&!"<unknown ssid>".equalsIgnoreCase(value))ssid=value.replaceAll("^\"|\"$","");}catch(Exception ignored){}
        j.put("wifi",wifiConnected?(ssid.isEmpty()?"Connected":ssid):"Not connected");j.put("lan",lanConnected?"Connected":"Not connected");String bt="Not connected";try{android.bluetooth.BluetoothAdapter adapter=android.bluetooth.BluetoothAdapter.getDefaultAdapter();if(adapter!=null&&adapter.isEnabled()){if(android.os.Build.VERSION.SDK_INT>=31&&checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)bt="Permission needed";else if(adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP)==android.bluetooth.BluetoothProfile.STATE_CONNECTED||adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET)==android.bluetooth.BluetoothProfile.STATE_CONNECTED||(android.os.Build.VERSION.SDK_INT>=28&&adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEARING_AID)==android.bluetooth.BluetoothProfile.STATE_CONNECTED)||(android.os.Build.VERSION.SDK_INT>=33&&adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.LE_AUDIO)==android.bluetooth.BluetoothProfile.STATE_CONNECTED))bt="Connected";}}catch(Exception ignored){}j.put("bluetooth",bt);j.put("uptime",String.format(Locale.US,"%.1f hours",android.os.SystemClock.elapsedRealtime()/3600000d));
        android.view.Display display=getWindowManager().getDefaultDisplay();android.util.DisplayMetrics dm=new android.util.DisplayMetrics();display.getRealMetrics(dm);android.view.Display.Mode mode=display.getMode();j.put("display",mode.getPhysicalWidth()+" × "+mode.getPhysicalHeight()+" @ "+Math.round(mode.getRefreshRate())+" Hz · UI "+dm.widthPixels+" × "+dm.heightPixels);j.put("displayWidth",mode.getPhysicalWidth());j.put("displayHeight",mode.getPhysicalHeight());j.put("refreshRate",Math.round(mode.getRefreshRate()));j.put("uiResolution",dm.widthPixels+" × "+dm.heightPixels);return j;
    }
    @Override public org.json.JSONArray apps() throws Exception {
        org.json.JSONArray list=new org.json.JSONArray();ArrayList<App> snapshot=new ArrayList<>(apps);Collections.sort(snapshot,(a,b)->a.label.compareToIgnoreCase(b.label));for(App a:snapshot){org.json.JSONObject j=new org.json.JSONObject();j.put("label",a.label);j.put("package",a.pkg);list.put(j);}return list;
    }
    @Override public boolean launch(String packageName){App a=app(packageName);if(a==null)return false;handler.post(()->open(a.intent));return true;}
    @Override public boolean stop(String packageName){if(packageName==null||app(packageName)==null)return false;try{MemoryHelperClient.request(this,"stop:"+packageName);return true;}catch(Exception e){return false;}}
    @Override public void paired(){handler.post(()->{setRemoteActive(true);if(mobileDialog!=null&&mobileDialog.isShowing())mobileDialog.dismiss();});}
    @Override public void remoteStateChanged(boolean isActive){handler.post(()->setRemoteActive(isActive));}
    @Override public void key(String key){
        if("library".equals(key)){playRemoteSound("ok");handler.post(()->{if(active)showLibrary();else{home();handler.postDelayed(()->{if(active)showLibrary();},500);}});return;}
        if("back".equals(key)){handler.post(()->{if(libraryDialog!=null&&libraryDialog.isShowing()){playRemoteSound("back");libraryDialog.dismiss();return;}if(mobileDialog!=null&&mobileDialog.isShowing()){playRemoteSound("back");mobileDialog.dismiss();return;}sendGlobalKey(key);});return;}
        if("up".equals(key)||"down".equals(key)){handler.post(()->{if(active&&moveFocusVertically(key))return;sendGlobalKey(key);});return;}
        sendGlobalKey(key);
    }
    private void playRemoteSound(String key){try{android.media.AudioManager audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);int effect="up".equals(key)?android.media.AudioManager.FX_FOCUS_NAVIGATION_UP:"down".equals(key)?android.media.AudioManager.FX_FOCUS_NAVIGATION_DOWN:"left".equals(key)?android.media.AudioManager.FX_FOCUS_NAVIGATION_LEFT:"right".equals(key)?android.media.AudioManager.FX_FOCUS_NAVIGATION_RIGHT:android.media.AudioManager.FX_KEY_CLICK;audio.playSoundEffect(effect);}catch(Exception ignored){}}
    private void sendGlobalKey(String key){worker.execute(()->{try{MemoryHelperClient.request(this,"key:"+key);}catch(Exception e){int code="up".equals(key)?KeyEvent.KEYCODE_DPAD_UP:"down".equals(key)?KeyEvent.KEYCODE_DPAD_DOWN:"left".equals(key)?KeyEvent.KEYCODE_DPAD_LEFT:"right".equals(key)?KeyEvent.KEYCODE_DPAD_RIGHT:"ok".equals(key)?KeyEvent.KEYCODE_DPAD_CENTER:KeyEvent.KEYCODE_BACK;handler.post(()->sendRemoteKey(code));}});}
    private void sendRemoteKey(int code){long now=SystemClock.uptimeMillis();dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0));dispatchKeyEvent(new KeyEvent(now,now+60,KeyEvent.ACTION_UP,code,0));}
    @Override public void home(){playRemoteSound("ok");handler.post(()->{Intent i=new Intent(this,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(i);});}
    @Override public void volume(String direction){handler.post(()->{android.media.AudioManager audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);int action=direction.equals("up")?android.media.AudioManager.ADJUST_RAISE:direction.equals("down")?android.media.AudioManager.ADJUST_LOWER:android.media.AudioManager.ADJUST_TOGGLE_MUTE;audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,action,android.media.AudioManager.FLAG_SHOW_UI);});}
    @Override public org.json.JSONObject processes() throws Exception{try{org.json.JSONObject raw=MemoryHelperClient.request(this,"snapshot");org.json.JSONArray result=new org.json.JSONArray(),entries=raw.optJSONArray("processes");if(entries!=null)for(int n=0;n<entries.length();n++){org.json.JSONObject source=entries.optJSONObject(n),item=new org.json.JSONObject();String process=source.optString("name"),pkg=process.split(":")[0];App owner=app(pkg);item.put("process",process);item.put("package",owner==null?"":pkg);item.put("label",owner==null?(process.equals("system")?"Android system":process):owner.label);item.put("kb",source.optLong("kb"));item.put("isApp",owner!=null);result.put(item);}raw.put("available",true);raw.put("processes",result);raw.put("error","");return raw;}catch(Exception e){org.json.JSONObject unavailable=new org.json.JSONObject();unavailable.put("available",false);unavailable.put("processes",new org.json.JSONArray());unavailable.put("error","Start the ADB helper from your computer to show running processes.");return unavailable;}}
    @Override public org.json.JSONObject clearMemory() throws Exception{artCache.evictAll();return MemoryHelperClient.request(this,"clear");}
    @Override public org.json.JSONArray files(String path) throws Exception{return MemoryHelperClient.request(this,"list:"+path).optJSONArray("entries");}
    @Override public String folderLabel(String path){return path==null||path.isEmpty()?"Shared TV storage":"/"+path;}
    @Override public RemoteControlServer.FileInfo upload(String requested,java.io.InputStream input,long length)throws Exception{if(requested==null)requested="upload.bin";if(length<0||length>2L*1024*1024*1024)throw new java.io.IOException("File exceeds 2 GB limit");org.json.JSONObject result=MemoryHelperClient.upload(this,requested,input,length);int dot=requested.lastIndexOf('.');String mime=dot<0?"application/octet-stream":android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(requested.substring(dot+1).toLowerCase(Locale.US));if(requested.toLowerCase(Locale.US).endsWith(".apk"))mime="application/vnd.android.package-archive";return new RemoteControlServer.FileInfo(result.optString("path"),requested,mime==null?"application/octet-stream":mime,length);}
    @Override public RemoteControlServer.FileInfo file(String id)throws Exception{org.json.JSONObject info=MemoryHelperClient.request(this,"stat:"+id);String name=info.optString("name","TV file");int dot=name.lastIndexOf('.');String mime=dot<0?"application/octet-stream":android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.US));if(name.toLowerCase(Locale.US).endsWith(".apk"))mime="application/vnd.android.package-archive";return new RemoteControlServer.FileInfo(id,name,mime==null?"application/octet-stream":mime,info.optLong("size"));}
    @Override public java.io.InputStream openFile(String id)throws Exception{return MemoryHelperClient.download(this,id).input;}
    @Override public boolean install(String id)throws Exception{
        RemoteControlServer.FileInfo info=file(id);if(!info.name.toLowerCase(Locale.US).endsWith(".apk"))throw new java.io.IOException("Select an APK file");if(!getPackageManager().canRequestPackageInstalls()){handler.post(()->{try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:"+getPackageName())));}catch(Exception ignored){}});throw new java.io.IOException("Allow Glide TV to install apps in TV settings, then try again");}
        android.content.pm.PackageInstaller installer=getPackageManager().getPackageInstaller();android.content.pm.PackageInstaller.SessionParams params=new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);int sessionId=installer.createSession(params);try(android.content.pm.PackageInstaller.Session session=installer.openSession(sessionId);MemoryHelperClient.Download source=MemoryHelperClient.download(this,id)){try(java.io.OutputStream target=session.openWrite("base.apk",0,info.size>0?info.size:-1)){byte[] buffer=new byte[65536];int n;while((n=source.input.read(buffer))!=-1)target.write(buffer,0,n);session.fsync(target);}android.content.Intent callback=new Intent(this,InstallResultReceiver.class).setAction("space.glidetv.launcher.INSTALL_RESULT").putExtra("session",sessionId);android.app.PendingIntent pending=android.app.PendingIntent.getBroadcast(this,sessionId,callback,android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_MUTABLE);session.commit(pending.getIntentSender());}return true;
    }
    String watchKey(String pkg){StringBuilder key=new StringBuilder(pkg);for(WatchItem w:watchItems)if(pkg.equals(w.pkg))key.append(w.intent).append(w.title).append(w.position).append(w.duration).append(w.art);return key.toString();}
    void renderSelectedWatch(){
        App selected=app(selectedPkg);if(selected==null)return;String key=watchKey(selectedPkg);if(key.equals(renderedWatchKey))return;renderedWatchKey=key;
        ArrayList<WatchItem> visible=new ArrayList<>();for(WatchItem w:watchItems)if(selectedPkg.equals(w.pkg))visible.add(w);
        int generation=++artworkGeneration,limit=visible.size();
        watchRow.removeAllViews();watchStrip.scrollTo(0,0);watchRow.setAlpha(1);
        if(visible.isEmpty()){for(WatchHolder unused:watchPool){unused.item=null;unused.image.setTag("");unused.image.setImageDrawable(null);}watchHasItems=false;bubbleOut();return;}
        watchHasItems=true;
        for(int n=0;n<limit;n++){if(n>=watchPool.size())watchPool.add(makeWatchCard());WatchHolder holder=watchPool.get(n);bindWatchCard(holder,visible.get(n),generation);watchRow.addView(holder.frame);}
        for(int n=limit;n<watchPool.size();n++){WatchHolder unused=watchPool.get(n);unused.item=null;unused.image.setTag("");unused.image.setImageDrawable(null);}
        reaimBubble();
    }
    void maybeSetupLocation(){if(!weatherLocation.configured()&&!prefs.getBoolean("location_prompted",false)&&!locationPromptScheduled){locationPromptScheduled=true;handler.postDelayed(()->{locationPromptScheduled=false;if(active&&!isDestroyed()){prefs.edit().putBoolean("location_prompted",true).apply();weatherLocation.showChooser();}},800);}}
    void chooseWeatherCity(){new AlertDialog.Builder(this).setTitle("Location & scenery").setItems(new String[]{"Set weather location", "Refresh downloaded scenery"},(d,n)->{if(n==0)weatherLocation.showChooser();else if(weatherLocation.configured()){toast("Checking scenery packs…");scenePacks.ensure(weatherLocation.latitude(),weatherLocation.longitude(),true,()->{backdrop.reloadScene();toast("Scenery is ready");});}else weatherLocation.showChooser();}).setNegativeButton("Close",null).show();}
    void locationChanged(){weatherFetched=0;weatherCity=weatherLocation.city();weather.setText(weatherCity);weatherDetail.setText("Loading local weather…");weatherStats.setText("");weatherRain.setText("");backdrop.setTimeZone(weatherLocation.timezone());backdrop.reloadScene();refreshWeather();}
    org.json.JSONObject fetchWeatherJson(String url)throws Exception{return readJson(url);}
    static org.json.JSONObject readJson(String url)throws Exception{
        java.net.URLConnection c=new java.net.URL(url).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(6000);try(java.io.InputStream in=c.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1){if(out.size()>200000)throw new java.io.IOException("Response limit");out.write(bytes,0,n);}return new org.json.JSONObject(out.toString("UTF-8"));}
    }
    String weatherNumber(org.json.JSONObject data,String field,String format){double n=data.optDouble(field,Double.NaN);return Double.isNaN(n)?"—":String.format(Locale.US,format,n);}
    void displayWeather(org.json.JSONObject data,String city,boolean cached){
        if(isDestroyed()||!city.equals(weatherCity))return;int code=data.optInt("weather_code",0);weatherCode=code;weatherIsDay=data.optInt("is_day",1)==1;
        String condition=code==0?"Clear":code==1?"Mainly clear":code==2?"Partly cloudy":code==3?"Overcast":code<=48?"Fog":code<=57?"Drizzle":code<=67?"Rain":code<=77?"Snow":code<=82?"Showers":code<=86?"Snow showers":"Thunderstorms";
        weather.setText(weatherNumber(data,"temperature_2m","%.0f")+"°  "+city);weatherDetail.setText(condition+" · Feels like "+weatherNumber(data,"apparent_temperature","%.0f")+"°"+(cached?" · cached":""));
        weatherStats.setText("Humidity "+weatherNumber(data,"relative_humidity_2m","%.0f")+"%  ·  UV "+weatherNumber(data,"uv_index","%.1f")+"  ·  AQI "+weatherNumber(data,"us_aqi","%.0f"));
        weatherRain.setText("Rain "+weatherNumber(data,"precipitation","%.1f")+" mm  ·  Chance "+weatherNumber(data,"precipitation_probability","%.0f")+"%  ·  Wind "+weatherNumber(data,"wind_speed_10m","%.0f")+" km/h");
        backdrop.setTimeZone(data.optString("timezone",weatherLocation.timezone()));weatherSymbol.setText(code==0?(weatherIsDay?"☀":"☾"):(code>=71&&code<=77)||code==85||code==86?"❄":code<=3?"☁":code<=67?"☂":"☁");weatherSymbol.setTextColor(code==0?(weatherIsDay?0xffffd18a:0xffd9cbff):code<=3?0xffdae8ff:0xff92cfff);backdrop.setEnvironment(data.optDouble("wind_speed_10m",Double.NaN),data.optDouble("cloud_cover",Double.NaN),data.optString("sunrise",""),data.optString("sunset",""));backdrop.setConditions(code,weatherIsDay,Calendar.getInstance().get(Calendar.HOUR_OF_DAY));readabilityOverlay.setSceneLuminance(backdrop.readabilityLuminance());
    }
    void refreshWeather(){
        if(!weatherLocation.configured()){weather.setText("Set your location");weatherDetail.setText("Settings → Location & scenery");return;}
        if(System.currentTimeMillis()-weatherFetched<1800000)return;weatherFetched=System.currentTimeMillis();weatherCity=weatherLocation.city();final String city=weatherCity,key=weatherLocation.key();final double lat=weatherLocation.latitude(),lon=weatherLocation.longitude();scenePacks.ensure(lat,lon,false,()->backdrop.reloadScene());
        try{String cached=prefs.getString("weather_v4_"+key,"");if(!cached.isEmpty())displayWeather(new org.json.JSONObject(cached),city,true);}catch(Exception ignored){}
        artwork.execute(()->{try{
            org.json.JSONObject all=fetchWeatherJson("https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,is_day,wind_speed_10m,cloud_cover&daily=sunrise,sunset&hourly=uv_index,precipitation_probability&forecast_days=1&timezone=auto"),data=all.getJSONObject("current"),hourly=all.getJSONObject("hourly");
            data.put("timezone",all.optString("timezone",weatherLocation.timezone()));
            org.json.JSONObject daily=all.getJSONObject("daily");data.put("sunrise",daily.getJSONArray("sunrise").optString(0,""));data.put("sunset",daily.getJSONArray("sunset").optString(0,""));String hour=data.getString("time").substring(0,13);org.json.JSONArray times=hourly.getJSONArray("time");for(int n=0;n<times.length();n++)if(times.getString(n).startsWith(hour)){data.put("uv_index",hourly.getJSONArray("uv_index").opt(n));data.put("precipitation_probability",hourly.getJSONArray("precipitation_probability").opt(n));break;}
            final org.json.JSONObject initial=new org.json.JSONObject(data.toString());handler.post(()->{if(key.equals(weatherLocation.key()))displayWeather(initial,city,false);});
            try{org.json.JSONObject air=fetchWeatherJson("https://air-quality-api.open-meteo.com/v1/air-quality?latitude="+lat+"&longitude="+lon+"&current=us_aqi,pm2_5,pm10&timezone=auto").getJSONObject("current");data.put("us_aqi",air.opt("us_aqi"));data.put("pm2_5",air.opt("pm2_5"));data.put("pm10",air.opt("pm10"));}catch(Exception ignored){}
            prefs.edit().putString("weather_v4_"+key,data.toString()).apply();handler.post(()->{if(key.equals(weatherLocation.key())){prefs.edit().putString("weather_timezone",data.optString("timezone",weatherLocation.timezone())).apply();displayWeather(data,city,false);}});
        }catch(Exception e){handler.post(()->{if(!isDestroyed()&&key.equals(weatherLocation.key())&&prefs.getString("weather_v4_"+key,"").isEmpty()){weather.setText(city);weatherDetail.setText("Weather unavailable");weatherStats.setText("");weatherRain.setText("");}});}});
    }
    String value(android.database.Cursor c,String name){int n=c.getColumnIndex(name);return n<0||c.isNull(n)?"":c.getString(n);}
    long number(android.database.Cursor c,String name){try{return Long.parseLong(value(c,name));}catch(Exception e){return 0;}}
    boolean visualWatchFixtureEnabled(){return (getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("visual_watch_fixture",false);}
    void loadWatchNext(){
        if(watchRow==null)return;
        if(visualWatchFixtureEnabled()){
            watchGeneration++;watchItems.clear();
            for(int n=0;n<8;n++){WatchItem item=new WatchItem();item.title=n==0?"Focus regression":"Watch fixture "+(n+1);item.pkg="space.glidetv.preview.coral";item.intent="intent:#Intent;action=android.intent.action.VIEW;package=space.glidetv.preview.coral;end";item.position=120000+n*5000;item.duration=180000;item.time=1731974400000L-n;item.type=1;watchItems.add(item);}
            renderSelectedWatch();return;
        }
        if(checkSelfPermission("android.permission.READ_TV_LISTINGS")!=PackageManager.PERMISSION_GRANTED){watchEmpty("Connect your watch history","Show Continue Watching from supported apps",true);return;}
        final int generation=++watchGeneration;
        worker.execute(()->{
            ArrayList<WatchItem> found=new ArrayList<>();String failure=null;
            try(android.database.Cursor c=getContentResolver().query(android.net.Uri.parse("content://android.media.tv/watch_next_program"),null,null,null,null)){
                if(c!=null)while(c.moveToNext()){
                    if(c.getColumnIndex("browsable")>=0&&number(c,"browsable")==0)continue;
                    WatchItem w=new WatchItem();w.title=value(c,"title");w.pkg=value(c,"package_name");w.intent=value(c,"intent_uri");w.art=value(c,"poster_art_uri");w.position=number(c,"last_playback_position_millis");w.duration=number(c,"duration_millis");w.time=number(c,"last_engagement_time_utc_millis");w.type=(int)number(c,"watch_next_type");
                    if(w.title.isEmpty()||w.intent.isEmpty()||w.type==3)continue;
                    try{if(!getPackageManager().getApplicationInfo(w.pkg,0).enabled)continue;}catch(Exception e){continue;}
                    found.add(w);
                }
            }catch(Exception e){failure=e.getClass().getSimpleName();}
            Collections.sort(found,(a,b)->Long.compare(b.time,a.time));
            final String error=failure;android.util.Log.i("GlideWatch","Watch Next entries: "+found.size()+"; error="+failure);
            handler.post(()->{if(isDestroyed()||generation!=watchGeneration)return;watchItems.clear();watchItems.addAll(found);renderSelectedWatch();});
        });
    }
    void watchEmpty(String title,String subtitle,boolean permission){
        artworkGeneration++;for(WatchHolder unused:watchPool){unused.item=null;unused.image.setTag("");unused.image.setImageDrawable(null);}watchRow.removeAllViews();watchHasItems=true;reaimBubble();LinearLayout box=column();box.setGravity(Gravity.CENTER_VERTICAL);box.setPadding(dp(4),dp(6),dp(4),dp(6));
        TextView t=text(title,15,ink);t.setTypeface(android.graphics.Typeface.create("sans-serif-light",0));box.addView(t);gap(box,5);box.addView(text(subtitle,11,0xffc0cce1));
        if(permission){gap(box,6);box.addView(button("Enable Continue Watching",()->requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"},40)));}
        watchRow.addView(box,new LinearLayout.LayoutParams(-2,dp(100)));
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] grants){super.onRequestPermissionsResult(code,names,grants);if(code==WeatherLocation.PERMISSION)weatherLocation.permissionResult();else if(code==40)loadWatchNext();else if(code==41){connectivity.update();connectivity.openWifiSettings();}else if(code==42){connectivity.update();connectivity.openBluetoothSettings();}else if(code==43)connectivity.update();}
    private static class WatchHolder {LinearLayout frame;ImageView image;TextView title;ProgressRim rim;float done;int accent=IconPalette.NEUTRAL;WatchItem item;}
    WatchHolder makeWatchCard(){
        WatchHolder holder=new WatchHolder();
        LinearLayout card=column();holder.frame=card;card.setClipChildren(false);card.setClipToPadding(false);card.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(CARD_W),-2);lp.setMarginEnd(dp(CARD_GAP));card.setLayoutParams(lp);
        FrameLayout pic=new FrameLayout(this);
        FrameLayout shot=new FrameLayout(this);shot.setClipToOutline(true);shot.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,android.graphics.Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(12));}});
        holder.image=new ImageView(this);holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);holder.image.setBackgroundColor(0xff1d2738);shot.addView(holder.image,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout.LayoutParams shotLp=new FrameLayout.LayoutParams(-1,-1);shotLp.setMargins(dp(6),dp(6),dp(6),dp(6));pic.addView(shot,shotLp);
        // The ring sits in its own margin outside the artwork, so it never blends into the picture.
        View edge=new View(this);ProgressRim rim=new ProgressRim(dp(17));holder.rim=rim;edge.setBackground(rim);pic.addView(edge,new FrameLayout.LayoutParams(-1,-1));
        card.addView(pic,new LinearLayout.LayoutParams(-1,dp(CARD_H)));gap(card,TITLE_GAP);
        holder.title=text("",11,ink);holder.title.setSingleLine(true);holder.title.setEllipsize(android.text.TextUtils.TruncateAt.END);holder.title.setGravity(Gravity.CENTER);
        card.addView(holder.title,new LinearLayout.LayoutParams(-1,-2));
        card.setFocusable(true);card.setClickable(true);
        card.setOnFocusChangeListener((v,f)->{rim.set(holder.done,f,holder.accent);holder.title.setTextColor(f?Color.WHITE:0xffc3d2e4);
            v.animate().cancel();v.animate().scaleX(f?1.06f:1f).scaleY(f?1.06f:1f).translationZ(dp(f?5:0)).setDuration(210).setInterpolator(new android.view.animation.PathInterpolator(.2f,.8f,.2f,1f)).start();});
        card.setOnClickListener(v->{WatchItem w=holder.item;if(w==null)return;try{Intent i=Intent.parseUri(w.intent,Intent.URI_INTENT_SCHEME);i.setPackage(w.pkg);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);open(i);}catch(Exception e){toast("This app\u2019s resume link is unavailable");}});return holder;
    }
    void bindWatchCard(WatchHolder holder,WatchItem item,int generation){
        holder.item=item;App owner=app(item.pkg);String name=owner==null?item.pkg:owner.label;
        holder.title.setText(item.title);holder.title.setTextColor(holder.frame.hasFocus()?Color.WHITE:0xffc3d2e4);
        holder.done=item.duration>0?Math.min(1f,Math.max(0f,item.position/(float)item.duration)):0f;
        holder.accent=owner==null?IconPalette.NEUTRAL:owner.accent;
        holder.rim.set(holder.done,holder.frame.hasFocus(),holder.accent);
        holder.frame.setContentDescription("Resume "+item.title+" in "+name);holder.image.setTag(item.art);holder.image.setImageDrawable(null);loadArtwork(item.art,holder.image,generation);
    }
    void loadArtwork(String uri,ImageView target,int generation){
        if(uri==null||uri.isEmpty())return;Bitmap cached=artCache.get(uri);if(cached!=null){target.setImageBitmap(cached);return;}
        java.lang.ref.WeakReference<ImageView> destination=new java.lang.ref.WeakReference<>(target);
        artwork.execute(()->{try{
            if(generation!=artworkGeneration)return;
            android.net.Uri u=android.net.Uri.parse(uri);java.io.InputStream stream;
            if("https".equals(u.getScheme())){java.net.URLConnection connection=new java.net.URL(uri).openConnection();connection.setConnectTimeout(4000);connection.setReadTimeout(4000);stream=connection.getInputStream();}
            else if("content".equals(u.getScheme())||"android.resource".equals(u.getScheme()))stream=getContentResolver().openInputStream(u);else return;
            if(stream==null)return;byte[] data;try(java.io.InputStream in=stream;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(generation!=artworkGeneration||out.size()+n>2*1024*1024)return;out.write(buf,0,n);}data=out.toByteArray();}
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);if(bounds.outWidth<1||bounds.outHeight<1)return;
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;while(bounds.outWidth/opts.inSampleSize>720||bounds.outHeight/opts.inSampleSize>720)opts.inSampleSize*=2;opts.inPreferredConfig=Bitmap.Config.HARDWARE;
            Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,opts);if(bitmap!=null&&generation==artworkGeneration){artCache.put(uri,bitmap);handler.post(()->{ImageView image=destination.get();if(!isDestroyed()&&generation==artworkGeneration&&image!=null&&uri.equals(image.getTag()))image.setImageBitmap(bitmap);});}
        }catch(Exception ignored){}});
    }
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    void open(Intent i){try{startActivity(i);}catch(Exception e){toast("Unable to open. Check that the app is enabled.");}}
}
