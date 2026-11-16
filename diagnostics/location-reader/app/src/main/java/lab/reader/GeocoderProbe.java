package lab.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Backend-only probe for an explicitly supplied public test-place query.
 * It never reads GPS/network/fused locations and never changes mock-location state.
 */
public final class GeocoderProbe extends Activity {
    public static final String EXTRA_QUERY = "query";
    private static final int MAX_RESULTS = 5;
    private static final long TIMEOUT_MILLIS = 20_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView output;
    private ProbeRequest active;

    private static final class ProbeRequest {
        final String id = UUID.randomUUID().toString();
        final String query;
        final long startedWallMillis = System.currentTimeMillis();
        final long startedElapsedNanos = SystemClock.elapsedRealtimeNanos();
        boolean implementationPresent;
        boolean finished;
        ProbeRequest(String query) { this.query = query; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        output = new TextView(this);
        output.setTextSize(13);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        output.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        installInsets(scroll);
        setContentView(scroll);
        scroll.requestApplyInsets();
        begin(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        begin(intent);
    }

    private void begin(Intent intent) {
        if (active != null) active.finished = true;
        main.removeCallbacksAndMessages(null);
        String query = intent == null ? "" : intent.getStringExtra(EXTRA_QUERY);
        if (query == null) query = "";
        ProbeRequest request = new ProbeRequest(query.trim());
        active = request;
        if (request.query.isEmpty() || request.query.length() > 256) {
            complete(request, "INVALID_QUERY", null, "显式测试查询不能为空，且不得超过256字");
            return;
        }
        try {
            request.implementationPresent = Geocoder.isPresent();
            if (!request.implementationPresent) {
                complete(request, "UNAVAILABLE", null, "设备没有Geocoder实现");
                return;
            }
            showAndSave(request, "QUERYING", null, null);
            Geocoder geocoder = new Geocoder(this, Locale.SIMPLIFIED_CHINESE);
            main.postDelayed(() -> complete(request, "TIMEOUT", null,
                "20秒内没有收到最终结果；迟到回调不会覆盖本记录"), TIMEOUT_MILLIS);
            if (Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocationName(request.query, MAX_RESULTS, new Geocoder.GeocodeListener() {
                    @Override public void onGeocode(List<Address> addresses) {
                        main.post(() -> complete(request, addresses == null || addresses.isEmpty() ? "EMPTY" : "RESULTS", addresses, null));
                    }
                    @Override public void onError(String errorMessage) {
                        main.post(() -> complete(request, "BACKEND_ERROR", null,
                            errorMessage == null ? "后端未给出错误详情" : errorMessage));
                    }
                });
            } else {
                // Legacy synchronous API can block; never run it on the Activity thread.
                new Thread(() -> legacyQuery(geocoder, request), "GeocoderProbe").start();
            }
        } catch (Exception error) {
            complete(request, "EXCEPTION", null, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void legacyQuery(Geocoder geocoder, ProbeRequest request) {
        try {
            List<Address> addresses = geocoder.getFromLocationName(request.query, MAX_RESULTS);
            main.post(() -> complete(request, addresses == null || addresses.isEmpty() ? "EMPTY" : "RESULTS", addresses, null));
        } catch (Exception error) {
            main.post(() -> complete(request, "EXCEPTION", null,
                error.getClass().getSimpleName() + ": " + error.getMessage()));
        }
    }

    private void complete(ProbeRequest request, String status, List<Address> addresses, String error) {
        if (active != request || request.finished || isFinishing() || isDestroyed()) return;
        request.finished = true;
        main.removeCallbacksAndMessages(null);
        showAndSave(request, status, addresses, error);
    }

    private void showAndSave(ProbeRequest request, String status, List<Address> addresses, String error) {
        try {
            JSONObject result = new JSONObject();
            result.put("diagnostic", "Geocoder backend probe; not main-app search acceptance");
            result.put("source", "Android Geocoder");
            result.put("request_id", request.id);
            result.put("query", request.query);
            result.put("locale", Locale.SIMPLIFIED_CHINESE.toLanguageTag());
            result.put("implementation_present", request.implementationPresent);
            result.put("api_mode", Build.VERSION.SDK_INT >= 33 ? "async" : "worker-thread legacy");
            result.put("status", status);
            result.put("error", error == null ? JSONObject.NULL : error);
            result.put("started_wall_millis", request.startedWallMillis);
            result.put("started_elapsed_nanos", request.startedElapsedNanos);
            result.put("recorded_wall_millis", System.currentTimeMillis());
            result.put("recorded_elapsed_nanos", SystemClock.elapsedRealtimeNanos());
            result.put("duration_millis", (SystemClock.elapsedRealtimeNanos() - request.startedElapsedNanos) / 1_000_000L);
            JSONArray items = new JSONArray();
            if (addresses != null) for (Address address : addresses) items.put(addressJson(address));
            result.put("result_count", items.length());
            result.put("results", items);
            result.put("network_note", "系统Geocoder后端可能联网；本探针不直接发HTTP请求");
            result.put("location_note", "未读取设备实时定位；坐标仅来自显式测试地名的Geocoder响应");
            save(result);
            output.setText("仅作后端可行性诊断，不是主应用搜索验收。\n仅记录显式测试查询；本页面不读取设备实时定位。\n\n" + result.toString(2));
        } catch (Exception failure) {
            output.setText("生成诊断记录失败：" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private JSONObject addressJson(Address address) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("source", "Android Geocoder");
        item.put("name", nullable(address.getFeatureName()));
        item.put("country", nullable(address.getCountryName()));
        item.put("country_code", nullable(address.getCountryCode()));
        item.put("admin_area", nullable(address.getAdminArea()));
        item.put("sub_admin_area", nullable(address.getSubAdminArea()));
        item.put("locality", nullable(address.getLocality()));
        item.put("sub_locality", nullable(address.getSubLocality()));
        item.put("thoroughfare", nullable(address.getThoroughfare()));
        JSONArray lines = new JSONArray();
        for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) lines.put(address.getAddressLine(i));
        item.put("address_lines", lines);
        boolean valid = address.hasLatitude() && address.hasLongitude() &&
            Double.isFinite(address.getLatitude()) && Double.isFinite(address.getLongitude()) &&
            address.getLatitude() >= -90 && address.getLatitude() <= 90 &&
            address.getLongitude() >= -180 && address.getLongitude() <= 180;
        item.put("valid_coordinates", valid);
        if (valid) {
            item.put("latitude", address.getLatitude());
            item.put("longitude", address.getLongitude());
        }
        return item;
    }

    private Object nullable(String value) { return value == null ? JSONObject.NULL : value; }

    private void save(JSONObject result) throws JSONException {
        File directory = getExternalFilesDir("diagnostics");
        if (directory == null) { result.put("export_error", "外部应用文件目录不可用"); return; }
        File destination = new File(directory, "geocoder-probe-latest.json");
        File temporary = new File(directory, "geocoder-probe-latest.json.tmp");
        result.put("export_path", destination.getAbsolutePath());
        try {
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建诊断目录");
            try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
                stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            if (!temporary.renameTo(destination)) throw new IOException("无法替换最新诊断结果");
        } catch (Exception error) {
            result.put("export_error", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void installInsets(ScrollView scroll) {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        scroll.setOnApplyWindowInsetsListener((content, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                content.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else content.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    @Override protected void onDestroy() {
        if (active != null) active.finished = true;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
