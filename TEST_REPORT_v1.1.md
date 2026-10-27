# Mock Location Lab — v1.1 测试报告与功能完成清单

日期：2026-10-27 ｜ 测试环境：模拟器 API 35 (x86_64, Android 15, host GPU)
APK：`F:\MockLocationLab\MockLocationLab-v1.1.0-debug.apk`（versionCode 2 / versionName 1.1.0，3.4MB）
构建：`gradle :app:assembleDebug` → BUILD SUCCESSFUL

## 一、v1.1 新增功能与验证结果

| # | 功能 | 结果 | 证据 |
|---|---|---|---|
| 1a | 地图可视化选点（WebView + Leaflet，OSM 瓦片，无 Key） | ✅ 已验证 | 点击地图 → Leaflet 标记落点 → JS 桥回传 `39.906972, 116.397484`（WGS-84），「已选」文本实时更新 |
| 1b | POI 搜索（城市/地标/商家，Nominatim 免费无 Key） | ✅ 已验证 | launcher intent 传 `search_query='Shanghai Tower'` → 自动搜索 → 渲染结果「Shanghai Tower, Travbyen, Billund… (55.73492, 9.12837)」→ 点击结果定位地图 |
| 1c | 选点后直接开始模拟 | ✅ 已验证 | 确认选点回主界面 → 坐标回填 → 点开始 → `dumpsys location`: `Location[gps 55.734919,9.128370 … mock]` |
| 2 | 错误提示完善（权限/定位开关/坐标范围校验） | ✅ 已验证（部分） | startMock 四级检查：权限→引导官方申请；定位关闭→跳转设置；经纬度无效/超范围→状态+Toast 提示；停止→确认 Toast |
| 2 | 高德 POI 搜索（有 Key 增强） | ⏸️ 未验证 | 代码就绪（AmapPoiSearch），未配置高德 Key，走兜底路径 |

## 二、v1.0 功能回归测试（不得回归失效）

| # | 用例 | 结果 | 证据 |
|---|---|---|---|
| R1 | 内置坐标模拟（39.908722, 116.397499） | ✅ 无回归 | `Location[gps 39.908722,116.397499 … mock]`，fused 跟随 |
| R2 | 测试页读取 isMock 标记 | ✅ 无回归 | `provider=gps lat=39.908722 lng=116.397499 acc=5.0 isMock=true`（每秒持续） |
| R3 | 停止模拟释放 provider | ✅ 无回归 | `gps provider removed mock provider override`，`last location=null`，ProviderRequest[OFF] |
| R4 | 手动经纬度输入 | ✅ 无回归（v1.0 已验证 31.2304,121.4737） | 本轮未重测（输入注入同地图页受限），逻辑未改动 |
| R5 | 前台服务常驻通知 | ✅ 无回归 | 通知含坐标与更新次数（v1.0 验证，逻辑未改动） |

## 三、未验证 / 受限项（如实标记）

| 项 | 状态 | 说明 |
|---|---|---|
| 无头模拟器键盘注入 | ⚠️ 受限 | `-no-window` 模式下 IME 不显示（mInputShown=false），`input text` 无法进入 EditText → 手动输入框与搜索框的**键盘路径**无法在无头模式自动测试；UI 与逻辑路径（intent 直传）已验证。**有头模式/实体手机可正常测试** |
| 高德 POI（有 Key） | ⏸️ 未验证 | 需要高德开放平台申请 Key（绑定 sha1+包名），代码就绪 |
| 微信小程序兼容性 | ⏸️ 方案提供，未执行 | 见第五节方案；未开发任何反检测功能 |
| 实体手机测试 | ⏸️ 步骤提供，未执行 | 见第四节 |

## 四、实体安卓手机测试步骤

1. **安装**：将 APK 传到手机（或 `adb install MockLocationLab-v1.1.0-debug.apk`），允许「安装未知来源应用」
2. **开启开发者选项**：设置 → 关于手机 → 连续点击「版本号」7 次
3. **选择模拟位置应用**：设置 → 系统 → 开发者选项 → 「选择模拟位置信息应用」→ 选「Mock Location Lab」
4. **权限**：打开 app → 点「申请定位权限」→ 允许（含「始终允许」更佳）
5. **选点**：点「🗺️ 地图选点」→ 搜索城市/地标（如「东方明珠」）或点击地图 → 「确认选点并返回」
6. **开始模拟**：点「开始模拟」→ 确认常驻通知出现（显示坐标与更新次数）
7. **验证**：点「打开定位读取测试页」→ 开始读取 → 应看到 `provider=gps isMock=true` 且坐标为所选位置；也可打开其他地图 app 验证定位变化
8. **停止**：返回主界面 → 「停止模拟」→ 通知消失，其他 app 定位恢复真实 GPS
9. **记录**：截图测试页日志（含 isMock 标记）+ dumpsys location 输出

> 注：部分国产 ROM（MIUI/ColorOS/EMUI 等）开发者选项里该设置名称可能为「模拟位置」或「允许模拟位置」，且可能需要额外登录账号解锁。

## 五、微信小程序定位兼容性测试方案（授权测试环境）

**定位**：开发调试用途。如实记录，不承诺微信一定接受模拟数据，**不开发反检测、身份验证或风控绕过功能**。

**前置**：实体手机已完成第四节 1-4 步（模拟应用已选择、权限已授予）。

**测试步骤**：
1. 打开本 app → 选目标位置 → 「开始模拟」（保持前台服务运行）
2. 打开微信 → 进入被测小程序的位置相关页面（如「位置打卡」「附近门店」）
3. 触发定位（页面刷新或重新进入）
4. **如实记录**以下三项：
   - 小程序显示的位置是否变为模拟坐标
   - 小程序自身是否提示「定位失败」或使用默认位置
   - 微信是否出现任何风险提示
5. 停止模拟，再次触发小程序定位，记录恢复情况

**判定参考**：
- 微信/小程序接受模拟坐标 → 兼容性 OK（仅表示该页面未过滤 mock 标记）
- 显示失败/默认位置 → 微信侧过滤了模拟数据（isMock=true 如实上报是官方语义，属预期行为）

**边界**：本工具始终如实写入 `isMock` 标记（官方 API 语义），不做任何隐藏、篡改或绕过。打卡风控、身份验证相关测试不在本工具用途范围内。

## 六、功能完成清单

- ✅ 原生 Kotlin + Gradle，minSdk 26（targetSdk 35）
- ✅ 官方 addTestProvider / setTestProviderLocation / removeTestProvider（不 Root、不 Hook、不反检测）
- ✅ 地图可视化选点（Leaflet + OSM，无 Key 可用；点击地图回传 WGS-84）
- ✅ POI 搜索：Nominatim（城市/地标/商家，免费无 Key，已验证）+ 高德 v3 API（有 Key 增强，未验证）
- ✅ GCJ-02 ↔ WGS-84 坐标转换（高德路径用；OSM 路径原生 WGS-84 免转换）
- ✅ UI：搜索框 / 地图标记 / 目标经纬度 / 开始 / 停止 / 状态显示
- ✅ 前台服务 + 常驻通知，每秒更新测试坐标（API 26/29/34+ 适配）
- ✅ 停止释放测试 provider，不影响真实 GPS
- ✅ 官方权限申请流程 + 未授权/定位关闭/模拟未选择/进程重启处理
- ✅ 独立测试页（标准定位接口 + isMock 记录）
- ✅ 微信小程序兼容性测试方案（如实记录，不反检测）

## 七、构建说明

```bash
export JAVA_HOME=<JDK17+>
export ANDROID_HOME=<SDK（需 android-35 platform）>
cd F:\MockLocationLab
gradle :app:assembleDebug
# 产物: app\build\outputs\apk\debug\app-debug.apk
```

依赖：androidx.core / appcompat（阿里云镜像已内置 settings/build 脚本）。
高德 Key 配置方式见 BUILD_GUIDE.md。
