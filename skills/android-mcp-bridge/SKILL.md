---
name: android-mcp-bridge
description: >-
  通过 Android MCP Bridge 读取和控制用户的安卓手机。当用户提到手机相关的操作或查询（电量、屏幕亮度、
  通知、联系人、日历、短信、通话记录、应用清单、传感器、蓝牙/Wi-Fi 状态、文件与媒体、剪贴板、
  截屏、录屏、点击/滑动/输入、拍照、录音、拨号或本机 shell）时使用。MCP 服务运行在手机本机
  127.0.0.1:18765，无令牌。
---

# Android MCP Bridge — Agent 操作手册

## 连接方式

- IPv4：`http://127.0.0.1:18765/mcp`；IPv6：`http://[::1]:18765/mcp`
- MCP Streamable HTTP，协议版本 `2025-03-26`，无需 Authorization。
- 客户端已接入 MCP 时，工具以原名直接调用（如 `device.battery`）。
- 没有集成 MCP 的环境，用本目录 `scripts/mcp_call.py`（纯标准库）：
  - `python3 mcp_call.py list` — 列出全部工具
  - `python3 mcp_call.py call device.battery '{}'`
  - `python3 mcp_call.py call device.settings.brightness.set '{"value":128,"confirm":true}'`

## 每次会话开始

先调用 `device.permissions.status` 和 `screen.status`，确认：运行时权限、写工具开关、Shell 开关、无障碍、LAN 状态，再决定用哪些工具。

## 工具与门禁（四档）

### A. 只读，无特殊门禁
| 工具 | 说明 |
|---|---|
| `device.info` / `device.hardware` | 设备/系统/内存/存储/传感器清单 |
| `device.apps.list` | 已安装应用清单（包名/标签/版本） |
| `device.battery` | 电量/充电/省电 |
| `device.settings.brightness.get` | 亮度与亮度模式 |
| `device.network` | 当前网络接口与地址 |
| `screen.status` | 各开关状态（无障碍/录屏/LAN/Shell/写工具） |
| `sensors.read` | 加速度/陀螺仪/磁场/光/距离/气压采样 |
| `bluetooth.status` / `wifi.status` | 蓝牙与 Wi-Fi 状态 |
| `media.list` / `files.list` | 媒体库/文件目录列表 |
| `phone.dial` | 打开拨号盘预填号码（不拨出） |

### B. 只读，需对应授权（未授权会抛 SecurityException）
`device.location`（定位）、`device.notifications`（通知读取）、`device.usage`（使用情况访问）、
`device.telephony.status`（电话状态）、`contacts.search`（联系人）、`calendar.calendars`/`calendar.list`（日历）、
`sms.list`（短信）、`calllog.list`（通话记录）、`clipboard.read`（剪贴板，后台可能被系统限制）、
`media.read` / `files.read`（≤5MB 返回 base64）。

### C. 写操作：需 App 内"写工具开关"开启 + LAN 关闭 + 参数 `confirm:true`
| 工具 | 关键参数 |
|---|---|
| `device.settings.brightness.set` | `value` 0–255；`mode` 0=手动/1=自动；`confirm` |
| `contacts.create/update/delete` | name/phone/email；rawContactId；confirm |
| `calendar.create/update/delete` | calendarId/title/startMs/endMs；eventId；confirm（Unix 毫秒） |
| `media.write` / `media.delete` | type/fileName/base64；type/id；confirm |
| `files.write` / `files.delete` | path/base64；path/recursive；confirm（限 /storage/emulated/0） |
| `sms.send` | to/text/confirm |
| `phone.call` | number/confirm（真拨出） |
| `clipboard.write` | text/confirm |
| `notifications.clear` | key/confirm（key 来自 device.notifications） |

### D. 交互/采集
| 工具 | 前置条件 |
|---|---|
| `system.shell` | App 内 Shell 开关 + LAN 关闭；不限时（`timeoutMs` 可选），输出≤32KiB |
| `screen.tap/swipe/key/text` | 系统设置里启用本 App 无障碍；LAN 关闭 |
| `screen.screenshot` | 每次弹系统授权框，120 秒内需用户点确认；工具阻塞等待 |
| `screen.record.start/stop` | 同上；autoStopSeconds 5–600 |
| `camera.photo` / `audio.record` | 相机/麦克风授权；Bridge 会短暂切前台（系统规则） |

## 写操作纪律

1. 调用前先用一句话告诉用户将改什么，再带 `confirm:true` 调用；不要静默修改数据。
2. 报错含"请在 App 中启用/开启"时，直接转告用户去 Bridge App 打开对应开关，不要盲目重试。
3. 删除类操作（contacts.delete、files.delete、media.delete、calendar.delete）二次确认对象名/ID。

## 屏幕与采集纪律

- `screen.screenshot` / `screen.record.start` 调用前提醒用户："马上会弹系统授权框，请在 120 秒内点立即开始"。
- 拍照/录音时 Bridge 界面会闪现 1–2 秒，提前告知用户属正常现象。
- 屏幕坐标操作（tap/swipe）不确定位置时，先 `screen.screenshot` 看一眼再操作。

## 敏感数据纪律

- 短信、通话记录、联系人、通知正文、位置属于高敏感数据：默认返回**计数+摘要**，用户明确要求才给明细。
- 不要把这些内容转发到任何外部服务或写进公开文件。
- `device.location` 只读缓存位置；返回"暂无缓存"时如实说明，不要反复重试。

## LAN 纪律

LAN 开关默认关闭。Shell、写工具、屏幕操作在 LAN 开启时会被强制隐藏/拒绝——这是设计行为不是故障。
除非用户主动要求，不要建议开启 LAN，更不要建议端口转发/公网暴露。

## 常见错误对照

| 报错关键字 | 含义与动作 |
|---|---|
| `请先授予` | 系统权限缺失，让用户在系统弹窗/设置中授权 |
| `请在 App 中启用` | Bridge 内对应开关未开（写工具/Shell） |
| `局域网监听启用时` | LAN 与该工具互斥，让用户关 LAN 或放弃该工具 |
| `未获得屏幕采集授权` | 用户取消了 MediaProjection 弹窗或超时 |
| `请先在系统设置中启用…无障碍服务` | 引导用户到无障碍设置开启本 App |
| `前台服务未能启动` | 让用户打开 Bridge App 保持前台后重试（while-in-use 限制） |
