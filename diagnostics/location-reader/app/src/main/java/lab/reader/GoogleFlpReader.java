package lab.reader;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.Locale;

/** Read-only Google Play services diagnostic. Coordinates remain in this screen's memory. */
public class GoogleFlpReader extends Activity {
    private static final long MAX_AGE_NANOS = 5_000_000_000L;
    private static final long SUBSCRIBE_TIMEOUT_MILLIS = 12_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder history = new StringBuilder();
    private FusedLocationProviderClient client;
    private TextView status;
    private TextView results;
    private Subscription subscription;
    private boolean resumed;
    private boolean destroyed;
    private boolean wantsRead;
    private long generation;

    private final class Subscription {
        final long round = ++generation;
        final long startedNanos = SystemClock.elapsedRealtimeNanos();
        final long startedWallMillis = System.currentTimeMillis();
        long lastSampleNanos = startedNanos;
        long lastUsableNanos;
        long sequence;
        boolean requesting = true;
        boolean removing;
        boolean cancelled;
        final LocationCallback callback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult result) {
                if (!isCurrent(Subscription.this)) return;
                if (!hasFinePermission()) {
                    failAndClean(Subscription.this, "精确定位权限已撤销，本轮读取已停止。");
                    return;
                }
                for (Location location : result.getLocations()) display(Subscription.this, location);
            }
        };
    }

    private final Runnable freshness = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            Subscription current = subscription;
            if (current != null && isCurrent(current) && !current.requesting) {
                if (!hasFinePermission()) {
                    failAndClean(current, "精确定位权限已撤销，本轮读取已停止。");
                } else {
                    long age = SystemClock.elapsedRealtimeNanos() - current.lastUsableNanos;
                    status.setText(current.lastUsableNanos > 0L && age >= 0L && age <= MAX_AGE_NANOS
                        ? "Google FLP 正在返回本轮新鲜样本，请核对目标坐标和 isMock。"
                        : "Google FLP 暂无可用的新鲜样本，等待回调。");
                }
            }
            main.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        LinearLayout contents = new LinearLayout(this);
        contents.setOrientation(LinearLayout.VERTICAL);
        contents.setPadding(padding, padding, padding, padding);
        contents.addView(text("Google Play 服务 FLP 独立读取\n请先确认标准 GPS、network 和系统 fused，再在此页作单独对照。"));
        Button retry = new Button(this);
        retry.setText("重新订阅 / 重试清理");
        retry.setOnClickListener(button -> {
            wantsRead = true;
            history.setLength(0);
            results.setText("");
            if (!hasFinePermission()) requestFinePermission();
            else if (subscription != null) stopSubscription(subscription);
            else startReadRound();
        });
        contents.addView(retry);
        status = text("准备检查 Google Play 服务。");
        contents.addView(status);
        results = text("");
        results.setTextIsSelectable(true);
        contents.addView(results);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(contents);
        installInsets(scroll);
        setContentView(scroll);
        scroll.requestApplyInsets();
        if (!hasFinePermission()) requestFinePermission();
    }

    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setTextSize(14);
        view.setText(value);
        return view;
    }

    private void requestFinePermission() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
    }

    private boolean hasFinePermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        wantsRead = true;
        history.setLength(0);
        results.setText("");
        startReadRound();
        main.removeCallbacks(freshness);
        main.post(freshness);
    }

    @Override protected void onPause() {
        resumed = false;
        wantsRead = false;
        main.removeCallbacks(freshness);
        history.setLength(0);
        results.setText("");
        status.setText("读取已暂停，返回后建立新一轮订阅。");
        if (subscription != null) stopSubscription(subscription);
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(code, permissions, grantResults);
        if (code != 1 || !resumed) return;
        if (hasFinePermission()) startReadRound();
        else status.setText("未授予精确位置权限，未开始读取。");
    }

    private void startReadRound() {
        if (!resumed || destroyed || !wantsRead) return;
        if (subscription != null) {
            if (subscription.cancelled) {
                status.setText("等待旧订阅解除，避免重复监听。");
                stopSubscription(subscription);
            }
            return;
        }
        if (!hasFinePermission()) {
            status.setText("需要精确位置权限后才能读取 Google FLP。");
            return;
        }
        int availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        if (availability != ConnectionResult.SUCCESS) {
            status.setText("Google Play 服务不可用，代码=" + availability + "。此设备的 Google FLP 尚不能测试。");
            return;
        }
        if (client == null) client = LocationServices.getFusedLocationProviderClient(this);
        Subscription current = new Subscription();
        subscription = current;
        status.setText("Google Play 服务可用，正在等待 FLP 订阅结果。");
        append(String.format(Locale.US,
            "轮次=%d；订阅起点 elapsedNanos=%d wallMillis=%d。只判断接收时年龄0～5000ms、晚于本轮且递增的样本。",
            current.round, current.startedNanos, current.startedWallMillis));
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMaxUpdateAgeMillis(0L)
            .setMaxUpdateDelayMillis(0L)
            .setWaitForAccurateLocation(false)
            .build();
        try {
            client.requestLocationUpdates(request, current.callback, Looper.getMainLooper())
                .addOnCompleteListener(task -> {
                    current.requesting = false;
                    if (subscription != current) return;
                    if (!task.isSuccessful()) {
                        failAndClean(current, "Google FLP 订阅失败，" + errorCode(task.getException()));
                    } else if (current.cancelled || !resumed || destroyed) {
                        stopSubscription(current);
                    } else {
                        status.setText("Google FLP 已确认订阅，等待本轮新回调。");
                    }
                });
            main.postDelayed(() -> {
                if (subscription == current && current.requesting && !current.cancelled) {
                    failAndClean(current, "Google FLP 订阅结果超时，当前读取无法确认；等待异步调用完成后清理。");
                }
            }, SUBSCRIBE_TIMEOUT_MILLIS);
        } catch (SecurityException error) {
            current.requesting = false;
            failAndClean(current, "Google FLP 拒绝定位读取权限，本轮已停止，正在请求清理订阅。");
        } catch (Exception error) {
            current.requesting = false;
            failAndClean(current, "请求 Google FLP 失败，" + errorCode(error));
        }
    }

    private boolean isCurrent(Subscription current) {
        return resumed && !destroyed && subscription == current && !current.cancelled;
    }

    private void failAndClean(Subscription current, String message) {
        if (subscription != current) return;
        wantsRead = false;
        status.setText(message);
        append(message);
        stopSubscription(current);
    }

    /** Removal is issued only after subscribe completes, so a late success cannot revive a listener. */
    private void stopSubscription(Subscription current) {
        current.cancelled = true;
        if (current.requesting || current.removing || subscription != current) return;
        current.removing = true;
        try {
            client.removeLocationUpdates(current.callback).addOnCompleteListener(task -> {
                current.removing = false;
                if (subscription != current) return;
                if (!task.isSuccessful()) {
                    wantsRead = false;
                    String message = "Google FLP 移除监听失败，" + errorCode(task.getException()) + "。请重试清理。";
                    if (!destroyed) { status.setText(message); append(message); }
                    return;
                }
                subscription = null;
                if (resumed && !destroyed && wantsRead) startReadRound();
            });
            main.postDelayed(() -> {
                if (subscription == current && current.removing) {
                    wantsRead = false;
                    if (!destroyed) {
                        String message = "Google FLP 移除结果超时，当前清理尚未确认；等待异步结果后重试。";
                        status.setText(message);
                        append(message);
                    }
                }
            }, SUBSCRIBE_TIMEOUT_MILLIS);
        } catch (Exception error) {
            current.removing = false;
            wantsRead = false;
            if (!destroyed) {
                String message = "请求移除 Google FLP 监听失败，" + errorCode(error) + "。请重试清理。";
                status.setText(message);
                append(message);
            }
        }
    }

    private static String errorCode(Exception error) {
        if (error instanceof ApiException) return "ApiException status=" + ((ApiException) error).getStatusCode();
        return error == null ? "无异常详情" : error.getClass().getSimpleName();
    }

    @SuppressWarnings("deprecation")
    private void display(Subscription current, Location location) {
        if (!isCurrent(current)) return;
        long receivedNanos = SystemClock.elapsedRealtimeNanos();
        long sampleNanos = location.getElapsedRealtimeNanos();
        long ageNanos = receivedNanos - sampleNanos;
        boolean afterSubscription = sampleNanos > current.startedNanos && sampleNanos <= receivedNanos;
        boolean increasing = sampleNanos > current.lastSampleNanos;
        boolean fresh = ageNanos >= 0L && ageNanos <= MAX_AGE_NANOS;
        boolean valid = Double.isFinite(location.getLatitude()) && Double.isFinite(location.getLongitude()) &&
            location.getLatitude() >= -90.0 && location.getLatitude() <= 90.0 &&
            location.getLongitude() >= -180.0 && location.getLongitude() <= 180.0;
        boolean usable = afterSubscription && increasing && fresh && valid;
        boolean mock = Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
        if (afterSubscription && increasing) current.lastSampleNanos = sampleNanos;
        if (usable) current.lastUsableNanos = sampleNanos;
        append(String.format(Locale.US,
            "轮次=%d #%d 订阅入口=Google FLP 实际provider=%s\n坐标(WGS84) %.6f, %.6f 精度=%.1fm isMock=%s\n" +
            "Location.time=%d location.elapsedRealtimeNanos=%d\n接收elapsedNanos=%d 接收wallMillis=%d\n" +
            "订阅起点elapsedNanos=%d wallMillis=%d\n接收时年龄=%.3fms\n" +
            "晚于本轮=%s 递增=%s 新鲜=%s 坐标有效=%s\n接收时可用样本=%s\n",
            current.round, ++current.sequence, location.getProvider(), location.getLatitude(), location.getLongitude(),
            location.getAccuracy(), mock, location.getTime(), sampleNanos, receivedNanos, System.currentTimeMillis(),
            current.startedNanos, current.startedWallMillis, ageNanos / 1_000_000.0,
            afterSubscription, increasing, fresh, valid, usable));
    }

    private void append(String message) {
        history.insert(0, message + "\n");
        if (history.length() > 20000) history.setLength(20000);
        results.setText(history.toString());
    }

    @SuppressWarnings("deprecation")
    private void installInsets(ScrollView scroll) {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        resumed = false;
        wantsRead = false;
        main.removeCallbacks(freshness);
        if (subscription != null) stopSubscription(subscription);
        history.setLength(0);
        super.onDestroy();
    }
}
