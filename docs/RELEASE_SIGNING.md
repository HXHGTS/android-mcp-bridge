# Release 签名

- Release 签名采用固定 JKS：私有 vault `keys/keystores/android-mcp-bridge-release.jks.b64`。
- Alias：`android-mcp-release`。
- Certificate SHA-256：`71:85:06:61:F8:97:3A:51:1C:B2:D0:B0:C7:34:69:25:A2:75:F8:D6:E2:E4:98:B5:B6:E1:CC:F0:0F:D4:2E:D1`。
- GitHub Actions secrets：`ANDROID_RELEASE_KEYSTORE_BASE64`、`ANDROID_RELEASE_KEYSTORE_PASSWORD`。Key password 与 store password 相同。
- Keystore 密码/私钥不写入公开仓库、日志、普通文档；备份与恢复说明只在私有 vault。
- push `main` 只产 debug artifact；手动 `workflow_dispatch` 填 `vMAJOR.MINOR.PATCH` 后产 signed release APK，并计算 SHA-256。

固定签名密钥已先备份至私有 vault（2026-09-28）并配置为仓库 Actions secrets。手动 Release 使用 `assembleRelease`；普通 push 仍只生成 debug artifact。稳定签名已在 SpeedyPage 上完成一次历史性验证；**根据后续构建政策，不再使用该服务器构建，今后一律由 GitHub Actions 验证/构建。**
