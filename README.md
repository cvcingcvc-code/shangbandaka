# Mock Location Lab

安卓原生**定位模拟器**（开发调试工具）。通过 Android 官方 Mock Location API（`addTestProvider` / `setTestProviderLocation`）把任意坐标注入系统 GPS provider，用于测试依赖定位的功能。无 Root、无 Hook、无任何检测绕过。

- **包名**：`com.mocklocation.lab`
- **最低系统**：Android 8.0（API 26），目标 API 35
- **技术栈**：Kotlin + WebView(Leaflet + OpenStreetMap 瓦片，免 Key) + Nominatim 地点搜索 + 前台服务
- **最新版本**：v1.1.1（见 [Releases](https://github.com/cvcingcvc-code/shangbandaka/releases)）

## 核心功能

| 功能 | 说明 |
|---|---|
| 地图选点 | Leaflet 地图点击任意位置选坐标（WGS-84） |
| 地点搜索 | Nominatim 全球搜索，结果显示**名称 + 城市 + 国家 + 坐标**；三种范围：视野优先 / 仅视野内 / 全球 |
| 模拟注入 | 前台服务常驻通知，约 1 秒/次向系统 GPS provider 注入所选坐标 |
| 定位验证 | 内置独立测试页，用标准 Android API 读取当前定位并显示 `isMock` 标记 |
| 坐标系转换 | 高德 POI（GCJ-02）→ WGS-84 自动转换；OSM 结果原生 WGS-84 直接注入 |

## 安装

1. 从 [Releases](https://github.com/cvcingcvc-code/shangbandaka/releases) 下载 APK 并安装（允许未知来源）。
2. **关键一步**：设置 → 系统 → 开发者选项 → **选择模拟位置信息应用** → 勾选 **Mock Location Lab**（不选此项模拟会失败）。
3. 首次打开授予**精确位置**权限，建议允许通知权限。

## 使用流程

1. **选点**（三选一）：
   - 搜索：输入城市/地标/商家（如「上海中心大厦」）→ 点「🗺️ 地图选点」→ 查看带城市与国家的结果 → 点击条目；
   - 地图点选：地图页直接点击任意位置；
   - 手动输入：主界面直接填纬度/经度。
2. 选中后地图移动到所选位置供核对 →「确认选点并返回」。
3. 主界面核对目标坐标 →「开始模拟」→ 通知栏出现前台服务与当前坐标。
4. 「打开定位读取测试页」验证任意应用视角的定位（应显示 `isMock=true`）。
5. 「停止模拟」释放测试 provider，系统恢复真实定位。

## 搜索范围说明（重要）

| 范围 | 行为 | 适用 |
|---|---|---|
| 视野优先（推荐） | 地图可视区域结果优先；**地图中心在中国时自动加 `countrycodes=cn`** | 国内地点（避免境外重名混淆） |
| 仅当前视野内 | `bounded=1` 严格限定可视区域 | 区域内精确查找 |
| 全球搜索 | 无任何地域限制 | **国外地点（如东京塔）必须切换此项** |

遵循 Nominatim 公共服务政策：1 req/s 客户端节流、带应用标识 UA、无自动补全请求。

## 停止与恢复

- 应用内「停止模拟」→ 测试 provider 立即释放（服务 + 主界面双保险）。
- 完全卸载：先停止模拟 → 开发者选项改回「无」→ 卸载应用，无系统残留。

## 已通过测试（模拟器 emulator-5554 / API 35，2026-10-27）

- ✅ 上海中心大厦：搜索 → 选择 → 注入 `31.235645,121.501250` → 标准 API `isMock=true` 连续读取 → 停止释放
- ✅ 北京天安门：同链路，`39.902722,116.391441`
- ✅ 广州塔：同链路，`23.109015,113.319148`
- ✅ 東京鐵塔（全球搜索）：同链路，`35.658449,139.745536`
- ✅ 停止后 `gps provider [mock]` 移除、`last location=null`、UI 状态「已停止，provider 已释放」
- ✅ 权限未授权/定位关闭/模拟应用未选择/进程重启的引导与恢复路径
- 详细证据：[TEST_REPORT_v1.1.1.md](TEST_REPORT_v1.1.1.md)

## 尚未验证的功能

- ⏳ **真实手机全链路验收**（当前证据全部来自模拟器；人工测试步骤见 [INSTALL_GUIDE.md](INSTALL_GUIDE.md)）
- ⏳ 微信小程序兼容性（小程序能否读到模拟坐标、其风控是否拒绝——不做任何检测绕过）
- ⏳ 高德 POI 搜索（代码就绪，未配置 Key，走内置兜底路径验证）

## 已知问题与限制

1. 只做标准 GPS provider 注入：走网络定位/自带 `isMock` 校验风控的应用可能不受影响或拒绝服务——这是设计边界，**本应用不实现任何检测绕过**。
2. 搜索结果为 WGS-84 直接注入；地图视觉对齐以 WGS-84 底图（OSM/Google Earth）为准。
3. Nominatim 限速 1 req/s，连续搜索会提示稍候；搜索失败不影响地图点选与手动输入。
4. 「视野优先」+ 中国中心会过滤国际地点，搜国外地标需切「全球搜索」。
5. 仅测试调试用途，请遵守当地法律法规，勿用于伪造考勤、签到等场景。

## 从源码构建

见 [BUILD_GUIDE.md](BUILD_GUIDE.md)。快速路径：

```bash
./gradlew :app:assembleDebug   # 调试包
./gradlew :app:assembleRelease # 需本地配置 keystore.properties（见 BUILD_GUIDE）
```

## 许可

仅供开发调试使用。
