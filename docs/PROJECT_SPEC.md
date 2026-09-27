# Android MCP Bridge 项目说明书

## 目标

在用户本人 Android 16/17 设备上提供一个 Kotlin 原生 MCP 服务，使 RikkaHub Agent 能通过本机 HTTP 访问用户明确授权的 Android 能力。授权不等于功能：**每个声明/引导的权限必须映射到一个或多个具名 MCP 工具，否则移除该权限。**

## 连接与发布

- Transport：MCP Streamable HTTP，服务运行时提供 IPv4 `127.0.0.1:18765/mcp` 与 IPv6 `[::1]:18765/mcp`。
- 不使用 Bearer token（RikkaHub 无自定义 token 支持）。LAN 监听默认关闭；用户开启后仅绑定当前 Wi‑Fi 的私有 IPv4 / ULA IPv6，界面二次确认。开启后同网段其他设备也能访问，不假设来访者只有 RikkaHub。
- push `main`：只构建 APK artifact。
- Release：仅 `workflow_dispatch` 手动触发并填写 tag；Release APK 使用固化的正式签名 keystore，附 SHA256SUMS。私钥/口令只存私有 `ikun-vault` 与 GitHub Actions secrets，禁止进公开代码仓。
- **构建环境唯一为 GitHub Actions**：禁止调用新加坡 SpeedyPage 或其他服务器/本机执行 Android APK 构建；本机只允许静态源码检查。

## 权限模型

1. 普通运行时权限由用户通过系统对话框授予；后台定位、通知监听、使用情况访问、全文件、悬浮窗、修改系统设置、电池优化等通过系统设置/专用确认流程。
2. Camera、麦克风与 MediaProjection 按 Android 规则使用前台界面/系统授权、清晰状态通知；不做隐蔽后台采集。
3. Accessibility 必须由用户到系统设置手动启用；屏幕操作显示持续状态并可随时停止。
4. 当前 Contacts/Calendar 写接口须用户在 App 内显式开启全局写工具开关，且 MCP 调用传 `confirm=true`；开关在 LAN 开启时强制关闭。更高风险/批量操作的逐笔设备端确认队列仍为 Release 前 TODO。
5. OS 签名权限、设备所有者、ADB/Shizuku/root 不能由普通 App 自行静默取得。

## 特权后端

Shizuku / ADB / 已 root provider 是可选 TODO：用户需先在系统/对应管理 App 中启用并单独授权；只向 MCP 发布参数校验的具名工具。禁止漏洞提权和默认任意 shell。任何 LAN 可达的原始 shell 接口都不在项目默认设计中。

## 工具与权限映射

详细矩阵见 [`PERMISSION_INTERFACE_MATRIX.md`](PERMISSION_INTERFACE_MATRIX.md)。目前代码实际可用工具包括设备/软件/权限信息、网络/电话状态、亮度、battery、缓存位置、通知、usage、通讯录与日历基础读写；用户确认开启后另有 loopback-only 的 app-UID system.shell。媒体文件、相机、屏幕与提权接口尚未完成，不能在 Release 说明中宣称已经支持。

## 当前权限完整性说明

“尽可能多”指系统允许普通应用经用户授权获得的权限与显式系统特殊访问，不代表可越过系统、厂商、设备所有者或 Play 分发政策限制。对短信、通话记录、电话、辅助功能和媒体采集等高敏感访问，必须在首次使用时单独解释用途并让用户授权；实现前在 TODO 中逐项跟踪对应 MCP 方法与测试。

## Agent fallback Shell

当前实现了 `system.shell` 作为 app-UID 低权限 fallback：默认关闭；由用户在 App 中确认启用；MCP 工具仅在 LAN 关闭时注册/执行；命令最长4096字符、超时≤15秒、输出≤32KiB。它不是 ADB/Root，也不绕过 Android sandbox。Shizuku/ADB 提权单独列在 TODO；不提供 LAN 可达的 shell。
