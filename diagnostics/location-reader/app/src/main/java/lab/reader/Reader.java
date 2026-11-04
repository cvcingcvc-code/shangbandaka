package lab.reader;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Independent diagnostic app. Results stay on-screen; no network, file or coordinate logging. */
public class Reader extends Activity {
    private LocationManager manager;
    private TextView view;
    private final StringBuilder history = new StringBuilder();
    private final List<LocationListener> listeners = new ArrayList<>();
    private boolean subscribed;
    private long sequence;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        view = new TextView(this);
        view.setTextSize(14);
        view.setTextIsSelectable(true);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(true);
        scroll.addView(view);
        installSystemBarInsets(scroll);
        setContentView(scroll);
        scroll.requestApplyInsets();
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        } else subscribe();
    }

    @SuppressWarnings("deprecation")
    private void installSystemBarInsets(ScrollView scroll) {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        scroll.setOnApplyWindowInsetsListener((content, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                content.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                content.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) subscribe();
        else append("需要精确位置权限才能读取。");
    }

    private void subscribe() {
        if (subscribed) return;
        subscribed = true;
        append("独立读取 GPS / network / Android 系统 fused；不含 Google FLP。\n只显示回调记录，不把旧缓存算作订阅后新样本。数据保留于本页内存，退出后不保存。");
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}) {
            try {
                if (!manager.getAllProviders().contains(provider)) { append(provider + "：设备未提供"); continue; }
                final long subscribedElapsedNanos = SystemClock.elapsedRealtimeNanos();
                final long subscribedWallMillis = System.currentTimeMillis();
                final long[] lastSampleElapsedNanos = {subscribedElapsedNanos};
                LocationListener listener = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        displayLocation(provider, subscribedElapsedNanos, subscribedWallMillis, lastSampleElapsedNanos, location);
                    }
                    @Override public void onProviderEnabled(String name) { append("订阅入口=" + provider + "：provider 已启用 " + name); }
                    @Override public void onProviderDisabled(String name) { append("订阅入口=" + provider + "：provider 已关闭 " + name); }
                    @SuppressWarnings("deprecation")
                    @Override public void onStatusChanged(String name, int status, Bundle extras) {
                        append("订阅入口=" + provider + "：provider 状态变化 " + name + " status=" + status);
                    }
                };
                manager.requestLocationUpdates(provider, 1000L, 0f, listener);
                listeners.add(listener);
                append(String.format(Locale.US, "%s：已订阅，起点 elapsedNanos=%d，wallMillis=%d；等待新回调", provider, subscribedElapsedNanos, subscribedWallMillis));
            } catch (Exception e) { append(provider + "：订阅失败 " + e.getClass().getSimpleName() + " " + e.getMessage()); }
        }
    }

    private void displayLocation(String subscribedProvider, long subscribedElapsedNanos, long subscribedWallMillis, long[] lastSampleElapsedNanos, Location location) {
        long receivedElapsedNanos = SystemClock.elapsedRealtimeNanos();
        long receivedWallMillis = System.currentTimeMillis();
        long locationElapsedNanos = location.getElapsedRealtimeNanos();
        boolean mock = Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
        long ageMs = (receivedElapsedNanos - locationElapsedNanos) / 1000000L;
        boolean afterSubscription = locationElapsedNanos > subscribedElapsedNanos && locationElapsedNanos <= receivedElapsedNanos;
        boolean newSample = afterSubscription && locationElapsedNanos > lastSampleElapsedNanos[0];
        String sampleStatus = locationElapsedNanos > receivedElapsedNanos ? "未来时间戳" :
            (newSample ? "订阅后新样本" : (afterSubscription ? "重复或乱序样本" : "订阅前或无有效时间戳"));
        if (newSample) lastSampleElapsedNanos[0] = locationElapsedNanos;
        String localTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date(location.getTime()));
        append(String.format(Locale.US,
            "#%d 订阅入口=%s 实际provider=%s\n坐标(WGS84) %.6f, %.6f 精度=%.1fm isMock=%s\nLocation.time=%d (%s)\nlocation.elapsedRealtimeNanos=%d\n接收elapsedNanos=%d 接收wallMillis=%d\n订阅起点elapsedNanos=%d wallMillis=%d\n接收时数据年龄=%dms；订阅后新样本=%s (%s)\n",
            ++sequence, subscribedProvider, location.getProvider(), location.getLatitude(), location.getLongitude(), location.getAccuracy(), mock,
            location.getTime(), localTime, locationElapsedNanos, receivedElapsedNanos, receivedWallMillis,
            subscribedElapsedNanos, subscribedWallMillis, ageMs, newSample, sampleStatus));
    }

    private void append(String text) {
        history.insert(0, text + "\n");
        if (history.length() > 20000) history.setLength(20000);
        view.setText(history.toString());
    }

    @Override protected void onDestroy() {
        for (LocationListener listener : listeners) {
            try { manager.removeUpdates(listener); } catch (Exception ignored) { /* Activity is closing; do not log coordinates. */ }
        }
        super.onDestroy();
    }
}
