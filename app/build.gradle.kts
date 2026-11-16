import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release 签名：signing/keystore.properties（本地，不入库）
val keystoreProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

val baiduProps = Properties().apply {
    val file = rootProject.file("signing/baidu.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}
val baiduMapAk = baiduProps.getProperty("BAIDU_MAP_AK", "").trim()
// Only a local configuration check. Real authorization is verified by the SDK.
val baiduKeyConfigured = baiduMapAk.isNotEmpty() &&
    baiduMapAk.all { it.code in 33..126 } &&
    baiduMapAk.lowercase() !in setOf("your_ak", "your_baidu_ak", "your_android_ak", "changeme", "todo")
val allowMissingBaiduKeyForQa = providers.gradleProperty("allowMissingBaiduKeyForQa")
    .map { it == "true" }.getOrElse(false)

val verifyReleaseBaiduKey = tasks.register("verifyReleaseBaiduKey") {
    group = "verification"
    description = "Reject a normal Release build without a configured Baidu Android AK."
    doLast {
        if (!baiduKeyConfigured) {
            if (!allowMissingBaiduKeyForQa) {
                throw GradleException("BAIDU_AK_NOT_CONFIGURED: 请在 signing/baidu.properties 配置 Android AK，" +
                    "不能使用空值、含空白/非 ASCII 的内容或占位文本。缺 AK 的内部回归须显式使用 " +
                    "-PallowMissingBaiduKeyForQa=true；该包不能用于百度搜索/地图验收。")
            }
            logger.warn("INTERNAL_QA_ONLY: 百度 AK 未配置，搜索和地图确认不可用。此包不得作为可用新版发布。")
        } else {
            logger.lifecycle("Baidu AK configuration is present. SDK authorization and map acceptance remain required.")
        }
    }
}

// Gate the prerequisite, rather than assembleRelease.doFirst, which runs after packaging.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseBaiduKey)
}

android {
    namespace = "com.mocklocation.lab"
    compileSdk = 35
    useLibrary("android.test.runner")
    useLibrary("android.test.base")

    defaultConfig {
        applicationId = "com.mocklocation.lab"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        versionCode = 17
        versionName = "1.1.2-rc.14"
        manifestPlaceholders["BAIDU_MAP_AK"] = baiduMapAk
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.baidu.lbsyun:BaiduMapSDK_Map:9.0.0")
    implementation("com.baidu.lbsyun:BaiduMapSDK_Search:9.0.0")
    testImplementation("junit:junit:4.13.2")
}
