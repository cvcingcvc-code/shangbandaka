package com.mocklocation.lab

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.LocationListener
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面：搜索框、目标经纬度、开始/停止模拟、状态显示。
 * 处理：权限未授权、系统定位关闭、模拟应用未选择、进程重启。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var lm: LocationManager
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etSearch: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnTest: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCoordInfo: TextView
    private lateinit var poiListContainer: LinearLayout

    private var targetLat = 39.908722
    private var targetLng = 116.397499

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
                       else @Suppress("DEPRECATION") location.isFromMockProvider
            tvCoordInfo.text = "读取到: %.5f, %.5f isMock=%s".format(
                location.latitude, location.longitude, mock.toString()
            )
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
        // 进程重启恢复：若服务还在跑，状态显示为模拟中
        if (isServiceRunning()) {
            tvStatus.text = getString(R.string.status_running)
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            tvStatus.text = getString(R.string.status_idle)
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
        // 自动化测试入口：launcher intent 直接带 search_query → 打开地图页并自动搜索
        val presetQuery = intent.getStringExtra("search_query")
        if (!presetQuery.isNullOrBlank()) {
            openMapPicker(presetQuery)
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndProviderState()
    }

    private fun isServiceRunning(): Boolean {
        // 简单恢复策略：provider 已注册即认为模拟中
        return try {
            val last = lm.getLastKnownLocation(MockLocationEngine.PROVIDER_NAME)
            last != null && (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) last.isMock
                             else @Suppress("DEPRECATION") last.isFromMockProvider)
        } catch (e: Exception) { false }
    }

    private fun buildUi() {
        val ctx = this
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // 搜索框
        etSearch = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine(true)
        }
        val btnSearch = Button(this).apply { text = "搜索 POI" }
        root.addView(etSearch)
        root.addView(btnSearch)

        poiListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(poiListContainer)

        // 地图选点入口（v1.1：WebView+Leaflet，无 Key 可用）
        val btnMap = Button(this).apply { text = "🗺️ 地图选点（城市/地标/商家）" }
        root.addView(btnMap)

        // 内置测试位置（Key 缺失兜底）
        val btnFallback = Button(this).apply { text = "内置测试位置" }
        root.addView(btnFallback)

        root.addView(TextView(this).apply {
            text = "目标坐标（支持手动输入）"
            setPadding(0, pad / 2, 0, pad / 4)
        })

        val coordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etLat = EditText(this).apply {
            hint = getString(R.string.lat_label)
            setText(targetLat.toString())
            setSingleLine(true)
        }
        etLng = EditText(this).apply {
            hint = getString(R.string.lng_label)
            setText(targetLng.toString())
            setSingleLine(true)
        }
        coordRow.addView(etLat, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        coordRow.addView(etLng, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(coordRow)

        // 坐标系说明
        root.addView(TextView(this).apply {
            text = "坐标系：GCJ-02（高德）↔ WGS-84（GPS）自动转换"
            textSize = 12f
            setPadding(0, 0, 0, pad / 2)
        })

        tvStatus = TextView(this).apply {
            text = getString(R.string.status_idle)
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(tvStatus)

        tvCoordInfo = TextView(this).apply {
            text = "尚未读取定位"
            textSize = 13f
        }
        root.addView(tvCoordInfo)

        // 按钮
        btnStart = Button(this).apply {
            text = getString(R.string.btn_start_mock)
            setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
        }
        btnStop = Button(this).apply {
            text = getString(R.string.btn_stop_mock)
            setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
        }
        btnTest = Button(this).apply { text = getString(R.string.open_test_reader) }
        val btnPerm = Button(this).apply { text = getString(R.string.request_location_perm) }
        val btnDev = Button(this).apply { text = getString(R.string.open_dev_settings) }

        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(btnTest)
        root.addView(btnPerm)
        root.addView(btnDev)

        setContentView(root)

        // 事件
        btnSearch.setOnClickListener { doSearch() }
        btnMap.setOnClickListener { openMapPicker() }
        btnFallback.setOnClickListener { showFallbackLocations() }
        btnStart.setOnClickListener { startMock() }
        btnStop.setOnClickListener { stopMock() }
        btnTest.setOnClickListener { startActivity(Intent(this, TestReaderActivity::class.java)) }
        btnPerm.setOnClickListener { requestLocationPermission() }
        btnDev.setOnClickListener { openMockAppSettings() }
    }

    /** 打开地图选点页，携带当前目标坐标作为初始中心；支持 search_query/search_scope 直传（自动化测试） */
    private fun openMapPicker(searchQuery: String? = null) {
        val i = Intent(this, MapPickerActivity::class.java)
            .putExtra("init_lat", targetLat)
            .putExtra("init_lng", targetLng)
        if (!searchQuery.isNullOrBlank()) i.putExtra("search_query", searchQuery)
        if (intent.hasExtra("search_scope")) i.putExtra("search_scope", intent.getIntExtra("search_scope", 0))
        startActivityForResult(i, 2001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == Activity.RESULT_OK && data != null) {
            val lat = data.getDoubleExtra("lat", Double.NaN)
            val lng = data.getDoubleExtra("lng", Double.NaN)
            val name = data.getStringExtra("name") ?: "地图点选"
            if (!lat.isNaN() && !lng.isNaN()) {
                // OSM/地图返回 WGS-84，直接注入系统无需转换
                targetLat = lat
                targetLng = lng
                etLat.setText("%.6f".format(lat))
                etLng.setText("%.6f".format(lng))
                tvCoordInfo.text = "已选: $name\nWGS-84 坐标已就绪，可直接开始模拟"
                Toast.makeText(this, "选点完成，点击「开始模拟」生效", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun doSearch() {
        val kw = etSearch.text.toString().trim()
        if (kw.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }
        AmapPoiSearch.search(this, kw, "北京", object : AmapPoiSearch.Callback {
            override fun onSuccess(list: List<AmapPoiSearch.Poi>) {
                renderPoiList(list)
                if (list.isEmpty()) Toast.makeText(this@MainActivity, "无结果", Toast.LENGTH_SHORT).show()
            }
            override fun onError(message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                showFallbackLocations()
            }
        })
    }

    private fun renderPoiList(list: List<AmapPoiSearch.Poi>) {
        poiListContainer.removeAllViews()
        for (poi in list.take(8)) {
            val tv = TextView(this).apply {
                text = "📍 ${poi.name}\n   ${poi.address} (%.4f, %.4f)".format(poi.lat, poi.lng)
                setPadding(0, 8, 0, 8)
                setOnClickListener {
                    // 高德返回 GCJ-02，注入系统需要 WGS-84 → 转换
                    val wgs = CoordConverter.gcj02ToWgs84(poi.lat, poi.lng)
                    targetLat = wgs[0]
                    targetLng = wgs[1]
                    etLat.setText("%.6f".format(targetLat))
                    etLng.setText("%.6f".format(targetLng))
                    tvCoordInfo.text = "已选择: ${poi.name}\nGCJ-02 → WGS-84 已转换"
                }
            }
            poiListContainer.addView(tv)
        }
    }

    private fun showFallbackLocations() {
        poiListContainer.removeAllViews()
        for ((name, lat, lng) in AppConfig.FALLBACK_LOCATIONS) {
            val tv = TextView(this).apply {
                text = "📍 $name (%.4f, %.4f)".format(lat, lng)
                setPadding(0, 8, 0, 8)
                setOnClickListener {
                    targetLat = lat
                    targetLng = lng
                    etLat.setText(lat.toString())
                    etLng.setText(lng.toString())
                    tvCoordInfo.text = "已选择: $name（GCJ-02 内置）"
                }
            }
            poiListContainer.addView(tv)
        }
    }

    private fun startMock() {
        // 1) 权限检查（未授权 → 官方流程引导）
        if (!hasLocationPermission()) {
            tvStatus.text = "状态：未授权定位权限"
            requestLocationPermission()
            Toast.makeText(this, "请先授予定位权限（点「申请定位权限」）", Toast.LENGTH_SHORT).show()
            return
        }
        // 2) 系统定位开关（关闭 → 跳转定位设置）
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            tvStatus.text = "状态：系统定位关闭"
            Toast.makeText(this, "系统定位已关闭，即将跳转定位设置页", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        // 3) 坐标有效性校验（范围 + 数值）
        val lat = etLat.text.toString().toDoubleOrNull()
        val lng = etLng.text.toString().toDoubleOrNull()
        if (lat == null || lng == null) {
            tvStatus.text = "状态：经纬度无效"
            Toast.makeText(this, "经纬度无效，请输入数值（如 39.908722）", Toast.LENGTH_SHORT).show()
            return
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            tvStatus.text = "状态：经纬度超出范围"
            Toast.makeText(this, "纬度需在 -90~90，经度需在 -180~180", Toast.LENGTH_LONG).show()
            return
        }
        // 4) 模拟应用未选择：启动服务后由服务注册结果反馈（注册失败时通知栏提示）
        targetLat = lat
        targetLng = lng
        // 启动前台服务（服务内部注册 provider 并每秒注入）
        MockLocationService.start(this, targetLat, targetLng)
        tvStatus.text = getString(R.string.status_running)
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun stopMock() {
        MockLocationService.stop(this)
        // 立即释放 provider（服务 stopMock 也会做，双保险）
        try { MockLocationEngine.removeTestProvider(lm) } catch (_: Exception) {}
        tvStatus.text = getString(R.string.status_stopped)
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        Toast.makeText(this, "已停止模拟，测试 provider 已释放", Toast.LENGTH_SHORT).show()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ), 100
        )
    }

    private fun openMockAppSettings() {
        Toast.makeText(this, "请在开发者选项中把「模拟位置信息应用」选为本应用", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }

    private fun checkPermissionsAndProviderState() {
        if (!hasLocationPermission()) {
            tvStatus.text = "状态：未授权定位权限"
        } else if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            tvStatus.text = "状态：系统定位关闭"
        } else if (isServiceRunning()) {
            tvStatus.text = getString(R.string.status_running)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                tvStatus.text = getString(R.string.status_idle)
                Toast.makeText(this, "定位权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = "状态：定位权限被拒绝"
                Toast.makeText(this, "权限被拒绝，无法模拟", Toast.LENGTH_LONG).show()
            }
        }
    }
}
