# Mock Location Lab — 构建与使用说明

原生 Android Kotlin 定位模拟器（非网页、非小程序）。基于官方
`LocationManager.addTestProvider / setTestProviderLocation`，
配合系统开发者选项「选择模拟位置信息应用」工作。
不使用 Root、Hook、反检测；停止后释放自己注册的 provider，不改真实 GPS 硬件。

## 环境

- JDK 17+（本项目用 JDK 21）
- Android SDK：platforms;android-35、build-tools;35.0.0、platform-tools
- Gradle 8.14.3（或用 wrapper）
- AGP 8.13.0 / Kotlin 2.0.21+（本项目验证用 2.2.20 亦可）

## 构建

```bash
export JAVA_HOME=<jdk>
export ANDROID_HOME=<sdk>
cd MockLocationLab
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

依赖解析失败时可加阿里云镜像（settings.gradle.kts 已内置）。

## 高德 Key 配置（可选）

缺 Key 时自动用「内置测试位置 + 手动经纬度」兜底，不影响核心模拟功能。
配置方式任选其一：

1. 运行时写入 app 私有目录（推荐）：
   `adb shell run-as com.mocklocation.lab sh -c 'echo AMAP_KEY=你的Key > files/mocklab.properties'`
2. 推送到外部存储：
   `adb push mocklab.properties /sdcard/Android/data/com.mocklocation.lab/files/`

Key 生效后，「搜索 POI」可用（学校/商场/地标等关键词，高德 v3 place/text API）。
高德返回 GCJ-02 坐标，选中后自动转 WGS-84 再注入系统（CoordConverter）。

## 使用步骤（真机/模拟器一致）

1. 安装 APK，打开「Mock Location Lab」
2. 系统设置 → 开发者选项 → 选择模拟位置信息应用 → 选「Mock Location Lab」
   （或 `adb shell appops set com.mocklocation.lab android:mock_location allow`）
3. 回到 app：申请定位权限 → 选目标位置（搜索/内置/手输经纬度）→ 点「开始模拟」
4. 前台服务启动，常驻通知显示当前坐标与更新次数，每秒注入一次
5. 打开「定位读取测试页」→ 开始读取：验证 `provider=gps isMock=true`
6. 点「停止模拟」：释放 provider，系统 GPS 恢复真实数据

## 系统状态处理

| 情况 | 处理 |
|---|---|
| 定位权限未授权 | 启动时检测，按钮触发官方 requestPermissions |
| 系统定位关闭 | 检测 isProviderEnabled，跳转定位设置页 |
| 模拟应用未选择 | addTestProvider 抛 SecurityException，通知引导去开发者选项 |
| 进程重启 | onCreate 检查 lastKnownLocation.isMock 恢复状态显示 |

## 测试结果（2026-10-27，模拟器 API 35 x86_64）

| 用例 | 结果 |
|---|---|
| assembleDebug 构建 | ✅ BUILD SUCCESSFUL（APK 3.4MB） |
| 安装到设备 | ✅ Streamed Install Success |
| 开始模拟（内置坐标 39.908722,116.397499） | ✅ `Location[gps 39.908722,116.397499 … mock]` |
| 手动输入坐标（31.2304,121.4737 上海） | ✅ `Location[gps 31.230400,121.473700 … mock]`，fused 跟随 |
| 测试页读取 isMock | ✅ `provider=gps … isMock=true`（每秒持续） |
| 停止模拟释放 provider | ✅ `gps provider removed mock provider override`，`last location=null` |
| 前台服务常驻通知 | ✅ 通知含坐标与更新次数 |
| POI 搜索（需 Key） | ⏸️ 未配置 Key，走兜底路径（功能代码已就绪） |

## 微信小程序兼容性说明

用户可自行对微信小程序执行兼容性测试（如位置打卡类页面读取到的坐标变化）。
注意：

- 微信是否采用模拟数据由微信侧策略决定，本工具**不承诺**微信一定接受
- 本工具不做反检测（isMock 标记始终如实写入）、不做身份验证、不做任何打卡风控绕过
- 用途定位：开发调试（LBS 功能测试、位置相关 UI 验证）

## 项目结构

```
MockLocationLab/
├── settings.gradle.kts          # buildscript classpath 方式引入 AGP/KGP
├── build.gradle.kts             # allprojects 仓库（含阿里云镜像）
├── app/build.gradle.kts         # minSdk 26 / targetSdk 35
└── app/src/main/
    ├── AndroidManifest.xml      # 权限 + 前台服务 location 类型
    ├── java/com/mocklocation/lab/
    │   ├── MainActivity.kt          # 搜索/手输/开始/停止/状态 UI
    │   ├── MockLocationEngine.kt    # addTestProvider 封装（API 26+ 适配）
    │   ├── MockLocationService.kt   # 前台服务，每秒注入
    │   ├── TestReaderActivity.kt    # 独立测试页（isMock 验证）
    │   ├── AmapPoiSearch.kt         # 高德 POI 搜索（Key 缺失兜底）
    │   ├── CoordConverter.kt        # GCJ-02 ↔ WGS-84
    │   └── AppConfig.kt             # 本地 Key 读取 + 内置测试位置
    └── res/                     # 布局/图标/主题资源
```
