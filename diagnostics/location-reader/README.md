# 独立定位读取诊断工具

独立包名 `lab.reader`，版本 `1.5-diagnostics` / versionCode 6。首页分别订阅 Android `LocationManager` 的 GPS、network、系统 fused，另提供可选的 Google Play 服务 FLP 读取页及显式测试地名的系统 Geocoder 后端探针。读取页不注入位置、不进入模拟模式，也不修改 Mock Location Lab 的状态。系统 fused 与 Google FLP 是不同入口，须分别记录。

## 构建

需要 JDK 17+、Android SDK platform 35，沿用主项目 Gradle 8.14.3 wrapper。从仓库根目录执行：

```powershell
.\gradlew.bat -p diagnostics/location-reader :app:assembleDebug --max-workers=1
```

设置本机 `JAVA_HOME`、`ANDROID_HOME`；不要把 SDK 路径或密钥写入仓库。依赖已缓存时可加 `--offline`。输出：`diagnostics/location-reader/app/build/outputs/apk/debug/app-debug.apk`。它使用诊断包的 debug 签名，不能覆盖安装主应用。构建产物、`.gradle`、`local.properties`、APK 与密钥由主仓库 `.gitignore` 排除。

## 读取与停止恢复验证

1. 在测试手机授权精确定位。前台页面分别显示三个订阅入口的结果；设备没有某入口或订阅失败时会直接显示。
2. 每条回调显示本轮编号、订阅入口、实际 provider、经纬度（Android Location，按 WGS84 解释）、精度、`isMock`、`Location.time`、位置及接收的 elapsed-realtime 纳秒值、接收时年龄和订阅起点。“递增样本”与“新鲜(0～5000ms)”分别显示；只有晚于本轮订阅、时间递增、年龄在闭区间 0～5000ms 且坐标有效，才标记“本轮可用样本”。订阅前缓存、重复、乱序、未来时间戳和过期数据均不能作可用样本。可用仅表示本轮时间与坐标有效，不表示模拟目标匹配或正常恢复已通过。
3. 验证停止恢复时，先记录主应用停止及清理结果的时间。此版本每次返回前台都会移除旧监听、清空旧显示并建立新的订阅起点；离开页面时停止读取。确认新的订阅起点晚于停止，再等待本轮可用且 `isMock=false` 的位置，并核对实际地点。旧轮次的迟到回调会被 generation 检查丢弃。没有新位置就记录“等待正常定位”，不能凭旧缓存宣布恢复。
4. 定位读取页只显示当前前台轮次内存中的有界历史，暂停即清空，不自动写实时定位日志或文件。需要留证时由测试者在离开页面前手动截图或复制，并注意真实位置隐私。页面不运行前台服务，不能用其离开页面后的断流推断主应用失效。精确位置权限不可用或被撤销时明确显示错误，返回系统设置授予后重新进入页面可重建订阅。
5. 本工具的读取结果不能证明地图当前位置、微信发送位置界面或指定授权小程序已接受模拟坐标。这些项目按主仓库真机验收表单独测试。

## 可选 Google FLP 读取

从首页点「Google FLP 独立读取」。本页依赖 `com.google.android.gms:play-services-location:21.4.0`，先检查设备 Google Play 服务可用性，再请求高精度连续定位。服务不可用时显示实际状态码，不把该设备记为 FLP 通过；不能用系统 fused 的结果代替此项。

订阅要求不交付初始旧缓存，回调仍逐条校验时间。屏幕显示本轮订阅起点、位置及接收的墙钟/单调时间、实际 provider、WGS-84 坐标、精度和 `isMock`。只有晚于本轮起点、时间递增、接收时年龄为 0～5000ms 且坐标有效的样本才标为可用。页面每秒复查新鲜程度，样本过期后撤销「正在返回本轮新鲜样本」提示。该提示只说明数据新鲜，不代表与用户选择的目标一致。

请求订阅和移除监听均检查异步结果，12 秒未确认时显示超时。离开页面使本轮失效并清空显示；如果订阅调用尚未完成，等待其完成后再移除，避免迟到成功恢复旧监听。旧订阅清理确认之前不会建立新订阅。「重新订阅 / 重试清理」可请求新一轮或重试失败的清理；仍在等待异步结果时继续显示未确认状态。返回页面后重新建立起点，停止恢复验证仍需新鲜且 `isMock=false` 的回调。

本页没有调用 `setMockMode` 或 `setMockLocation`。它用于先确定实际 Google FLP 读数是否存在缺口，再决定生产服务是否需要对应适配。[Google 官方接口说明](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)

2026-11-15 的 v6 debug 合并清单包含精确/粗略定位及 AndroidX 签名级内部接收权限，没有 INTERNET、广告或推送权限。定位后端及 Google Play 服务可能联网，不能将整个诊断过程描述为离线。读取页没有自行发送坐标的 HTTP 代码，也不把实时坐标写入文件或日志。此构建及 lint 通过；Google FLP 实际读取仍须以适用设备证据为准。

## 系统 Geocoder 后端探针

显式启动 `lab.reader/.GeocoderProbe`，通过字符串 extra `query` 传入一个已明确的公开测试地点，例如：

```powershell
adb shell am start -n lab.reader/.GeocoderProbe --es query '上海人民广场'
```

它只验证系统 Geocoder 后端是否存在、是否对给定原文返回结果，不代表主应用真实搜索框或手指选点已通过。不要用这一入口代替用户流程验收，也不自动纠正或选择查询名称。

Android 33+ 使用异步 `getFromLocationName`；旧版本使用后台线程的同步接口。每次最多请求5条，20秒未回调记 TIMEOUT，迟到或旧请求的回调不覆盖新记录。屏幕和 JSON 记录请求 ID、查询原文、来源、locale、isPresent、状态、地址名称/完整地址行、返回坐标及开始/完成的墙钟和单调时间。isPresent=true 不保证查询成功；返回地址也需要核对是否为用户所指地点。

最新一次显式查询导出为 `/sdcard/Android/data/lab.reader/files/diagnostics/geocoder-probe-latest.json`，可用 `adb pull` 保存；每次查询会更新该文件，开始时状态为 QUERYING，结束时为 RESULTS、EMPTY、UNAVAILABLE、TIMEOUT 或错误。`request_id` 和 `query` 用于排除上一轮残留。该文件只保存测试查询的 Geocoder 结果；探针不调用实时定位读取 API，不导出 Reader 页读到的真实定位。

探针没有自行发送 HTTP 请求，但系统 Geocoder 服务可能通过网络提供查询结果，不能描述为离线搜索。关闭自动备份声明；Geocoder 查询导出仅供本地诊断，不自动上传。Geocoder 探针不使用 Google FLP。

主项目生命周期测试、各读取入口的实际回调、真机安装与目标应用显示须按各自证据记录，不能用源码或构建成功代替运行通过。
