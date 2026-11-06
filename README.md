# Mock Location Lab

Android 开发测试工具，使用系统测试位置 provider；不隐藏模拟位置标记，也不绕过第三方检测。

当前候选版本：`v1.1.2-rc.10`（versionCode 13）。**实体手机验收尚未完成：BLOCKED: PHYSICAL_DEVICE_NOT_TESTED。** 模拟器结果不能证明真机、地图当前位置、微信发送位置界面或指定小程序兼容。

## 使用

1. 安装 Release APK，授予精确位置权限，并开启系统定位。
2. 在系统开发者选项的「选择模拟位置信息应用」中选择 Mock Location Lab。
3. 输入准确地点名称或地址并搜索。优先使用手机系统地点服务；没有匹配结果时，可点「备用：联网搜索当前文字」。核对名称、城市、国家和地图后确认，也可手动点图或输入 WGS-84 经纬度。修改搜索文字后必须重新确认地点。
4. 点击「开始模拟」，等待「运行中」。程序持续验证 GPS、network 的新回调；旧缓存、重复回调不能续期，坐标不符或中断会撤销运行状态。这不代表地图和微信已验收。
5. 点击「停止模拟」。程序先停止注入、请求解除测试位置，再显示「模拟已解除，等待正常定位」。取得停止之后新的非模拟定位前，不宣称恢复完成；已经发送的位置消息不会改变。

运行期间固定所选坐标，并更新定位时间；划掉最近任务不当作停止命令。运行时有超时的 CPU 唤醒保护，停止和失败均释放；耗电、厂商回收及锁屏实际效果仍需真机验证。系统 fused / Google FLP 尚未完成可行性检查，当前没有新增模拟接口。

系统地点服务可能通过设备后端联网；备用搜索使用公共 Nominatim，地图使用 OpenStreetMap，服务可能限流或不可用。Nominatim 每秒一次上限适用于整个应用的所有用户，且要求能够不更新应用切换服务；当前候选仅供小规模自测，不满足公开规模运行要求。参见 https://operations.osmfoundation.org/policies/nominatim/。地点数据与地图来自 OpenStreetMap 贡献者。请检查结果中的城市、国家和坐标。

## 构建与验证

参见 [BUILD_GUIDE.md](BUILD_GUIDE.md) 和 [INSTALL_GUIDE.md](INSTALL_GUIDE.md)。系统定位、独立应用和获授权微信小程序分别记录，不互相替代。小程序是否接受模拟坐标取决于其自身实现，本项目不绕过检测。

冻结需求见 [FIXED_LOCATION_REQUIREMENTS.md](docs/FIXED_LOCATION_REQUIREMENTS.md)；十轮真机验收见 [PHYSICAL_DEVICE_ACCEPTANCE.md](docs/PHYSICAL_DEVICE_ACCEPTANCE.md)。独立读取工具源码位于 `diagnostics/location-reader`。

rc.10 的 [审计与验证记录](docs/AUDIT_RC10_INTERNAL.md) 区分网络用途、代码修复与尚未执行的设备检查；源码审计不能替代动态抓包或正常使用验收。

下载：[rc.10 自测候选 Pre-release](https://github.com/cvcingcvc-code/shangbandaka/releases/tag/v1.1.2-rc.10)。按用户要求先提供安装包自行测试，模拟器及真机验收仍为 BLOCKED，不能据此宣称地图或微信已兼容。
