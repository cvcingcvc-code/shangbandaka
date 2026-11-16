# rc.9 测评预发布与重新下载记录

日期：2026-11-04。按用户最新要求，先提供供真机测评使用的 Pre-release 下载；模拟器及真机验收仍未完成，不晋级稳定版。

- [发布页](https://github.com/cvcingcvc-code/shangbandaka/releases/tag/v1.1.2-rc.9)
- [主 APK 下载](https://github.com/cvcingcvc-code/shangbandaka/releases/download/v1.1.2-rc.9/MockLocationLab-v1.1.2-rc.9-candidate.apk)
- [SHA-256 文件](https://github.com/cvcingcvc-code/shangbandaka/releases/download/v1.1.2-rc.9/MockLocationLab-v1.1.2-rc.9-candidate.apk.sha256)
- [独立读取工具](https://github.com/cvcingcvc-code/shangbandaka/releases/download/v1.1.2-rc.9/IndependentLocationReader-v1.1-diagnostics.apk)

标签 `v1.1.2-rc.9` 指向应用源码提交 `ab83e2c491017e125b090c80b0c6cffd54b1e305`，预发布标记为 true、草稿标记为 false，未设置为稳定 Latest。旧 beta Release 保留。

主 APK：包名 `com.mocklocation.lab`，versionCode 12，文件大小 2,798,868 字节。

SHA-256：`83B97F85A91E82D20B916A106D9E797DC1084DDA7B684A821435D8C41893D9E4`。

上传前文件、GitHub asset digest 与重新下载后的文件三者 SHA-256 一致。重新下载的 APK 已经 apksigner 验证，证书 SHA-256 为 `461211f988cfaf6a3cf6d267e3f151474f719abd169155c856d5d1a0b38704b6`，与旧版相同。签名密钥未上传。

该记录只证明上传和下载文件一致、签名有效，不证明手机安装或定位成功。当前 ADB 列表仍为空。发布说明明确保留 `BLOCKED: PHYSICAL_DEVICE_NOT_TESTED`，并列出未执行的模拟器、真机十轮、地图、微信、小程序和停止恢复验收。

下一步按 [真机验收表](PHYSICAL_DEVICE_ACCEPTANCE.md) 检查已授权连接的 REDMI K80 Ultra。保留旧应用数据，优先覆盖安装；若系统拒绝升级，不静默卸载或清除数据。
