package space.glidetv.launcher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** An inline, status-bar-like row of selectable connection indicators. */
public final class ConnectivityStatus extends LinearLayout {
    public interface PermissionRequest { void request(String permission, int requestCode); }

    private final Activity activity;
    private final PermissionRequest permissionRequest;
    private final StatusItem wifi, ethernet, bluetooth;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    public ConnectivityStatus(Activity activity, PermissionRequest permissionRequest) {
        super(activity);
        this.activity = activity;
        this.permissionRequest = permissionRequest;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        wifi = item(Glyph.WIFI, "", "Wi-Fi network name", this::selectWifi);
        ethernet = item(Glyph.ETHERNET, "LAN", "Wired network", this::openInternetSettings);
        bluetooth = item(Glyph.BLUETOOTH, "", "Bluetooth device", this::selectBluetooth);
        update();
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private StatusItem item(int icon, String label, String description, Runnable action) {
        StatusItem result = new StatusItem(activity, icon, label, description);
        result.setFocusable(true);
        result.setClickable(true);
        result.setOnClickListener(v -> action.run());
        result.setOnFocusChangeListener((v, focused) -> result.focused(focused));
        LayoutParams lp = new LayoutParams(-2, dp(32));
        lp.setMarginStart(dp(12));
        addView(result, lp);
        return result;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            update();
            handler.postDelayed(this, 4000);
        }
    };

    public void start() {
        running = true;
        handler.removeCallbacks(poll);
        update();
        handler.postDelayed(poll, 4000);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(poll);
    }

    public void requestInitialStatusPermissions(int requestCode) {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);missing.add(Manifest.permission.ACCESS_FINE_LOCATION);}
        if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!missing.isEmpty()) activity.requestPermissions(missing.toArray(new String[0]), requestCode);
    }

    public void update() {
        boolean wifiConnected = false, lanConnected = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null) {
                    wifiConnected |= caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    lanConnected |= caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                }
            }
        } catch (Exception ignored) { }

        String ssid = "";
        if (wifiConnected && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                WifiManager manager = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = manager == null ? null : manager.getConnectionInfo();
                ssid = info == null ? "" : info.getSSID();
                if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)) ssid = "";
                else if (ssid.length() > 1 && ssid.charAt(0) == '"' && ssid.charAt(ssid.length() - 1) == '"') ssid = ssid.substring(1, ssid.length() - 1);
            } catch (Exception ignored) { ssid = ""; }
        }
        wifi.setVisibility(wifiConnected ? VISIBLE : GONE);
        wifi.setLabel(ssid);

        ethernet.setVisibility(lanConnected ? VISIBLE : GONE);

        boolean btConnected = hasBluetoothConnection();
        bluetooth.setVisibility(btConnected ? VISIBLE : GONE);
    }

    private boolean hasBluetoothConnection() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;
            if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;
            if (adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) return true;
            if (adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED) return true;
            if (Build.VERSION.SDK_INT >= 28 && adapter.getProfileConnectionState(BluetoothProfile.HEARING_AID) == BluetoothProfile.STATE_CONNECTED) return true;
            return Build.VERSION.SDK_INT >= 33 && adapter.getProfileConnectionState(BluetoothProfile.LE_AUDIO) == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception ignored) { return false; }
    }

    private void selectWifi() {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.request(Manifest.permission.ACCESS_FINE_LOCATION, 41);
            return;
        }
        openInternetSettings();
    }

    private void selectBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.request(Manifest.permission.BLUETOOTH_CONNECT, 42);
            return;
        }
        openBluetoothSettings();
    }

    public void openWifiSettings() { openPanel("android.settings.panel.action.INTERNET_CONNECTIVITY", Settings.ACTION_WIFI_SETTINGS); }
    public void openBluetoothSettings() { openPanel("android.settings.panel.action.BLUETOOTH", Settings.ACTION_BLUETOOTH_SETTINGS); }
    private void openInternetSettings() { openWifiSettings(); }

    private void openPanel(String action, String fallback) {
        try {
            Intent panel = new Intent(action);
            if (panel.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(panel);
                return;
            }
        } catch (Exception ignored) { }
        try { activity.startActivity(new Intent(fallback)); }
        catch (Exception ignored) { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private final class StatusItem extends LinearLayout {
        private final Glyph glyph;
        private final TextView text;
        private final int normal = 0xffdce8f1, active = 0xffd6f4e3;

        StatusItem(Context context, int icon, String label, String description) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(2), 0, dp(2), 0);
            glyph = new Glyph(context, icon);
            addView(glyph, new LayoutParams(dp(24), dp(24)));
            text = new TextView(context);
            text.setText(label);
            text.setTextSize(12);
            text.setTextColor(normal);
            text.setSingleLine(true);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setMaxWidth(dp(165));
            LayoutParams tp = new LayoutParams(-2, -2);
            tp.setMarginStart(dp(5));
            addView(text, tp);
            setContentDescription(description + ". Select to open settings.");
        }

        void setLabel(String value) {
            text.setText(value);
            setContentDescription((value.isEmpty() ? "Wi-Fi network" : "Wi-Fi " + value) + ". Select to open Wi-Fi settings.");
        }

        void focused(boolean value) {
            text.setTextColor(value ? active : normal);
            glyph.setColor(value ? active : normal);
            animate().cancel();
            animate().scaleX(value ? 1.06f : 1f).scaleY(value ? 1.06f : 1f).setDuration(140).start();
        }
    }

    private static final class Glyph extends View {
        static final int WIFI = 0, ETHERNET = 1, BLUETOOTH = 2;
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = 0xffdce8f1;
        Glyph(Context context, int type) { super(context); this.type = type; }
        void setColor(int value) { color = value; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f*s); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            if (type == WIFI) {
                float cx = w*.5f, cy = h*.72f;
                canvas.drawArc(cx-8*s, cy-11*s, cx+8*s, cy+5*s, 225, 90, false, paint);
                canvas.drawArc(cx-5.5f*s, cy-7.5f*s, cx+5.5f*s, cy+3.5f*s, 225, 90, false, paint);
                canvas.drawArc(cx-2.5f*s, cy-4.5f*s, cx+2.5f*s, cy+.5f*s, 225, 90, false, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx,cy,1.2f*s,paint);
            } else if (type == ETHERNET) {
                Path p = new Path();
                p.moveTo(w*.5f,h*.12f); p.lineTo(w*.5f,h*.48f); p.moveTo(w*.22f,h*.48f); p.lineTo(w*.78f,h*.48f);
                p.moveTo(w*.28f,h*.48f); p.lineTo(w*.28f,h*.76f); p.lineTo(w*.12f,h*.76f); p.lineTo(w*.12f,h*.9f); p.lineTo(w*.4f,h*.9f); p.lineTo(w*.4f,h*.76f); p.lineTo(w*.28f,h*.76f);
                p.moveTo(w*.72f,h*.48f); p.lineTo(w*.72f,h*.76f); p.lineTo(w*.6f,h*.76f); p.lineTo(w*.6f,h*.9f); p.lineTo(w*.88f,h*.9f); p.lineTo(w*.88f,h*.76f); p.lineTo(w*.72f,h*.76f);
                canvas.drawPath(p,paint);
            } else {
                Path p = new Path();
                p.moveTo(w*.5f,h*.05f); p.lineTo(w*.79f,h*.29f); p.lineTo(w*.5f,h*.5f); p.lineTo(w*.21f,h*.23f);
                p.moveTo(w*.21f,h*.77f); p.lineTo(w*.5f,h*.5f); p.lineTo(w*.79f,h*.71f); p.lineTo(w*.5f,h*.95f);
                p.moveTo(w*.5f,h*.05f); p.lineTo(w*.5f,h*.95f);
                canvas.drawPath(p,paint);
            }
        }
    }
}
