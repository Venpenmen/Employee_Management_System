# 兼容模式动态同源准入（2026-10-30 变更记录）

## 变更动机

原实现在兼容模式下要求 `network-mode.json` 预置**精确的外部 HTTP Origin**（`testOrigin`），服务端只接受该一个 Host。而 RC2 网络设置窗口没有录入该字段的控件，导致：

1. 在窗口中把安全模式切换为兼容模式必然保存失败，报 `Compatibility mode needs the exact non-loopback HTTP test Origin`；
2. 经 Sakura Frp 等代理访问时，域名与端口由服务商分配，可能随时变化，写死一个地址等于让远程访问无法自举；
3. 与验收标准 4.1「配置流程与自动识别」不符。

## 新准入规则

兼容模式（`mode=compat`）不再读取 `testOrigin` 做准入，改为在请求层做**动态同源**判定：

| 检查 | 规则 | 代码位置 |
| --- | --- | --- |
| 来源 | 请求远端地址必须是回环（Frp 客户端与服务器同机） | `Rc2NetworkPolicy.permits` 首行 |
| Host 唯一 | 请求只能带一个 `Host` 头 | 同上 |
| Host 合法性 | 必须是合法的 `http://host[:port]`（默认 80） | `Rc2NetworkPolicy.origin` |
| 同源（写操作） | `Origin` 必须等于 Host 推出的 Origin；无 `Origin` 时 `Referer` 必须同源 | `permits` 非 GET/HEAD/OPTIONS 分支 |
| 跨站 | `Sec-Fetch-Site: cross-site` 一律拒绝 | 同上 |
| 会话 | Cookie 名与 realm 仍绑定启动实例、代际与 Connector | `EntryIsolation.Valve` |

安全模式（`mode=safe`）不变：只接受 `http://127.0.0.1:<port>` 与 `http://localhost:<port>`，本机 HTTP 仍需一次性入口代码。原生 HTTPS 入口仍要求 `secureOrigin` 严格匹配。配置文件里保留的 `testOrigin` 只做参数提示校验（若填写则必须是干净的单条 http/https Origin），不再影响准入。

## 明确放弃的保护

- 该模式接受**任意域名**，因此无法抵御 DNS rebinding（攻击者域名解析到 127.0.0.1 且端口相同时，Host 与 Origin 会互相自洽）。这正是它继续被标为「仅虚构或脱敏数据」的原因。
- 不引入 `X-Forwarded-*` 信任：仍以本机 Connector 的真实 Host 为准，代理若改写 Host 则会被同源校验拒绝，需在代理侧保留原始 Host。

## 验证结果（2026-10-30 19:15–19:34）

| 项目 | 结果 |
| --- | --- |
| 离线 Maven 全量 | `Tests run: 164, Failures: 0, Errors: 0, Skipped: 0`（`RC2-P6-2d4fd69637fc480991bc7dccdb2289b3`） |
| 入口/会话组 | 88 项通过（`20261030-191552-5b68bdc66b2c`），含 3 项新增断言 |
| 边界拒绝组 | 23 项通过（`20261030-192322-df396176312b`） |
| 数据库 HTTP 组 | 52 项通过（`20261030-191937-372519eeaad4`） |
| 数据库故障组 | 60 项通过（`20261030-192516-efd19fff855e`） |
| JAR 重建 | 提交 `5380d93`，sha256 `E10E44F827F4B6D894E4FE39ED6606955130097948D3E07FCB00BF7B36C90223`，210 项输入一致 |
| 新候选 | `dist/EMS_v1.2.1_complete_candidate_RC2_adaptive`，运行时包 267,644,458B（`8dcee808…`）/源码包 268,182,686B（`d3d0edd5…`） |
| 冒烟 + 审计 | 9 项启动验证 + 10 项审计全通过，`FINAL_ACCEPTANCE.json` = LOCAL_CANDIDATE_ACCEPTED / NOT_RELEASED |

仍未验证：真实 Frp 隧道下的远端访问、公网证书、第二台物理设备、纯净断网 Windows、以及代理改写 Host 的情形（后者会被同源校验拒绝，需隧道保留原始 Host）。

## 影响文件

- `backend/src/main/java/com/example/ems/network/Rc2NetworkPolicy.java`：兼容模式取消 Origin 必填；`permits` 改为 Host↔Origin 同源判定。
- `server-complete-rc2/network-policy.ps1`：`testOrigin` 由必填精确 Origin 改为可选提示字段。
- `server-complete-rc2/network-mode-gui.ps1`：说明文案更新（无需预填地址、仍保留业务检查、仅限脱敏数据）。
- `backend/src/test/java/com/example/ems/network/Rc2NetworkPolicyTest.java`：改为断言任意 Host + 同源准入 + 跨站拒绝。
- `tools/test-rc2-entries.cjs`：新增真实 HTTP 断言——新代理 Host 直接可用、外来 Origin 的写操作被拒、切回安全模式后外来 Host 被拒。
- `README.md`、`RELEASE_NOTES_v1.2.1.md`：按 RC2 实际入口重写（同时修复残留的 RC1 文本）。
- `docs/RC2_DEPLOYMENT.md`、`docs/EMS_v1.2.1_COMPLETE_RC2_DELIVERY_REPORT.md`：同步新的兼容模式说明。

## 验证要求

需重跑：离线 Maven 全量测试（含 `Rc2NetworkPolicyTest`、`EntryIsolationContainerTest`）、入口组 `test-rc2-entries.ps1`（含新增三条断言）、边界组 `test-rc2-boundary.ps1 -BoundaryOnly`、数据库 HTTP 组与故障组；随后重建 JAR、重打包、冒烟与审计。
