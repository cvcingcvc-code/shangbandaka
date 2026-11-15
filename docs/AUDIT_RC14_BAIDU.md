# rc.14 百度主流程与定位边界审计

2026-11-15；版本 1.1.2-rc.14 / versionCode 17。整体为 **BLOCKED: PHYSICAL_DEVICE_NOT_TESTED**；百度线上搜索与选点为 **BLOCKED: BAIDU_AK_NOT_CONFIGURED**。本记录不能证明手机地图、微信或指定小程序接受目标位置。

## 调查结论与修复范围

旧版百度入口只打开客户端，未把用户选中的坐标带回应用，仍需抄地址并再次搜索。历史 Nominatim 门牌查询为空，系统 Geocoder 曾超时，严格匹配无法补全上游数据。rc.14 改为本应用中的百度 POI 检索、候选列表和同源地图确认，不选择第一条结果冒充用户确认。

原 CoordConverter 求逆公式存在错误，但旧主流程未调用它，不能据此认定用户之前的定位失败由此造成。本轮百度输出统一 GCJ-02，提前转换为 WGS-84 并绑定确认目标。迭代解验证的是本地模型一致性，实际地图落点精度仍待验。

此前 rc.11 的未授权前台服务崩溃已在 rc.12 修复并有模拟器证据。rc.14 保留原服务和引擎：请求串行、重复请求去重、部分失败清理、持久不确定状态、连续新鲜回调验证、固定坐标及递增时间戳。停止先解除测试 provider，再等待独立读取的新非模拟位置。未取得手机各接口的新回调前，没有盲目增加系统 fused 或 Google FLP 模拟。

不能把旧缓存不同直接解释成融合定位问题，也不能用百度 SDK 搜索替代第三方定位兼容性验证。Google FLP 官方接口和系统 fused 不同；本轮仅增加可选的独立只读页，以检查 Play 服务可用性、订阅/取消结果及本轮新回调。

## 改动与代码审计

- MainActivity：主流程改为应用内百度选点，手动选点直接进入地图；查询改动继续清除旧目标。
- BaiduMapPickerActivity / BaiduSdkAccess：真实鉴权、首次同意后初始化、请求与查询代次校验、超时和组件异常关闭确认；新选点清除旧渲染状态，核对目标中心、标记、有效数据和新渲染完成。没有 AK 时明确禁用搜索和确认。
- CoordConverter / CoordConverterTest：修正迭代求逆，拒绝非法/未收敛输入，增加 5 项模型回归。
- 构建与清单：固定百度地图/检索 SDK 9.0.0，从本机 signing/baidu.properties 取 AK；保留原签名，密钥文件忽略。
- 独立 Reader v6：保留标准三入口，新加可选 Google FLP 只读订阅与异步清理，未加入任何模拟或检测绕过接口。

第二次只读审计发现的新目标沿用旧渲染状态、搜索异常未兜底、回调线程/销毁检查不足及转换验证过晚，已修补。授权、地图和搜索仍需配置真实 AK 后验证实际 SDK 行为，代码检查不代替这些测试。

主应用合并清单无新增广告/推送服务或额外导出业务组件。保留的旧 OSM/Geocoder 代码不再是首页百度主流程；未新增远控、账号、AI、签到或后台上报代码。百度 SDK 自身处理网络与设备信息，服务说明已披露，尚未动态抓包，不宣称第三方二进制零数据处理。Reader v6 合并权限只有定位及 AndroidX 签名级内部接收权限，无 INTERNET；后端及 Play 服务仍可能联网。

## 本轮可复查结果

本机 JDK 21、Gradle 8.14.3、Android compile/target 35。内存受限，单 worker、512 MB Gradle 堆依次运行。百度 AAR 在下载后与官方 SHA-1 核对，本地临时 Maven 镜像仅用于避免下载超时，不修改依赖内容，也不提交到仓库。

| 项目 | 结果与证据 |
| --- | --- |
| 主应用 JVM | PASS：47 项，0 错误/失败；包含新增 5 项坐标模型测试 |
| 主应用 lintDebug | PASS：0 错误、72 警告；旧 API、版本、样式等提示仍保留 |
| debug APK / Android 测试 APK | PASS：审计补丁后构建完成 |
| Reader v6 构建 / lintDebug | PASS：修复 AndroidX 配置与显式权限异常处理后完成 |
| API 35 模拟器服务与配置拦截 | PASS：10 项 / 67.287 秒，含十轮开始—停止、撤销授权与清理重试、重复请求、未注册注入异常、内部搜索路由和缺 AK 拦截 |
| 原签名内部候选 | PASS：Release 构建、签名、从 rc.1 覆盖升级及启动通过。包内没有 AK，不是可用新版 |
| 独立标准接口读取 | PASS（仅模拟器）：GPS、network、系统 fused 的本轮新鲜回调均为所填目标，isMock=true |
| Google FLP 读取 | PASS（仅模拟器）：Google Play 服务 24.23.35 可用，FLP 新回调与目标一致；停止后取得新的非模拟基准位置 |
| 锁屏、停止与恢复 | 约 15 秒熄屏后仍更新；点击停止后 provider/运行唤醒锁释放。独立系统 fused 新非模拟位置通过；正常 GPS/network 独立回调证据未完整取得，保留 BLOCKED |
| 百度真实中文搜索、地图确认与落点 | BLOCKED：无 AK |
| 实体手机、真实地图当前位置 | BLOCKED：本轮无连接设备及通过证据 |
| 微信发送位置、指定授权小程序 | BLOCKED：没有本轮实际读取/显示证据；历史错误未因本次构建而消失 |

本地原始记录目录：build/baidu-sdk-inspect、build/acceptance-rc14。测试报告在 app/build/reports、app/build/test-results，产物和原始日志不上传公开仓库。

首次仪器运行因夹具在 tearDown 撤销运行时权限而终止，系统退出原因为 PERMISSION CHANGE；该次不计通过。后续修正为外部预先授权，并以 CLEAR_TASK 隔离 singleTask 首页后，完整 10 项通过。工作流及构建说明同时更新，测试仍保留实体设备硬保护。

## 内部包与签名

源代码提交为 `05ace9503b6ce580911455311b496f2bc36a7420`，APK 内 version-control-info 与之相符。百度选点提交为 `c86479c8064f829750160e83abdc0aa8f62c21af`。文档后续提交不改变该包源码。

内部 APK：`build/acceptance-rc14/MockLocationLab-v1.1.2-rc.14-internal-no-baidu-key.apk`，108282861 字节。

完整 SHA-256：`9F90DB00B7DB97586C5B68BCA7E8D8EC6EF40D967207877E7DA6D5F333039624`。

Reader v6 debug APK SHA-256：`8417B7B56470E63642BF7641ABFBCF556469E92D1FB588C3BD3A1FB277515469`。

原证书 SHA-256：`461211f988cfaf6a3cf6d267e3f151474f719abd169155c856d5d1a0b38704b6`，与旧 Release 相同。v2 签名校验及 zipalign 16 KB ZIP 对齐检查通过，4 个 arm64 原生库 PT_LOAD 对齐均至少 16 KB；这些静态检查不替代 Android 16 真机运行。

构建保留百度 common 依赖的 D8 栈映射警告与原生调试符号未剥离提示。构建最终成功，但 SDK 初始化、真实授权和实际地图渲染仍须有 AK 后实测，不能按编译成功消除这些待验项。

## 签名版界面与独立回调证据

仅在本机临时、只读 API 35 AVD 中执行。开发者授权和权限的命令均有模拟器检查，没有修改实体手机。安装前是 rc.1 / versionCode 4，直接覆盖后为 rc.14 / versionCode 17，没有卸载旧签名包。模拟器冷启动曾出现 System UI / Messages ANR，关闭相应系统弹窗后才继续；没有将这些受干扰的初次界面截图记为通过。

主界面在 720×1280、320 dpi 下可以找到开始和停止。实际点开搜索后，未配置 AK 的页面明确禁用搜索，原文提示缺少密钥；不是百度搜索 PASS。随后通过界面输入备用 WGS-84 目标 `31.230416, 121.473701` 并点击开始，没有用 Intent 预置模拟坐标。

Reader v6 的 `reader-mock2.png` 显示 GPS、network、系统 fused 同一目标、isMock=true，样本均晚于各自本轮起点且时间递增，接收年龄分别约 2.373、325.586、197.743 ms。`google-flp.png` 显示 Google FLP 同目标、新鲜且 isMock=true，样本年龄约 21.770 ms。在该模拟器没有观察到 FLP 读数缺口，不据此推定红米也有同样行为。

锁屏后 power 记录为 Asleep，固定模拟运行唤醒锁存在；约 15 秒后 GPS/network 的位置时间仍更新。返回主界面状态仍为运行，再点停止。系统日志于设备时间 11:42:10 分别记录 GPS/network mock override 移除；服务及运行唤醒锁随后不存在，界面显示「模拟已解除，等待正常定位」。本次固定运行约 2 分 20 秒，不满足真机五分钟场景，未扩大通过范围。

停止后用模拟器正常 GNSS 基准 `37.421998, -122.084000` 更新独立系统定位，未调用应用测试 provider。`google-flp-restored.png` 和 `standard-restored.png` 分别记录 Google FLP、系统 fused 晚于停止及新订阅起点的非模拟回调，显示约 `37.421997, -122.084000`、isMock=false，年龄约 6.999 / 3.319 ms。正常 GPS 在 dumpsys 中有新非模拟位置，但 Reader 截图未取得对应 GPS 新回调；network 仍等待/关闭。这两项独立恢复证据不完整，不能用 fused 的结果替代。

本次没有百度在线地图、第三方地图当前位置、微信发送位置界面或指定小程序显示的验收记录。真实手机与这些目标应用仍是 BLOCKED，既往用户报告的失败未被本轮电脑通过撤销。

百度配置见 [BAIDU_SETUP](BAIDU_SETUP.md)，真机逐项验收见 [PHYSICAL_DEVICE_ACCEPTANCE](PHYSICAL_DEVICE_ACCEPTANCE.md)。没有百度线上与真机证据，不发布为已满足唯一需求的稳定版，也不把模拟器通过改写成所有手机应用通过。
