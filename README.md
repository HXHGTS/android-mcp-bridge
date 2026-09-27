# Android MCP Bridge

Kotlin 原生 Android companion app，为同一台手机上的 RikkaHub 提供本机 MCP Streamable HTTP 服务。

## 监听范围与地址

默认仅监听回环：

- IPv4：`http://127.0.0.1:18765/mcp`
- IPv6：`http://[::1]:18765/mcp`

App 提供复制按钮，并提供默认关闭的“允许同一局域网连接”开关。开启后仅尝试绑定当前 Wi-Fi 上的 RFC1918 私有 IPv4 与 IPv6 ULA 地址；不绑定蜂窝接口，也不绑定全球可路由 IPv6。Wi-Fi 地址变化时会更新监听。没有令牌认证，开启 LAN 后，同一局域网内其他设备可调用 MCP 已开放工具，开启前有二次警告。**不要端口转发、反向代理或暴露到互联网。**

MCP 使用 Streamable HTTP、协议版本 `2025-03-26`，不需要 Authorization Header。

## 运行时权限

界面提供一次性申请当前 Android 版本适用的常见运行时权限：位置、联系人、日历、相机、麦克风、电话状态、运动/身体传感器、蓝牙、附近 Wi-Fi、通知及媒体读取；后台定位单独申请。另提供通知读取、使用情况访问、所有文件访问、悬浮窗、修改系统设置、电池优化豁免的设置入口。安卓仍要求用户逐项批准；部分系统签名、设备所有者、root 或受平台政策限制的权限，普通 App 不能通过弹窗强行获得。

本版新增大批准接口（详见下方清单）。**除 Shizuku/ADB/root 提权后端外，矩阵内工具均已写入代码，但尚未经实机逐项验证**。当前接口：

- `device.info` / `device.hardware`：设备、系统、内存/存储、传感器信息。
- `device.apps.list`：已安装应用清单（需 `QUERY_ALL_PACKAGES` 声明）。
- `device.permissions.status`：本 App 已声明权限和特殊访问状态。
- `device.battery`：电量、充电状态、省电模式。
- `device.location`：系统缓存的最后位置，不启动持续定位。
- `device.notifications`：最多 30 条当前活动通知标题/正文，需通知读取授权。
- `device.usage`：最近 1–168 小时应用前台使用时长，需使用情况访问授权。
- `device.network` / `device.telephony.status`：网络接口/传输与基础电话网络状态；电话号码仅在额外授权时返回。
- `device.settings.brightness.get` / `.set`：亮度读取/修改；写入需系统授权与写工具开关。
- `contacts.search` / `contacts.create` / `contacts.update` / `contacts.delete`：需联系人读写权限；写工具另需用户打开写入开关、LAN关闭并传 `confirm=true`。
- `calendar.calendars` / `calendar.list` / `calendar.create` / `calendar.update` / `calendar.delete`：需日历权限；写工具有相同写入开关与显式确认。
- `system.shell`：用户手动启用后、LAN 关闭时提供 App UID 下的本机 shell（非 ADB/root）；命令≤4096字符、输出≤32KiB，**执行时间不设上限**（timeoutMs 可选）。
- `sensors.read` / `bluetooth.status` / `wifi.status` / `clipboard.read|write`：传感器采样、蓝牙/Wi-Fi 状态、剪贴板。
- `media.list|read|write|delete`：媒体库读、增、删（写/删需写开关+confirm）。
- `files.list|read|write|delete`：主外部存储文件读、写、删（写/删需写开关+confirm；非空目录需 recursive=true）。
- `sms.list|send` / `calllog.list` / `phone.dial|call`：短信读发、通话记录、拨号（发短信/直拨需写开关+confirm）。
- `screen.screenshot` / `screen.record.start|stop`：截屏/录屏，每次调用触发系统 MediaProjection 授权弹窗，120 秒内需点确认。
- `screen.tap|swipe|key|text`：无障碍屏幕操作，需你先在系统设置启用本 App 的无障碍服务；LAN 开启时这些工具全部隐藏。
- `camera.photo` / `audio.record`：无预览静拍与录音，前台服务会短暂切到本 App（系统 while-in-use 规则所需）。
- `notifications.clear`：按 key 清除单条通知（需通知读取授权+写开关+confirm）。

通知内容、位置与使用情况属于敏感数据。当前版无令牌，LAN 默认关闭；只在可信网络中按需启用。

## 构建与 Release

GitHub Actions 在 push 到 `main` 后只构建 debug APK 并上传 30 天 artifact，**不会自动创建 Release**。Release 仅由 `workflow_dispatch` 手动触发：填写 `release_tag` 后才会发布 APK 与 SHA256SUMS；留空则仅构建。手动发布时 APK `versionName` 取标签去掉可选 `v` 前缀后的版本号。

本项目后续构建**完全在 GitHub Actions**执行，不在 SpeedyPage 或其他本机/服务器构建。push `main` 只产 debug artifact；带 tag 的手动 Release 也只由 GitHub Actions 构建。当前 `minSdk=26`、`targetSdk=36`。请从 Actions artifact 或 GitHub Release 获取 APK。允许本地静态检查源码，但不要在 SpeedyPage 构建 APK。

## 软件/硬件信息与本机 Shell

工具列表还提供 `device.info`、`device.hardware`、`device.apps.list` 和 `device.permissions.status`。应用清单需要 `QUERY_ALL_PACKAGES`；该权限受应用商店政策限制，本仓库按侧载/自用项目说明，不保证可通过 Play 审核。设备序列号、IMEI、MAC 等受限标识不读取。

本机 Shell 工具 `system.shell` 默认关闭，用户在 App 中明确开启后才出现在 `tools/list`；它仅以 App UID 执行，不是 ADB/Shizuku/root，执行时间不限（timeoutMs 可选限制），输出最多 32 KiB。只要 LAN 开关开启，Shell 工具会自动关闭且不对 LAN 提供。RikkaHub 可访问同机 loopback，因此启用前请确认信任该 Agent。
