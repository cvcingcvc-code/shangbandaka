# 固定地点模拟的故障分析与实施方向

研究日期为 2026-11-15。范围是中文选点、固定模拟、可靠停止，以及 REDMI K80 Ultra 上地图当前位置、微信发送位置和指定授权小程序的实测。以下分别列出已有事实、待验证推断和实施条件。

本文主体是百度原生接入前的调查快照。随后 rc.14 已写入百度 SDK 搜索、地图确认、坐标转换修复和可选 Google FLP 只读工具；当前实施与测试状态以 [STATUS](../STATUS.md) 及 [百度配置说明](BAIDU_SETUP.md) 为准。尚无 AK 与新真机证据，研究中的实现方向不作为验收通过结论。

## 当前版本与证据

GitHub 最新安装候选为 rc.11。修复分支本地 HEAD 为 a781df7，较远端多 3 个提交。rc.12 的已签名包和通过记录保存在本地；rc.13 有源码提交，但上轮构建日志停在任务中途，没有完整构建成功和设备回归结论。2026-11-15 再查 ADB 无设备。

已确认的历史缺陷包括模拟授权缺失、rc.11 未授权时前台服务启动崩溃、Android 15 搜索框受状态栏遮挡、旧结果与旧坐标残留、中文门牌与行政区匹配不足。rc.12 对部分缺陷有模拟器修复证据，rc.13 的门牌标点改动尚需完成回归。

2026-11-06 的 rc.12 签名包在 API 35 模拟器上完成了真实东京塔搜索、OSM 地图选点确认、开始、独立 GPS/network/系统 fused 新回调、短时熄屏和停止后非模拟 GPS/fused 恢复。上海中心大厦的真实查询有一条结果，世纪大道 100 号的测试查询返回空数组。正常 network 恢复未取得。百度客户端完成安装与外部跳转，首次隐私确认页之后的搜索和蓝点未验收。具体证据见 [联网复验](COMPUTER_ACCEPTANCE_RC12_ONLINE.md)。

这些结果限定在对应模拟器。REDMI / Android 16、实际地图、微信和小程序尚无新的一致性记录。

## 搜索链路为什么仍不顺

首页百度按钮调用 ACTION_VIEW，搜索仓库仅包含系统 Geocoder 和 Nominatim。百度客户端选中的地点尚未成为应用中的确认坐标，用户还要带回地址再查一次。现有实现没有解决自动取得所选地点的需求。百度 URI 文档描述页面调起，应用内 POI 回调应使用正式地图 SDK 或 Place API。[百度 URI](https://lbsyun.baidu.com/docs/webapi?title=mapadjustment%2Furi%2Fandriod) · [POI 检索](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Fguide%2Fsearch%2Fpoi)

Geocoder 本身无结果可用性和准确性保证，现有设备后端超时会让主流程等待。Nominatim 对具体中文门牌的覆盖也有缺口。严格匹配只能拒绝错误结果，无法补全上游没有的数据。[Android Geocoder](https://developer.android.com/reference/android/location/Geocoder)

若选百度为正式中文搜索源，应在本应用内取得 POI 名称、城市、地址、坐标及坐标类型，由同源地图完成确认。Place API 需要开发者 AK，当前个人权益表列地点检索 100 次/日、3 次/秒，具体仍以账号权益为准。[Place API](https://lbs.baidu.com/docs/webapi?title=placev3%2Fguide%2Fwebservice-placeapiV3%2FinterfaceDocumentV3) · [开发者权益](https://lbs.baidu.com/solutions/privilege)

接入前必须验证坐标接口。现有引擎与 OSM 选点按 WGS-84 工作，百度国内数据通常为 BD-09 或 GCJ-02。仓库中的 CoordConverter 当前没有调用方。百度官方转换服务不提供输出 GPS/WGS-84 的转换。应先验证有来源依据的目标坐标和地图落点，不能直接把不同坐标系的数字传进引擎。[坐标说明](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Fguide%2Ftool%2Fcoordinate) · [转换服务](https://lbsyun.baidu.com/docs/webapi?title=geoconv%2Fguide%2Fchangeposition-base)

## 模拟坐标为什么未必出现在目标应用

Android 测试 provider 替换同名定位 provider，当前代码覆盖 GPS 与 network。系统 fused 可以汇集其他入口，Google Play 服务 FLP 则是另一组接口。模拟器系统 fused 曾跟随目标，红米对应行为还没有新回调证据。[LocationManager](https://developer.android.com/reference/android/location/LocationManager)

Google 官方 FLP 有 setMockMode 和 setMockLocation，模拟模式影响使用该 FLP 的跨进程客户端。只有确认红米具有可用 Google Play 服务、FLP 独立读数确有缺口时才增加这条适配，进入、注入、退出均须等待异步成功，并保持递增时间戳。[Google FLP](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)

第三方应用还可能使用自己的 SDK 网络定位、缓存或模拟位置过滤。百度官方定位 SDK 文档说明多源识别模拟卫星位置的能力，这证明此类差异存在。它不能证明当前微信或百度手机客户端具体采用了哪一条路径。Android 官方测试位置会标为 isMock。[百度定位 SDK](https://lbs.baidu.com/docs/android?title=android-locsdk%2Fguide%2Faddition-func%2Fmock-location) · [Android 测试位置](https://developer.android.com/reference/android/location/LocationManager#setTestProviderLocation(java.lang.String,android.location.Location))

因此，增加更多 provider 或在本应用集成百度定位 SDK，都不能直接证明第三方应用改变了当前定位。应先取得同时间的独立回调与目标应用实际显示。

## 下一阶段实施门槛

先完成 rc.13 的本地构建、回归和签名包复验，保留已通过链路的证据。在下一次受控设备对照中，使用一个已有可靠 WGS-84 坐标的地点验证定位分发，让搜索覆盖与定位读取各有清楚结论。

| 新观测 | 对应动作 |
| --- | --- |
| GPS 或 network 也读不到本轮目标 | 查 AppOp、精确权限、注册/注入异常、前台服务存活，先修已用接口 |
| GPS/network 正确，系统 fused 有新的不同坐标 | 调查该机系统 fused 的实际 provider 与回调，受控验证系统接口适配 |
| 标准接口正确，可用 Google FLP 有新的不同坐标 | 按官方接口验证 FLP 模拟模式及退出清理 |
| 独立接口正确，地图或微信仍显示旧位置 | 核对当前定位入口、数据时间、坐标系与目标应用实际行为，保留截图和回调时序 |
| 第三方明确拒绝模拟位置 | 记录该项 FAIL；可控制源码的授权测试应用可提供独立测试模式，原第三方兼容需求仍未通过 |
| 停止后仍返回旧模拟缓存 | 保持等待正常定位，持续观察停止后的新非模拟回调 |

中文搜索的下一步应先验证三个准确名称和两个门牌地址能返回正确候选，再决定正式数据源。模拟引擎的下一步应由红米的独立接口差异决定。第三方兼容性的 PASS 必须包含地图蓝点、微信新打开发送位置页面和指定小程序各自的证据。

研究结论为具备继续验证和修复的技术路径，整体目标仍为 BLOCKED: PHYSICAL_DEVICE_NOT_TESTED。公开测试接口支持固定位置注入，任意应用是否接受该位置取决于其读取与处理方式。当前证据不足以承诺所有应用统一显示所选地点。
