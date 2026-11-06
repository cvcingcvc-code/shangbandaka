package com.mocklocation.lab

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** 搜索、确认位置并启动由服务实际反馈状态的模拟定位。 */
class MainActivity : AppCompatActivity() {
    private lateinit var lm: LocationManager
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var btnBaiduLookup: Button
    private lateinit var btnMap: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSelection: TextView
    private var targetLat = 39.908722
    private var targetLng = 116.397499
    private var selectionText = "尚未确认地点；也可手动输入坐标"

    private fun isSessionBusy() = MockLocationStatus.current.phase in setOf(
        MockLocationStatus.Phase.PREPARING, MockLocationStatus.Phase.VERIFYING,
        MockLocationStatus.Phase.RUNNING, MockLocationStatus.Phase.STOPPING
    )

    private val statusListener: (MockLocationStatus.Status) -> Unit = { status ->
        val busy = isSessionBusy()
        val mockSelected = runCatching { MockLocationEngine.isMockAppSelected(this) }.getOrNull()
        val stateText = if (status.phase == MockLocationStatus.Phase.STOPPED_WAITING) {
            "状态：模拟已解除，等待正常定位\n${status.message}"
        } else "状态：${status.message}"
        tvStatus.text = when {
            mockSelected == false -> "$stateText\n尚未获得模拟位置授权：请在开发者选项中选择 Mock Location Lab。"
            mockSelected == null -> "$stateText\n无法检查模拟位置授权，请重新打开应用检查。"
            else -> stateText
        }
        btnStart.isEnabled = !busy && mockSelected == true && hasValidCoordinateInput()
        // 停止也用于授权恢复后的显式清理；失败状态不能禁用重试。
        btnStop.isEnabled = status.phase != MockLocationStatus.Phase.STOPPING
        btnSearch.isEnabled = !busy
        btnBaiduLookup.isEnabled = !busy
        btnMap.isEnabled = !busy
        etSearch.isEnabled = !busy
        etLat.isEnabled = !busy
        etLng.isEnabled = !busy
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val restoreSelection = savedInstanceState?.getInt("selection_schema", 0) == 1
        if (savedInstanceState != null && restoreSelection) {
            targetLat = savedInstanceState.getDouble("targetLat", targetLat)
            targetLng = savedInstanceState.getDouble("targetLng", targetLng)
            selectionText = savedInstanceState.getString("selectionText", selectionText)
        }
        buildUi()
        if (savedInstanceState != null) {
            etSearch.setText(savedInstanceState.getString("query", ""))
            if (restoreSelection) {
                etLat.setText(savedInstanceState.getString("lat", ""))
                etLng.setText(savedInstanceState.getString("lng", ""))
                selectionText = savedInstanceState.getString("selectionText", selectionText)
                tvSelection.text = selectionText
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MockLocationService.reportPendingCleanup(this)
        MockLocationStatus.addListener(statusListener)
        statusListener(MockLocationStatus.current)
    }

    override fun onStop() {
        MockLocationStatus.removeListener(statusListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selection_schema", 1)
        outState.putDouble("targetLat", targetLat)
        outState.putDouble("targetLng", targetLng)
        outState.putString("selectionText", selectionText)
        outState.putString("query", etSearch.text.toString())
        outState.putString("lat", etLat.text.toString())
        outState.putString("lng", etLng.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        etSearch = EditText(this).apply {
            hint = "输入城市、地标或具体地点"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchPlace()
                    true
                } else false
            }
        }
        root.addView(etSearch)
        btnSearch = Button(this).apply {
            text = "搜索地点"
            setOnClickListener { searchPlace() }
        }
        btnBaiduLookup = Button(this).apply {
            text = "百度地图查地址"
            setOnClickListener { openBaiduLookup() }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnSearch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnBaiduLookup, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        root.addView(TextView(this).apply {
            text = "可在百度地图查完整地址，返回后粘贴地址再搜索。"
        })
        btnMap = Button(this).apply {
            text = "地图手动选点"
            setOnClickListener { openMapPicker() }
        }
        root.addView(btnMap)
        tvSelection = TextView(this).apply {
            text = selectionText
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(tvSelection)
        root.addView(TextView(this).apply { text = "备用：手动输入 WGS-84 经纬度" })
        val coords = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etLat = EditText(this).apply {
            hint = "纬度"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
        }
        etLng = EditText(this).apply {
            hint = "经度"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
        }
        val coordinateWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                selectionText = "已输入坐标，请检查后开始模拟"
                tvSelection.text = selectionText
            }
            override fun afterTextChanged(s: Editable?) { refreshStartAvailability() }
        }
        etLat.addTextChangedListener(coordinateWatcher)
        etLng.addTextChangedListener(coordinateWatcher)
        var previousQuery = PlaceSearchPolicy.normalize(etSearch.text.toString())
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = PlaceSearchPolicy.normalize(s?.toString().orEmpty())
                if (query == previousQuery) return
                previousQuery = query
                if (isSessionBusy()) return
                // A new search must not accidentally start the previous confirmed target.
                etLat.text.clear()
                etLng.text.clear()
                selectionText = "搜索文字已更改，请重新搜索并确认地点，或输入坐标"
                tvSelection.text = selectionText
                refreshStartAvailability()
            }
        })
        coords.addView(etLat, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        coords.addView(etLng, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(coords)
        tvStatus = TextView(this).apply {
            text = "状态：尚未开始"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(tvStatus)
        btnStart = Button(this).apply { text = "开始模拟"; setOnClickListener { startMock() } }
        btnStop = Button(this).apply { text = "停止模拟"; setOnClickListener { MockLocationService.stop(this@MainActivity) } }
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(Button(this).apply {
            text = "打开开发者选项"
            setOnClickListener { openMockAppSettings() }
        })
        setContentView(ScrollView(this).apply { addView(root); isFillViewport = true })
    }

    private fun openMapPicker(query: String? = null) {
        if (isSessionBusy()) return
        val i = Intent(this, MapPickerActivity::class.java)
            .putExtra("init_lat", targetLat).putExtra("init_lng", targetLng)
        if (!query.isNullOrBlank()) i.putExtra("search_query", query)
        startActivityForResult(i, 2001)
    }

    private fun searchPlace() {
        if (isSessionBusy()) return
        val query = etSearch.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "请输入地点", Toast.LENGTH_SHORT).show()
            return
        }
        if (rejectPastedLink(query)) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etSearch.windowToken, 0)
        openMapPicker(query)
    }

    private fun openBaiduLookup() {
        if (isSessionBusy()) return
        val query = etSearch.text.toString().trim()
        if (rejectPastedLink(query)) return
        val uri = Uri.Builder().scheme("baidumap").authority("map")
        if (query.isNotEmpty()) {
            uri.path("place/search").appendQueryParameter("query", query)
            PlaceSearchPolicy.explicitCity(PlaceSearchPolicy.normalize(query))?.let {
                uri.appendQueryParameter("region", it)
            }
        }
        uri.appendQueryParameter("src", "andr.mocklocation.lab")
        val launch = Intent(Intent.ACTION_VIEW, uri.build()).setPackage("com.baidu.BaiduMap")
        try {
            startActivity(launch)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "无法打开百度地图。请确认已安装，或使用本应用的地点搜索。", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "手机限制了应用跳转。请手动打开百度地图查地址。", Toast.LENGTH_LONG).show()
        }
    }

    private fun rejectPastedLink(query: String): Boolean {
        if (!query.contains("://")) return false
        Toast.makeText(this, "请复制地点完整地址，分享链接不能直接用于选点。", Toast.LENGTH_LONG).show()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2001 || resultCode != Activity.RESULT_OK || data == null) return
        if (isSessionBusy()) return
        val lat = data.getDoubleExtra("lat", Double.NaN)
        val lng = data.getDoubleExtra("lng", Double.NaN)
        if (!lat.isFinite() || !lng.isFinite()) return
        targetLat = lat
        targetLng = lng
        etLat.setText(lat.toString())
        etLng.setText(lng.toString())
        selectionText = "已确认：${data.getStringExtra("name") ?: "地图点选"}\n${data.getStringExtra("detail") ?: ""}"
        tvSelection.text = selectionText
    }

    private fun startMock() {
        if (isSessionBusy()) return
        if (!hasLocationPermission()) {
            tvStatus.text = "状态：需要精确定位权限"
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            tvStatus.text = "状态：系统定位关闭，请先开启"
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        if (!MockLocationEngine.isMockAppSelected(this)) {
            tvStatus.text = "状态：请进入开发者选项，选择 Mock Location Lab 作为模拟位置信息应用。"
            return
        }
        val lat = etLat.text.toString().toDoubleOrNull()
        val lng = etLng.text.toString().toDoubleOrNull()
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            tvStatus.text = "状态：请输入有效坐标（纬度 -90～90，经度 -180～180）"
            return
        }
        targetLat = lat
        targetLng = lng
        MockLocationService.start(this, lat, lng)
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this,
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasValidCoordinateInput(): Boolean {
        val lat = etLat.text.toString().toDoubleOrNull() ?: return false
        val lng = etLng.text.toString().toDoubleOrNull() ?: return false
        return lat.isFinite() && lat in -90.0..90.0 && lng.isFinite() && lng in -180.0..180.0
    }

    private fun refreshStartAvailability() {
        if (!::btnStart.isInitialized) return
        btnStart.isEnabled = !isSessionBusy() && hasValidCoordinateInput() &&
            runCatching { MockLocationEngine.isMockAppSelected(this) }.getOrDefault(false)
    }

    private fun openMockAppSettings() {
        Toast.makeText(this, "请进入开发者选项，选择 Mock Location Lab 作为模拟位置信息应用。", Toast.LENGTH_LONG).show()
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        catch (e: Exception) { tvStatus.text = "状态：无法打开开发者选项：${e.message}" }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            tvStatus.text = if (hasLocationPermission()) "状态：权限已授予，请点击开始模拟"
                else "状态：定位权限被拒绝，无法开始模拟"
        }
    }
}
