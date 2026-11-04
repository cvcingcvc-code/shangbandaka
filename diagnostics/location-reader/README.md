# 独立定位读取诊断工具

独立包名 `lab.reader`，版本 `1.1-diagnostics` / versionCode 2。此工具只订阅 Android `LocationManager` 的 GPS、network、系统 fused，既不注入位置，也不修改 Mock Location Lab 的状态。系统 fused 与 Google Play services FLP 是不同入口；本工具没有 GMS 依赖，不能代替 Google FLP 验证。

## 构建

需要 JDK 17+、Android SDK platform 35，沿用主项目 Gradle 8.14.3 wrapper。从仓库根目录执行：

```powershell
.\gradlew.bat -p diagnostics/location-reader :app:assembleDebug --max-workers=1
```

设置本机 `JAVA_HOME`、`ANDROID_HOME`；不要把 SDK 路径或密钥写入仓库。依赖已缓存时可加 `--offline`。输出：`diagnostics/location-reader/app/build/outputs/apk/debug/app-debug.apk`。它使用诊断包的 debug 签名，不能覆盖安装主应用。构建产物、`.gradle`、`local.properties`、APK 与密钥由主仓库 `.gitignore` 排除。

## 读取与停止恢复验证

1. 在测试手机授权精确定位。前台页面分别显示三个订阅入口的结果；设备没有某入口或订阅失败时会直接显示。
2. 每条回调显示订阅入口、实际 provider、经纬度（Android Location，按 WGS84 解释）、精度、`isMock`、`Location.time`、位置及接收的 elapsed-realtime 纳秒值、接收时年龄、订阅起点，以及是否为订阅后且递增的新样本。订阅前缓存、重复、乱序和未来时间戳不作为新样本。
3. 验证停止恢复时，先记录主应用停止及清理结果的时间。按返回键退出本诊断页面后重新打开，确认新的订阅起点晚于停止；仅切换到最近任务不一定重建订阅。等待收到该起点之后、时间有效且 `isMock=false` 的新位置，再核对实际地点。没有新位置就记录“等待正常定位”，不能凭旧缓存宣布恢复。
4. 此工具只显示当前页面内存中的有界历史，不自动写坐标日志或文件，没有网络权限。需要留证时由测试者手动截图或复制，并注意真实位置隐私。页面不运行前台服务；后台可能被系统限频，不能用其后台断流推断主应用失效。
5. 本工具的读取结果不能证明地图当前位置、微信发送位置界面或指定授权小程序已接受模拟坐标。这些项目按主仓库真机验收表单独测试。

当前仅准备源码；主项目生命周期测试、真机安装与实际读取需按各自证据记录，不能用构建成功代替运行通过。
