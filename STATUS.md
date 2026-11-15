# 当前状态

更新时间：2026-11-15。修复分支 `fix/real-device-location-v1.1.2`，当前开发代码 `v1.1.2-rc.14` / versionCode 17。GitHub 已发布版本仍是 rc.11；本轮尚未作为可用新版发布。旧 Release 保留。

整体：**BLOCKED: PHYSICAL_DEVICE_NOT_TESTED**。本轮开始时 ADB 无实体手机，尚无百度 Android AK。实际百度搜索和地图选点为 **BLOCKED: BAIDU_AK_NOT_CONFIGURED**；地图当前位置、微信发送位置界面和指定授权小程序都没有本轮通过证据。此前用户报告的显示错误仍是未解决的验收问题。

已写入应用内百度 SDK 9.0.0 中文搜索及地图确认，替代从百度客户端抄回地址。新查询使旧结果和坐标失效，用户逐条选择后确认地图；缺 AK、未同意服务说明、授权失败、地图未验证或坐标转换失败时禁止确认。GCJ-02 到 WGS-84 使用修正的本地近似模型，实际落点精度待验证。

生产服务本轮未增加新模拟入口，仍连续验证 GPS 与 network，固定所选坐标并更新有效时间。可选 Google FLP 独立只读页用于先记录实际接口差异；不是融合模拟适配。停止先停止注入并解除测试 provider，随后显示等待新的正常定位，不把清掉标记视为恢复证明。

审计补丁后本地 47 项 JVM 测试、主应用 lintDebug、debug APK 及设备测试 APK 构建通过；lint 0 错误、72 警告，包含旧 API、依赖及样式提示。Reader v6 debug 构建和 lint 通过，合并清单无 INTERNET、广告或推送权限。主应用源码和合并清单无新增广告/推送组件；百度 SDK 网络及设备信息处理有服务说明，未完成动态抓包。

API 35 的 10 项集成用例通过，含十轮开始—停止及失败清理；原签名 rc.14 内部包从 rc.1 覆盖升级成功。在界面手动输入 WGS-84 坐标后点击开始，独立 Reader 的 GPS、network、系统 fused 和适用 Google FLP 均取得一致的新鲜模拟回调。短时锁屏约 15 秒后仍持续注入。点击停止后 provider 和运行唤醒锁释放，独立系统 fused 与 Google FLP 取得新非模拟基准位置；正常 GPS/network 独立恢复回调尚未完整取得，不能记为通过。详见 [本轮审计](docs/AUDIT_RC14_BAIDU.md)。

内部包沿用原证书，源码提交为 05ace9503b6ce580911455311b496f2bc36a7420，完整 SHA-256 见审计。包内没有 AK，仅供内部配置拦截及备用坐标链路回归；本轮不创建可用新版 Release。百度线上检索、鉴权及地图渲染无法用缺 AK 包验收，不将配置拦截测试当作正常搜索通过。

申请步骤、包名和原 Release SHA-1：[百度配置说明](docs/BAIDU_SETUP.md)。研究与证据边界：[可行性调查](docs/FEASIBILITY_REVIEW_20261115.md)。真机操作与逐项结果：[十轮验收表](docs/PHYSICAL_DEVICE_ACCEPTANCE.md)。

历史证据：[rc.12 服务/UI/签名升级复验](docs/COMPUTER_ACCEPTANCE_RC11.md)、[rc.12 联网搜索及 OSM 地图回归](docs/COMPUTER_ACCEPTANCE_RC12_ONLINE.md)。这些通过限定于对应模拟器和旧主流程，不能替代本轮百度或真机验收。
