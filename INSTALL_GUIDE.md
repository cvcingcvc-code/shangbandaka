# 候选版安装和实体手机测试

候选版本 `v1.1.2-rc.6`，包名 `com.mocklocation.lab`，Android 8.0 及以上。真实手机验收状态：**BLOCKED: PHYSICAL_DEVICE_NOT_TESTED**。

1. 获取对应候选 APK 并核对公布的 SHA-256。优先直接覆盖安装；如系统提示签名冲突，不要卸载，先备份应用内重要数据，再由用户决定是否卸载旧包。不同签名无法覆盖升级。
2. 开启系统定位，授予精确位置权限；在开发者选项「选择模拟位置信息应用」中选择 Mock Location Lab。
3. 在应用中输入地点并搜索，点选明确结果，核对城市、国家和地图后确认。点击「开始模拟」，记录状态和目标经纬度。
4. 用独立 Android 定位读取应用检查标准 API 返回坐标和 `isMock`；记录系统型号、Android 版本和结果。另行在用户有权测试的微信小程序中检查，并记录该小程序是否接受坐标，不作检测绕过。
5. 切换前后台、锁屏后检查状态，点击「停止模拟」，用独立读取端验证后续正常定位。定位恢复可能需要等待设备获取真实位置。

如失败，请保留失败页面截图和设备型号、Android 版本、操作步骤。连接 USB 调试后可提供相关日志：

```powershell
adb logcat -d -s MockLocationService MockLocationStatus MapPickerSearch
adb shell dumpsys location
```

日志可能含目标坐标和地点名称，分享前请检查个人敏感信息。安装、系统模拟、独立读取及微信小程序兼容性必须分别记录。
