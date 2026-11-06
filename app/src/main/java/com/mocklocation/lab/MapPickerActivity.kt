package com.mocklocation.lab

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.FrameLayout
import java.util.Locale
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 系统地点搜索后明确选择并在地图确认；联网备用搜索由用户单独触发。
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

    private val queryGate = PlaceSearchQueryGate()
    private var mapSelectionId = 0
    private var lastResults: List<PlaceCandidate> = emptyList()
    private lateinit var searchRepository: PlaceSearchRepository
    private var activeSearch: PlaceSearchRepository.Request? = null

    /** JS 桥：地图点击回传（WGS-84） */
    inner class JsBridge {
        @JavascriptInterface
        fun onMapClick(lat: Double, lng: Double, contextId: Int) {
            runOnUiThread {
                if (!isDestroyed && !isFinishing && mapPanel.visibility == View.VISIBLE &&
                    contextId == mapSelectionId) pick(lat, lng, "地图点选", "", fromMap = true)
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
            runOnUiThread {
                // Manual mode has no selected point and uses id 0 in the page.
                if (id == 0 && !selLat.isFinite() && mapPanel.visibility == View.VISIBLE) {
                    showMapUnavailable(mapSelectionId)
                } else showMapUnavailable(id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchRepository = PlaceSearchRepository(this)
        buildUi()
        if (savedInstanceState != null) {
            // Do not restore a map confirmation across recreation. Restore the list so
            // the user can explicitly pick and confirm again without another request.
            etSearch.setText(savedInstanceState.getString("query", ""))
            @Suppress("DEPRECATION")
            val saved = savedInstanceState.getParcelableArrayList<Bundle>("results")
            if (saved != null) renderResults(saved.map { item ->
                PlaceCandidate(item.getString("name", ""), item.getString("city", ""), item.getString("country", ""),
                    item.getDouble("lat"), item.getDouble("lng"), item.getString("display", ""),
                    PlaceSearchSource.values().firstOrNull { it.name == item.getString("source") } ?: PlaceSearchSource.NOMINATIM,
                    item.getString("region", ""), item.getString("countryCode", ""), item.getBoolean("administrative"))
            }.filter { PlaceSearchPolicy.accepts(etSearch.text.toString(), it) })
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
                putString("source", p.source.name); putString("region", p.region)
                putString("countryCode", p.countryCode); putBoolean("administrative", p.administrativeOnly)
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
                    if (!MapNavigationPolicy.isLocalMap(url)) return
                    val lat = if (selLat.isFinite()) selLat else intent.getDoubleExtra("init_lat", 39.908722)
                    val lng = if (selLng.isFinite()) selLng else intent.getDoubleExtra("init_lng", 116.397499)
                    if (!selLat.isFinite()) tvMapStatus.text = "点击地图选择位置；地图需要网络连接"
                    val selectionId = if (selLat.isFinite()) mapSelectionId else 0
                    view?.evaluateJavascript("window.setCenter && setCenter($lat, $lng, $selectionId); window.setInteractionContext && setInteractionContext($mapSelectionId)", null)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    handleMapNavigation(request?.url?.toString(), request?.isForMainFrame == true,
                        request?.hasGesture() == true)

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    handleMapNavigation(url, isMainFrame = true, hasGesture = false)
            }
            map.settings.javaScriptEnabled = true
            // android_asset remains available when arbitrary filesystem access is disabled.
            map.settings.allowFileAccess = false
            map.settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            map.settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            map.settings.allowUniversalAccessFromFileURLs = false
            map.settings.domStorageEnabled = false
            map.settings.javaScriptCanOpenWindowsAutomatically = false
            map.settings.setSupportMultipleWindows(false)
            map.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            map.settings.cacheMode = WebSettings.LOAD_DEFAULT
            map.settings.userAgentString += " MockLocationLab (+https://github.com/cvcingcvc-code/shangbandaka)"
            map.addJavascriptInterface(JsBridge(), "AndroidBridge")
            map.webChromeClient = WebChromeClient()
            map.loadUrl(MapNavigationPolicy.MAP_URL)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "地图初始化失败", e)
            mapContainer.removeAllViews()
            webView?.destroy()
            webView = null
            showMapUnavailable(mapSelectionId)
        }
    }

    /** External attribution pages open without this app's JavaScript bridge. */
    private fun handleMapNavigation(url: String?, isMainFrame: Boolean, hasGesture: Boolean): Boolean {
        return when (MapNavigationPolicy.decide(url, isMainFrame, hasGesture)) {
            MapNavigationPolicy.Decision.LOCAL_MAP -> false
            MapNavigationPolicy.Decision.EXTERNAL_BROWSER -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(requireNotNull(url))).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Open attribution page failed: ${e.javaClass.simpleName}")
                    Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
                true
            }
            MapNavigationPolicy.Decision.BLOCK -> true
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
            text = "搜索准确名称或地址，选择匹配结果后在地图确认"
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
        // Editing invalidates both an outstanding request and already rendered results.
        // Install only after all views exist, including when restoring query text.
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                cancelSearch()
                lastResults = emptyList()
                resultContainer.removeAllViews()
                ++mapSelectionId
                selLat = Double.NaN
                selLng = Double.NaN
                selName = ""
                selDetail = ""
                btnConfirm.isEnabled = false
                tvSelected.text = "尚未选点：请搜索并确认新地点"
                resultsLabel.text = "输入已更改，请点击搜索；旧结果已失效"
                webView?.evaluateJavascript("window.clearSelection && clearSelection()", null)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
    }

    private fun showMapPanel() {
        cancelSearch()
        searchPanel.visibility = View.GONE
        mapPanel.visibility = View.VISIBLE
        // A WebView that was GONE may have a stale Leaflet viewport size.
        mapPanel.post {
            if (!isDestroyed && !isFinishing && mapPanel.visibility == View.VISIBLE) {
                webView?.evaluateJavascript("window.setInteractionContext && setInteractionContext($mapSelectionId); window.refreshMap && refreshMap()", null)
            }
        }
    }

    private fun showSearchPanel() {
        ++mapSelectionId
        selLat = Double.NaN
        selLng = Double.NaN
        btnConfirm.isEnabled = false
        tvSelected.text = "尚未选点：点击地图选择位置"
        webView?.evaluateJavascript("window.clearSelection && clearSelection()", null)
        mapPanel.visibility = View.GONE
        searchPanel.visibility = View.VISIBLE
        // Entering the map cancels response tickets. Rebind the unchanged, previously
        // completed result list so Back does not leave visible but inert list items.
        if (lastResults.isNotEmpty()) {
            renderResults(lastResults)
            if (lastResults.first().source == PlaceSearchSource.SYSTEM) addOnlineFallback()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mapPanel.visibility == View.VISIBLE && lastResults.isNotEmpty()) showSearchPanel()
        else super.onBackPressed()
    }

    /** No autocomplete: every provider request follows an explicit user action. */
    private fun doSearch() = beginSearch(online = false)

    private fun beginSearch(online: Boolean) {
        val keyword = etSearch.text.toString().trim()
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }
        cancelSearch()
        hideKeyboard()
        val ticket = queryGate.capture(keyword)
        lastResults = emptyList()
        selLat = Double.NaN
        selLng = Double.NaN
        ++mapSelectionId
        btnConfirm.isEnabled = false
        tvSelected.text = "尚未选点：点击地图选择位置"
        resultContainer.removeAllViews()
        val source = if (online) PlaceSearchSource.NOMINATIM else PlaceSearchSource.SYSTEM
        resultsLabel.text = "${source.label}搜索中…"
        resultContainer.addView(TextView(this).apply { text = "正在查询，最多等待 9 秒；不会自动选择结果" })
        val receive: (PlaceSearchOutcome) -> Unit = { result ->
            if (!isDestroyed && !isFinishing && queryGate.accepts(ticket, etSearch.text.toString()) &&
                searchPanel.visibility == View.VISIBLE) {
                activeSearch = null
                renderOutcome(result, ticket)
                android.util.Log.i(TAG, "Search complete revision=${ticket.revision} source=${result.source} state=${result.state} accepted=${result.places.size} received=${result.received}")
            }
        }
        activeSearch = if (online) searchRepository.searchOnline(keyword, receive)
            else searchRepository.searchSystem(keyword, receive)
    }

    private fun cancelSearch() {
        queryGate.invalidate()
        activeSearch?.cancel()
        activeSearch = null
    }

    private fun renderOutcome(result: PlaceSearchOutcome, ticket: PlaceSearchQueryGate.Ticket) {
        if (result.state == PlaceSearchState.RESULTS) {
            renderResults(result.places, ticket)
            resultsLabel.text = "${result.source.label}：${result.places.size} 个匹配地点，请明确选择" +
                if (result.cached) "（本机缓存）" else ""
        } else {
            lastResults = emptyList()
            resultContainer.removeAllViews()
            val reason = when (result.state) {
                PlaceSearchState.EMPTY -> if (result.received > 0)
                    "返回的地点均不符合输入的城市或具体关键词，已过滤；不会用其他城市或城市中心代替"
                    else "没有找到匹配地点；可尝试准确全名、所在区或门牌地址"
                PlaceSearchState.UNAVAILABLE -> "手机没有可用的系统地点服务"
                PlaceSearchState.TIMEOUT -> "地点服务超时；迟到结果不会覆盖当前选择"
                PlaceSearchState.NETWORK_ERROR -> "地点服务网络请求失败${result.detail.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}"
                PlaceSearchState.BUSY -> "地点服务忙，请稍后重试"
                else -> "地点服务错误${result.detail.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}"
            }
            resultsLabel.text = "${result.source.label}：搜索未完成选点"
            resultContainer.addView(TextView(this).apply {
                text = reason
                setPadding(0, 8, 0, 8)
            })
        }
        if (result.source == PlaceSearchSource.SYSTEM) {
            addOnlineFallback()
        }
    }

    private fun addOnlineFallback() {
        resultContainer.addView(Button(this).apply {
            text = "备用：联网搜索当前文字"
            setOnClickListener { beginSearch(online = true) }
        })
    }

    private fun renderResults(list: List<PlaceCandidate>,
                              ticket: PlaceSearchQueryGate.Ticket = queryGate.capture(etSearch.text.toString())) {
        lastResults = list
        resultContainer.removeAllViews()
        resultsLabel.text = if (list.isEmpty()) "没有找到地点，请换个关键词"
            else "找到 ${list.size} 个地点，请选择具体结果"
        for (p in list.take(8)) {
            val tv = TextView(this).apply {
                // 必须显示：地点名称 + 城市 + 国家
                text = "📍 ${p.name}\n${p.city}, ${p.country}\n${p.displayName}\n来源：${p.source.label}\n" +
                    String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng)
                val inset = (8 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                isFocusable = true
                textSize = 16f
                val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = attrs.getDrawable(0)
                attrs.recycle()
                setOnClickListener {
                    if (!queryGate.accepts(ticket, etSearch.text.toString()) ||
                        searchPanel.visibility != View.VISIBLE) return@setOnClickListener
                    pick(p.lat, p.lng, p.name, "${p.displayName}\n来源：${p.source.label}",
                        coordinateNote = if (p.source == PlaceSearchSource.SYSTEM) "系统地址坐标，请核对地图" else "WGS-84")
                }
            }
            resultContainer.addView(tv)
            resultContainer.addView(View(this).apply {
                setBackgroundColor(0xffdddddd.toInt())
            }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.density).toInt().coerceAtLeast(1)))
        }
    }

    /** 明确选择：用户点击某条结果/地图某点后才置为已选，绝不自动选 */
    private fun pick(lat: Double, lng: Double, name: String, detail: String, fromMap: Boolean = false,
                     coordinateNote: String = "WGS-84") {
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return
        hideKeyboard()
        showMapPanel()
        selLat = lat
        selLng = lng
        selName = name
        selDetail = detail
        tvSelected.text = "已选: $name\n$detail\n" + String.format(Locale.US, "%.6f, %.6f（%s）", lat, lng, coordinateNote)
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

    override fun onDestroy() {
        cancelSearch()
        webView?.destroy()
        super.onDestroy()
    }
}
