# TsukimiBox 
- 基于[Nekobox 1.4.2](https://github.com/MatsuriDayo/NekoBoxForAndroid)修改
- 加入了代理流量监控，不必在Web Dashboard查看，且可持续储存。
- 支持接管、独立分流并统计 **Root (`UID=0`)** 进程的网络流量（含 TCP、UDP 与 DNS）。
- Based on a modified version of [Nekobox](https://github.com/MatsuriDayo/NekoBoxForAndroid).
- Added proxy traffic monitoring, eliminating the need to check the Web Dashboard, with support for persistent data storage.
- Added support for taking over, routing, and monitoring **Root (`UID=0`)** process network traffic (including TCP, UDP, and DNS).

sing-box / universal proxy toolchain for Android.

一款使用 sing-box 的 Android 通用代理软件.

## Root (`UID=0`) 流量接管 / Root (`UID=0`) Traffic Takeover

* **分应用代理（Per-App Proxy）**：在“分应用代理”列表顶部置顶提供 `Root (UID=0)` 选项。当在代理模式（白名单）下勾选 `Root (UID=0)` 时，应用会自动将 `VpnService` 规则反转为排除未勾选应用（`addDisallowedApplication`），从而突破 Android `PackageManager` 无法直接对无 APK 的 `UID=0` 调用 `addAllowedApplication` 的限制；在绕过模式（黑名单）下勾选时亦会自动反转以绕过 `UID=0` 流量。
* **强制策略路由（可选 Root 权限）**：在“设置 → 路由设置 → 强制接管 Root (UID=0) 流量”开启后，VPN 启动时会通过 `su` 自动向内核注入高优先级 IPv4/IPv6 策略路由规则（`ip rule pref 9990 uidrange 0-0 fwmark 0x0/0x20000`）指向当前活跃的 `tun` 路由表，并在 VPN 停止时自动清理。
* **路由分流与流量统计**：支持在自定义路由规则（Route）中选择 `Root (UID=0)` 对其 TCP、UDP 及 DNS 请求（`user_id: [0]`）进行独立分流，并在“流量监控”页面实时显示与持久化统计 `Root (UID=0)` 的代理流量。

* **Per-App Proxy**: Provides a pinned `Root (UID=0)` entry at the top of the Per-App Proxy list. Selecting `Root (UID=0)` in Proxy (whitelist) mode automatically inverts `VpnService` rules to `addDisallowedApplication` for unselected apps so `UID=0` traffic stays inside the TUN interface; selecting it in Bypass (blacklist) mode inverts to `addAllowedApplication` so `UID=0` bypasses the VPN.
* **Policy Routing Override (Optional Root)**: When "Force Root (UID=0) Traffic Takeover" is enabled under Route Settings, starting the VPN automatically injects high-priority IPv4/IPv6 policy routing rules (`ip rule pref 9990 uidrange 0-0 fwmark 0x0/0x20000`) via `su` into the active `tun` routing table and cleans them up when stopping.
* **Custom Routing & Traffic Monitoring**: Supports independent routing rules (`user_id: [0]`) for `Root (UID=0)` across TCP, UDP, and DNS queries, and tracks real-time and historical `Root (UID=0)` traffic in the Traffic Monitor.

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
