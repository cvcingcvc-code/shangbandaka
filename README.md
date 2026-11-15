# Mock Location Lab

Android 开发测试工具，使用系统测试位置 provider；不隐藏模拟位置标记，也不绕过第三方检测。

GitHub 已发布候选仍为 `v1.1.2-rc.11`；当前开发代码为 `v1.1.2-rc.14`（versionCode 17），尚未作为可用新版发布。百度 AK 未配置，实体手机验收为 **BLOCKED: PHYSICAL_DEVICE_NOT_TESTED**。最新验证和限制见 [STATUS.md](STATUS.md)。

## 使用

1. 安装 Release APK，授予精确位置权限，并开启系统定位。
2. 在系统开发者选项的「选择模拟位置信息应用」中选择 Mock Location Lab。
3. rc.14 在本应用内使用百度搜索与地图。输入准确名称或完整地址，核对城市，选择结果后在百度地图确认；也可手动点图或输入 WGS-84 经纬度。修改搜索文字后必须重新确认地点。搜索需要在构建时配置 Android AK，见 [百度申请与配置](docs/BAIDU_SETUP.md)。缺 AK 时明确提示，禁用百度搜索与确认，不会假装搜索成功。
4. 点击「开始模拟」，等待「运行中」。程序持续验证 GPS、network 的新回调；旧缓存、重复回调不能续期，坐标不符或中断会撤销运行状态。这不代表地图和微信已验收。
5. 点击「停止模拟」。程序先停止注入、请求解除测试位置，再显示「模拟已解除，等待正常定位」。取得停止之后新的非模拟定位前，不宣称恢复完成；已经发送的位置消息不会改变。

rc.14 不再要求从百度客户端抄回地址。百度地图和搜索 SDK 在首次同意服务说明后才初始化；地图和 POI 统一为 GCJ-02，再用本地近似模型转为引擎的 WGS-84。转换模型单元测试不等于实际地图落点通过。百度搜索、鉴权、地图瓦片和真实手指确认仍须配置 AK 后验收。

运行期间固定所选坐标，并更新定位时间；划掉最近任务不当作停止命令。运行时有超时的 CPU 唤醒保护，停止和失败均释放；耗电、厂商回收及锁屏实际效果仍需真机验证。系统 fused / Google FLP 尚未完成可行性检查，当前没有新增模拟接口。

百度服务有账号权益和配额要求，以控制台为准。仓库保留旧版 Geocoder、Nominatim、OpenStreetMap 代码供历史回归；rc.14 首页主流程已经切换为百度原生选点。当前源码和合并清单未新增广告或推送组件，百度 SDK 的网络与设备信息处理在地图服务说明中披露；这不代表已经完成动态网络抓包。

## 构建与验证

参见 [BUILD_GUIDE.md](BUILD_GUIDE.md) 和 [INSTALL_GUIDE.md](INSTALL_GUIDE.md)。系统定位、独立应用和获授权微信小程序分别记录，不互相替代。小程序是否接受模拟坐标取决于其自身实现，本项目不绕过检测。

冻结需求见 [FIXED_LOCATION_REQUIREMENTS.md](docs/FIXED_LOCATION_REQUIREMENTS.md)；十轮真机验收见 [PHYSICAL_DEVICE_ACCEPTANCE.md](docs/PHYSICAL_DEVICE_ACCEPTANCE.md)。独立读取工具源码位于 `diagnostics/location-reader`。

rc.10 的 [审计与验证记录](docs/AUDIT_RC10_INTERNAL.md) 区分网络用途、代码修复与尚未执行的设备检查；源码审计不能替代动态抓包或正常使用验收。

历史下载：[rc.11 自测候选](https://github.com/cvcingcvc-code/shangbandaka/releases/tag/v1.1.2-rc.11)。旧版保留，它不包含当前百度原生选点改动。模拟器回归只证明对应环境，不替代地图当前位置、微信发送位置和指定授权小程序的真机验收。
