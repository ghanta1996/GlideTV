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
    private LinearLayout page, favoriteRow, library, watchRow;
    private TextView watchCount;
    private final java.util.concurrent.ExecutorService artwork=Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String,Bitmap> artCache=new android.util.LruCache<String,Bitmap>(2*1024*1024){protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
    private final android.database.ContentObserver watchChanges=new android.database.ContentObserver(handler){public void onChange(boolean self){if(active)loadWatchNext();}};
    private int watchGeneration=0;
    private HorizontalScrollView appStrip, watchStrip;
    private UsageMeters meters;
    private ConnectivityStatus connectivity;
    private RemoteControlServer mobileControl;
    private AppUpdater appUpdater;
    private TextView updateNotice;
    private WeatherLocation weatherLocation;private ScenePacks scenePacks;private boolean locationPromptScheduled;
    private View remoteHit;
    private RemoteGlyph remoteGlyph;
    private TextView remoteLabel;
    private boolean remoteActive;
    private Atmosphere backdrop;
    private int weatherCode=0;
    private boolean weatherIsDay=true;
    private TextView watchTitle, weather, weatherDetail, weatherSymbol, weatherStats, weatherRain;
    private final Runnable trimIdle=()->worker.execute(()->System.gc());
    private String selectedPkg, watchFingerprint="", renderedWatchKey="";
    private final ArrayList<WatchHolder> watchPool=new ArrayList<>();
    private LinearLayout emptyWatch;private TextView emptyWatchTitle;
    private final ArrayList<WatchItem> watchItems=new ArrayList<>();
    private AlertDialog libraryDialog;
    private AlertDialog mobileDialog;
    private String weatherCity="Your location";
    private long weatherFetched;
    private final Runnable selectWatch=()->renderSelectedWatch();
    private TextView clock, date, count;
    private View firstApp;
    private boolean loaded=false, active=false, loading=false;
    private String restoreFocus, movingPkg;
    private ArrayList<String> moveBackup;
    private boolean consumeCenterUp;
    private android.content.SharedPreferences prefs;
    private static final String STOCK="com.google.android.apps.tv.launcherx";
    private static class App {
        String pkg,label; Intent intent; Drawable icon;
        App(String p,String l,Intent i,Drawable d){pkg=p;label=l;intent=i;icon=d;}
    }
    private final Runnable ticker=new Runnable(){public void run(){
        if(clock!=null){Date now=new Date();clock.setText(localTime("h:mm",now));date.setText(localTime("EEEE, d MMMM",now));}
        if(active){if(appUpdater!=null)appUpdater.checkIfDue();if(backdrop!=null){backdrop.setConditions(weatherCode,weatherIsDay,Calendar.getInstance().get(Calendar.HOUR_OF_DAY));if(weatherCode==0&&weatherSymbol!=null){boolean day=backdrop.isDaytime();weatherSymbol.setText(day?"☀":"☾");weatherSymbol.setTextColor(day?0xffffd18a:0xffd9cbff);}}refreshWeather();handler.postDelayed(this,60000-System.currentTimeMillis()%60000);}
    }};
    private final BroadcastReceiver changes=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(active)loadApps();else loaded=false;}};
    @Override public void onCreate(Bundle b){
        super.onCreate(b);prefs=getSharedPreferences("home",MODE_PRIVATE);weatherLocation=new WeatherLocation(this,this::locationChanged);scenePacks=new ScenePacks(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        String saved=prefs.getString("favorites",null);
        if(saved!=null){for(String p:saved.split(","))if(!p.isEmpty())favorites.add(p);}

        buildShell();
        appUpdater=new AppUpdater(this);appUpdater.setAvailabilityChanged(()->{String version=appUpdater.availableVersion();updateNotice.setText(version==null?"":"↑  Glide "+version+" available");updateNotice.setVisibility(version==null?View.GONE:View.VISIBLE);});
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_PACKAGE_ADDED);filter.addAction(Intent.ACTION_PACKAGE_REMOVED);filter.addAction(Intent.ACTION_PACKAGE_CHANGED);filter.addDataScheme("package");registerReceiver(changes,filter,Context.RECEIVER_EXPORTED);
        getContentResolver().registerContentObserver(android.net.Uri.parse("content://android.media.tv/watch_next_program"),true,watchChanges);
        loadApps();
    }
    @Override public void onResume(){super.onResume();active=true;if(appUpdater!=null)appUpdater.onResume();weatherLocation.onResume();handler.removeCallbacks(ticker);ticker.run();meters.start();connectivity.start();if(weatherLocation.configured()&&!prefs.getBoolean("connection_permissions_prompted",false)){prefs.edit().putBoolean("connection_permissions_prompted",true).apply();connectivity.requestInitialStatusPermissions(43);}backdrop.setRunning(true);if(!loaded)loadApps();loadWatchNext();refreshWeather();maybeSetupLocation();}
    @Override public void onPause(){super.onPause();active=false;handler.removeCallbacks(ticker);meters.stop();connectivity.stop();backdrop.setRunning(false);}
    @Override public void onDestroy(){super.onDestroy();if(appUpdater!=null)appUpdater.close();weatherLocation.close();scenePacks.close();handler.removeCallbacksAndMessages(null);unregisterReceiver(changes);backdrop.dispose();getContentResolver().unregisterContentObserver(watchChanges);if(mobileControl!=null)mobileControl.stop();worker.shutdownNow();artwork.shutdownNow();}
    @Override public void onBackPressed(){if(firstApp!=null)firstApp.requestFocus();}
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
            view.setScaleX(has?1.025f:1f);view.setScaleY(has?1.025f:1f);
            view.setBackground(new GlassDrawable(dp(18),has));
        });
    }
    TextView button(String name,Runnable action){TextView b=text(name,13,ink);b.setGravity(Gravity.CENTER);b.setPadding(dp(17),0,dp(17),0);styleFocus(b,0xff192934,0xff304840);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(32));lp.setMarginStart(dp(8));b.setLayoutParams(lp);return b;}
    View remoteShortcut(){LinearLayout hit=row();remoteHit=hit;hit.setGravity(Gravity.CENTER);hit.setPadding(dp(9),0,dp(9),0);hit.setBackground(new GlassDrawable(dp(18),false));RemoteGlyph glyph=new RemoteGlyph(this);remoteGlyph=glyph;hit.addView(glyph,new LinearLayout.LayoutParams(dp(21),dp(27)));TextView label=text("Remote",12,0xffdce8f1);remoteLabel=label;label.setSingleLine(true);label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMarginStart(dp(6));hit.addView(label,lp);hit.setFocusable(true);hit.setClickable(true);hit.setContentDescription("Glide Remote · show mobile pairing code");hit.setOnClickListener(v->showMobileControl());hit.setOnFocusChangeListener((v,focused)->{int color=remoteActive?0xff65df9b:focused?mint:0xffdce8f1;glyph.setColor(color);label.setTextColor(color);hit.setBackground(new GlassDrawable(dp(18),focused||remoteActive));v.animate().cancel();v.animate().scaleX(focused?1.05f:1f).scaleY(focused?1.05f:1f).setDuration(140).start();});return hit;}
    private void setRemoteActive(boolean value){remoteActive=value;if(remoteGlyph!=null){int color=value?0xff65df9b:0xffdce8f1;remoteGlyph.setColor(color);remoteLabel.setText(value?"Active":"Remote");remoteLabel.setTextColor(color);remoteHit.setBackground(new GlassDrawable(dp(18),value));remoteHit.setContentDescription(value?"Glide Remote · connected and active":"Glide Remote · show mobile pairing code");}}
    private final class RemoteGlyph extends View{private final android.graphics.Paint p=new android.graphics.Paint(3);private int color=0xffdce8f1;RemoteGlyph(Context c){super(c);}void setColor(int value){color=value;invalidate();}@Override protected void onDraw(Canvas c){float s=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight(),x=w*.5f,y=h*.51f;p.setColor(color);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(1.7f*s);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeJoin(android.graphics.Paint.Join.ROUND);c.drawRoundRect(w*.17f,h*.025f,w*.83f,h*.975f,6*s,6*s,p);c.drawLine(x,h*.12f,x,h*.22f,p);c.drawCircle(x,h*.29f,1.5f*s,p);c.drawCircle(x,y,6.7f*s,p);c.drawLine(x,y-3.3f*s,x,y+3.3f*s,p);c.drawLine(x-3.3f*s,y,x+3.3f*s,y,p);p.setStyle(android.graphics.Paint.Style.FILL);c.drawCircle(x-3.2f*s,h*.83f,1.25f*s,p);c.drawCircle(x+3.2f*s,h*.83f,1.25f*s,p);}}
    void buildShell(){
        getWindow().setBackgroundDrawable(new ColorDrawable(0xff090f21));
        FrameLayout root=new FrameLayout(this);backdrop=new Atmosphere(this);root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        page=column();page.setPadding(dp(40),dp(18),dp(40),dp(12));page.setClipChildren(false);root.addView(page,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        LinearLayout top=row();
        meters=new UsageMeters(this);meters.setOnRamClickListener(v->new RamDialog(this,()->{artCache.evictAll();handler.removeCallbacks(trimIdle);handler.postDelayed(trimIdle,600);},icons,pkg->{App owner=app(pkg);return owner==null?pkg:owner.label;}));top.addView(meters);connectivity=new ConnectivityStatus(this,(permission,requestCode)->requestPermissions(new String[]{permission},requestCode));top.addView(connectivity);top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));LinearLayout.LayoutParams remoteLp=new LinearLayout.LayoutParams(dp(96),dp(32));remoteLp.setMarginEnd(dp(8));top.addView(remoteShortcut(),remoteLp);top.addView(button("App library",()->showLibrary()));top.addView(button("Settings",()->options()));page.addView(top,new LinearLayout.LayoutParams(-1,dp(32)));gap(page,12);
        LinearLayout hero=row();LinearLayout time=column();clock=text("",56,ink);clock.setTypeface(android.graphics.Typeface.create("sans-serif-light",0));date=text("",15,Color.WHITE);time.addView(clock);gap(time,3);time.addView(date);hero.addView(time,new LinearLayout.LayoutParams(dp(235),-2));
        weatherSymbol=text("◌",43,0xffffd695);hero.addView(weatherSymbol);LinearLayout weatherBox=column();weatherBox.setGravity(Gravity.START);weather=text("Set your location",28,ink);weatherDetail=text("Loading weather…",14,Color.WHITE);weatherBox.addView(weather);gap(weatherBox,5);weatherBox.addView(weatherDetail);gap(weatherBox,5);weatherStats=text("",13,Color.WHITE);weatherRain=text("",13,Color.WHITE);weatherBox.addView(weatherStats);gap(weatherBox,4);weatherBox.addView(weatherRain);for(TextView detail:new TextView[]{date,weatherDetail,weatherStats,weatherRain})detail.setShadowLayer(dp(.7f),0,dp(.5f),0x90000b19);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(dp(420),-2);wp.setMarginStart(dp(16));hero.addView(weatherBox,wp);page.addView(hero,new LinearLayout.LayoutParams(-1,dp(110)));gap(page,13);
        LinearLayout fh=row();TextView ft=text("Favorites",17,ink);ft.setTypeface(null,1);fh.addView(ft,new LinearLayout.LayoutParams(0,-2,1));count=text("",10,0xffb4c4dc);fh.addView(count);page.addView(fh,new LinearLayout.LayoutParams(-1,dp(20)));gap(page,5);
        appStrip=new HorizontalScrollView(this);appStrip.setHorizontalScrollBarEnabled(false);appStrip.setClipToPadding(false);appStrip.setClipChildren(false);appStrip.setPadding(dp(4),dp(5),dp(4),dp(5));appStrip.setOverScrollMode(View.OVER_SCROLL_NEVER);appStrip.setSmoothScrollingEnabled(true);favoriteRow=row();favoriteRow.setClipChildren(false);appStrip.addView(favoriteRow);page.addView(appStrip,new LinearLayout.LayoutParams(-1,dp(146)));gap(page,13);
        LinearLayout wh=row();watchTitle=text("Continue watching",17,ink);watchTitle.setTypeface(null,1);wh.addView(watchTitle,new LinearLayout.LayoutParams(0,-2,1));watchCount=text("",10,0xffb4c4dc);wh.addView(watchCount);page.addView(wh,new LinearLayout.LayoutParams(-1,dp(20)));gap(page,7);
        watchStrip=new HorizontalScrollView(this);watchStrip.setHorizontalScrollBarEnabled(false);watchStrip.setClipChildren(false);watchStrip.setClipToPadding(false);watchStrip.setPadding(dp(4),dp(4),dp(4),dp(4));watchStrip.setOverScrollMode(View.OVER_SCROLL_NEVER);watchStrip.setSmoothScrollingEnabled(true);watchRow=row();watchRow.setClipChildren(false);watchStrip.addView(watchRow);page.addView(watchStrip,new LinearLayout.LayoutParams(-1,dp(132)));
        updateNotice=button("",()->appUpdater.showAvailable());updateNotice.setVisibility(View.GONE);updateNotice.setTextColor(mint);updateNotice.setContentDescription("Glide update available. Select to review and install.");FrameLayout.LayoutParams noticeLp=new FrameLayout.LayoutParams(-2,dp(34),Gravity.BOTTOM|Gravity.END);noticeLp.setMargins(0,0,dp(40),dp(12));root.addView(updateNotice,noticeLp);
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
                String revision=r.activityInfo.applicationInfo.sourceDir;String label=prefs.getString("app-label:"+pkg,null);if(!revision.equals(prefs.getString("app-revision:"+pkg,""))||label==null){label=r.loadLabel(pm).toString();prefs.edit().putString("app-label:"+pkg,label).putString("app-revision:"+pkg,revision).apply();}
                Intent i=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(pkg,r.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Drawable icon=icons.get(pkg);
                if(icon==null){try{
                    java.io.File directory=new java.io.File(getCacheDir(),"icons");directory.mkdirs();java.io.File file=new java.io.File(directory,pkg+"-"+Integer.toHexString(revision.hashCode())+".png");Bitmap bmp=file.isFile()?BitmapFactory.decodeFile(file.getAbsolutePath()):null;
                    if(bmp==null){Drawable raw=r.loadIcon(pm);int edge=dp(favorites.contains(pkg)?76:48);bmp=Bitmap.createBitmap(edge,edge,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bmp);raw.setBounds(0,0,edge,edge);raw.draw(canvas);try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){bmp.compress(Bitmap.CompressFormat.PNG,100,out);}}
                    icon=new BitmapDrawable(getResources(),bmp);icons.put(pkg,icon);
                }catch(Exception ignored){}}
                found.add(new App(pkg,label,i,icon));
            }
            Collections.sort(found,(a,b)->a.label.compareToIgnoreCase(b.label));
            handler.post(()->{loading=false;if(isDestroyed())return;apps.clear();apps.addAll(found);if(!prefs.contains("favorites")){for(int n=0;n<Math.min(6,apps.size());n++)favorites.add(apps.get(n).pkg);prefs.edit().putString("favorites",String.join(",",favorites)).apply();}loaded=true;updateApps();});
        });
    }
    App app(String p){for(App a:apps)if(a.pkg.equals(p))return a;return null;}
    void updateApps(){
        renderFavorites();count.setText(apps.size()+" apps in your library");
    }
    void renderFavorites(){
        View old=getCurrentFocus();String focus=restoreFocus!=null?restoreFocus:old!=null&&old.getTag() instanceof String?(String)old.getTag():null;restoreFocus=null;
        favoriteRow.removeAllViews();firstApp=null;
        for(String p:favorites){App a=app(p);if(a==null)continue;View item=tile(a,true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(132),dp(128));lp.setMarginEnd(dp(10));favoriteRow.addView(item,lp);if(firstApp==null)firstApp=item;}
        if(firstApp==null){favoriteRow.addView(button("Choose favorites",()->showLibrary()));}
        View target=focus==null?null:favoriteRow.findViewWithTag(focus);if(target!=null)target.requestFocus();else if(firstApp!=null){firstApp.requestFocus();selectApp(appsForFirstFavorite());}
    }
    App appsForFirstFavorite(){for(String p:favorites){App a=app(p);if(a!=null)return a;}return null;}
    void selectApp(App a){if(a==null||a.pkg.equals(selectedPkg))return;selectedPkg=a.pkg;handler.removeCallbacks(selectWatch);handler.postDelayed(selectWatch,16);}
    View tile(App a,boolean favorite){
        LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setClipChildren(false);item.setClipToPadding(false);item.setPadding(dp(5),dp(4),dp(5),dp(4));item.setTag((favorite?"fav:":"app:")+a.pkg);item.setContentDescription(a.label+(favorite?", favorite. Hold OK to move.":". Hold OK to pin."));
        FrameLayout halo=new FrameLayout(this);halo.setPadding(dp(4),dp(4),dp(4),dp(4));ImageView logo=new ImageView(this);logo.setImageDrawable(a.pkg.equals("com.netflix.ninja")?new NetflixIcon():a.icon);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setBackgroundColor(0xff111724);logo.setClipToOutline(true);logo.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,android.graphics.Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(16));}});halo.addView(logo,new FrameLayout.LayoutParams(-1,-1));item.addView(halo,new LinearLayout.LayoutParams(dp(favorite?84:70),dp(favorite?84:70)));gap(item,favorite?18:8);
        TextView name=text(a.label,11,ink);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER);item.addView(name,new LinearLayout.LayoutParams(-1,-2));item.setFocusable(true);item.setClickable(true);
        item.setOnFocusChangeListener((v,f)->{halo.setBackground(null);halo.animate().cancel();halo.animate().scaleX(f?1.35f:1).scaleY(f?1.35f:1).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();if(f&&favorite)selectApp(a);name.setTextColor(f?Color.WHITE:0xffcbd7e9);});
        item.setOnClickListener(v->{if(!favorite&&libraryDialog!=null)libraryDialog.dismiss();open(a.intent);});item.setOnLongClickListener(v->{if(favorite)startMove(a);else appOptions(a,false);return true;});
        item.setOnKeyListener((v,k,e)->{if(k==KeyEvent.KEYCODE_MENU&&e.getAction()==KeyEvent.ACTION_DOWN){appOptions(a,favorite);return true;}return false;});return item;
    }
    void startMove(App a){movingPkg=a.pkg;moveBackup=new ArrayList<>(favorites);View v=favoriteRow.findViewWithTag("fav:"+a.pkg);if(v!=null)v.setAlpha(.65f);}
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        int k=e.getKeyCode();if(e.getAction()==KeyEvent.ACTION_DOWN&&backdrop!=null)backdrop.pauseMotionFor(280);if(consumeCenterUp&&e.getAction()==KeyEvent.ACTION_UP&&(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER)){consumeCenterUp=false;return true;}
        if(movingPkg!=null){
            if(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT){if(e.getAction()==KeyEvent.ACTION_DOWN){int old=favorites.indexOf(movingPkg),next=old+(k==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);if(next>=0&&next<favorites.size()){String other=favorites.get(next);Collections.swap(favorites,old,next);View current=favoriteRow.findViewWithTag("fav:"+movingPkg),neighbor=favoriteRow.findViewWithTag("fav:"+other);if(current!=null&&neighbor!=null){playRemoteSound(k==KeyEvent.KEYCODE_DPAD_LEFT?"left":"right");int target=favoriteRow.indexOfChild(neighbor);favoriteRow.removeView(current);favoriteRow.addView(current,target);current.requestFocus();}}}return true;}
            if(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){movingPkg=null;consumeCenterUp=true;saveFavorites();}return true;}
            if(k==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_DOWN){String pkg=movingPkg;movingPkg=null;favorites.clear();favorites.addAll(moveBackup);restoreFocus="fav:"+pkg;renderFavorites();}return true;}
            return true;
        }
        if(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT){
            View focus=getCurrentFocus();android.view.ViewGroup region=isInside(focus,favoriteRow)?favoriteRow:isInside(focus,watchRow)?watchRow:null;
            if(region!=null){if(e.getAction()==KeyEvent.ACTION_DOWN){ArrayList<View> choices=region.getFocusables(View.FOCUS_FORWARD);int index=choices.indexOf(focus),next=index+(k==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);if(index>=0&&next>=0&&next<choices.size()){playRemoteSound(k==KeyEvent.KEYCODE_DPAD_LEFT?"left":"right");choices.get(next).requestFocus();}}return true;}
        }
        if(e.getAction()==KeyEvent.ACTION_DOWN&&(k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_DPAD_DOWN)&&moveFocusVertically(k==KeyEvent.KEYCODE_DPAD_UP?"up":"down"))return true;
        return super.dispatchKeyEvent(e);
    }
    private boolean moveFocusVertically(String direction){View current=getCurrentFocus();if(current==null){if(firstApp==null)return false;playRemoteSound(direction);firstApp.requestFocus();selectApp(appsForFirstFavorite());return true;}View next=current.focusSearch("up".equals(direction)?View.FOCUS_UP:View.FOCUS_DOWN);if(next==null||next==current)return false;playRemoteSound(direction);next.requestFocus();if(next.getTag() instanceof String&&((String)next.getTag()).startsWith("fav:"))selectApp(app(((String)next.getTag()).substring(4)));return true;}
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
    void options(){new AlertDialog.Builder(this).setTitle("Glide TV").setItems(new String[]{"TV settings","Location & scenery","Refresh apps and watch history","Mobile control","Check for updates","About Glide TV"},(d,n)->{
        if(n==0)open(new Intent(Settings.ACTION_SETTINGS));
        else if(n==1)chooseWeatherCity();
        else if(n==2){loadApps();loadWatchNext();}
        else if(n==3)showMobileControl();
        else if(n==4){if(appUpdater==null)appUpdater=new AppUpdater(this);appUpdater.check();}
        else new AlertDialog.Builder(this).setTitle("Glide Glass").setMessage("Glide TV 4.5.0\n\nHold OK on a favorite to move it, then use left/right. OK saves, Back cancels. Press Menu for other app options. Pin apps from the library with a long press.\n\nContinue Watching shows only history shared by the highlighted app. RAM and storage update every five seconds while Home is visible.\n\nWeather: Open-Meteo (open-meteo.com). City search: Open-Meteo / GeoNames. Air quality: CAMS ENSEMBLE via Open-Meteo. CC BY 4.0. AQI uses the US scale; UV and rain chance are hourly forecasts. No ads or analytics.").setPositiveButton("Done",null).show();
    }).setNegativeButton("Close",null).show();}
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
        watchTitle.setText("Continue in "+selected.label);int limit=Math.min(12,visible.size());watchCount.setText(visible.isEmpty()?"":limit<visible.size()?limit+" of "+visible.size()+" titles":visible.size()+" titles");
        watchRow.removeAllViews();watchStrip.scrollTo(0,0);watchRow.setAlpha(1);
        if(visible.isEmpty()){
            if(emptyWatch==null){emptyWatch=column();emptyWatch.setGravity(Gravity.CENTER_VERTICAL);emptyWatch.setPadding(dp(20),dp(15),dp(20),dp(15));emptyWatchTitle=text("",18,0xffd4dfef);emptyWatchTitle.setTypeface(android.graphics.Typeface.create("sans-serif-light",0));emptyWatch.addView(emptyWatchTitle);gap(emptyWatch,8);emptyWatch.addView(text("Shared watch history appears here when this app provides it.",11,0xffa9bdd5));}
            emptyWatchTitle.setText("Nothing to resume in "+selected.label);watchRow.addView(emptyWatch,new LinearLayout.LayoutParams(dp(850),dp(124)));
        }else for(int n=0;n<limit;n++){if(n>=watchPool.size())watchPool.add(makeWatchCard());WatchHolder holder=watchPool.get(n);bindWatchCard(holder,visible.get(n));watchRow.addView(holder.frame);}
        for(int n=limit;n<watchPool.size();n++){WatchHolder unused=watchPool.get(n);unused.item=null;unused.image.setTag("");unused.image.setImageDrawable(null);}
        handler.removeCallbacks(trimIdle);handler.postDelayed(trimIdle,1600);
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
        backdrop.setTimeZone(data.optString("timezone",weatherLocation.timezone()));weatherSymbol.setText(code==0?(weatherIsDay?"☀":"☾"):(code>=71&&code<=77)||code==85||code==86?"❄":code<=3?"☁":code<=67?"☂":"☁");weatherSymbol.setTextColor(code==0?(weatherIsDay?0xffffd18a:0xffd9cbff):code<=3?0xffdae8ff:0xff92cfff);backdrop.setEnvironment(data.optDouble("wind_speed_10m",Double.NaN),data.optDouble("cloud_cover",Double.NaN),data.optString("sunrise",""),data.optString("sunset",""));backdrop.setConditions(code,weatherIsDay,Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
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
    void loadWatchNext(){
        if(watchRow==null)return;
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
        watchRow.removeAllViews();watchCount.setText(permission?"Permission needed":"Shared by supported apps");LinearLayout box=column();box.setGravity(Gravity.CENTER_VERTICAL);box.setPadding(dp(22),dp(18),dp(22),dp(18));box.setBackground(new GlassDrawable(dp(22),false));
        TextView t=text(title,19,ink);t.setTypeface(android.graphics.Typeface.create("sans-serif-light",0));box.addView(t);gap(box,9);box.addView(text(subtitle,12,0xffc0cce1));
        if(permission){gap(box,12);box.addView(button("Enable Continue Watching",()->requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"},40)));}
        watchRow.addView(box,new LinearLayout.LayoutParams(dp(860),dp(124)));
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] grants){super.onRequestPermissionsResult(code,names,grants);if(code==WeatherLocation.PERMISSION)weatherLocation.permissionResult();else if(code==40)loadWatchNext();else if(code==41){connectivity.update();connectivity.openWifiSettings();}else if(code==42){connectivity.update();connectivity.openBluetoothSettings();}else if(code==43)connectivity.update();}
    private static class WatchHolder {FrameLayout frame;ImageView image;TextView service,title;ProgressBar progress;WatchItem item;}
    WatchHolder makeWatchCard(){
        WatchHolder holder=new WatchHolder();FrameLayout frame=new FrameLayout(this);holder.frame=frame;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(246),dp(124));lp.setMarginEnd(dp(13));frame.setLayoutParams(lp);frame.setClipToOutline(true);frame.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,android.graphics.Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(18));}});
        holder.image=new ImageView(this);holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);holder.image.setBackgroundColor(0xff29364b);frame.addView(holder.image,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x05071422,0x30080e1c,0xf0080e1c}));frame.addView(shade,new FrameLayout.LayoutParams(-1,-1));LinearLayout words=column();words.setPadding(dp(14),0,dp(14),dp(10));holder.service=text("",9,0xffd1e2f2);holder.service.setLetterSpacing(.1f);words.addView(holder.service);gap(words,4);holder.title=text("",14,ink);holder.title.setMaxLines(1);holder.title.setEllipsize(android.text.TextUtils.TruncateAt.END);holder.title.setTypeface(null,1);words.addView(holder.title);gap(words,6);
        holder.progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);holder.progress.setMax(1000);holder.progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe1f3ff));holder.progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55ffffff));words.addView(holder.progress,new LinearLayout.LayoutParams(-1,dp(3)));frame.addView(words,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));View border=new View(this);border.setBackground(new GlassDrawable(dp(18),false,true));frame.addView(border,new FrameLayout.LayoutParams(-1,-1));frame.setFocusable(true);frame.setClickable(true);frame.setOnFocusChangeListener((v,f)->border.setBackground(new GlassDrawable(dp(18),f,true)));
        frame.setOnClickListener(v->{WatchItem w=holder.item;if(w==null)return;try{Intent i=Intent.parseUri(w.intent,Intent.URI_INTENT_SCHEME);i.setPackage(w.pkg);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);open(i);}catch(Exception e){toast("This app’s resume link is unavailable");}});return holder;
    }
    void bindWatchCard(WatchHolder holder,WatchItem item){holder.item=item;App owner=app(item.pkg);String name=owner==null?item.pkg:owner.label;holder.service.setText(name.toUpperCase(Locale.getDefault()));holder.title.setText(item.title);holder.progress.setVisibility(item.duration>0?View.VISIBLE:View.INVISIBLE);holder.progress.setProgress(item.duration>0?(int)Math.min(1000,Math.max(0,1000*item.position/item.duration)):0);holder.frame.setContentDescription("Resume "+item.title+" in "+name);holder.image.setTag(item.art);holder.image.setImageDrawable(null);loadArtwork(item.art,holder.image);}
    void loadArtwork(String uri,ImageView target){
        if(uri==null||uri.isEmpty())return;Bitmap cached=artCache.get(uri);if(cached!=null){target.setImageBitmap(cached);return;}
        java.lang.ref.WeakReference<ImageView> destination=new java.lang.ref.WeakReference<>(target);
        artwork.execute(()->{try{
            android.net.Uri u=android.net.Uri.parse(uri);java.io.InputStream stream;
            if("https".equals(u.getScheme())){java.net.URLConnection connection=new java.net.URL(uri).openConnection();connection.setConnectTimeout(4000);connection.setReadTimeout(4000);stream=connection.getInputStream();}
            else if("content".equals(u.getScheme())||"android.resource".equals(u.getScheme()))stream=getContentResolver().openInputStream(u);else return;
            if(stream==null)return;byte[] data;try(java.io.InputStream in=stream;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>2*1024*1024)return;out.write(buf,0,n);}data=out.toByteArray();}
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);if(bounds.outWidth<1||bounds.outHeight<1)return;
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;while(bounds.outWidth/opts.inSampleSize>720||bounds.outHeight/opts.inSampleSize>720)opts.inSampleSize*=2;opts.inPreferredConfig=Bitmap.Config.HARDWARE;
            Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,opts);if(bitmap!=null){artCache.put(uri,bitmap);handler.post(()->{ImageView image=destination.get();if(!isDestroyed()&&image!=null&&uri.equals(image.getTag()))image.setImageBitmap(bitmap);});}
        }catch(Exception ignored){}});
    }
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    void open(Intent i){try{startActivity(i);}catch(Exception e){toast("Unable to open. Check that the app is enabled.");}}
}
