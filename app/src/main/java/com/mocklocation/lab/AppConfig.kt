package com.mocklocation.lab

import android.content.Context
import java.io.File

/**
 * 本地配置读取：高德 Key 从项目根 local.properties 或 app 私有目录配置文件读取。
 * 缺失时 UI 层用手动经纬度 + 内置测试位置兜底。
 */
object AppConfig {

    private const val LOCAL_PROPS = "local.properties"
    private const val PRIVATE_PROPS = "mocklab.properties"
    const val KEY_AMAP = "AMAP_KEY"

    /** 内置测试位置（北京天安门附近，GCJ-02） */
    val FALLBACK_LOCATIONS = listOf(
        Triple("天安门（内置测试）", 39.908722, 116.397499),
        Triple("北京西站（内置测试）", 39.894914, 116.322431),
        Triple("北京大学（内置测试）", 39.992824, 116.310886),
        Triple("上海人民广场（内置测试）", 31.232762, 121.469204),
        Triple("深圳市民中心（内置测试）", 22.546054, 114.054516)
    )

    fun readAmapKey(context: Context): String? {
        // 1) app 私有目录配置（用户运行时写入）
        val privateFile = File(context.filesDir, PRIVATE_PROPS)
        if (privateFile.exists()) {
            privateFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith(KEY_AMAP)) {
                    val v = trimmed.substringAfter('=').trim().trim('"')
                    if (v.isNotEmpty()) return v
                }
            }
        }
        // 2) 项目根 local.properties（构建期文件，运行时也可能打包进来）
        val localFile = File(context.getExternalFilesDir(null)?.parentFile?.parentFile, LOCAL_PROPS)
            .takeIf { it?.exists() == true }
        // local.properties 通常不打包，主要靠 1)；这里再尝试常见挂载路径
        val candidates = listOf(
            File("/sdcard/Android/data/com.mocklocation.lab/files/mocklab.properties"),
            File("/sdcard/mocklab.properties")
        )
        for (f in candidates) {
            if (f.exists() && f.canRead()) {
                f.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith(KEY_AMAP)) {
                        val v = trimmed.substringAfter('=').trim().trim('"')
                        if (v.isNotEmpty()) return v
                    }
                }
            }
        }
        return null
    }

    /** 保存 Key 到 app 私有目录，供用户在运行时配置 */
    fun saveAmapKey(context: Context, key: String) {
        File(context.filesDir, PRIVATE_PROPS).writeText("$KEY_AMAP=$key\n")
    }
}
