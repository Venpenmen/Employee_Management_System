# Complete Candidate RC2 实施台账

2026-10-30，基线6b06414、断点395。原用户BAT、AGENTS.md、交接文档未纳入提交。历史S0–S9及226保护摘要核验不变。

2026-10-30 19:15–19:34：兼容模式由"预置精确 testOrigin"改为"Host↔Origin 动态同源准入"（变更说明见 `docs/RC2_COMPAT_DYNAMIC_ORIGIN_20261030.md`），提交 `5380d93`；JAR 重建、P5–P6 相关组全部重跑、新候选 `dist/EMS_v1.2.1_complete_candidate_RC2_adaptive` 重新打包并审计；断点 sequence 580，P0–P7 均为 DONE。

2026-11-01 13:40–14:30：入口拒绝由布尔判定改为带原因码（`entry-port`/`remote-address`/`host-count`/`secure-origin`/`host`/`cross-site`/`origin`/`referer`/`header-format`），响应回显请求 Host、远端地址、连接器端口、模式与期望入口（说明见 `docs/RC2_ENTRY_REFUSAL_REASONS_20261101.md`），提交 `f8bde26`；同时修复"403 也清入口 Cookie 导致会话被踢"的回归，提交 `fc244f0`；离线 Maven 165 项通过，四组重跑与重新打包审计通过，新候选 `dist/EMS_v1.2.1_complete_candidate_RC2_adaptive3`；断点 P0–P7 均 DONE。

2026-11-01 14:40–15:25：按用户实机 403 定位，把 `header-format` 兜底拆成 `host-format`/`origin-format`/`referer-format`，并在拒绝响应里回显 `method`、`path` 与 `headers{origin,referer,fetchSite}`（提交 `f70eede`）；为便于现场观察新增只读端口监控 `tools/port-monitor.ps1` + `启动端口监控.bat`（提交 `b38b54d`）。由提交 `f70eede` 重建 JAR（`D7D857EF…`）、四组重跑（入口 88/边界 23/数据库 52/故障 60）、重新打包并审计通过，新候选 `dist/EMS_v1.2.1_complete_candidate_RC2_adaptive5`；断点 sequence 690，P0–P7 均 DONE。

2026-11-01 15:11–15:25 证据丢失事件：操作者清理 D 盘时删除了 `dist/` 下三个 2026-10-28 开发产物目录（`network-mode-test`、`rc2-resume-20261028`、`database-compat-p0-20261028-final`）与 `dist/EMS_v1.2.1_complete_candidate`，验收基线 pin 的 13 项文件随之丢失且全项目无副本。经操作者确认后，把这 13 项从活动保护基线中移除，原始路径与 SHA256 完整保留在 `docs/RC2_PROTECTED_INPUT_LOSS_20261101.json`；未重新生成、未改历史摘要注意。同时修复断点引擎在证据文件消失时崩溃的问题（`digest()` 返回 None、仅新产生的无效证据才拒绝保存），提交 `9eb4a90`。

2026-11-01 15:30–15:50：修复用户实机反馈的两个缺陷。①`server-complete-rc2/network-mode-gui.ps1`、`network-policy.ps1` 等 21 个 `.ps1` 曾被保存为无 BOM 的 UTF-8，Windows PowerShell 5.1 按 ANSI 解码后中文变成乱码、窗口脚本报“缺少右 }”，现已全部回写 UTF-8 BOM，并在审计里加闸门（包内 18 个 server 脚本逐个校验 BOM，`bom=18`）。②`/local-entry` 的 POST 在浏览器发 `Origin: null`（不透明源）时被 `origin-format` 拒绝；现在仅在 `Sec-Fetch-Site` 为 `same-origin`/`none` 时按“无 Origin”处理（跨站仍先被 `cross-site` 拒绝，写操作仍需会话绑定的 CSRF 令牌），空 Origin/空 Referer 一并规范化。提交 `c1e32d1`；离线 Maven 165 项通过，四组重跑（入口 88、边界 23、数据库 52、故障 60）全部通过，重新打包并审计 10 项全通过，新候选 `dist/EMS_v1.2.1_complete_candidate_RC2_adaptive6`；断点 sequence 719，P0–P7 均 DONE。

2026-11-01 16:00–16:20：修复“本机入口码刚取就说过期”的误报。用户实机 POST `/local-entry` 被 `LocalEntryAdmission` 拒绝，而该层自带一份 Origin 严格相等检查（与 `Rc2NetworkPolicy` 并列），所以上一轮的 opaque-origin 放行没覆盖到它；同时该层拒绝响应只有一句“需要有效的本机入口授权，或代码已过期、已使用”，把重放、实例不匹配、CSRF 失效、Origin 不符全说成“过期”。现在：①该层同样接受 `Origin: null`＋`Sec-Fetch-Site: same-origin/none`，跨站仍拒绝，CSRF 与会话绑定照旧；②拒绝响应改为带 `reason`（`code-format`/`proof-missing`/`proof-instance`/`proof-expired`/`proof-expiry-window`/`proof-format`/`proof-replay`/`proof-auth`/`proof-capacity`/`proof-malformed`/`origin`/`body`/`csrf`/`admission-required`）、`hint` 中文处置建议与 `request{method,path,host,headers{origin,referer,fetchSite}}` 回显；③`redeemReason()` 取代布尔判定并保留 `redeem()` 兼容既有测试。提交 `fec0797`；离线 Maven 167 项通过，入口 88 与边界 23 两组重跑通过，重新打包审计 10 项全通过，新候选 `dist/EMS_v1.2.1_complete_candidate_RC2_adaptive7`；断点 sequence 739，P0–P7 均 DONE。

2026-11-01 16:30–17:05（另一会话执行，本次仅补齐记录）：为 Sakura Frp 自动 HTTPS 新增 `edgeTls` 边缘 TLS 兼容开关（提交 `0a2178e`）。现场复现：隧道在代理侧终结 TLS，应用侧连接始终是明文 HTTP、浏览器却按 `https://` 发送 `Origin`，严格同源比对必然判 `origin`。开关只在兼容模式可用，只忽略 `http:`/`https:` 协议差异，host 与端口仍须完全一致；`cross-site`、重复 Cookie、跨入口 SID、非回环来源、伪造 `X-Forwarded-*` 的判定全部不变，且不构成传输安全证明（真实敏感数据仍不得经远程明文使用）。新增两个单测，离线 Maven 169 项 0 失败；打包 `dist/EMS_v1.2.1_complete_candidate_RC2_adaptive8`（JAR `D22C138C…`、sourceCommit `0a2178e`），冒烟 9 项、审计 10 项通过。操作者已在其环境以兼容模式 + 边缘 TLS 完成本机登录，真实公网隧道仍由操作者复测，未宣称验证。

2026-11-01 21:0x–21:5x：修三处“交付面”缺陷（无代码改动）。①包内 `docs/COMPLETE_CANDIDATE_GUIDE.md` 仍是 RC1 文案（`server-complete` 目录、Portable 8481、旧 `build-manifest-complete.json`），按 RC2 实现重写为入口一览/首次初始化/一次性入口码/两种模式与 `edgeTls`/403·401 原因码/数据日志路径/端口监控/受控旧版副本/诊断与二级凭证恢复；②`tools/port-monitor.ps1` 与根目录 `启动端口监控.bat` 此前只存在于源码工作树，运行包内缺失，现加入 `package-complete-rc2.ps1` 的必需投递清单并由审计校验存在与 BOM；③为防止“包内文档滞后于字节”，打包脚本现在要求交付报告必须写明本次候选目录名与将被打包的 JAR sha256，审计新增第 11 项 `docs-accuracy`（指南不得再指向历史目录/8481/旧清单；报告须命中当前候选名与 JAR 摘要）。触发点：adaptive8 的包内报告把 JAR sha256 写错一位（`D22C138C6AC1…` 对实际 `D22C138C7B1A…`），旧审计只校验 buildId 未发现。新候选 `dist/EMS_v1.2.1_complete_candidate_RC2_adaptive9`。

|阶段|状态|证据/剩余工作|
|---|---|---|
|RC2-P0|通过|append独立夹具9、既有断点回归10；追加为396，P0接受397；pre-rc2-395只读恢复点；已知两安装记录只读核查，不代表全机无EMS|
|RC2-P1|核心通过|核心实现已验证：Java定向54、PS夹具9；原生容器隔离及延迟TLS真实测试。完整应用/JDBC及本机控制台展示待P2/P3/P6|
|RC2-P2|通过|最新真实应用85项通过（20261030-155156-9e1101a3d5e1），覆盖完整模式重启/并发/失效授权/SSE/原生TLS及实际远程失败本机可用；PS租约及配置夹具10通过；完整离线Maven164通过|
|RC2-P3|运行/工具通过|独立运行与85项实际链路、脚本夹具11、分组6及严格引用保留6通过；服务器/源码打包器待P7执行，尚未发行|
|RC2-P4|通过|实际Chrome16及摘要单元3通过；配对包下载、默认折叠/滚动、取消/过期与隐藏参数隔离均实际验证|
|RC2-P5|通过|最新重跑：HTTP 52（20261101-150109-15d22463025e）、真实Chrome 16（20261030-174551，前端未变）、故障矩阵60（20261101-150437-11b2e621d49f）；回执绑定/基础完整选择/冲突跳过确认/过期与篡改拒绝/真实切换后双会话失效/注入失败与真实终止broker均恢复原指针|
|RC2-P6|通过|安全模式入口88（20261101-145533-10d0a76a1458，含兼容模式对新代理Host直接放行、外来Origin写操作403、切回安全模式后外来Host 403）、边界拒绝23（20261101-145853-a1aedf43fca7）、数据库故障60（20261101-150437-11b2e621d49f）、离线Maven 165（0失败/0错误/0跳过）；证据组6与保留引用6未重跑，原证据（20261030-180437-892520888547、20261030-180502-6e281f4dbb0c）保留不变|
|RC2-P7|通过|最新候选 `dist/EMS_v1.2.1_complete_candidate_RC2_adaptive9`（本候选无代码改动，JAR 仍为提交 0a2178e 的 D22C138C…）：运行时包与源码包各 543/835 条目（字节与 sha256 见候选目录 SHA256SUMS.txt）、210 项输入一致、离线 Maven 169；解压后安全模式启动 9 项全通过（127.0.0.1:55901）；审计 11 项全通过（1378 条目、保护基线 213、脚本 BOM 齐备、新增 docs-accuracy）；`FINAL_ACCEPTANCE.json` status=LOCAL_CANDIDATE_ACCEPTED、releaseStatus=NOT_RELEASED；回归：入口 88（20261101-163823-33978dba5cfb）、边界 23（20261101-164154-07a8d6acf7bd）、数据库 52（20261101-154520-60f197b4fc4c）、故障 60（20261101-154813-9e01b3cf9f81）；入口/边界/打包/冒烟/审计由另一会话直接执行，未留 runner 证据记录，以其结果文件为准（均已入 rc2-p7-results.json 的 inputs 摘要）

用户已批准安全模式增加一次性本机入口授权，以防回环HTTP被FRP转发后仅靠Host被误当本机。代码短期单次、绑定实例/启动代际，仅本机控制台显示；不写URL/日志/发行物。兼容模式保留全部业务安全规则；不修改既有隧道。此表不是RC2完成声明。
