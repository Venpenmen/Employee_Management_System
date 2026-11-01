# EMS rc2 — ChatGPT/RDC 接手记录（2026-10-28）

## 交接基线

- 分支：`feature/v121-rc2-security`；接手前 HEAD：`1bf615a`，原始恢复点：`514d5a0`。
- 接手前未提交的 7 个已修改文件与 1 个新增文件均保留；原始差异备份在 `.release-evidence/rc2/handoff-prework/`（包含 patch、状态与新增 JSON）。
- 不修改原 v1.2.0、旧 v1.2.1、rc1 ZIP，不访问生产库，不推送远程仓库。

## 本轮已执行

- 核查当前 rc2 JAR 内 V1/V2 迁移原始 SHA-256 均与 `docs/RC2_RECOVERY_POINT.json` 匹配。
- 运行 `launcher/build-release.ps1`：Maven 构建成功，生成 rc2 JAR，三份 rc2 ZIP 完成内置校验（运行时哈希、ZIP 白名单、迁移文件、敏感文件排除）；日志：`rc2-handoff-build.log`。
- 发行目录：`dist/rc2/`；包含便携版、服务器版、源码 ZIP 和 `SHA256SUMS.txt`。两份运行 ZIP 当前字节相同、使用不同模式启动入口，不能宣称不同功能载荷。
- 运行 `launcher/test-rc2.ps1 -MigrationOnly`：返回 0，隔离历史升级、server/portable 迁移及事务检查成功；证据：`.release-evidence/1.2.1/20261028-190525-fb295ae70910`，日志：`rc2-handoff-migration.log`。
- `launcher/test-rc2.ps1` 完整安全 HTTP/Chrome 浏览器回归已完成，`FULL_RC2_EXIT=0`；证据：`.release-evidence/1.2.1/20261028-190811-f9603ff2e50a`，日志：`rc2-handoff-security-browser.log`。

## 未完成的人工验收与边界

- 纯净 Windows 10/11 标准用户无 Java/MySQL 的离线运行；真实第二台设备 SSE；IDEA GUI Debug；公网 HTTPS/FRP；用户真实旧版备份及人工切换；真实业务数据抽查。
- 正式发布前按 `docs/RC2_ACCEPTANCE_CHECKLIST.md` 逐项人工验收，不自动部署或覆盖正式安装。
- 恢复历史迁移时不得仅依赖 Git 检出（LF/CRLF 字节差异），应核对 rc1 源码 ZIP、原始哈希及 Flyway 校验。

## Codex 恢复额度后

1. 先检查本文件、最新 Git log/status、`docs/TEST_REPORT.md`、`docs/RC2_DATABASE_MATRIX.md` 和本轮测试日志；不要重新实现 P0–P4。
2. 对最后提交和未提交修改进行独立代码审查，确认 MIG-01～17 证据、事务边界及包内隐私保护。
3. 如完整安全/浏览器测试失败，先定位并定向修复，再重跑受影响验收。
4. 完成人工验收后由用户决定是否正式发布；不得自行推送远程仓库。
