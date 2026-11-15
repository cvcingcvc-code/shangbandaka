package com.mocklocation.lab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.Marker
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.poi.OnGetPoiSearchResultListener
import com.baidu.mapapi.search.poi.PoiCitySearchOption
import com.baidu.mapapi.search.poi.PoiDetailResult
import com.baidu.mapapi.search.poi.PoiDetailSearchResult
import com.baidu.mapapi.search.poi.PoiIndoorResult
import com.baidu.mapapi.search.poi.PoiResult
import com.baidu.mapapi.search.poi.PoiSearch
import java.util.Locale

/** Baidu POI results and manual map points both require an explicit map confirmation. */
class BaiduMapPickerActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var cityInput: EditText
    private lateinit var queryInput: EditText
    private lateinit var searchButton: Button
    private lateinit var statusView: TextView
    private lateinit var results: LinearLayout
    private lateinit var searchPanel: LinearLayout
    private lateinit var mapPanel: LinearLayout
    private lateinit var mapContainer: LinearLayout
    private lateinit var selectedView: TextView
    private lateinit var confirmButton: Button
    private var mapView: MapView? = null
    private var baiduMap: BaiduMap? = null
    private var markerIcon: BitmapDescriptor? = null
    private var marker: Marker? = null
    private var search: PoiSearch? = null
    private var queryRevision = 0L
    private var authorizationReady = false
    private var authorizationWaiting = true
    private var mapLoaded = false
    private var renderedDataValid = false
    private var mapSettled = false
    private var selected: LatLng? = null
    private var selectedWgs: DoubleArray? = null
    private var selectionRevision = 0L
    private var renderedSelection = -1L
    private var selectedName = ""
    private var selectedAddress = ""
    private var destroyed = false
    private var resumed = false
    private var mapFailed = false
    private val authorizationListener: (BaiduSdkAccess.Status) -> Unit = { status ->
        if (!destroyed && !mapFailed) {
            authorizationReady = status.state == BaiduSdkAccess.State.AUTHORIZED
            authorizationWaiting = status.state == BaiduSdkAccess.State.WAITING
            if (!authorizationReady) {
                invalidateSearch()
                results.removeAllViews()
                renderedDataValid = false
                renderedSelection = -1L
            }
            statusView.text = status.message
            searchButton.isEnabled = authorizationReady && baiduMap != null
            refreshConfirmation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val query = savedInstanceState?.getString("query") ?: intent.getStringExtra("search_query").orEmpty()
        queryInput.setText(query)
        cityInput.setText(savedInstanceState?.getString("city")
            ?: PlaceSearchPolicy.explicitCity(PlaceSearchPolicy.normalize(query)).orEmpty())
        if (savedInstanceState == null && intent.getBooleanExtra("manual_pick", false)) showMap()
        if (!BaiduSdkAccess.hasKey(this)) {
            statusView.text = "此安装包未配置百度地图密钥，搜索与地图暂不可用。请安装配置完成的候选包。"
            return
        }
        if (BaiduSdkAccess.hasConsent(this)) initializeMap()
        else showConsent()
    }

    private fun showConsent() {
        val explanation = TextView(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            text = "地点搜索和地图由百度地图 SDK 提供。使用时会处理搜索文字、地图浏览区域及网络和设备信息。\n\n" +
                "请阅读百度地图开放平台隐私政策：\nhttps://lbsyun.baidu.com/index.php?title=openprivacy"
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        AlertDialog.Builder(this).setTitle("使用百度地图")
            .setView(explanation)
            .setNegativeButton("暂不使用") { _, _ ->
                statusView.text = "尚未同意百度地图服务说明，搜索与地图未启动。"
            }
            .setPositiveButton("同意并使用") { _, _ ->
                if (BaiduSdkAccess.recordConsent(this)) initializeMap()
                else statusView.text = "无法保存地图服务选择，请重新进入此页。"
            }.show()
    }

    private fun initializeMap() {
        try {
            BaiduSdkAccess.addListener(authorizationListener)
            BaiduSdkAccess.initialize(this)
            val view = MapView(this)
            mapView = view
            baiduMap = view.map
            searchButton.isEnabled = authorizationReady
            mapContainer.addView(view, LinearLayout.LayoutParams(-1, -1))
            if (resumed) view.onResume()
            baiduMap!!.setOnMapLoadedCallback {
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    mapLoaded = true
                    refreshConfirmation()
                }
            }
            baiduMap!!.setOnMapRenderValidDataListener { valid, code, _ ->
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    renderedDataValid = valid && selectedTargetCentered()
                    if (!valid) statusView.text = "百度地图数据加载失败（错误码 ${code}），请检查网络。"
                    refreshConfirmation()
                }
            }
            baiduMap!!.setOnMapRenderCallbadk {
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    if (selectedTargetCentered()) renderedSelection = selectionRevision
                    refreshConfirmation()
                }
            }
            baiduMap!!.setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
                override fun onMapStatusChangeStart(status: MapStatus) = markMapSettled(false)
                override fun onMapStatusChangeStart(status: MapStatus, reason: Int) = markMapSettled(false)
                override fun onMapStatusChange(status: MapStatus) {}
                override fun onMapStatusChangeFinish(status: MapStatus) = markMapSettled(true)
            })
            baiduMap!!.setOnMapClickListener(object : BaiduMap.OnMapClickListener {
                override fun onMapClick(point: LatLng) {
                    if (!destroyed && mapPanel.visibility == View.VISIBLE) pick(point, "地图点选", "")
                }
                override fun onMapPoiClick(poi: MapPoi) {
                    if (!destroyed && mapPanel.visibility == View.VISIBLE) pick(poi.position, poi.name.orEmpty(), "")
                }
            })
            val lat = intent.getDoubleExtra("init_lat", Double.NaN)
            val lng = intent.getDoubleExtra("init_lng", Double.NaN)
            if (validCoordinate(lat, lng)) {
                val center = CoordConverter.wgs84ToGcj02(lat, lng)
                baiduMap!!.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(LatLng(center[0], center[1]), 15f))
            }
            main.postDelayed({
                if (!destroyed && !mapFailed && authorizationWaiting) {
                    statusView.text = "百度地图授权结果超时；请检查网络、应用密钥与安装包签名，完全关闭本应用后重试。"
                }
            }, 15000)
        } catch (e: Exception) {
            mapFailed = true
            authorizationReady = false
            statusView.text = "百度地图启动失败：${e.javaClass.simpleName}"
            searchButton.isEnabled = false
            confirmButton.isEnabled = false
        } catch (e: LinkageError) {
            mapFailed = true
            authorizationReady = false
            statusView.text = "此设备无法加载百度地图组件：${e.javaClass.simpleName}"
            searchButton.isEnabled = false
            confirmButton.isEnabled = false
        }
    }

    private fun buildUi() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        searchPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cityInput = EditText(this).apply { hint = "城市，如上海"; setSingleLine() }
        queryInput = EditText(this).apply {
            hint = "输入地点名称或完整地址"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { searchPlaces(); true } else false
            }
        }
        searchPanel.addView(cityInput)
        searchPanel.addView(queryInput)
        searchButton = Button(this).apply {
            text = "搜索地点"
            isEnabled = false
            setOnClickListener { searchPlaces() }
        }
        searchPanel.addView(searchButton)
        statusView = TextView(this).apply { text = "准备百度地图"; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(statusView)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchPanel.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        searchPanel.addView(Button(this).apply {
            text = "地图手动选点"
            setOnClickListener {
                if (baiduMap != null && authorizationReady) { hideKeyboard(); showMap() }
                else statusView.text = "请先完成百度地图授权与加载。"
            }
        })
        root.addView(searchPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        mapPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        mapPanel.addView(Button(this).apply {
            text = "返回搜索"
            setOnClickListener { mapPanel.visibility = View.GONE; searchPanel.visibility = View.VISIBLE }
        })
        selectedView = TextView(this).apply { text = "点击地图选择具体位置"; maxLines = 4 }
        mapPanel.addView(selectedView)
        mapContainer = LinearLayout(this)
        mapPanel.addView(mapContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        confirmButton = Button(this).apply {
            text = "确认选点并返回"
            isEnabled = false
            setOnClickListener { confirmSelection() }
        }
        mapPanel.addView(confirmButton)
        root.addView(mapPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        setLocationScreenContent(root)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                invalidateSearch()
                results.removeAllViews()
                selected = null
                selectedWgs = null
                ++selectionRevision
                removeMarker()
                selectedView.text = "输入已更改，请重新搜索或在地图选点。"
                refreshConfirmation()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        cityInput.addTextChangedListener(watcher)
        queryInput.addTextChangedListener(watcher)
    }

    private fun searchPlaces() {
        val city = cityInput.text.toString().trim()
        val query = queryInput.text.toString().trim()
        if (destroyed || mapFailed || !authorizationReady || baiduMap == null) return
        if (city.isEmpty() || query.isEmpty()) { statusView.text = "请填写城市和具体地点或地址。"; return }
        hideKeyboard()
        invalidateSearch()
        results.removeAllViews()
        selected = null
        selectedWgs = null
        ++selectionRevision
        if (!removeMarker()) return
        refreshConfirmation()
        val revision = queryRevision
        try {
            val request = PoiSearch.newInstance()
            search = request
            statusView.text = "正在百度地图搜索…"
            request.setOnGetPoiSearchResultListener(object : OnGetPoiSearchResultListener {
                override fun onGetPoiResult(result: PoiResult) {
                    main.post {
                        if (destroyed || mapFailed || !authorizationReady ||
                            revision != queryRevision || search !== request) return@post
                        search = null
                        destroySearch(request)
                        if (result.error != SearchResult.ERRORNO.NO_ERROR) {
                            statusView.text = "百度搜索未完成：${result.error.name}。请核对城市和名称。"
                            return@post
                        }
                        val candidates = result.allPoi.orEmpty().filter {
                            it.location != null && validCoordinate(it.location.latitude, it.location.longitude)
                        }
                        statusView.text = if (candidates.isEmpty()) "没有可用地点，请核对城市和完整名称。"
                            else "找到 ${candidates.size} 个地点，请选一个并在地图确认。"
                        candidates.forEach { poi ->
                            results.addView(Button(this@BaiduMapPickerActivity).apply {
                                text = "${poi.name.orEmpty()}\n${poi.city.orEmpty()}\n${poi.address.orEmpty()}"
                                isAllCaps = false
                                setOnClickListener {
                                    if (authorizationReady && revision == queryRevision &&
                                        city == cityInput.text.toString().trim() &&
                                        query == queryInput.text.toString().trim()) {
                                        pick(poi.location, poi.name.orEmpty(), poi.address.orEmpty())
                                    }
                                }
                            })
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onGetPoiDetailResult(result: PoiDetailResult) {}
                override fun onGetPoiDetailResult(result: PoiDetailSearchResult) {}
                override fun onGetPoiIndoorResult(result: PoiIndoorResult) {}
            })
            val accepted = request.searchInCity(
                PoiCitySearchOption().city(city).keyword(query).cityLimit(true).pageCapacity(10)
            )
            if (!accepted) {
                invalidateSearch()
                statusView.text = "百度搜索请求未被接受，请核对城市后重试。"
                return
            }
            main.postDelayed({
                if (!destroyed && revision == queryRevision && search === request) {
                    invalidateSearch()
                    statusView.text = "百度搜索超时，请检查网络后重试。"
                }
            }, 12000)
        } catch (e: Exception) {
            invalidateSearch()
            statusView.text = "百度搜索启动失败：${e.javaClass.simpleName}。请重开地图后重试。"
        } catch (e: LinkageError) {
            invalidateSearch()
            statusView.text = "百度搜索组件无法加载：${e.javaClass.simpleName}"
        }
    }

    private fun invalidateSearch() {
        ++queryRevision
        search?.let { destroySearch(it) }
        search = null
    }

    private fun destroySearch(request: PoiSearch) {
        try { request.destroy() }
        catch (e: Exception) { logCleanupFailure("搜索请求清理", e) }
        catch (e: LinkageError) { logCleanupFailure("搜索组件清理", e) }
    }

    private fun pick(point: LatLng, name: String, address: String) {
        if (destroyed || mapFailed || !authorizationReady || baiduMap == null ||
            !validCoordinate(point.latitude, point.longitude)) return
        selected = null
        selectedWgs = null
        ++selectionRevision
        renderedSelection = -1L
        renderedDataValid = false
        mapSettled = false
        confirmButton.isEnabled = false
        val wgs = try { CoordConverter.gcj02ToWgs84(point.latitude, point.longitude) }
        catch (_: IllegalArgumentException) {
            if (!removeMarker()) return
            selectedView.text = "无法转换该点的系统坐标，请重新选点。"
            statusView.text = selectedView.text
            return
        }
        if (search != null) invalidateSearch()
        try {
            selected = point
            selectedWgs = wgs
            selectedName = name.ifBlank { "地图点选" }
            selectedAddress = address
            marker?.remove()
            if (markerIcon == null) {
                markerIcon = BitmapDescriptorFactory.fromBitmap(android.graphics.Bitmap.createBitmap(40, 40,
                    android.graphics.Bitmap.Config.ARGB_8888).apply {
                    val canvas = android.graphics.Canvas(this)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xff1565c0.toInt()
                    }
                    canvas.drawCircle(20f, 20f, 18f, paint)
                    paint.color = android.graphics.Color.WHITE
                    canvas.drawCircle(20f, 20f, 7f, paint)
                })
            }
            marker = baiduMap!!.addOverlay(
                MarkerOptions().position(point).icon(markerIcon).anchor(0.5f, 0.5f)
            ) as Marker
            selectedView.text = "$selectedName\n$selectedAddress\n" +
                String.format(Locale.US, "%.6f, %.6f（百度地图 GCJ-02）", point.latitude, point.longitude)
            showMap()
            baiduMap!!.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(point, 17f))
            statusView.text = "正在加载所选地点的地图，请核对标记。"
            refreshConfirmation()
            val revision = selectionRevision
            main.postDelayed({
                if (!destroyed && !mapFailed && authorizationReady &&
                    revision == selectionRevision && !confirmButton.isEnabled) {
                    statusView.text = "所选地点的地图尚未确认加载，请检查网络或重新选点。"
                }
            }, 12000)
        } catch (e: Exception) {
            selected = null
            selectedWgs = null
            failMap("地图选点失败", e)
        } catch (e: LinkageError) {
            selected = null
            selectedWgs = null
            failMap("地图选点组件无法加载", e)
        }
    }

    private fun showMap() {
        searchPanel.visibility = View.GONE
        mapPanel.visibility = View.VISIBLE
    }

    private fun refreshConfirmation() {
        if (!::confirmButton.isInitialized) return
        confirmButton.isEnabled = false
        if (destroyed || mapFailed || !authorizationReady || selectedWgs == null) return
        val point = selected
        val view = mapView
        try {
            val screenPoint = if (point != null) baiduMap?.projection?.toScreenLocation(point) else null
            val markerVisible = marker != null && view != null && screenPoint != null &&
                screenPoint.x in 0 until view.width && screenPoint.y in 0 until view.height
            confirmButton.isEnabled = mapLoaded && renderedDataValid &&
                renderedSelection == selectionRevision && mapSettled && markerVisible && selectedTargetCentered()
            if (confirmButton.isEnabled) statusView.text = "请核对地图标记，再确认此地点。"
        } catch (e: Exception) {
            failMap("无法验证地图标记", e)
        } catch (e: LinkageError) {
            failMap("地图验证组件无法加载", e)
        }
    }

    private fun selectedTargetCentered(): Boolean {
        if (destroyed || mapFailed || !authorizationReady) return false
        val point = selected ?: return false
        return try {
            val center = baiduMap?.mapStatus?.target ?: return false
            mapPanel.visibility == View.VISIBLE &&
                kotlin.math.abs(center.latitude - point.latitude) < 0.00001 &&
                kotlin.math.abs(center.longitude - point.longitude) < 0.00001
        } catch (e: Exception) {
            failMap("无法验证地图中心", e)
            false
        } catch (e: LinkageError) {
            failMap("地图中心组件无法加载", e)
            false
        }
    }

    private fun failMap(reason: String, error: Throwable) {
        mapFailed = true
        authorizationReady = false
        renderedDataValid = false
        renderedSelection = -1L
        searchButton.isEnabled = false
        confirmButton.isEnabled = false
        invalidateSearch()
        results.removeAllViews()
        statusView.text = "$reason：${error.javaClass.simpleName}，请重新进入地图。"
    }

    private fun removeMarker(): Boolean {
        val previous = marker
        marker = null
        return try {
            previous?.remove()
            true
        } catch (e: Exception) {
            failMap("地图标记清理失败", e)
            false
        } catch (e: LinkageError) {
            failMap("地图标记组件无法加载", e)
            false
        }
    }

    private fun logCleanupFailure(operation: String, error: Throwable) {
        Log.w("BaiduMapPicker", "$operation 失败：${error.javaClass.simpleName}")
    }

    private fun markMapSettled(settled: Boolean) {
        runOnUiThread {
            if (!destroyed) {
                mapSettled = settled
                refreshConfirmation()
            }
        }
    }

    private fun confirmSelection() {
        if (selected == null) return
        refreshConfirmation()
        if (!confirmButton.isEnabled) return
        val wgs = selectedWgs ?: return
        if (!validCoordinate(wgs[0], wgs[1])) { statusView.text = "选点坐标无效，请重新选择。"; return }
        setResult(Activity.RESULT_OK, Intent().putExtra("lat", wgs[0]).putExtra("lng", wgs[1])
            .putExtra("name", selectedName).putExtra("detail", selectedAddress))
        finish()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(queryInput.windowToken, 0)
        queryInput.clearFocus()
        cityInput.clearFocus()
    }

    private fun validCoordinate(lat: Double, lng: Double) =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    override fun onResume() {
        super.onResume()
        resumed = true
        try { mapView?.onResume() }
        catch (e: Exception) { failMap("地图恢复失败", e) }
        catch (e: LinkageError) { failMap("地图恢复组件无法加载", e) }
    }

    override fun onPause() {
        resumed = false
        try { mapView?.onPause() }
        catch (e: Exception) { failMap("地图暂停失败", e) }
        catch (e: LinkageError) { failMap("地图暂停组件无法加载", e) }
        finally { super.onPause() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("city", cityInput.text.toString())
        outState.putString("query", queryInput.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        invalidateSearch()
        BaiduSdkAccess.removeListener(authorizationListener)
        val view = mapView
        val icon = markerIcon
        mapView = null
        baiduMap = null
        marker = null
        markerIcon = null
        try { view?.onDestroy() }
        catch (e: Exception) { logCleanupFailure("地图清理", e) }
        catch (e: LinkageError) { logCleanupFailure("地图组件清理", e) }
        try { icon?.recycle() }
        catch (e: Exception) { logCleanupFailure("地图标记资源清理", e) }
        catch (e: LinkageError) { logCleanupFailure("地图标记组件资源清理", e) }
        super.onDestroy()
    }
}
