# TODO — Android MCP Bridge

## P0：发布与基线
- [x] push 只构建 artifact，Release workflow 手动触发、填写 tag 后才发布。
- [x] 建立并备份固定 Release keystore；Actions signing secrets 已配置。
- [x] 对固定签名配置完成 `assembleRelease` + `apksigner verify`；签名证书指纹与 keystore 一致。该验证早于新增接口代码；当前工作树仍需 GitHub CI 编译。
- [ ] Release gate：权限→工具矩阵无未映射项；Android 16/17 安装/授权测试通过；清单和工具描述一致。

## P1：权限—工具接口一一对应
- [ ] Contacts：搜索/读取/新增/更新/删除；破坏性写入进入设备端确认队列。
- [ ] Calendar：列表/详情/新建/更新/删除；处理时区、重复事件、日历 ID。
- [x] Files/media：media.list/read/write/delete + files.list/read/write/delete 已入码（路径限定主存储、写删需开关+confirm）；重命名未做；待 CI 编译与实机验证。
- [x] Phone/SMS：telephony 状态、sms.list/send、calllog.list、phone.dial/call、sensors.read 已入码；发送/直拨需写开关+confirm；待 CI 与实机验证。
- [x] Bluetooth/Wi-Fi 只读状态（bt.status/wifi.status）入码；扫描与连接/断开未做（受系统限制）。
- [x] Special settings：brightness get/set 含 mode 参数（0=手动/1=自动）入码。
- [ ] 对 Manifest、首次权限引导、状态页、MCP `tools/list` 与 README 做自动一致性检查。

## P1：屏幕与媒体交互
- [x] Screenshot：screen.screenshot（MediaProjection 每次授权，返回 JPEG base64+存文件）入码；待实机验证。
- [x] Screen recording：screen.record.start/stop（媒体投影 FGS 常驻通知、自动停秒数）入码；待实机验证。
- [x] Screen operations：AccessibilityBridgeService + screen.tap/swipe/key/text 入码，需用户手动启用无障碍；screen.status 可查状态；待实机验证。
- [x] Camera photo：camera.photo 无预览静拍（Camera1）入码，前台服务短暂可见；camera.video 未做；待实机验证。
- [x] Audio capture：audio.record（1–120秒 m4a）入码，走 microphone FGS；待实机验证。
- [ ] 所有媒体结果只在本机/已请求的 MCP 响应中传递，不自动上传或持久保留，除非用户另行选择。

## P2：高权限 provider（用户启动与授权）
- [ ] Shizuku provider：用户手动启动服务、给本 App 授权；报告授权状态；仅具名、参数校验接口。
- [ ] ADB/wireless debugging provider：仅支持用户手动配对/启动，不自行打开调试、不复用其他证书。
- [ ] Root provider：仅在设备已 root 且 root manager 明确批准时评估；不包含漏洞利用。
- [ ] 提权 provider 的任何工具都不默认暴露任意命令；LAN 开启时默认隐藏/拒绝高危 provider 工具。

## P3：端到端
- [ ] MCP initialize/tools/list/call，IPv4/IPv6 loopback、LAN 默认关闭/启用/关闭/网卡变化测试。
- [ ] 权限拒绝、授权撤销、服务停止/重启、Activity 被系统回收测试。
- [ ] 写操作确认队列：超时、拒绝、重复调用和审计记录测试。
- [ ] 手动 workflow_dispatch 发布一个签名 Release，核验 APK 证书指纹与固定 keystore 一致。
- [ ] 短信/通话记录：调查 Android 16/17 runtime 授权与公开分发政策，若作为侧载版功能启用，只读接口先行；不做静默发送/拨号。

## 已加代码、待 GitHub CI 与实机核验（v0.4.0 批次）
- [x] `device.info` / `device.hardware` / `device.apps.list` / `device.permissions.status` / `device.network` / `device.telephony.status` / brightness 工具（GitHub CI待编译验证）。
- [x] `system.shell` app-UID fallback：默认关闭、15秒/32KiB限额；LAN 开启时动态隐藏并拒绝执行（GitHub CI待编译验证）。
- [ ] GitHub Actions 编译 + MCP tools/list/tool-call 验证；Android 16/17实机核验 `QUERY_ALL_PACKAGES` 返回范围与Shell开关。
