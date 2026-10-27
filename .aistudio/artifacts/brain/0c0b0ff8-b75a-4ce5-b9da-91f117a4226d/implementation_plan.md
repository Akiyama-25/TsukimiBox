# TsukimiBox Root 流量（UID=0）全链路接管与分流方案

本方案旨在为 TsukimiBox 提供完整的 Root 进程（UID=0）网络流量接管能力。通过结合 **Android VpnService 黑名单排除法反转** 与 **可选的 Root 策略路由（`ip rule`）注入**，在不破坏现有 VPN 沙盒安全性的前提下，实现在分应用代理、路由规则（含 TCP/UDP/DNS）和流量监控中对 `root (UID=0)` 的独立控制与可视化。

---

## 用户评审与关键决策确认

> [!IMPORTANT]
> 以下为您在需求澄清阶段确认的核心技术与交互决策：

- **已确认决策 1（底层接管机制）**：采用 **VpnService 黑名单反转 + 可选 Root 策略路由（`ip rule`）注入** 双重机制。
  - 当需要在 VPN 中包含 `UID=0` 时，利用 Android `VpnService.Builder.addDisallowedApplication()` 默认覆盖 `0..99999` 全 UID 段的特性，将白名单模式自动反转为黑名单排除模式，从而将没有应用包名的 `UID=0` 保留在 VPN 路由范围内；
  - 同时支持在设置中启用 Root 级 `ip rule` 策略路由注入（`uidrange 0-0 lookup <tun_table>`），解决部分 ROM/内核中系统策略路由优先级高于 VPN 导致 Root 流量逃逸的问题。
- **已确认决策 2（受控功能模块）**：
  - **分应用代理（AppManager）**：置顶或内置显示虚拟项 `Root (UID=0)`（标识符 `root`），支持在代理模式与绕过模式下自由勾选。
  - **路由规则（Route）**：在应用选择器与 `ConfigBuilder` 中支持 `UID=0` 匹配，解除原有 `uid >= 1000` 的硬编码限制。
  - **流量监控（TrafficMonitor）**：支持捕获、统计并在按应用排行中独立展示 `Root (UID=0)` 的代理上传与下载流量。
- **已确认决策 3（流量处理范围）**：**全部接管（包含 TCP、UDP 及 Root 进程发出的 DNS 请求）**，在路由规则与 DNS 规则生成时同步注入 `user_id = [0]`。

---

## 1. 概述与核心设计理念

- **功能目标**：让运行在 `UID=0` 下的 Root 守护进程、命令行工具（如 `curl`、`git`、`magisk`、`su` 脚本等）的网络流量能够被 TsukimiBox 的 `sing-box` 核心透明接管、按规则分流并计入流量统计。
- **适用场景**：Root 设备高级用户、开发者及需要在终端环境或系统级守护进程中使用代理、FakeDNS 或自定义分流规则的场景。
- **核心价值**：解决标准 Android `VpnService.Builder.addAllowedApplication()` 因 `UID=0` 无对应 APK 包名而无法纳入白名单，以及 TsukimiBox 内部多处 `uid >= 1000` / `uid > 1000` 过滤导致 Root 流量无法分流与监控的痛点。

---

## 2. 用户体验与视觉交互设计

### 关键用户流程

1. **分应用代理（AppManager）配置 Root 流量**：
   - 用户进入「设置 -> 分应用代理」，在应用列表（勾选显示系统应用或搜索 `root` / `0`）中看到带有专属安全/终端图标的 **`Root (UID=0)`** 条目（包名标识为 `root`，显示 `root (0)`）。
   - 在 **代理模式（仅代理选中应用）** 下勾选 `Root`：VPN 启动时自动切换为黑名单排除模式（排除所有未勾选的已安装应用），从而保留 `UID=0` 流量进入 TUN 网卡。
   - 在 **绕过模式（绕过选中应用）** 下：
     - 若**未勾选** `Root`：`UID=0` 默认随全局流量进入 VPN；
     - 若**已勾选** `Root`（希望绕过 Root 流量）：VPN 自动反转为白名单模式（仅允许未勾选的已安装应用），或通过策略路由放行 `UID=0`。
2. **路由规则（Route）按 Root 进程分流**：
   - 用户在「路由 -> 新建/编辑规则 -> 应用」中勾选 `Root (UID=0)`，并设置出站为「代理」、「直连/绕过」、「拦截」或特定节点。
   - 核心配置生成时自动为该路由规则和对应的 DNS 规则写入 `"user_id": [0]`，确保 Root 进程的 TCP、UDP 和 DNS 请求均严格遵循该规则。
3. **可选 Root 策略路由增强（设置页开关）**：
   - 在「设置 -> 路由设置」中提供 **「强制接管 Root 流量 (ip rule)」** 开关。开启后，当 VPN 建立或关闭时，后台通过 `su -c` 自动挂载/清理针对 `uidrange 0-0` 的高优先级策略路由与 DNS 转发保障。
4. **流量监控（TrafficMonitor）可视化**：
   - 用户在「流量监控 -> 按应用」标签页中，可直接查看 `Root (UID=0)` 的实时与历史代理流量占比、上传/下载字节数，支持按 UID（`0`）、名称或流量大小排序。

### 视觉风格与组件规范

- **设计一致性**：严格遵循项目现有的 Material Design 组件体系与深浅色主题自适应规范。
- **专属标识**：`Root (UID=0)` 虚拟应用条目使用清晰的管理员/安全徽章图标（如 `@drawable/ic_baseline_security_24`），主标题为本地化名称 `Root (系统超级用户)` / `Root (Superuser)`，副标题显示 `root (0)`，与其他系统组件视觉层级协调统一。

---

## 3. 核心架构决策与技术权衡

- **决策 1：虚拟包名 `root` 统一映射 `UID=0`**
  - *方案*：在 `PackageCache` 中注册虚拟包标识 `"root"` -> `0`，并在 `uidMap[0]` 中关联 `"root"`。
  - *原因*：TsukimiBox 的分应用代理（`DataStore.individual`）、路由规则（`RuleEntity.packages`）及流量监控数据库（`AppTrafficRecord`）均以 `packageName` 字符串作为持久化主键。通过引入虚拟包名 `"root"`，无需改动 Room 数据库 Schema 即可无缝打通所有模块。
- **决策 2：VpnService 黑白名单智能反转 + 可选 `ip rule` 双保险**
  - *方案*：
    1. 在 `VpnService.startVpn()` 中，计算 `UID=0` 是否需要进入 VPN：
       - 当未开启分应用代理（全局 VPN）时，`VpnService` 不调用 `addAllowedApplication`，Android `netd` 默认规则即为 `uidrange 0-99999`，天然包含 `UID=0`。
       - 当开启分应用代理且需要代理 `UID=0` 时，强制使用 `builder.addDisallowedApplication(unselectedApps)`（黑名单排除法），使 `UID=0` 留在 VPN 路由内。
       - 当开启分应用代理且需要绕过 `UID=0` 时，强制使用 `builder.addAllowedApplication(allowedApps)`（白名单法），由于没有包名对应 `UID=0`，`UID=0` 将被天然排除在 VPN 之外。
    2. 当用户开启「Root 策略路由强制接管」且 `UID=0` 处于被代理状态时，VPN 建立后自动探测当前 `tun` 接口路由表，并通过 `su` 注入高优先级 `ip rule add from all uidrange 0-0 lookup <tun_table>`（以及 IPv6 对应规则），在 VPN 断开时自动清理，防止自定义内核或系统 `local_network` 规则截胡 Root 流量。
- **决策 3：精准区分 `UID=0`（Root）与未知连接所有者**
  - *方案*：在 `NativeInterface.findConnectionOwner()` 中，当 `ConnectivityManager.getConnectionOwnerUid()` 抛出异常或未找到连接（`INVALID_UID = -1`）时返回 `-1` 而非 `0`；仅当真实解析出 `uid == 0`（或通过 `/proc/net/{tcp,tcp6,udp,udp6}` 确认 `uid == 0`）时，才将其识别为 Root 流量（`packageName = "root"`，`uid = 0`）。
  - *原因*：避免将查询失败的未知连接误判为 Root 流量，确保 `user_id: [0]` 路由规则与流量监控统计的绝对准确性。

---

## 4. 技术架构与数据流图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        UI & 配置层 (User Interface)                      │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌───────────────┐  │
│  │  AppManagerActivity  │  │   AppListActivity    │  │ Global Prefs  │  │
│  │ (分应用代理勾选 root)  │  │ (路由规则勾选 root)   │  │ (Root策略开关) │  │
│  └──────────┬───────────┘  └──────────┬───────────┘  └───────┬───────┘  │
└─────────────┼─────────────────────────┼──────────────────────┼──────────┘
              │                         │                      │
              ▼                         ▼                      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      包缓存与配置构建层 (Core Logic)                      │
│  ┌──────────────────────────────┐   ┌────────────────────────────────┐  │
│  │        PackageCache          │   │         ConfigBuilder          │  │
│  │ • 注入虚拟包 "root" <-> UID 0 │   │ • 允许 uid == 0 写入 user_id   │  │
│  │ • 提供 Root 图标与本地化名称    │   │ • 同步生成路由规则与 DNS 规则   │  │
│  └──────────────┬───────────────┘   └───────────────┬────────────────┘  │
└─────────────────┼───────────────────────────────────┼───────────────────┘
                  │                                   │
                  ▼                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     VPN 隧道与内核接管层 (VPN & Routing)                  │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                         VpnService.startVpn()                     │  │
│  │ 1. 若代理含 root: 自动转为 addDisallowedApplication(未选应用)       │  │
│  │ 2. 若绕过含 root: 自动转为 addAllowedApplication(已选代理应用)      │  │
│  │ 3. 若开启 Root 策略路由: su 注入 ip -4/-6 rule uidrange 0-0 指向 tun │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                   进程识别与流量监控层 (Process & Traffic)                │
│  ┌──────────────────────────────┐   ┌────────────────────────────────┐  │
│  │       NativeInterface        │   │     TrafficMonitorManager      │  │
│  │ • getConnectionOwnerUid +    │   │ • 支持记录 uid == 0 活跃连接    │  │
│  │   /proc/net 回退解析 UID=0   │   │ • TrafficMonitorFragment 展示  │  │
│  │ • uid==0 映射为 "root"        │   │   Root (UID=0) 图标与流量明细   │  │
│  └──────────────────────────────┘   └────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 核心状态流转与交互映射

1. **虚拟 Root 应用注册 (`PackageCache` & `AppManagerActivity` / `AppListActivity`)**：
   - 在 `PackageCache.reload()` 中构造合成的 `PackageInfo` 与 `ApplicationInfo`（`packageName = "root"`，`uid = 0`，带 `FLAG_SYSTEM` 标记），写入 `installedPackages`、`installedApps`、`packageMap["root"] = 0` 及 `uidMap[0] = hashSetOf("root")`。
   - `PackageCache.loadLabel("root")` 返回本地化字符串（如 `Root (UID=0)`），并在 `AppManagerActivity`、`AppListActivity`、`AppListPreference` 与 `TrafficMonitorFragment` 中为 `"root"` 提供专属图标渲染。
2. **VPN 路由构建与 Root `ip rule` 管理 (`VpnService`)**：
   - 根据 `DataStore.proxyApps`、`DataStore.bypass` 以及 `individual` 中是否包含 `"root"`，动态决定调用 `addDisallowedApplication` 或 `addAllowedApplication`，确保 `UID=0` 在需要接管时不被 `VpnService` 过滤。
   - 新增辅助管理器 `RootRouteController`：当用户在设置中开启 `rootTrafficTakeover`（Root 策略路由增强）且 VPN 需要接管 Root 流量时，在 `startVpn` 建立连接后自动查找 `tun` 网卡对应的路由表 ID，并添加 `ip -4 rule add from all uidrange 0-0 lookup <table_id> pref 9990` 及 IPv6 规则；在 `killProcesses` / `onDestroy` 时幂等清理。
3. **sing-box 路由与 DNS 规则生成 (`ConfigBuilder`)**：
   - 将 `PackageCache[it]?.takeIf { uid -> uid >= 1000 }` 修改为允许 `uid == 0 || uid >= 1000`。
   - 当规则包含 `"root"` 时，`ruleObj.user_id` 与对应的 `DNSRule_DefaultOptions.user_id` 均包含 `0`，配合内置的 `hijack-dns` 与 `dns-remote`/`dns-fake`，实现对 Root 进程 TCP、UDP 与 DNS 请求的完整接管与分流。
4. **UID=0 连接归属识别与流量统计 (`NativeInterface` & `TrafficMonitorManager`)**：
   - 在 `NativeInterface.findConnectionOwner()` 中，若 Android 10+ 的 `ConnectivityManager.getConnectionOwnerUid()` 对 `UID=0` 连接返回异常或无效值，自动回退通过 `/proc/net/tcp[6]` 与 `/proc/net/udp[6]` 查找套接字 inode 与 `uid`；未知连接返回 `-1`。
   - `NativeInterface.packageNameByUid(uid)` 对 `uid == 0` 返回 `"root"`。
   - `TrafficMonitorManager` 允许 `uid == 0` 参与活跃连接记录与流量权重分配，并在 `TrafficMonitorFragment` 中展示 `Root (UID=0)` 的流量详情。
