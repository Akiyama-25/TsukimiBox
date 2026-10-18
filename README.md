# TsukimiBox 
- 基于[Nekobox 1.4.2](https://github.com/MatsuriDayo/NekoBoxForAndroid)修改
- 加入了代理流量监控，不必在Web Dashboard查看，且可持续储存。
- Based on a modified version of [Nekobox](https://github.com/MatsuriDayo/NekoBoxForAndroid).
- Added proxy traffic monitoring, eliminating the need to check the Web Dashboard, with support for persistent data storage.

sing-box / universal proxy toolchain for Android.

一款使用 sing-box 的 Android 通用代理软件.

## 支持的代理协议 / Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess
* Trojan
* VLESS
* AnyTLS
* ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard
* Trojan-Go (trojan-go-plugin)
* NaïveProxy (naive-plugin)
* Mieru (mieru-plugin)

请到[NekoBox插件页面](https://matsuridayo.github.io/nb4a-plugin/)下载插件以获得完整的代理支持.

Please visit [NekoBox plugin page](https://matsuridayo.github.io/nb4a-plugin/) to download plugins for full proxy
supports.

## 支持的订阅格式 / Supported Subscription Format

* 一些广泛使用的格式 (如 Shadowsocks, ClashMeta 和 v2rayN)
* sing-box 出站

仅支持解析出站，即节点。分流规则等信息会被忽略。

* Some widely used formats (like Shadowsocks, ClashMeta and v2rayN)
* sing-box outbound

Only resolving outbound, i.e. nodes, is supported. Information such as diversion rules are ignored.

## 已知问题 / Known Problem

- 在**中国大陆版本的OriginOS 6**的设备上，可能会出现网络连接失败的问题，目前暂未定位到具体原因。该问题在原项目[Nekobox 1.4.2](https://github.com/MatsuriDayo/NekoBoxForAndroid)上不会出现，若遇到问题，请将侧边栏中的“日志”保存并提交到Issue中，并考虑换用原项目。

- On devices running the **Mainland China version of OriginOS 6**, network connection failures may occur, and the exact cause has not yet been identified. This issue does not occur in the original project [Nekobox 1.4.2](https://github.com/MatsuriDayo/NekoBoxForAndroid). If you encounter this issue, please save the "Logs" from the sidebar, submit them in an Issue, and consider switching to the original project.

## Credits

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
