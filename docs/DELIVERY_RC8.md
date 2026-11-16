# rc.8 断点修复交付记录

日期：2026-11-04。总体结论：**BLOCKED: PHYSICAL_DEVICE_NOT_TESTED**。

需求以 [固定地点模拟与可靠停止](FIXED_LOCATION_REQUIREMENTS.md) 为准，验收必须覆盖 [十轮真机记录表](PHYSICAL_DEVICE_ACCEPTANCE.md)。

## 故障与本轮修改

原实现的待清理标记可能拦住再次启动；只有启动时验证两路回调，无法持续判断运行健康；停止主要依赖服务销毁。它们是已确认的代码缺口，尚不能证明是历史地图和微信显示原位置的完整根因。融合定位记录可能是缓存，本轮没有新增 fused 或 Google FLP 模拟。

本轮修改 `MockLocationService.kt`、`MockLocationStatus.kt`、`MainActivity.kt`、`MockLocationEngine.kt`：

- 请求编号取消未送达的旧启动，重复开始不创建任务，运行期间目标固定。
- 每次注入同一坐标，更新递增的墙钟及单调时间戳。
- 显式停止立即停止注入、移除监听及测试 provider；销毁、任务移除和异常路径补充清理。部分注册失败撤销已尝试操作；移除失败或授权不确定时保留标记和原因。
- 状态区分准备、验证中、运行、停止中、清理失败、失败及解除后等待正常定位；退出超时不覆盖原清理错误。没有独立的新非模拟位置证据时，不设置“恢复完成”。
- 运行期间持有有超时的部分唤醒锁，失败和停止释放；实际锁屏、厂商后台策略和耗电仍待验证。

新增纯逻辑 `MockSessionVerifier.kt`：每路都必须取得本次启动之后、时间递增、新鲜、标记为模拟且匹配目标的回调；旧缓存及重复回调不能续期。新鲜错误结果立即失败，必要入口中断十秒即失败，迟到回调不能重启已过期会话。

独立诊断工具位于 `diagnostics/location-reader`，分别订阅 GPS、network、系统 fused，显示坐标、入口、实际 provider、墙钟、单调时间、模拟标记和数据年龄。它没有融合模拟或 Google FLP，也不会把坐标写入文件或上传。真实恢复需要重新订阅后取得新的非模拟样本，不能把页面中的旧样本算作恢复。

## 实际验证

| 项目 | 本轮结果 | 证据及限制 |
| --- | --- | --- |
| 11 项 JVM 验证器测试 | PASS | 0 failure / 0 error；不代表设备通过 |
| lintDebug | PASS | 最终源码检查通过，仍有兼容 API 弃用警告 |
| 主 APK、设备测试 APK 编译 | PASS | 设备测试未执行 |
| 独立读取工具构建 | PASS | 未安装、未运行 |
| 服务模拟器回归 | BLOCKED | 本机可用内存约 0.7 GB；新增云端流程后，截至本轮查询没有执行记录，手动启动返回默认分支不存在该工作流的 404；未修改 main 来强行启用 |
| REDMI K80 Ultra 接口可行性检查 | BLOCKED | ADB 当前无设备；GPS/network/系统 fused 的独立新回调、适用的 Google FLP 均未完成本轮检查 |
| 地图当前位置、微信发送位置界面、指定授权小程序 | BLOCKED | rc.8 尚未上机；历史地图/微信显示原位置的 FAIL 保留，不能被构建结果覆盖 |
| 停止后新的正常位置、十轮及锁屏 | BLOCKED | 未取得实际读取和显示证据 |

设备仪器测试现包含十轮服务开始—停止、撤权、重复请求及取消未送达启动；它们仅允许在模拟器运行，且不能替代十轮完整真机验收。

## 源码与候选包

修复分支：`fix/real-device-location-v1.1.2`。应用源码修复提交：`42f6abdeb9c9e40eb023369ecfe680c78d8a3e80`。后续 CI 配置提交 `08f05a0` 没有更改 APK 源码。草稿审阅：[PR #1](https://github.com/cvcingcvc-code/shangbandaka/pull/1)。

候选包：`MockLocationLab-v1.1.2-rc.8-candidate.apk`，versionCode 11。SHA-256：

`042071142CEA4E1C61109A6A32DC348B541310326BFDC86324C2096465BC279B`

Release 证书 SHA-256：`461211f988cfaf6a3cf6d267e3f151474f719abd169155c856d5d1a0b38704b6`，与旧版相同；密钥未入库。候选未安装到用户手机，也未发布 GitHub Release；旧版本保留。

独立读取 APK：`IndependentLocationReader-v1.1-diagnostics.apk`，包名 `lab.reader`，versionCode 2，targetSdk 35。SHA-256：

`B691446C8E8069F9BF0C2285495B722C6B859AB8A35103682C9478581960F0B0`

下一步需要已授权连接的真机，以及明确的地图应用和授权小程序测试对象。先保存同一轮各接口的新回调、坐标系及显示证据，再决定是否增加定位接口。不绕过模拟检测，不承诺所有应用接受坐标。
