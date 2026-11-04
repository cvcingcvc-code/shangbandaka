# Mock Location Lab

Android 开发测试工具，使用系统测试位置 provider；不隐藏模拟位置标记，也不绕过第三方检测。

当前候选版本：`v1.1.2-rc.9`（versionCode 12）。**实体手机验收尚未完成：BLOCKED: PHYSICAL_DEVICE_NOT_TESTED。** 模拟器结果不能证明真机、地图当前位置、微信发送位置界面或指定小程序兼容。

## 使用

1. 安装 Release APK，授予精确位置权限，并开启系统定位。
2. 在系统开发者选项的「选择模拟位置信息应用」中选择 Mock Location Lab。
3. 输入城市、地标或地点并搜索；在结果中明确选择地点，核对地图后确认。也可手动点击地图或输入 WGS-84 经纬度。
4. 点击「开始模拟」，等待「运行中」。程序持续验证 GPS、network 的新回调；旧缓存、重复回调不能续期，坐标不符或中断会撤销运行状态。这不代表地图和微信已验收。
5. 点击「停止模拟」。程序先停止注入、请求解除测试位置，再显示「模拟已解除，等待正常定位」。取得停止之后新的非模拟定位前，不宣称恢复完成；已经发送的位置消息不会改变。

运行期间固定所选坐标，并更新定位时间。运行时有超时的 CPU 唤醒保护，停止和失败均释放；耗电及锁屏实际效果仍需真机验证。系统 fused / Google FLP 尚未完成可行性检查，当前没有新增模拟接口。

搜索使用公共 Nominatim，地图使用 OpenStreetMap；需网络连接，服务可能限流或不可用。公共服务的每秒一次上限适用于整个应用的所有用户，且要求能够不更新应用切换服务；当前候选仅供小规模测试，不满足公开规模运行要求。参见 https://operations.osmfoundation.org/policies/nominatim/。地点数据与地图来自 OpenStreetMap 贡献者。请检查结果中的城市、国家和坐标。

## 构建与验证

参见 [BUILD_GUIDE.md](BUILD_GUIDE.md) 和 [INSTALL_GUIDE.md](INSTALL_GUIDE.md)。系统定位、独立应用和获授权微信小程序分别记录，不互相替代。小程序是否接受模拟坐标取决于其自身实现，本项目不绕过检测。

冻结需求见 [FIXED_LOCATION_REQUIREMENTS.md](docs/FIXED_LOCATION_REQUIREMENTS.md)；十轮真机验收见 [PHYSICAL_DEVICE_ACCEPTANCE.md](docs/PHYSICAL_DEVICE_ACCEPTANCE.md)。独立读取工具源码位于 `diagnostics/location-reader`。

rc.9 的 [静态审计与候选交付记录](docs/AUDIT_RC9.md) 区分网络用途、代码修复与尚未执行的设备检查；源码审计不能替代动态抓包或正常使用验收。
