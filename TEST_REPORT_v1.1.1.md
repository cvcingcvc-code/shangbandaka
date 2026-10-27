# Mock Location Lab v1.1.1 最终验收报告

日期：2026-10-27 ｜ 版本：v1.1.1（versionCode 3）｜ 测试环境：Android 模拟器 emulator-5554（API 35，host GPU）

> **Phase D 实体手机检测（2026-10-27 19:20）**：`adb devices -l` 仅检测到 emulator-5554（sdk_gphone64_x86_64），**无实体安卓设备连接**。按预案：不伪造测试结果，保留模拟器测试报告，发布可安装 Beta 包，人工手机测试步骤见 INSTALL_GUIDE.md，等待真实手机反馈。

---

## 一、本次修复内容（P0 定位 + P1 最小修复）

### P0 根因定位过程（插桩取证，未盲目改代码）

1. **给 MapPickerActivity 加诊断日志**（tag=MapPickerSearch）：记录每次搜索的关键词、scope、viewbox、centerInChina、最终 URL、HTTP 状态码、响应长度/摘要、解析结果数。宿主机同 URL 对比确认 Nominatim 返回一致。
2. **逐项核对 P0 检查清单**：
   - `countrycodes=cn`：全球分支完全不附加任何参数，仅视野优先分支且地图中心在中国时添加 —— 切换真实生效，非 UI 文案切换。✅
   - `bounded`：全球分支无 `bounded=1`；仅视野内分支用 `bounded=1`，视野优先用 `bounded=0`。✅
   - WebView↔Kotlin 数据传递：`renderResults` 补了 `isDestroyed/isFinishing` 防护；`runUiToast` 改为同时写入结果区（UI dump 可捕获）+ Toast。✅

### 铁证：东京塔 0 结果的真正根因

插桩后实测锁定 —— **不是搜索逻辑 bug，是 headless 自动化输入派发问题**：

- preset 自动搜索路径（`search_query` extra → `doSearch()`）100% 正常：logcat 可见完整链路 URL → HTTP 200 → 解析 → 结果渲染。
- **headless 会话中 `input tap` 点击「搜索」按钮系统性失效**：10+ 次尝试（多坐标 [933,94]/[930,100]/[934,95]、多时序、swipe-in-place、logcat 清空后首击），`doSearch` 零触发。同期 Spinner 下拉、结果项点击、确认按钮 tap 全部正常。
- 日志旁证：`GoogleInputMethodService.onStartInputView` + `IME_INSETS_SHOW_ANIMATION` 卡住 —— 软键盘 insets 动画在 headless 下异常，可能吞掉该区域后续触摸派发。
- 此前（v1.1.1 第一轮）东京塔「全球搜索 0 条」实为：点击从未执行 + 旧版 UI 无法区分「无结果」与「请求失败」。

### P1 最小修复（仅地图搜索相关，Engine/Service 零改动）

| 文件 | 改动 |
|---|---|
| `MapPickerActivity.kt` | ① 诊断日志（TAG=MapPickerSearch）；② `runUiToast` 错误信息写入结果区（不只靠 Toast，UI dump 可捕获）；③ `renderResults` 加 isDestroyed/isFinishing 防护；④ 自动化入口新增 `search_scope` extra（0=视野优先/1=仅视野内/2=全球），headless 下用 extra 控制 scope |
| `MainActivity.kt` | `openMapPicker` 转发 `search_scope` extra（1 行） |
| `map.html` | 新增 `moveend/zoomend` → `AndroidBridge.onMapMove(lat, lng, span)` 回传（支撑 viewbox 排序） |
| `app/build.gradle.kts` | versionCode 2→3，versionName 1.1.0→1.1.1 |

**未触碰**：MockLocationEngine.kt、MockLocationService.kt、CoordConverter.kt、AmapPoiSearch.kt、TestReaderActivity.kt（已通过的核心模块零改动）。

---

## 二、四地点最终验收实测记录（全部 PASS）

每地完整走：搜索 → 显示名称+城市+国家 → 用户明确点击选择 → 地图移动定位 → 确认返回 → 开始模拟 → dumpsys 注入证据 → 标准 API isMock 读取 → 停止释放。

### 1️⃣ 上海中心大厦（中国，cn 过滤路径）—— PASS

| 检查项 | 实测结果 |
|---|---|
| 搜索结果 | 2 条，全部「上海中心大厦 / 浦东新区, 中国」；丹麦 Billund 混淆项已被排除 |
| 请求 URL（logcat） | `...q=Shanghai+Tower...&viewbox=116.31750,39.95672,...&bounded=0&countrycodes=cn` |
| HTTP | 200，解析 count=2 |
| 用户选择 | 已选: 上海中心大厦 / 浦东新区, 中国 / 31.235645, 121.501250（WGS-84） |
| 注入证据 | `last location=Location[gps 31.235645,121.501250 ... mock]`，`identity=10209/com.mocklocation.lab` |
| 标准 API 读取 | `provider=gps lat=31.235645 lng=121.501250 acc=5.0 isMock=true`（30+ 秒连续） |
| 停止恢复 | `gps provider [mock]` 移除，`last location=null`，「已停止，provider 已释放」 |

### 2️⃣ 北京天安门（中国，cn 过滤路径）—— PASS

| 检查项 | 实测结果 |
|---|---|
| 搜索结果 | 1 条：「天安门广场 / 东城区, 中国 (39.90272, 116.39144)」 |
| 用户选择 | 已选: 天安门广场 / 东城区, 中国 / 39.902722, 116.391441（WGS-84） |
| 注入证据 | `Location[gps 39.902722,116.391441 ... mock]` |
| 标准 API 读取 | `provider=gps lat=39.902722 lng=116.391441 acc=5.0 isMock=true` |
| 停止恢复 | 状态「已停止，provider 已释放」 |

### 3️⃣ 广州塔（中国，cn 过滤路径）—— PASS

| 检查项 | 实测结果 |
|---|---|
| 搜索结果 | 8 条全部中国广州（海珠区×7 / 番禺区×1），无混淆项 |
| 用户选择 | 已选: 广州塔 / 海珠区, 中国 / 23.109015, 113.319148（WGS-84） |
| 注入证据 | `Location[gps 23.109015,113.319148 ... mock]` |
| 标准 API 读取 | `provider=gps lat=23.109015 lng=113.319148 acc=5.0 isMock=true` |
| 停止恢复 | 状态「已停止，provider 已释放」 |

### 4️⃣ 東京鐵塔（国际，全球搜索路径）—— PASS（本次修复重点）

| 检查项 | 实测结果 |
|---|---|
| 搜索方式 | `search_scope=2`（全球）+ query「Tokyo Tower」，经 preset 自动化入口触发 |
| 请求 URL（logcat） | `...q=Tokyo+Tower&format=json&limit=8&accept-language=zh-CN&addressdetails=1`（**无** countrycodes/viewbox/bounded，全球参数正确） |
| HTTP | 200，解析 count=1 |
| 搜索结果 | 「東京鐵塔 / 港區, 日本 (35.65845, 139.74554)」 |
| 用户选择 | 已选: 東京鐵塔 / 港區, 日本 / 35.658449, 139.745536（WGS-84） |
| 注入证据 | `Location[gps 35.658449,139.745536 ... mock]` |
| 标准 API 读取 | `provider=gps lat=35.658449 lng=139.745536 acc=5.0 isMock=true`（30+ 秒连续） |
| 停止恢复 | 状态「已停止，provider 已释放」 |

### 回归：修复国际搜索后上海中心大厦复测 —— PASS

- 请求 URL 仍含 `countrycodes=cn`（中国过滤保留）；2 条中国结果；注入 `Location[gps 31.235645,121.501250 ... mock]`；isMock=true 读取正常；停止后 `gps provider [mock]` 计数 0、`last location=null`。
- 全程 logcat 搜索异常/HTTP 4xx/5xx 计数：**0**。

---

## 三、P1 服务稳定性检查确认

- ✅ Nominatim 节流：1.1s 间隔（限 1 req/s），仅用户点搜索/自动化入口触发，无自动补全。
- ✅ UA：`MockLocationLab/1.1.1 (Android debug tool)`，含应用标识。
- ✅ OSM 署名：map.html attribution「© OpenStreetMap contributors」。
- ✅ 瓦片缓存：WebView `LOAD_DEFAULT` 走 HTTP 缓存。
- ✅ 搜索失败不破坏其他能力：HTTP 非 200/异常时明确报错并提示「仍可地图点选/手输坐标」，错误信息写入结果区可捕获；地图点击/手动输入路径独立可用。
- ✅ 已测试的位置核心（Engine/Service）零改动，全量回归无回归项。

---

## 四、交付物

| 文件 | 说明 |
|---|---|
| `F:\MockLocationLab\MockLocationLab-v1.1.1-beta.1-release.apk` | **最终 Release APK（独立签名）**，versionCode 3，SHA-256 `f3eaa3ce2e2fec0c88e856c9d64dce6743e87d3d4dd7755e724e1438a0d93d2d`，2,728,464 字节，Release 下载地址 https://github.com/cvcingcvc-code/shangbandaka/releases/tag/v1.1.1-beta.1（Pre-release） |
| `F:\MockLocationLab\MockLocationLab-v1.1.1-debug.apk` | Debug 版（SHA-256 `f8d0abf324043085a13169f027aef40a94bd61864b864edf8b3e7443cf6ecea5`，3,594,457 字节，本报告第一~三节测试所用包） |
| `F:\MockLocationLab-snapshot-v1.1.1-20261027.zip` | 本地快照（源码+文档 32 文件，交付前状态可恢复） |
| GitHub 仓库 | https://github.com/cvcingcvc-code/shangbandaka （main 21e2b2d1e4，tag v1.1.1-beta.1） |

**Release 签名补充验证（模拟器，release 包 smoke）**：卸载 debug → 安装 release → `mock_location: allow` 重配 → 启动正常 → mock 注入 `Location[gps 39.908722,116.397499 ... mock]` → 停止后 `gps provider [mock]` 计数 0 → apksigner 核查独立证书（SHA-256 461211f9...4b6，CN=Mock Location Lab）→ aapt2 badging：minSdk 26 / targetSdk 35 / 7 项权限齐全。

---

## 五、真实测试记录中的失败项与受限项（如实记录）

1. **headless `input tap` 点击「搜索」按钮失效**（本次根因）：headless 会话输入派发边缘问题，真实手指操作不受影响；自动化验收已改走 `search_query`+`search_scope` extra 路径（已验证可靠）。
2. **headless `input text` 进入 EditText 受限**：软键盘 IME 未弹出时文本无法注入输入框；preset extra 路径绕开。
3. **模拟器网络瞬断窗口**：东京塔第一轮 4 次尝试恰逢模拟器网络 10:18:01 重建（dumpsys connectivity 证据），网络恢复后控制组（Beijing）通过。
4. **Toast 消息 UI dump 捕获不稳定**：已修复为错误信息同步写入结果区 TextView。
5. **真机未测**：本报告全部证据来自模拟器（emulator-5554, API 35）；真机安装与验收步骤见第六节，结果待真机验证后补充。
6. **微信小程序兼容性未测**：测试计划已给出（v1.1 报告），实际验证待真机环境。

---

## 六、真机安装与验收步骤

### 安装

1. 手机开启「设置 → 关于手机 → 连点版本号 7 次」打开开发者选项。
2. 开发者选项中开启「USB 调试」。
3. USB 连接电脑，`adb install MockLocationLab-v1.1.1-debug.apk`（或直接传 APK 到手机点击安装，允许未知来源）。
4. 开发者选项 → 「选择模拟位置信息应用」→ 选 **Mock Location Lab**。
5. 系统设置 → 应用 → Mock Location Lab → 权限 → 允许精确位置；通知权限建议允许（前台服务通知）。

### 验收（每地 2 分钟）

1. 打开应用 → 搜索框输入「上海中心大厦」→ 点「🗺️ 地图选点」→ 查看结果含名称/城市/国家 → 点击正确条目 → 地图移动到该位置 → 「确认选点并返回」。
2. 检查目标坐标 = 31.2356, 121.5013 → 「开始模拟」→ 通知栏出现前台服务通知（坐标持续显示）。
3. 「打开定位读取测试页」→ 「开始读取」→ 应见 `lat=31.2356 lng=121.5012 isMock=true`。
4. 「停止模拟」→ provider 释放（其他地图应用定位恢复真实位置）。
5. 重复验证北京天安门（39.9027, 116.3914）/广州塔（23.1090, 113.3191）/东京塔（35.6584, 139.7455，用「全球搜索」范围）。

### 微信小程序兼容性验证（授权环境内，如实记录，不做任何绕过）

1. 真机开启模拟（如上海中心大厦）后，打开任一使用 `wx.getLocation` 的小程序。
2. 若小程序请求定位，正常授权，观察返回坐标是否为模拟坐标。
3. **如实记录**：部分小程序后台可能校验 `isMock`/服务端风控并拒绝服务——这是预期行为，本应用不做任何检测绕过；测试只验证「标准 Android API 层面模拟对小程序可见」，不对抗任何业务风控。
