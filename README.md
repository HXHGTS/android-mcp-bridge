# Android MCP Bridge Lite

Kotlin 原生 Android companion app，为同一台手机上的 RikkaHub 提供本机 MCP Streamable HTTP 服务。

## 监听范围与地址

默认仅监听回环：

- IPv4：`http://127.0.0.1:18765/mcp`
- IPv6：`http://[::1]:18765/mcp`

App 提供复制按钮，并提供默认关闭的“允许同一局域网连接”开关。开启后仅尝试绑定当前 Wi-Fi 上的 RFC1918 私有 IPv4 与 IPv6 ULA 地址；不绑定蜂窝接口，也不绑定全球可路由 IPv6。Wi-Fi 地址变化时会更新监听。没有令牌认证，开启 LAN 后，同一局域网内其他设备可调用 MCP 已开放工具，开启前有二次警告。**不要端口转发、反向代理或暴露到互联网。**

MCP 使用 Streamable HTTP、协议版本 `2025-03-26`，不需要 Authorization Header。

## 运行时权限

界面提供一次性申请当前 Android 版本适用的常见运行时权限：位置、联系人、日历、相机、麦克风、电话状态、运动/身体传感器、蓝牙、附近 Wi-Fi、通知及媒体读取；后台定位单独申请。另提供通知读取、使用情况访问、所有文件访问、悬浮窗、修改系统设置、电池优化豁免的设置入口。安卓仍要求用户逐项批准；部分系统签名、设备所有者、root 或受平台政策限制的权限，普通 App 不能通过弹窗强行获得。

申请到的权限不会自动代表 MCP 已开放对应功能；当前 MCP 工具白名单如下：

- `device.battery`：电量、充电状态、省电模式。
- `device.location`：系统缓存的最后位置，不启动持续定位。
- `device.notifications`：最多 30 条当前活动通知标题/正文，需通知读取授权。
- `device.usage`：最近 1–168 小时应用前台使用时长，需使用情况访问授权。

通知内容、位置与使用情况属于敏感数据。Lite 版无令牌，LAN 默认关闭；只在可信网络中按需启用。

## 构建

GitHub Actions 在 push 到 `main` 后以 JDK 17、Android SDK 36 构建 debug APK，并上传 30 天 workflow artifact。当前 `minSdk=26`、`targetSdk=36`。

```sh
gradle --no-daemon :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。
