package com.mocklocation.lab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * 独立测试页：读取 Android 标准定位接口，验证模拟位置是否被正确接收、是否标记 isMock。
 */
class TestReaderActivity : AppCompatActivity() {

    private lateinit var lm: LocationManager
    private lateinit var tvLog: TextView
    private val logs = StringBuilder()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock
            } else {
                @Suppress("DEPRECATION")
                location.isFromMockProvider
            }
            val line = "provider=%s lat=%.6f lng=%.6f acc=%.1f isMock=%s time=%tT\n".format(
                location.provider, location.latitude, location.longitude,
                location.accuracy, isMock, location.time
            )
            logs.insert(0, line)
            if (logs.length > 4000) logs.setLength(4000)
            runOnUiThread { tvLog.text = logs.toString() }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "订阅 GPS_PROVIDER (1000ms)，观察 isMock 标记"
        })
        val btnStart = Button(this).apply { text = "开始读取" }
        val btnStop = Button(this).apply { text = "停止读取" }
        val btnClear = Button(this).apply { text = "清空日志" }
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(btnClear)
        tvLog = TextView(this).apply {
            text = "等待数据…"
            textSize = 12f
        }
        root.addView(tvLog)
        setContentView(root)

        btnStart.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 200
                )
                return@setOnClickListener
            }
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, listener
                )
                Toast.makeText(this, "已订阅 GPS_PROVIDER", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "订阅失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        btnStop.setOnClickListener {
            lm.removeUpdates(listener)
            Toast.makeText(this, "已停止读取", Toast.LENGTH_SHORT).show()
        }
        btnClear.setOnClickListener {
            logs.setLength(0)
            tvLog.text = "日志已清空"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "权限已授予，请点击开始读取", Toast.LENGTH_SHORT).show()
        }
    }
}
