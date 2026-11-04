# 当前状态

更新时间：2026-11-04。分支：`fix/real-device-location-v1.1.2`。本轮审计起点：`f42b054993f14e1278ae53f112460bb1ff0f598e`。

总体：**BLOCKED: PHYSICAL_DEVICE_NOT_TESTED**。ADB 列表仍为空；历史地图、微信显示原位置及停止卡住的 FAIL 不被构建结果覆盖。

当前候选 rc.9 / versionCode 12。20 项 JVM 测试通过，lintDebug、设备测试 APK 编译及原签名 Release 构建通过；未运行设备测试或模拟器回归。云端没有 workflow/run/checks 记录，手动 dispatch 的默认分支 404 不是 push/PR 未运行的完整解释，根因未确认。

审计移除主动输出的搜索词/坐标日志、限制带选点桥的 WebView 导航，修正清理重试成功后仍显示失败的状态；静态检查未发现消息推送、广告、遥测或远程控制入口。搜索和地图仍会联网，未做动态抓包。

候选 APK SHA-256：`83B97F85A91E82D20B916A106D9E797DC1084DDA7B684A821435D8C41893D9E4`。证书 SHA-256：`461211f988cfaf6a3cf6d267e3f151474f719abd169155c856d5d1a0b38704b6`，与旧版一致。按用户最新要求，已上传 [rc.9 Pre-release](https://github.com/cvcingcvc-code/shangbandaka/releases/tag/v1.1.2-rc.9) 供真机测评下载；未安装到手机，未满足正常使用和稳定发布条件。模拟器/真机 BLOCKED 不因上传而改变。

详见 [rc.9 审计记录](docs/AUDIT_RC9.md)、[十轮真机验收表](docs/PHYSICAL_DEVICE_ACCEPTANCE.md) 和 [草稿 PR #1](https://github.com/cvcingcvc-code/shangbandaka/pull/1)。

上传和重新下载检查已完成：本地文件、GitHub asset digest、重新下载后的 SHA-256 一致；下载包签名有效。见 [发布与重新下载记录](docs/RELEASE_RC9.md)。这不改变设备及兼容性 BLOCKED 状态。
