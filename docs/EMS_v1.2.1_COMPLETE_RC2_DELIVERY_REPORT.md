# EMS v1.2.1 交付报告（本地候选）

- 报告日期：2026-10-30（2026-11-01 多次追加）
- 对外版本：**v1.2.1**
- 内部构建标识：`v1.2.1-complete-rc2`（schemaVersion 3；作为构建/运行时目录标识保留，不再作为对外版本号）
- 分支：`feature/v121-test-network-mode`
- 构建时记录源码提交：`49a747e33c39503636a8302aab6c82e4e7c56765`
- 当前工作树 HEAD：`4737fd91d297f5031667c6694b4158f0df182112`（4737fd9）
- 可执行 JAR：`app/ems-1.2.1-complete-rc2.jar`，SHA-256 `83036A4B00A51B0B555388B09ED9224EE2FF6CAE7B8C69A215409172CE73915F`
- 构建清单：`app/build-manifest-complete-rc2.json`，记录 210 项源码输入
- 保护文件：活动基线 213 项，逐项摘要核对无差异（原 226 项中有 13 项属 2026-10-28 开发产物，操作者清理 D 盘时移除，原始路径与摘要保留在 docs/RC2_PROTECTED_INPUT_LOSS_20261101.json）
- 运行时清单：`bootstrap/runtime-sha256.json`，492 项

## 1. 身份一致性说明

清单记录的 `sourceCommit` 为构建当时的提交（49a747e）。本轮接管逐项复核了清单中的 210 项源码输入与当前工作树（HEAD 4737fd9）的 SHA-256，结果为 0 处不符；JAR 实测 SHA-256 与清单一致。因此本候选打出的运行时载荷与当前源码字节相同，其后提交属测试与界面分层改动，未改变已打包字节。

若需要把 `sourceCommit` 字段与 HEAD 对齐，可在正式发布前用 `tools/build-complete-rc2.ps1` 重建一次 JAR，并重新执行 P5/P6 受影响验收；本次交付不做该重建，以免用新产物替换已被 P5/P6 实测过的字节。

## 2. 交付内容

| 路径 | 说明 |
|---|---|
| `app/ems-1.2.1-complete-rc2.jar` | 可执行 Spring Boot 包 |
| `app/version-complete-rc2.json` | 候选身份（version 1.2.1 / schemaVersion 3 / buildId） |
| `app/build-manifest-complete-rc2.json` | 构建清单与源码输入摘要 |
| `app/migrations/profile-v1.1.1.sql` | 历史档案表迁移 |
| `bootstrap/schema.sql`、`bootstrap/runtime-sha256.json` | 初始结构与运行时摘要清单 |
| `server-complete-rc2/`（18 个 .ps1） | 启动、停止、初始化、网络模式、本机入口代码、备份、恢复、校验、监控、日志 |
| `RC2_*.bat`（7 个） | 服务器初始化、本机入口代码、网络模式、启动、状态、停止、日志 |
| `runtime/` | 固定版本 JRE 与 MySQL 运行时（按 492 项清单校验） |
| `compatibility/EMS_v1.2.0_Windows_Portable.zip` | 受控旧版运行时，仅用于兼容往返（不可变，摘要固定） |
| `docs/` | 部署、升级、数据库矩阵、人工验收、已知边界等说明 |
| `源码.zip` | 与运行时包同源的完整源码交付 |

RC2 使用独立命名空间：`app/*complete-rc2*`、`data/complete-rc2/<mode>/`、`logs/complete-rc2/<mode>/`、`server-complete-rc2/network-mode.json`。原 complete 与 RC1 路径、数据、凭据保持独立，互不覆盖。

## 3. 阶段与验收证据

| 阶段 | 结果 | 证据 |
|---|---|---|
| RC2-P0 | 通过 | 独立夹具 9 项、既有断点回归 10 项；只读恢复点 `pre-rc2-395` |
| RC2-P1 | 核心通过 | Java 定向 54 项、PS 夹具 9 项；原生容器隔离与延迟 TLS 实测 |
| RC2-P2 | 通过 | 真实应用 85 项（`20261030-155156-9e1101a3d5e1`）、PS 租约/配置 10 项、离线 Maven 164 项 |
| RC2-P3 | 运行与工具通过 | 独立服务器运行、脚本夹具 11 项、分组 6 项、严格引用保留 6 项 |
| RC2-P4 | 通过 | 实际 Chrome 16 项 + 摘要单元 3 项（`20261030-162429-01c8e070a778`） |
| RC2-P5 | 通过 | 原因码改动后重跑：HTTP 52 项（`20261101-141448-a41f318a22d0`）、真实 Chrome 16 项（`20261030-174551`，前端未变）、数据库故障矩阵 60 项转入 P6 重跑（`20261101-141755-c69cbee01b2d`） |
| RC2-P6 | 通过 | 安全模式入口 88 项（`20261101-140900-03a9c2a33577`，含兼容模式新代理 Host 直接放行、外来 Origin 写操作 403、切回安全模式后外来 Host 403）、边界拒绝 23 项（`20261101-141252-ad8e2c8fa36a`）、数据库故障 60 项（`20261101-141755-c69cbee01b2d`）、离线 Maven 169 项（0 失败/0 错误/0 跳过，含 edgeTls 两项）；证据组 6 项与保留引用 6 项未重跑（原证据 `20261030-180437-892520888547`、`20261030-180502-6e281f4dbb0c` 保留） |
| RC2-P7 | 通过 | 打包 `tools/package-complete-rc2.ps1` 通过（最终候选 `dist/EMS_v1.2.1_本地候选`，对外包名 `EMS员工管理系统_v1.2.1_服务器版_Windows_x64.zip` 与 `EMS员工管理系统_v1.2.1_源码.zip`，逐条重算一致，字节与 sha256 见候选目录 `SHA256SUMS.txt`）；本候选相对 adaptive9 只改对外名称与文档，JAR 未变（`D22C138C…`，构建提交 `0a2178e`）；独立候选目录启动验证 9 项（安全模式、127.0.0.1:55901、`/health` 200、`/login` 200、`check-server` 三项 True、`stop-server` 退出 0）；审计 `tools/audit-complete-rc2.ps1` 11 项全通过。机器可读结果见候选目录 `BUILD_INVENTORY.json`、`CANDIDATE_SMOKE.json`、`FINAL_ACCEPTANCE.json` |

P5 关键验证点：预览摘要只展示白名单结构信息（版本、表数量、记录数、扩展与冲突计数），详情默认折叠；确认必须绑定服务端回执摘要、基础/完整升级选择、冲突跳过确认与 90 秒有效期；过期、篡改、错配、未绑定 grant 一律拒绝；真实切换后高层与员工会话同时失效并可用 BCrypt 账号重新登录；切换前自动生成加密恢复备份；注入切换失败、陈旧回滚日志、真实终止 broker 三种故障均恢复原指针。

P6 关键验证点：安全模式与兼容模式二选一入口、默认仅回环监听、Cookie 按入口与模式命名且 Host-only/HttpOnly/SameSite=Lax（仅原生 TLS 置 Secure）、跨入口改名 SID 与预认证会话不能继承身份、拒绝重放不撤销合法入口会话；本机入口一次性代码 90 秒有效、单次使用、只在交互控制台显示，不写入 URL/日志/安装包；命令、诊断与公开错误输出不含口令、路径、连接串。

## 4. 验证范围（如实区分三类）

已自动化验证并有证据文件：上表全部条目，证据保存在 `.release-evidence/1.2.1/<runId>/` 与 `.codex-continuation/evidence/`。

本机实际运行验证：三种模式的服务器启动与停止、真实 MySQL 实例、真实 Chrome 浏览器界面操作、真实 HTTP/HTTPS 本机入口、注入与真实中断下的数据库切换与恢复。

未执行或环境受限：

1. 纯净 Windows 10/11 断网环境解压即用；
2. 真实第二台物理设备（SSE 推送与局域网入口）；
3. 真实公网域名证书与真实 Sakura Frp 隧道；
4. 真实生产数据迁移与切换（本候选只用合成夹具数据）；
5. IDEA GUI 调试界面的完整人工核对；
6. `docs/COMPLETE_MANUAL_ACCEPTANCE.md` 中的人工验收表。

上述任一项未完成，都不能声称完成生产上线；本候选只是本地候选交付。

> 版本说明：归档在候选 ZIP 内的本报告是打包时刻（P7 打包前）的修订版，其第 3 节 P7 行与第 7 节的候选路径/字节数显示为上一次打包的结果；P7 的实际结果以工作树中的本报告、候选目录 `BUILD_INVENTORY.json`/`CANDIDATE_SMOKE.json`/`FINAL_ACCEPTANCE.json` 以及 `.codex-continuation/rc2-p7-results.json` 为准。归档文本与工作树文本的差异仅限这些结果行与本节说明，候选二进制载荷不受影响。

## 5. 部署与启动

安全模式（默认）：

1. 在完整解压的新目录运行 `server-complete-rc2/start-server.ps1`，默认仅监听 `127.0.0.1:8483`；
2. 首次启动在本机控制台设置高层账号；
3. 在另一个本机交互控制台运行 `server-complete-rc2/local-entry-code.ps1`，取得 90 秒单次代码，在本机浏览器 `/local-entry` 输入；
4. 退出或会话超时后需重新授权。

兼容模式（仅虚构或脱敏数据）：用 `RC2_Network_Mode.bat` 在停止状态下选择兼容模式并勾选确认即可，**不需要预先填写外部地址**（历史 `testOrigin` 字段可保留但不再参与准入）。重启后经 Frp 等代理访问任意域名与端口均可进入，由服务端校验请求 Host 与该请求 Origin 严格一致，跨站请求、伪造转发头、跨入口 SID 仍被拒绝。该模式不创建隧道、不扩大监听，仍要求登录、三级权限、CSRF、重新授权与恢复确认。

远程安全入口默认关闭；启用需在 `network-mode.json` 配置 `httpsEnabled=true`、`httpsPort=8484`、精确 HTTPS `secureOrigin` 与 `data/complete-rc2/server/` 内的 PKCS12 keystore 与证书链，口令仅经进程环境 `EMS_TLS_STORE_PASSWORD` 提供。

停止使用同目录 `server-complete-rc2/stop-server.ps1`，只处理本安装 PID、启动时间、可执行路径与状态，不按名称或端口批量结束进程。

## 6. 数据库管理

默认展示基础备份/恢复；高级区域集中配对导出、扩展包与回程证明。恢复为整体替换而非合并；预览只展示结构摘要并默认折叠详情；切换清空表单、不发送隐藏高级字段；变更文件或条件后必须重新校验。Flyway V1/V2/V3 与既有 Snapshot 编码保持不变；加密备份中的历史 buildId 只是格式标签，不作为本安装身份。

## 7. 结论

- RC2-P0~P7 已按证据通过，P7 打包与审计分别由 `tools/package-complete-rc2.ps1`（`rc2-package-results.json`：`passed=true`、`allEntriesRehashed=true`、`historicalArtifactsOverwritten=false`）、`tools/smoke-candidate-rc2.ps1`（`CANDIDATE_SMOKE.json`：9 项全通过）与 `tools/audit-complete-rc2.ps1`（`rc2-audit-results.json`：11 项全通过）执行，结果写入候选目录 `BUILD_INVENTORY.json` 与 `FINAL_ACCEPTANCE.json`。
- 候选目录 `dist/EMS_v1.2.1_本地候选`（构建提交 `0a2178e`，JAR sha256 `D22C138C7B1A6A6F3CE44F5E0DC7480382C2874322CDBD24D4D03C39F61D5AAC`，210 项源码输入一致）：
  - `EMS员工管理系统_v1.2.1_服务器版_Windows_x64.zip`（运行时包，545 个条目）；
  - `EMS员工管理系统_v1.2.1_源码.zip`（源码包，836 个条目）；
  - 解压目录 `EMS_v1.2.1_服务器版`；
  - 两个包的字节数与 sha256 以候选目录 `SHA256SUMS.txt` 与 `BUILD_INVENTORY.json` 为准确值（本报告在打包前定稿，因此不复制可变数字）。
- 两包共 1381 个条目逐条重算摘要一致，源码包 210 项输入与构建清单一致，JAR 内 V1/V2/V3 三项迁移哈希与保护摘要一致。
- 独立启动验证在复制出的解压目录内完成（不污染 dist）：默认安全模式、回环 `127.0.0.1:55901`、首启由候选包自带 `LocalSetup` 建立高层账号、`/health` 200、`/login` 200、`check-server` 报 applicationRunning/databaseRunning/ready 均 True、`stop-server` 退出 0 且 `ready` 标记消失，验证后临时目录已删除。
- 审计 11 项：活动基线 213 项保护摘要未变；构建身份（buildId、JAR 摘要、sourceCommit）一致；两包字节一致；JAR 内迁移齐备；26 个必需投递文件（含 `tools/port-monitor.ps1`、`启动端口监控.bat`）+ 18 个服务器脚本 + 7 个入口 BAT 齐备且脚本带 UTF-8 BOM；包内 492 项运行时清单复算一致且旧版兼容包摘要固定；83 条文本条目未含私钥或明文口令；安全/兼容入口与回环默认在脚本和文档中一致；包内指南与交付报告对应当前候选（`docs-accuracy`）；10 个数据库生命周期脚本齐备；候选可启动。
- 本候选**不构成正式发布**：未推送到远程仓库、未创建 GitHub Release、未切换公网入口、未对任何真实数据库执行恢复或切换。
- 进入正式发布准备前必须完成第 4 节列出的未执行项，并由人工验收表逐项签署。

## 2026-11-01 追加：边缘 TLS（代理终结 HTTPS）

提交 `0a2178e` 新增兼容模式专用开关 `edgeTls`（`network-mode.json` 的 `edgeTls` / 环境变量 `EMS_RC2_EDGE_TLS` / 网络模式窗口复选框）。当隧道在应用之外终结 TLS（Sakura Frp 自动 HTTPS、Cloudflare Tunnel、nginx 反代等），应用侧连接始终是明文 HTTP，浏览器却按 `https://` 发送 `Origin`，严格同源比对必然拒绝正常写操作；该开关只忽略 `http:`/`https:` 协议差异，host 与端口仍必须完全一致，`cross-site`、重复 Cookie、跨入口 SID、非回环来源与伪造 `X-Forwarded-*` 的判定不变，且只在兼容模式可用。

安全边界：它承认“协议在应用之外已终结”，不构成传输安全证明，也不改变 `forward-headers-strategy: none`；沿用仅虚构或脱敏数据的限制，真实敏感数据的公网使用继续受正式发布门禁约束。

## 2026-11-01 追加：交付完整性与文档一致性（adaptive9）

本候选不含代码改动（JAR 与 `0a2178e` 相同），只修三处“交付面”缺陷：

1. **重写包内指南** `docs/COMPLETE_CANDIDATE_GUIDE.md`：原文仍是 RC1 文案（指向 `server-complete` 目录、Portable 8481 入口、旧 `build-manifest-complete.json`），与 RC2 包内实际目录、端口、入口码流程均不符。现按当前实现重写，覆盖 7 个入口 BAT、首次初始化、一次性入口码、两种网络模式与 `edgeTls`、403/401 原因码、数据与日志路径、端口监控、受控旧版副本、诊断命令与二级凭证恢复。
2. **端口监控入包**：`tools/port-monitor.ps1` 与根目录 `启动端口监控.bat` 此前只在源码工作树，运行包内缺失；现已加入必需投递清单，并由审计校验其存在与 UTF-8 BOM。
3. **文档不得滞后于字节**：`tools/package-complete-rc2.ps1` 现在要求交付报告必须写明本次候选目录名与将被打包的 JAR sha256，否则拒绝打包；`tools/audit-complete-rc2.ps1` 新增第 11 项 `docs-accuracy`，在包内校验指南不再指向历史目录/端口、交付报告命中当前候选名与 JAR 摘要。此前 adaptive8 的包内报告曾把 JAR sha256 写错一位（`D22C138C6AC1…` vs 实际 `D22C138C7B1A…`），旧审计只校验 buildId 因而未发现——新断言正是对该类错误的守卫。

仍未完成（须在正式发布门禁前由人工验收）：安全模式在操作者环境中的实机复测、真实 Sakura Frp 公网隧道、真实第二设备、纯净断网 Windows、IDEA GUI、真实生产数据迁移；P6 的证据组 6 项与保留引用 6 项仍沿用 10-30 结果，未在最新代码上重跑。

## 2026-11-01 追加：对外身份转正为 v1.2.1

按操作者决定，只转**对外身份**，不动内部构建标识（`v1.2.1-complete-rc2`、`server-complete-rc2`、`RC2_*.bat`、`app/ems-1.2.1-complete-rc2.jar` 均保留）：

- 交付包对外命名去掉 `CompleteCandidateRC2`：运行时包 `EMS员工管理系统_v1.2.1_服务器版_Windows_x64.zip`、源码包 `EMS员工管理系统_v1.2.1_源码.zip`、解压目录 `EMS_v1.2.1_服务器版`、候选目录默认 `dist/EMS_v1.2.1_本地候选`。
- 本报告与 `README.md`、`RELEASE_NOTES_v1.2.1.md`、`docs/COMPLETE_CANDIDATE_GUIDE.md` 的标题与自述统一为 **v1.2.1**，并在首次出现处标注"内部构建标识 v1.2.1-complete-rc2"。
- 未改变任何运行时行为：JAR、脚本逻辑、审计项、保护基线（213 项）与证据链均不变；改名后重新打包、冒烟与 11 项审计全部通过。
- 工程目录同时转正：`employee-management-system_v1.2.1-next` → `employee-management-system_v1.2.1`；原来占用该名字的 2026-10-26 快照归档为 `employee-management-system_v1.2.1_旧快照_20261026`（未删除）。改名后从新路径重跑 11 项审计全通过。
- 仍不构成正式发布：未推送远程、未建 Release、未切换公网入口。
