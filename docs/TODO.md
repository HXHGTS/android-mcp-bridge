# TODO — Android MCP Bridge

## P0：发布与基线
- [x] push 只构建 artifact，Release workflow 手动触发、填写 tag 后才发布。
- [x] 建立并备份固定 Release keystore；Actions signing secrets 已配置。
- [x] 对当前 Gradle signed-release 配置完成 `assembleRelease` + `apksigner verify`；签名证书指纹与固定 keystore 一致。当前验证包仍只有现有4个MCP工具，不能视为权限/屏幕接口完成版。
- [ ] Release gate：权限→工具矩阵无未映射项；Android 16/17 安装/授权测试通过；清单和工具描述一致。

## P1：权限—工具接口一一对应
- [ ] Contacts：搜索/读取/新增/更新/删除；破坏性写入进入设备端确认队列。
- [ ] Calendar：列表/详情/新建/更新/删除；处理时区、重复事件、日历 ID。
- [ ] Files/media：使用 MediaStore/SAF；列表/读/写/重命名/删除；路径与 URI 做范围校验。
- [ ] Phone state/number、activity recognition、body sensors：按 OS 能力读状态/读数；不自动拨号。
- [ ] Bluetooth 与 nearby Wi‑Fi：扫描、枚举、连接/断开；遵守运行时授权与系统限制。
- [ ] Special settings：权限状态与打开系统设置页；只实现 Android 明确允许的写操作。
- [ ] 对 Manifest、首次权限引导、状态页、MCP `tools/list` 与 README 做自动一致性检查。

## P1：屏幕与媒体交互
- [ ] Screenshot：MediaProjection 会话授权后按需截帧；明确当前投屏状态。
- [ ] Screen recording：MediaProjection 系统确认、前台服务常驻通知、用户可见停止控制；不隐蔽录屏。
- [ ] Screen operations：用户手动启用 Accessibility；实现受限 tap/swipe/text/back/home 工具，显示服务状态；无 arbitrary shell。
- [ ] Camera photo/video：明确相机权限、可见预览/前台会话、输出 URI；禁止静默后台拍摄。
- [ ] Audio capture：用户启动的前台录音会话与明显停止入口。
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
