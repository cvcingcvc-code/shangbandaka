# 构建说明

需要 JDK 17+、Android SDK platform 35、build-tools 和 Gradle wrapper。设置 `JAVA_HOME`、`ANDROID_HOME` 后执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Release 签名配置读取本地 `signing/keystore.properties`；密钥及配置不提交。发布前用 `apksigner verify --print-certs` 对比此前 Release 的签名证书，计算新 APK SHA-256。没有原密钥时不要换签名冒充兼容升级包。

rc.14 百度主流程需要本机 `signing/baidu.properties` 中的 Android AK，包名和 Release SHA-1 见 [申请与配置](docs/BAIDU_SETUP.md)。没有 AK 也可以构建以检查配置拦截、定位生命周期和备用坐标输入；这样的安装包无法执行百度搜索/地图确认，不作为可用新版发布。不要把签名配置或 AK 文件提交到仓库。

最新验收见 [STATUS.md](STATUS.md)。模拟器测试不替代实体手机验收。47 项 JVM 测试覆盖定位、清理、坐标转换、地图导航、搜索匹配、超时与查询修订；`node tools/check-map-bridge.cjs` 检查保留的旧地图桥。设备仪器测试仅允许在模拟器运行，会临时改变测试应用的 AppOp 并恢复；真实设备按独立验收表操作。

模拟器仪器测试须先安装 debug APK，在外部执行 `adb shell pm grant com.mocklocation.lab android.permission.ACCESS_FINE_LOCATION` 和对应的 `ACCESS_COARSE_LOCATION`。不要在仪器测试 tearDown 中撤销运行时权限，这会让 Android 终止整个测试进程。测试完成后由外部测试环境负责还原权限或关闭临时模拟器，不能在实体手机运行此套 AppOps 测试。GitHub 工作流已包含安装及授权步骤。
