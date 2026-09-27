# Android MCP Bridge

Kotlin 原生 Android companion app：在用户启动后，于本机 `127.0.0.1:18765/mcp` 提供经过 Bearer 密钥认证的 MCP Streamable HTTP endpoint。

> 当前为早期 MVP。不会静默申请权限、不会后台定位、不会自启动，也不会上传设备数据。普通 App 无法获得 Android 的全部系统/特权权限。

## 当前能力

- 本机 MCP server（仅 loopback；不监听 LAN/公网）
- 256-bit `SecureRandom` bearer key，使用 Android Keystore AES-GCM 加密保存；支持复制和轮换
- `device.battery`：电池百分比、充电状态、省电模式
- `device.location`：读取系统缓存的最后已知位置；要求用户授权，不启动连续定位
- 权限清单入口：定位、联系人、日历、相机、麦克风、蓝牙和通知等 Android 可请求权限；特殊访问仅提供系统设置入口
- 用户启动的前台服务与常驻通知

## 构建

GitHub Actions 在 push 到 `main` 后使用 JDK 17、Android SDK 36 构建 debug APK，并将其作为 30 天保留的 workflow artifact 上传。Android 16/17 可安装运行；当前 `targetSdk=36`。

本地构建需 JDK 17、Android SDK 36、Gradle 8.11.1：

```sh
gradle --no-daemon :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## RikkaHub 配置（需客户端支持 MCP Streamable HTTP）

- URL：`http://127.0.0.1:18765/mcp`
- Method：POST / Streamable HTTP
- Header：`Authorization: Bearer <App 中显示的随机密钥>`
- MCP protocol version：`2025-03-26`

客户端对自定义 header 和 HTTP loopback 的支持需以其版本为准。

## 公网访问状态

公网入口暂不启用。本机 HTTP endpoint 不应直接暴露到互联网：Bearer token 与设备数据会在未加密链路上泄露。公网功能需先接入 HTTPS 反向代理/隧道，并落实用户生成的随机密钥、撤销、TLS 与访问范围控制；不能把“绑定 `0.0.0.0`”误当成安全公网服务。

## 安全边界

- 权限必须经 Android 系统授权；部分访问需要系统设置、默认应用角色、设备所有者或系统签名，普通 App 无法取得。
- 当前开放工具只有电池与缓存位置。联系人/日历/相机/麦克风/蓝牙权限入口不代表这些数据已作为 MCP 工具开放。
- MCP 暴露的工具是明确白名单；不会执行 shell、任意代码或未定义的系统操作。
- 请勿把访问密钥发给不可信模型/服务。轮换密钥后，旧密钥立即失效。
