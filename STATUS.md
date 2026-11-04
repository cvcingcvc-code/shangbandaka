# 当前状态

更新时间：2026-11-04。分支：`fix/real-device-location-v1.1.2`。本轮起点提交：`05a05a3b932ed993c0748981f806b509e275c636`；当前可能存在并行开发修改，最终提交和构建结果由主任务补充。

总体：**BLOCKED: PHYSICAL_DEVICE_NOT_TESTED**。本轮 ADB 列表为空。地图和微信原位置显示、停止卡住属于历史失败；本轮修复后尚无重新验收证据。

需求已冻结并建立 [十轮真机验收表](docs/PHYSICAL_DEVICE_ACCEPTANCE.md)。当前推进生命周期、状态与持续验证，尚未决定增加融合定位模拟接口。未宣称地图、微信或指定授权小程序兼容。

本轮 rc.8：11 项 JVM 测试通过（零失败/错误），lintDebug、仪器测试 APK 编译及原签名 Release 构建通过。仪器测试尚未运行；本地可用内存约 0.7 GB，不启动模拟器，改由 GitHub Actions 尝试 API 35 回归。云端回归结论须以任务实际结果为准。

截至本轮查询，GitHub 没有返回云端运行记录；手动 dispatch 因默认分支缺工作流而返回 404。模拟器回归仍为 BLOCKED；修复已推送并建立 [草稿 PR #1](https://github.com/cvcingcvc-code/shangbandaka/pull/1)。详见 [rc.8 交付记录](docs/DELIVERY_RC8.md)。

候选 APK SHA-256：`042071142CEA4E1C61109A6A32DC348B541310326BFDC86324C2096465BC279B`。证书 SHA-256：`461211f988cfaf6a3cf6d267e3f151474f719abd169155c856d5d1a0b38704b6`，与旧版一致。候选尚未安装到真机。未完成必要回归前不发布新候选 Release，也不晋级稳定版。
