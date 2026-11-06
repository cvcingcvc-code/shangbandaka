# 固定地点模拟与可靠停止：真机验收记录

更新时间：2026-11-06。当前结论：**BLOCKED: PHYSICAL_DEVICE_NOT_TESTED**（本轮 ADB 列表为空，未执行下表）。这不撤销历史已发现的失败，也不把编译、安装或本应用回调视为其他应用通过。

## 设备与测试对象

| 字段 | 当前记录 |
| --- | --- |
| 设备 | 历史记录：REDMI K80 Ultra，型号 25060RK16C |
| 系统 | 历史记录：Android 16 / API 36；重连后重新确认 |
| 本轮连接 | `adb devices -l` 无设备，BLOCKED |
| 待验收版本、提交和 APK SHA-256 | rc.11 / versionCode 14；见 [候选发布页](https://github.com/cvcingcvc-code/shangbandaka/releases/tag/v1.1.2-rc.11) |
| 开发者选项模拟应用、AppOps、精确定位权限 | 重连后记录；历史授权不证明本轮有效 |
| 地图 App 名称、版本、当前位置入口 | 待指定和记录；必须检查当前位置，不是地图镜头 |
| 微信版本和发送位置界面入口 | 待记录；重新进入发送位置界面，不用历史消息 |
| 授权小程序名称、版本、授权范围和测试页面 | 尚未指定，BLOCKED |
| Google Play 服务是否可用及版本 | 未确认；仅适用时验证 Google FLP |
| 独立读取工具 | 独立包实时订阅 GPS / network / 系统 fused；使用前记录版本与 SHA-256；Google FLP 需另行适用性检查 |

## 证据规则

每轮创建独立证据目录 `R01` 至 `R10`，记录开始、每次关键读取、停止和恢复的设备时间及采集时间。日志、截图和操作步骤使用同一轮编号；分享前去除真实住址、账号等不必要私人数据，保留测试地点和时间对应关系。

定位回调至少记录：订阅入口、实际 provider、经纬度、坐标系、精度、`isMock`、`Location.time`、`elapsedRealtimeNanos`、接收时的单调时间和计算的数据年龄。初次验证只用启动之后的回调；停止恢复只用停止之后新的非模拟位置。仅 `getLastKnownLocation` / `getLastLocation` 的旧缓存不足以通过。

中文地点确认后记录唯一目标、搜索结果城市和国家、输入坐标系与转换方式。地图、微信、小程序若采用不同坐标系，比较转换后的同一实际地点并说明依据；必要时记录允许误差及原因，不临时扩大误差掩盖错误。

标准接口每一列分别判定，系统 fused 与 Google FLP 不能互代。Google FLP 不适用时填写“不适用 + 设备证据”，不能填 PASS。地图应记录当前位置蓝点或等价当前定位状态；微信应记录新打开的发送位置页面的当前位置；小程序应记录指定测试页实际读取结果及时间。若显示旧位置或拒绝模拟，先保存证据，不能猜测检测方式。

## 十轮开始—停止矩阵

表内编号应链接到对应证据。所有未执行项保持 BLOCKED，不预填 PASS。

| 轮次 / 场景 | GPS 新回调 | network 新回调 | 系统 fused 新回调 | Google FLP（适用时） | 地图当前位置 | 微信发送位置界面 | 授权小程序 | 停止解除模拟 | 新非模拟位置 | 证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R01 基本中文搜索、确认、开始、停止 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |
| R02 同一地点再次开始—停止 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |
| R03 重复点击开始与停止 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |
| R04 持续运行至少 5 分钟 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |
| R05 切换地图、微信、读取端与本应用 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |
| R06 锁屏至少 2 分钟后解锁 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |
| R07 停止后重新启动本应用再开始 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |
| R08 停止后确认另一个上海测试地点再开始 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |
| R09 再次切换前后台及持续运行 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |
| R10 再次锁屏、停止并确认正常定位 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 待采集 |

R08 的地点变更只发生在停止之后，用户重新确认；任何一轮运行期间不得自动切换目标。若某轮被阻塞，保留该轮结果，不删除失败轮以凑足十轮通过。

R01 同时核对中文搜索、重名结果选择，以及小屏和键盘弹出时搜索、地图确认和停止按钮能否点击；未操作不记通过。

有百度地图时，另记「百度地图查地址」是否带入关键词、完整地址能否回应用得到正确地点，以及地图确认是否对应同一目标。测试百度地图当前位置时使用「我的位置」，搜索结果或地图标记只用于选点。

## 异常与恢复专项

以下为补充验证，不能替代十轮实际用户流程：

| 场景 | 必须观察的结果 | 当前结果 |
| --- | --- | --- |
| 未选择本应用为模拟位置应用 | 明确授权指引，绝不显示运行正常 | BLOCKED |
| 拒绝精确定位权限 | 具体错误，绝不显示运行正常 | BLOCKED |
| 部分 provider 注册或注入失败 | 撤销本轮已完成操作，显示错误与清理状态 | BLOCKED |
| 运行期间撤销模拟授权 | 撤销运行正常状态，停止注入，说明待清理项 | BLOCKED |
| 新回调缺失、时间戳过旧或坐标不符 | 不把缓存当验证，超时或不一致有具体原因 | BLOCKED |
| 杀进程或系统回收后再打开 | 不虚报运行，不恢复默认坐标，根据实际请求结果恢复 | BLOCKED |
| 停止清理失败后重试 | 保留未确认记录，可重试，不能仅清除标记 | BLOCKED |
| 已移除模拟但暂时无真实定位 | 显示“模拟已解除，等待正常定位”，不称恢复完成 | BLOCKED |

## 历史证据，不能用作本轮通过

| 项目 | 历史记录 | 判定和局限 |
| --- | --- | --- |
| 旧版授权列表 | 曾因缺少 ACCESS_MOCK_LOCATION 声明而无法在选择列表看到本应用；rc.5 覆盖安装后可选中 | 历史问题已改代码，需本轮再验证授权 |
| rc.6 本应用 GPS / network 回调 | 曾记录目标坐标与 mock 标记 | 仅为本应用回调，不等于独立读取或第三方 PASS |
| 地图与微信定位效果 | 用户报告仍显示原位置 | **历史 FAIL**；修复后未重新验证，不能提升为 PASS |
| 微信发送位置界面与指定小程序的分别记录 | 既有反馈未完整分项，缺具体页面、版本和时间对应关系 | 分项为 BLOCKED；不能臆造其中某一项已通过或具体失败原因 |
| 系统 fused 最近值 | 曾与目标不同，可能为缓存 | 无新回调证据，BLOCKED，不能定为根因 |
| 停止与再次开始 | 曾出现“无法确认测试位置是否释放”并卡住的用户反馈 | **历史 FAIL**；停止后正常定位未完整确认 |
| 独立读取和十轮回归 | 未完成本轮真机记录 | BLOCKED |

## 发布门槛

候选包沿用原签名，提供新 APK 完整 SHA-256、提交和分项测试状态，保留旧 Release。用户要求先取得安装包自测时，预发布页应明确列出未执行的回归。未取得真实设备通过证据时，总体验收保持 BLOCKED，已观测的失败单独保留。若目标应用在既定边界内不能接受模拟坐标，写明未满足该项需求，不添加检测绕过。
