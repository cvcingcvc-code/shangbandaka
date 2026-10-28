package com.mocklocation.lab

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.FrameLayout
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
 * 全球搜索后由用户选择具体结果，再在地图确认；无自动补全或自动选点。
 * Nominatim 请求仅由搜索操作触发，并在本页面按每秒一次节流。
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
    private lateinit var searchPanel: LinearLayout
    private lateinit var mapPanel: LinearLayout
    private lateinit var resultsLabel: TextView

    private var selLat = Double.NaN
    private var selLng = Double.NaN
    private var selName = ""
    private var selDetail = ""

    private var lastSearchAt = 0L
    private var searchGeneration = 0
    private var mapSelectionId = 0
    private var lastResults: List<NomaPoi> = emptyList()

    /** JS 桥：地图点击回传（WGS-84） */
    inner class JsBridge {
        @JavascriptInterface
        fun onMapClick(lat: Double, lng: Double) {
            runOnUiThread {
                if (!isDestroyed && !isFinishing) pick(lat, lng, "地图点选", "", fromMap = true)
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
        if (savedInstanceState != null) {
            // Do not restore a map confirmation across recreation. Restore the list so
            // the user can explicitly pick and confirm again without another request.
            etSearch.setText(savedInstanceState.getString("query", ""))
            @Suppress("DEPRECATION")
            val saved = savedInstanceState.getParcelableArrayList<Bundle>("results")
            if (saved != null) renderResults(saved.map {
                NomaPoi(it.getString("name", ""), it.getString("city", ""), it.getString("country", ""),
                    it.getDouble("lat"), it.getDouble("lng"), it.getString("display", ""))
            })
            else resultsLabel.text = "请点击搜索，再选择具体地点"
            return
        }
        // 搜索由主界面的用户操作触发；结果仍需明确选择。
        // 主界面搜索词带入；仍需用户点击结果并在地图确认。
        val presetQuery = intent.getStringExtra("search_query")
        if (!presetQuery.isNullOrBlank()) {
            etSearch.setText(presetQuery)
            doSearch()
        } else {
            // Let the native controls draw first; WebView initialization still runs on the main thread.
            showMapPanel()
            mapContainer.post { if (!isFinishing && !isDestroyed) startMap() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", etSearch.text.toString())
        if (lastResults.isNotEmpty()) {
            outState.putParcelableArrayList("results", ArrayList(lastResults.map { p -> Bundle().apply {
                putString("name", p.name); putString("city", p.city); putString("country", p.country)
                putDouble("lat", p.lat); putDouble("lng", p.lng); putString("display", p.displayName)
            } }))
        }
        super.onSaveInstanceState(outState)
    }

    private fun startMap() {
        if (webView != null) return
        btnLoadMap.visibility = View.GONE
        tvMapStatus.text = "地图加载中，需要网络连接"
        try {
            val map = WebView(this)
            webView = map
            mapContainer.addView(map, FrameLayout.LayoutParams(-1, -1))
            map.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val lat = if (selLat.isFinite()) selLat else intent.getDoubleExtra("init_lat", 39.908722)
                    val lng = if (selLng.isFinite()) selLng else intent.getDoubleExtra("init_lng", 116.397499)
                    if (!selLat.isFinite()) tvMapStatus.text = "点击地图选择位置；地图需要网络连接"
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

        // 搜索与地图分成两个状态，结果列表和地图都获得主要屏幕空间。
        searchPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etSearch = EditText(this).apply {
            hint = "搜索城市/地标/商家"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
            }
        }
        val btnSearch = Button(this).apply { text = "搜索" }
        searchRow.addView(etSearch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(btnSearch)
        searchPanel.addView(searchRow)
        resultsLabel = TextView(this).apply {
            text = "全球搜索：点击具体地点，再在地图上确认"
            setPadding(0, pad / 2, 0, pad / 2)
        }
        searchPanel.addView(resultsLabel)

        // 搜索结果可滚动，独立条目显示完整地址以区分重名地点。
        resultContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val resultScroll = ScrollView(this).apply {
            addView(resultContainer)
            isFillViewport = false
        }
        searchPanel.addView(resultScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        searchPanel.addView(Button(this).apply {
            text = "在地图上手动选点"
            setOnClickListener {
                hideKeyboard()
                showMapPanel()
                startMap()
            }
        })
        root.addView(searchPanel, LinearLayout.LayoutParams(-1, 0, 1f))

        mapPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        mapPanel.addView(Button(this).apply {
            text = "更换地点 / 搜索"
            setOnClickListener { showSearchPanel() }
        })
        tvSelected = TextView(this).apply {
            text = "尚未选点：点击地图选择位置"
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, pad / 2, 0, pad / 2)
        }
        mapPanel.addView(tvSelected)
        mapContainer = FrameLayout(this)
        btnLoadMap = Button(this).apply {
            text = "加载地图"
            setOnClickListener { startMap() }
        }
        mapContainer.addView(btnLoadMap, FrameLayout.LayoutParams(-1, -1))
        mapPanel.addView(mapContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        tvMapStatus = TextView(this).apply { text = "地图需要网络连接" }
        mapPanel.addView(tvMapStatus)
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
        mapPanel.addView(btnRetryMap)

        btnConfirm = Button(this).apply {
            text = "确认选点并返回"
            isEnabled = false
        }
        mapPanel.addView(btnConfirm)
        root.addView(mapPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        btnSearch.setOnClickListener { doSearch() }
        btnConfirm.setOnClickListener { confirmAndReturn() }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
    }

    private fun showMapPanel() {
        searchPanel.visibility = View.GONE
        mapPanel.visibility = View.VISIBLE
    }

    private fun showSearchPanel() {
        ++mapSelectionId
        selLat = Double.NaN
        selLng = Double.NaN
        btnConfirm.isEnabled = false
        tvSelected.text = "尚未选点：点击地图选择位置"
        mapPanel.visibility = View.GONE
        searchPanel.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mapPanel.visibility == View.VISIBLE && lastResults.isNotEmpty()) showSearchPanel()
        else super.onBackPressed()
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
        hideKeyboard()
        lastSearchAt = now
        val generation = ++searchGeneration
        lastResults = emptyList()
        selLat = Double.NaN
        selLng = Double.NaN
        ++mapSelectionId
        btnConfirm.isEnabled = false
        tvSelected.text = "尚未选点：点击地图选择位置"
        resultContainer.removeAllViews()
        resultContainer.addView(TextView(this).apply { text = "搜索中…" })
        resultsLabel.text = "全球搜索中…"
        android.util.Log.i(TAG, "doSearch kw='$kw' scope=global")
        Thread {
            var finalUrl = ""
            try {
                val enc = URLEncoder.encode(kw, "UTF-8")
                val sb = StringBuilder()
                sb.append("https://nominatim.openstreetmap.org/search")
                    .append("?q=").append(enc)
                    .append("&format=json&limit=8&accept-language=zh-CN")
                    .append("&addressdetails=1") // 结构化城市/国家
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
                    if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
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
                resultsLabel.text = "搜索未完成，请重试或手动选点"
                resultContainer.addView(TextView(this).apply {
                    text = "⚠️ $msg"
                    setPadding(0, 8, 0, 8)
                })
                if (searchPanel.visibility == View.VISIBLE) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderResults(list: List<NomaPoi>) {
        lastResults = list
        resultContainer.removeAllViews()
        resultsLabel.text = if (list.isEmpty()) "没有找到地点，请换个关键词"
            else "找到 ${list.size} 个地点，请选择具体结果"
        for (p in list.take(8)) {
            val tv = TextView(this).apply {
                // 必须显示：地点名称 + 城市 + 国家
                text = "📍 ${p.name}\n${p.city}, ${p.country}\n${p.displayName}\n" + String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng)
                val inset = (8 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                isFocusable = true
                textSize = 16f
                val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = attrs.getDrawable(0)
                attrs.recycle()
                setOnClickListener { pick(p.lat, p.lng, p.name, "${p.city}, ${p.country}") }
            }
            resultContainer.addView(tv)
            resultContainer.addView(View(this).apply {
                setBackgroundColor(0xffdddddd.toInt())
            }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.density).toInt().coerceAtLeast(1)))
        }
    }

    /** 明确选择：用户点击某条结果/地图某点后才置为已选，绝不自动选 */
    private fun pick(lat: Double, lng: Double, name: String, detail: String, fromMap: Boolean = false) {
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return
        hideKeyboard()
        showMapPanel()
        selLat = lat
        selLng = lng
        selName = name
        selDetail = detail
        tvSelected.text = "已选: $name\n$detail\n" + String.format(Locale.US, "%.6f, %.6f（WGS-84）", lat, lng)
        btnConfirm.isEnabled = false
        btnRetryMap.visibility = View.GONE
        tvMapStatus.text = "正在加载所选位置的地图，请稍候…"
        val id = ++mapSelectionId
        scheduleMapTimeout(id)
        // 地图移动到对应位置，供用户检查确认
        if (webView == null) startMap()
        else mapPanel.post {
            if (id == mapSelectionId && !isDestroyed && !isFinishing) {
                webView?.evaluateJavascript("window.setCenter && setCenter($lat, $lng, $id, $fromMap)", null)
            }
        }
    }

    private fun scheduleMapTimeout(id: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (id == mapSelectionId && !btnConfirm.isEnabled) showMapUnavailable(id)
        }, 12000)
    }

    private fun showMapUnavailable(id: Int) {
        if (id != mapSelectionId || isFinishing || isDestroyed || btnConfirm.isEnabled) return
        tvMapStatus.text = if (selLat.isFinite()) "无法加载所选位置的地图，请检查网络后重试；也可返回手动输入坐标。"
            else "无法加载地图，请检查网络后重试，或搜索地点。"
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
