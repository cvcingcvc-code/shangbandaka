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
import java.util.Iterator;

/** Independent diagnostic app. Results stay on-screen; no network, file or coordinate logging. */
public class Reader extends Activity {
    private LocationManager manager;
    private TextView view;
    private final StringBuilder history = new StringBuilder();
    private final List<LocationListener> listeners = new ArrayList<>();
    private boolean resumed;
    private long generation;
    private long sequence;
    private static final long MAX_AGE_NANOS = 5_000_000_000L;

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
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        startReadRound();
    }

    @Override protected void onPause() {
        resumed = false;
        String cleanupError = cancelSubscriptions();
        history.setLength(0);
        append("读取已暂停。返回此页将清空旧显示并建立新一轮订阅，不沿用旧回调。" +
            (cleanupError == null ? "" : "\n" + cleanupError));
        super.onPause();
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
        if (code != 1) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (resumed) startReadRound();
        } else append("未授予精确位置权限，未开始读取；可在系统应用设置中授予后返回此页。");
    }

    /** A new foreground round owns its own listeners and monotonic start boundary. */
    private void startReadRound() {
        String cleanupError = cancelSubscriptions();
        history.setLength(0);
        sequence = 0;
        final long round = generation;
        append("读取轮次=" + round + "；返回前台已重建订阅起点。可用样本只代表时间和坐标有效，不代表目标匹配或恢复已通过。");
        if (cleanupError != null) { append(cleanupError + "；未创建重复订阅，请重新进入此页重试。"); return; }
        if (!resumed) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            append("精确位置权限不可用，未开始订阅。");
            return;
        }
        append("独立读取 GPS / network / Android 系统 fused；不含 Google FLP。\n可用样本必须晚于本轮订阅、时间递增且年龄在0～5000ms内。数据保留于本页内存，暂停后清空。");
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}) {
            try {
                if (!manager.getAllProviders().contains(provider)) { append(provider + "：设备未提供"); continue; }
                final long subscribedElapsedNanos = SystemClock.elapsedRealtimeNanos();
                final long subscribedWallMillis = System.currentTimeMillis();
                final long[] lastSampleElapsedNanos = {subscribedElapsedNanos};
                LocationListener listener = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        if (!isActiveRound(round)) return;
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            String error = cancelSubscriptions();
                            history.setLength(0);
                            append("精确定位权限已撤销，本轮读取已停止；没有有效的当前读取证据。" +
                                (error == null ? "" : "\n" + error));
                            return;
                        }
                        displayLocation(round, provider, subscribedElapsedNanos, subscribedWallMillis, lastSampleElapsedNanos, location);
                    }
                    @Override public void onProviderEnabled(String name) {
                        if (isActiveRound(round)) append("订阅入口=" + provider + "：provider 已启用 " + name);
                    }
                    @Override public void onProviderDisabled(String name) {
                        if (isActiveRound(round)) append("订阅入口=" + provider + "：provider 已关闭 " + name);
                    }
                    @SuppressWarnings("deprecation")
                    @Override public void onStatusChanged(String name, int status, Bundle extras) {
                        if (isActiveRound(round)) append("订阅入口=" + provider + "：provider 状态变化 " + name + " status=" + status);
                    }
                };
                listeners.add(listener);
                manager.requestLocationUpdates(provider, 1000L, 0f, listener);
                append(String.format(Locale.US, "%s：已订阅，起点 elapsedNanos=%d，wallMillis=%d；等待新回调", provider, subscribedElapsedNanos, subscribedWallMillis));
            } catch (SecurityException e) {
                String error = cancelSubscriptions();
                history.setLength(0);
                append(provider + "：定位权限不可用，本轮读取已停止。" + (error == null ? "" : "\n" + error));
                return;
            }
            catch (Exception e) { append(provider + "：订阅失败 " + e.getClass().getSimpleName() + " " + e.getMessage()); }
        }
    }

    private boolean isActiveRound(long round) { return resumed && generation == round; }

    private String cancelSubscriptions() {
        ++generation; // Invalidate already-queued callbacks before removal.
        if (manager == null) return null;
        List<String> errors = new ArrayList<>();
        Iterator<LocationListener> iterator = listeners.iterator();
        while (iterator.hasNext()) {
            try { manager.removeUpdates(iterator.next()); iterator.remove(); }
            catch (Exception error) { errors.add("移除旧监听器失败：" + error.getClass().getSimpleName()); }
        }
        return errors.isEmpty() ? null : String.join("；", errors);
    }

    private void displayLocation(long round, String subscribedProvider, long subscribedElapsedNanos, long subscribedWallMillis, long[] lastSampleElapsedNanos, Location location) {
        if (!isActiveRound(round)) return;
        long receivedElapsedNanos = SystemClock.elapsedRealtimeNanos();
        long receivedWallMillis = System.currentTimeMillis();
        long locationElapsedNanos = location.getElapsedRealtimeNanos();
        boolean mock = Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
        long ageNanos = receivedElapsedNanos - locationElapsedNanos;
        double ageMs = ageNanos / 1_000_000.0;
        boolean afterSubscription = locationElapsedNanos > subscribedElapsedNanos && locationElapsedNanos <= receivedElapsedNanos;
        boolean increasing = locationElapsedNanos > lastSampleElapsedNanos[0];
        boolean fresh = ageNanos >= 0L && ageNanos <= MAX_AGE_NANOS;
        boolean coordinatesValid = Double.isFinite(location.getLatitude()) && Double.isFinite(location.getLongitude()) &&
            location.getLatitude() >= -90.0 && location.getLatitude() <= 90.0 &&
            location.getLongitude() >= -180.0 && location.getLongitude() <= 180.0;
        boolean usable = afterSubscription && increasing && fresh && coordinatesValid;
        String sampleStatus = locationElapsedNanos > receivedElapsedNanos ? "未来时间戳" :
            (!afterSubscription ? "订阅前或无有效时间戳" : (!increasing ? "重复或乱序样本" :
                (!fresh ? "样本已过期" : (!coordinatesValid ? "坐标无效" : "本轮可用的新鲜样本"))));
        if (afterSubscription && increasing) lastSampleElapsedNanos[0] = locationElapsedNanos;
        String localTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date(location.getTime()));
        append(String.format(Locale.US,
            "轮次=%d #%d 订阅入口=%s 实际provider=%s\n坐标(WGS84) %.6f, %.6f 精度=%.1fm isMock=%s\nLocation.time=%d (%s)\nlocation.elapsedRealtimeNanos=%d\n接收elapsedNanos=%d 接收wallMillis=%d\n订阅起点elapsedNanos=%d wallMillis=%d\n接收时数据年龄=%.3fms\n晚于本轮订阅=%s；递增样本=%s；新鲜(0～5000ms)=%s；坐标有效=%s\n本轮可用样本=%s (%s)\n",
            round, ++sequence, subscribedProvider, location.getProvider(), location.getLatitude(), location.getLongitude(), location.getAccuracy(), mock,
            location.getTime(), localTime, locationElapsedNanos, receivedElapsedNanos, receivedWallMillis,
            subscribedElapsedNanos, subscribedWallMillis, ageMs, afterSubscription, increasing, fresh, coordinatesValid, usable, sampleStatus));
    }

    private void append(String text) {
        history.insert(0, text + "\n");
        if (history.length() > 20000) history.setLength(20000);
        view.setText(history.toString());
    }

    @Override protected void onDestroy() {
        resumed = false;
        cancelSubscriptions();
        super.onDestroy();
    }
}
