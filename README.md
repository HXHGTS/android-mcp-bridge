# Android MCP Bridge Lite

Kotlin 原生 Android companion app，为同一台手机上的 RikkaHub 提供本机 MCP Streamable HTTP 服务。

> 本 Lite 版按用户要求移除 Bearer 密钥，MCP 服务仅绑定 IPv4 `127.0.0.1` 和 IPv6 `::1`。这不是应用隔离边界：同一手机上的其他 App 也可能访问 loopback。**切勿将此端口反向代理、端口转发或暴露到局域网/互联网。**

## MCP 地址

- IPv4：`http://127.0.0.1:18765/mcp`
- IPv6：`http://[::1]:18765/mcp`
- App 界面提供单独复制及双地址复制按钮。
- MCP Streamable HTTP，协议版本 `2025-03-26`；无 Authorization Header。

本服务必须由用户在本机启动。RikkaHub 要支持 loopback HTTP 和 Streamable HTTP；App 的两个 loopback listener 都须成功启动。若失败，App 会在状态页显示 IPv4/IPv6 状态。

## 工具

- `device.battery`：电量、充电状态、省电模式。
- `device.location`：系统缓存的最后位置；需要定位授权，不启动持续定位。
- `device.notifications`：最多返回 30 条当前活动通知标题/正文；需要在安卓系统设置开启通知读取。
- `device.usage`：读取最近 1–168 小时应用前台使用时长，默认 24 小时；需要在安卓系统设置开启使用情况访问。

通知正文、位置和应用使用时长属于敏感数据；MCP 工具只在本机提供，不上传数据。用户可随时撤销通知读取、使用情况访问和位置权限。

## 权限

普通运行时权限由用户逐项授权。通知读取和使用情况访问属于特殊访问，App 只打开系统设置页，不会静默授予。Android 系统签名/特权权限仍无法由普通 App 获取。

## 构建

GitHub Actions 在 push 到 `main` 后以 JDK 17、Android SDK 36 构建 debug APK，并上传 30 天 workflow artifact。当前 `minSdk=26`，`targetSdk=36`。

```sh
gradle --no-daemon :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。
