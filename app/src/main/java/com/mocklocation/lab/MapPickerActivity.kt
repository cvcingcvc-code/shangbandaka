package com.mocklocation.lab

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.FrameLayout
import android.view.MotionEvent
import java.util.Locale
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 地图选点页 v1.1.1：WebView + Leaflet（OSM 瓦片，免费无 Key）。
 * P0 搜索准确性修复：
 * - 结果显示 地点名称 + 城市 + 国家（addressdetails=1 结构化）
 * - 地图可视区域影响排序（viewbox + bounded=0 视野优先；可选 仅视野内 / 全球）
 * - 全球结果可按地图视野优先排列，不默认限制国家
 * - 用户必须明确点击结果选择，绝不自动选第一条
 * - 选中后地图移动到对应位置并显示完整信息，供检查确认坐标
 * P1 稳定性：
 * - Nominatim 1 req/s 节流（仅用户点搜索触发，无自动补全）
 * - 应用标识 UA、OSM 署名（map.html）、瓦片浏览器缓存
 * - 搜索失败不影响地图点选与手动输入坐标
 */
class MapPickerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MapPickerSearch"
    }

    private var webView: WebView? = null
    private lateinit var mapContainer: FrameLayout
    private lateinit var btnLoadMap: Button
    private lateinit var etSearch: EditText
    private lateinit var tvSelected: TextView
    private lateinit var btnConfirm: Button
    private lateinit var tvMapStatus: TextView
    private lateinit var btnRetryMap: Button
    private lateinit var resultContainer: LinearLayout
    private lateinit var spScope: Spinner

    private var selLat = Double.NaN
    private var selLng = Double.NaN
    private var selName = ""
    private var selDetail = ""

    // 当前地图中心（JS 回传，用于 viewbox 与 cn 判断）
    private var mapCenterLat = 39.908722
    private var mapCenterLng = 116.397499
    // 当前地图视野跨度（JS 回传）
    private var mapSpan = 0.08

    private var lastSearchAt = 0L
    private var searchGeneration = 0
    private var mapSelectionId = 0

    /** JS 桥：地图点击回传（WGS-84） */
    inner class JsBridge {
        @JavascriptInterface
        fun onMapClick(lat: Double, lng: Double) {
            runOnUiThread {
                pick(lat, lng, "地图点选", "")
            }
        }

        @JavascriptInterface
        fun onMapMove(lat: Double, lng: Double, span: Double) {
            runOnUiThread {
                mapCenterLat = lat
                mapCenterLng = lng
                mapSpan = if (span > 0) span else 0.08
            }
        }

        @JavascriptInterface
        fun onMapReady(id: Int) {
            runOnUiThread {
                if (id == mapSelectionId && selLat.isFinite() && !isFinishing && !isDestroyed) {
                    tvMapStatus.text = "地图已加载，请核对标记位置"
                    btnRetryMap.visibility = View.GONE
                    btnConfirm.isEnabled = true
                }
            }
        }

        @JavascriptInterface
        fun onMapUnavailable(id: Int) {
            runOnUiThread { showMapUnavailable(id) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        // 搜索由主界面的用户操作触发；结果仍需明确选择。
        // 自动化测试入口：Intent 直传搜索词（仍需用户点击结果确认）
        // search_scope: 0=视野优先(默认) 1=仅视野内 2=全球 —— headless 测试 tap 无效，用 extra 控制
        val presetQuery = intent.getStringExtra("search_query")
        if (!presetQuery.isNullOrBlank()) {
            val presetScope = intent.getIntExtra("search_scope", 0)
            if (presetScope in 0..2) spScope.setSelection(presetScope)
            etSearch.setText(presetQuery)
            doSearch()
        } else {
            // Let the native controls draw first; WebView initialization still runs on the main thread.
            mapContainer.post { if (!isFinishing && !isDestroyed) startMap() }
        }
    }

    private fun startMap() {
        if (webView != null) return
        btnLoadMap.visibility = View.GONE
        tvMapStatus.text = "地图加载中，需要网络连接"
        try {
            val map = WebView(this)
            webView = map
            mapContainer.addView(map, FrameLayout.LayoutParams(-1, -1))
            map.setOnTouchListener { view, event ->
                view.parent.parent.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                false
            }
            map.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val lat = if (selLat.isFinite()) selLat else intent.getDoubleExtra("init_lat", 39.908722)
                    val lng = if (selLng.isFinite()) selLng else intent.getDoubleExtra("init_lng", 116.397499)
                    view?.evaluateJavascript("window.setCenter && setCenter($lat, $lng, $mapSelectionId)", null)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            map.settings.javaScriptEnabled = true
            map.settings.domStorageEnabled = true
            map.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            map.addJavascriptInterface(JsBridge(), "AndroidBridge")
            map.webChromeClient = WebChromeClient()
            map.loadUrl("file:///android_asset/map.html")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "地图初始化失败", e)
            mapContainer.removeAllViews()
            webView?.destroy()
            webView = null
            showMapUnavailable(mapSelectionId)
        }
    }

    private fun buildUi() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // 搜索行
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etSearch = EditText(this).apply {
            hint = "搜索城市/地标/商家"
            setSingleLine(true)
        }
        val btnSearch = Button(this).apply { text = "搜索" }
        searchRow.addView(etSearch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(btnSearch)
        root.addView(searchRow)

        // 搜索范围：视野优先（默认）/ 仅视野内 / 全球
        spScope = Spinner(this).apply {
            adapter = ArrayAdapter(this@MapPickerActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("视野优先（全球）", "仅当前视野内", "全球搜索"))
        }
        root.addView(spScope)

        // 搜索结果列表
        resultContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val resultScroll = ScrollView(this).apply {
            addView(resultContainer)
            isFillViewport = false
            setOnTouchListener { view, event ->
                view.parent.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                false
            }
        }
        root.addView(resultScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (120 * resources.displayMetrics.density).toInt()))

        // 固定地图高度，外层可滚动，避免小屏幕或键盘挤没地图和确认按钮。
        mapContainer = FrameLayout(this)
        btnLoadMap = Button(this).apply {
            text = "打开地图手动选点"
            setOnClickListener { startMap() }
        }
        mapContainer.addView(btnLoadMap, FrameLayout.LayoutParams(-1, -1))
        root.addView(mapContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (240 * resources.displayMetrics.density).toInt()))

        tvMapStatus = TextView(this).apply { text = "地图加载中，需要网络连接" }
        root.addView(tvMapStatus)
        btnRetryMap = Button(this).apply {
            text = "重试加载地图"
            visibility = View.GONE
            setOnClickListener {
                tvMapStatus.text = "正在重新加载地图…"
                visibility = View.GONE
                if (selLat.isFinite()) {
                    btnConfirm.isEnabled = false
                    val id = ++mapSelectionId
                    scheduleMapTimeout(id)
                }
                if (webView == null) startMap() else webView?.reload()
            }
        }
        root.addView(btnRetryMap)

        // 已选坐标（含名称/城市/国家）
        tvSelected = TextView(this).apply {
            text = "尚未选点（搜索选择或点击地图）"
            setPadding(0, pad, 0, pad / 2)
        }
        root.addView(tvSelected)

        btnConfirm = Button(this).apply {
            text = "确认选点并返回"
            isEnabled = false
        }
        root.addView(btnConfirm)
        setContentView(ScrollView(this).apply { addView(root); isFillViewport = true })

        btnSearch.setOnClickListener { doSearch() }
        btnConfirm.setOnClickListener { confirmAndReturn() }
    }

    /** 搜索请求（用户点按钮触发，无自动补全；1 req/s 节流） */
    private fun doSearch() {
        val now = System.currentTimeMillis()
        if (now - lastSearchAt < 1100) {
            Toast.makeText(this, "Nominatim 限每秒 1 次请求，请稍候再搜", Toast.LENGTH_SHORT).show()
            return
        }
        val kw = etSearch.text.toString().trim()
        if (kw.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }
        lastSearchAt = now
        val generation = ++searchGeneration
        selLat = Double.NaN
        selLng = Double.NaN
        ++mapSelectionId
        btnConfirm.isEnabled = false
        tvSelected.text = "请选择搜索结果或点击地图"
        resultContainer.removeAllViews()
        resultContainer.addView(TextView(this).apply { text = "搜索中…" })
        // P0 诊断：主线程先快照请求参数，避免后台线程读 UI 控件的竞态
        val scopePos = spScope.selectedItemPosition
        val vbParam = viewboxParam()
        val inChina = isCenterInChina()
        android.util.Log.i(TAG, "doSearch kw='$kw' scope=$scopePos viewbox=$vbParam centerInChina=$inChina")
        Thread {
            var finalUrl = ""
            try {
                val enc = URLEncoder.encode(kw, "UTF-8")
                val sb = StringBuilder()
                sb.append("https://nominatim.openstreetmap.org/search")
                    .append("?q=").append(enc)
                    .append("&format=json&limit=8&accept-language=zh-CN")
                    .append("&addressdetails=1") // 结构化城市/国家
                when (scopePos) {
                    0 -> { // 视野优先：viewbox 偏向排序，不硬限制国家
                        sb.append("&viewbox=").append(vbParam).append("&bounded=0")
                        // 视野只影响排序，不限制国家。
                    }
                    1 -> { // 仅当前视野内
                        sb.append("&viewbox=").append(vbParam).append("&bounded=1")
                    }
                    else -> { /* 全球：无附加参数（无 countrycodes/viewbox/bounded） */ }
                }
                finalUrl = sb.toString()
                android.util.Log.i(TAG, "request GET $finalUrl")
                val conn = URL(finalUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "MockLocationLab/1.1.1 (Android debug tool)")
                val code = conn.responseCode
                val body = if (code == 200) readStream(conn) else ""
                android.util.Log.i(TAG, "response http=$code bodyLen=${body.length} head=${body.take(150)}")
                conn.disconnect()
                if (code != 200) {
                    runUiToast("搜索失败 HTTP $code（仍可地图点选/手输坐标）", generation)
                    return@Thread
                }
                val arr = org.json.JSONArray(body)
                val out = ArrayList<NomaPoi>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val lat = o.optDouble("lat", Double.NaN)
                    val lng = o.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lng.isNaN()) continue
                    val name = o.optString("name", "").ifBlank {
                        o.optString("display_name", "").substringBefore(',')
                    }
                    // addressdetails: city/state/country
                    val addr = o.optJSONObject("address") ?: JSONObject()
                    val city = listOf("city", "town", "village", "municipality", "county", "state")
                        .map { addr.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: "城市未标注"
                    val country = addr.optString("country", "").ifBlank { "国家未标注" }
                    out.add(NomaPoi(name, city, country, lat, lng, o.optString("display_name", "")))
                }
                runOnUiThread { if (!isDestroyed && !isFinishing && generation == searchGeneration) renderResults(out) }
                android.util.Log.i(TAG, "parsed count=${out.size} first=${out.firstOrNull()?.let { "${it.name}|${it.city}|${it.country}|${it.lat},${it.lng}" } ?: "none"}")
                if (out.isEmpty()) runUiToast("无结果（仍可地图点选/手输坐标）", generation)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "search exception url=$finalUrl", e)
                runUiToast("搜索错误: ${e.javaClass.simpleName}: ${e.message}（仍可地图点选/手输坐标）", generation)
            }
        }.start()
    }

    private fun runUiToast(msg: String, generation: Int) {
        runOnUiThread {
            if (!isDestroyed && !isFinishing && generation == searchGeneration) {
                // P0-6：错误必须写进结果区（UI dump 可捕获），不能只靠 Toast
                resultContainer.removeAllViews()
                resultContainer.addView(TextView(this).apply {
                    text = "⚠️ $msg"
                    setPadding(0, 8, 0, 8)
                })
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** viewbox=left,top,right,bottom（当前地图可视区域） */
    private fun viewboxParam(): String {
        val d = mapSpan
        val left = mapCenterLng - d
        val right = mapCenterLng + d
        val top = mapCenterLat + d * 0.6
        val bottom = mapCenterLat - d * 0.6
        return String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", left, top, right, bottom)
    }

    /** 地图中心是否明确在中国境内（粗判即可） */
    private fun isCenterInChina(): Boolean {
        return !CoordConverter.outOfChina(mapCenterLat, mapCenterLng)
    }

    private fun renderResults(list: List<NomaPoi>) {
        resultContainer.removeAllViews()
        for (p in list.take(8)) {
            val tv = TextView(this).apply {
                // 必须显示：地点名称 + 城市 + 国家
                text = "📍 ${p.name}\n${p.city}, ${p.country}\n${p.displayName}\n" + String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng)
                val inset = (8 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                isFocusable = true
                setOnClickListener { pick(p.lat, p.lng, p.name, "${p.city}, ${p.country}") }
            }
            resultContainer.addView(tv)
        }
    }

    /** 明确选择：用户点击某条结果/地图某点后才置为已选，绝不自动选 */
    private fun pick(lat: Double, lng: Double, name: String, detail: String) {
        selLat = lat
        selLng = lng
        selName = name
        selDetail = detail
        tvSelected.text = "已选: $name\n$detail\n%.6f, %.6f（WGS-84）".format(lat, lng)
        btnConfirm.isEnabled = false
        btnRetryMap.visibility = View.GONE
        tvMapStatus.text = "正在加载所选位置的地图，请稍候…"
        val id = ++mapSelectionId
        scheduleMapTimeout(id)
        // 地图移动到对应位置，供用户检查确认
        if (webView == null) startMap()
        else webView?.evaluateJavascript("window.setCenter && setCenter($lat, $lng, $id)", null)
    }

    private fun scheduleMapTimeout(id: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (id == mapSelectionId && !btnConfirm.isEnabled) showMapUnavailable(id)
        }, 12000)
    }

    private fun showMapUnavailable(id: Int) {
        if (id != mapSelectionId || isFinishing || isDestroyed || btnConfirm.isEnabled) return
        tvMapStatus.text = "无法加载所选位置的地图，请检查网络后重试；也可返回手动输入坐标。"
        btnRetryMap.visibility = View.VISIBLE
        btnConfirm.isEnabled = false
    }

    private fun confirmAndReturn() {
        if (selLat.isNaN() || selLng.isNaN()) {
            Toast.makeText(this, "请先选点", Toast.LENGTH_SHORT).show()
            return
        }
        val data = Intent().apply {
            putExtra("lat", selLat)
            putExtra("lng", selLng)
            putExtra("name", selName)
            putExtra("detail", selDetail)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun readStream(conn: HttpURLConnection): String {
        val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
        val sb = StringBuilder()
        var line: String? = reader.readLine()
        while (line != null) {
            sb.append(line)
            line = reader.readLine()
        }
        reader.close()
        return sb.toString()
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }

    data class NomaPoi(val name: String, val city: String, val country: String,
                       val lat: Double, val lng: Double, val displayName: String)
}
