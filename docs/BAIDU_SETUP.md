# 百度地图配置与当前验收

本项目使用百度 Android 地图和检索 SDK 9.0.0，中文搜索结果在应用内选择并由百度地图确认。首次使用须阅读并同意地图服务说明，再初始化 SDK。启动模拟前仍须在 Android 开发者选项选择 Mock Location Lab。

## 申请 Android AK

1. 登录 [百度控制台](https://lbsyun.baidu.com/apiconsole/key)，进入「应用管理 → 我的应用 → 创建应用」。
2. 应用名填写 Mock Location Lab，类型选择「Android SDK」，按控制台勾选本应用使用的地图和检索服务。
3. 填写下表的包名及发布版 SHA-1，提交后取得 Android AK。[百度官方申请说明](https://lbsyun.baidu.com/docs/guide?title=android-guide%2Fcreate-project%2Fak)

| 配置项 | 值 |
| --- | --- |
| 包名 | com.mocklocation.lab |
| Release SHA-1 | C7:61:83:AB:07:04:36:8B:2C:DF:85:3C:7E:E8:06:33:F4:53:ED:B4 |

将生成的 Android AK 写入本机文件 `F:\MockLocationLab\signing\baidu.properties`。

可以用记事本新建这个文件，保存类型选择「所有文件」，避免变成 baidu.properties.txt。AK 不用发到公开仓库或聊天中。

```properties
BAIDU_MAP_AK=在这里填写你申请的Android密钥
```

重新构建并安装原签名 Release。调试证书的 SHA-1 与 Release 不同，调试测试需要控制台中对应的证书授权。密钥文件已由 Git 忽略；签名密钥和密码仍仅保存在本地。Android AK 会嵌入安装包，由百度校验包名和证书。

AK 是使用百度服务的必要配置。缺少 AK 时，选点页明确提示配置缺失，搜索和确认禁用。配置成功后还须实测实际搜索、地图瓦片、授权错误与所选地点的落点。[百度接入说明](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Fguide%2Fcreate-project%2Fandroidstudio)

## 坐标与定位

百度地图使用 GCJ-02，应用采用本地近似模型转换为引擎使用的 WGS-84。单元测试验证转换模型的一致性，实际地图上的同一地点仍需核对。

固定模拟当前覆盖 GPS 与 network，独立读取工具可分别检查系统 fused 和 Google Play 服务 FLP。Google FLP 页只读取，依据实际回调决定是否需要后续官方模拟接口适配。目标地图、微信发送位置与授权小程序分别验收。

停止先解除测试位置，再等待新的正常位置。尚未取得新位置时显示等待状态。

2026-11-15 项目尚未取得用户的百度 AK，当前 ADB 无实体手机。百度线上选点与真实地图、小程序效果为 BLOCKED。此次集成以完成构建和可验证的配置拦截为阶段验收，完整结果见项目 STATUS。
