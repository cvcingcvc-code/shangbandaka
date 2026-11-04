# 构建说明

需要 JDK 17+、Android SDK platform 35、build-tools 和 Gradle wrapper。设置 `JAVA_HOME`、`ANDROID_HOME` 后执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Release 签名配置读取本地 `signing/keystore.properties`；密钥及配置不提交。发布前用 `apksigner verify --print-certs` 对比此前 Release 的签名证书，计算新 APK SHA-256。没有原密钥时不要换签名冒充兼容升级包。

`v1.1.2-rc.9` 的真机验收状态及验证步骤见 [INSTALL_GUIDE.md](INSTALL_GUIDE.md)。模拟器测试不替代实体手机验收。20 项 JVM 测试覆盖新鲜回调、清理状态和地图导航边界；设备仪器测试仅允许在模拟器运行，它们会临时改变测试应用的 AppOp 并恢复。真实设备按独立验收表操作。
