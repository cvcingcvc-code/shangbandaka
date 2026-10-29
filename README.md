# Mock Location Lab

Android 开发测试工具，使用系统测试位置 provider；不隐藏模拟位置标记，也不绕过第三方检测。

当前候选版本：`v1.1.2-rc.6`（versionCode 9）。**实体手机验收尚未完成：BLOCKED: PHYSICAL_DEVICE_NOT_TESTED。** 模拟器结果不能证明真机或微信小程序兼容。

## 使用

1. 安装 Release APK，授予精确位置权限，并开启系统定位。
2. 在系统开发者选项的「选择模拟位置信息应用」中选择 Mock Location Lab。
3. 输入城市、地标或地点并搜索；在结果中明确选择地点，核对地图后确认。也可手动点击地图或输入 WGS-84 经纬度。
4. 点击「开始模拟」，等待状态显示「模拟已成功」。该状态表示测试 provider 的目标坐标已注入；此状态验证 GPS 和网络测试接口；其他应用使用融合定位或自行判断时，是否接受需单独验证。
5. 点击「停止模拟」。应用会报告停止和清理请求；实际定位恢复需在设备和独立读取端验证。

搜索使用公共 Nominatim，地图使用 OpenStreetMap；需网络连接，服务可能限流或不可用。公共服务的每秒一次上限适用于整个应用的所有用户，且要求能够不更新应用切换服务；当前候选仅供小规模测试，不满足公开规模运行要求。参见 https://operations.osmfoundation.org/policies/nominatim/。地点数据与地图来自 OpenStreetMap 贡献者。请检查结果中的城市、国家和坐标。

## 构建与验证

参见 [BUILD_GUIDE.md](BUILD_GUIDE.md) 和 [INSTALL_GUIDE.md](INSTALL_GUIDE.md)。系统定位、独立应用和获授权微信小程序分别记录，不互相替代。小程序是否接受模拟坐标取决于其自身实现，本项目不绕过检测。
