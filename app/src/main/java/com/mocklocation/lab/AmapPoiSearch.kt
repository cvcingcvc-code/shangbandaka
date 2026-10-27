package com.mocklocation.lab

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 高德 Web 服务 API：POI 关键词搜索。
 * Key 缺失时上层用内置测试位置兜底。
 * 注意：高德返回 GCJ-02 坐标，注入前统一由 CoordConverter 处理。
 */
object AmapPoiSearch {

    data class Poi(val name: String, val lat: Double, val lng: Double, val address: String)

    private val handler = Handler(Looper.getMainLooper())

    interface Callback {
        fun onSuccess(list: List<Poi>)
        fun onError(message: String)
    }

    fun search(context: Context, keyword: String, city: String = "北京", cb: Callback) {
        val key = AppConfig.readAmapKey(context)
        if (key.isNullOrBlank()) {
            cb.onError("未配置高德 Key（在 app 私有目录写入 mocklab.properties: AMAP_KEY=xxx），已用内置位置兜底")
            return
        }
        Thread {
            try {
                val encKw = URLEncoder.encode(keyword, "UTF-8")
                val encCity = URLEncoder.encode(city, "UTF-8")
                val url = "https://restapi.amap.com/v3/place/text" +
                    "?keywords=$encKw&city=$encCity&offset=10&page=1&key=$key&extensions=base"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val body = readStream(conn)
                conn.disconnect()
                if (code != 200) {
                    postError(cb, "HTTP $code")
                    return@Thread
                }
                val json = JSONObject(body)
                val status = json.optString("status")
                if (status != "1") {
                    postError(cb, "高德错误: ${json.optString("info")}")
                    return@Thread
                }
                val pois = json.optJSONArray("pois") ?: JSONArray()
                val out = ArrayList<Poi>()
                for (i in 0 until pois.length()) {
                    val p = pois.getJSONObject(i)
                    val locStr = p.optString("location") // "lng,lat"
                    val parts = locStr.split(",")
                    if (parts.size == 2) {
                        out.add(
                            Poi(
                                name = p.optString("name"),
                                lng = parts[0].toDoubleOrNull() ?: continue,
                                lat = parts[1].toDoubleOrNull() ?: continue,
                                address = p.optString("address")
                            )
                        )
                    }
                }
                handler.post { cb.onSuccess(out) }
            } catch (e: Exception) {
                postError(cb, e.message ?: "网络错误")
            }
        }.start()
    }

    private fun postError(cb: Callback, msg: String) {
        handler.post { cb.onError(msg) }
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
}
