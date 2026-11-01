# Sakura FRP HTTPS adaptation — rc2 security decision

## Confirmed topology

Sakura FRP tunnel #29662911 is a TCP tunnel with automatic HTTPS and bound hostname `www.u3089790.nyat.app`, remote port 14938, forwarding to EMS `127.0.0.1:8383`. Sakura reported a wildcard certificate and successful tunnel startup. The application receives HTTP after TLS termination. `DatabaseAccess.user()` deliberately requires `HttpServletRequest.isSecure()` for nonlocal/proxied database management.

## Security finding

**Do not set `server.forward-headers-strategy=framework` or blindly trust `X-Forwarded-Proto`.** The current TCP tunnel forwards arbitrary HTTP requests to the same backend listener; a direct HTTP client can supply spoofed forwarded headers. A browser-visible valid HTTPS certificate is insufficient evidence that a particular backend request arrived via HTTPS. A secret header alone does not prove transport security when the untrusted entry point can relay arbitrary headers.

## Safe supported deployment design (not yet activated)

Use a separate TLS endpoint that terminates TLS under operator control, with a valid certificate for the public hostname. Disable Sakura automatic HTTPS and configure the TCP tunnel as **raw TCP passthrough** to this TLS endpoint. The backend must receive genuine HTTPS (so `request.isSecure()` is true), or a reverse proxy must enforce TLS itself and inject authenticated metadata over a private, isolated backend connection while removing all client-provided forwarding headers. The public tunnel must never reach that private backend HTTP port directly. Preserve session authentication, secondary verification, CSRF, audit and existing restrictions.

For direct TLS termination in EMS, provision a valid certificate and private key independently and configure Spring Boot SSL and secure session cookies in a separate isolated server installation. The existing rc2 launcher uses HTTP health checks and cannot be switched to HTTPS by setting an environment variable alone; launcher readiness, URL display and packaging must first be updated and tested. Do not import Sakura's private keys or change the running instance without authorization.

## Temporary safe usage

Keep Sakura auto-HTTPS for ordinary browser access only. Access `/admin/database` from the server itself at `http://127.0.0.1:<actual EMS port>`; do not relax `DatabaseAccess` or expose backups over the public HTTP listener. This is not full public HTTPS deployment. Test coverage asserts that spoofed `X-Forwarded-Proto` and remote HTTP are rejected.

## Pending for implementation

- Choose certificate provisioning method and operator-controlled TLS endpoint.
- Verify Sakura raw TCP passthrough and the complete HTTPS request path.
- Extend launcher health checks and URL handling for TLS.
- Run isolated end-to-end TLS tests, forged-header tests, login/CSRF/database-operation tests, and repackage a new candidate.
- No old EMS instance, live database, FRP tunnel, or installed package is modified by this document.

## 2026-10-28 Codex 恢复审查与隔离验证

上述隧道拓扑来自 RDC 交接记录，本轮未连接、修改或切换现有隧道，不能将交接陈述当作本轮证书验证结果。RDC 的 DatabaseAccessTest 两项新增测试保留；本轮新增 DatabaseTransportTlsTest，使用随机回环端口和一天有效的临时自签名证书，通过真实 Tomcat/Java HTTP 客户端验证：明文连接携带伪造 Forwarded/X-Forwarded-* 仍返回 403；真实 TLS 的 isSecure 为 true；无高层身份的 TLS 请求仍返回 403；默认 JVM 信任不接受测试自签名证书。测试客户端只信任指定测试证书并保留主机名校验，没有全局 trust-all，也未安装系统证书。

该测试的身份与事务依赖是虚构测试替身，只证明传输边界；不是完整 EMS 经 FRP 的登录/CSRF/数据库管理验收。完整 EMS 登录、权限与 CSRF 证据单列在 TEST_REPORT.md。

## 真实部署人工步骤（未执行）

1. 先选择并准备独立的操作员可控 TLS 终止点、域名有效证书及私钥存储方案。私钥/口令不交给测试日志或源码，不从现有 Sakura 安装提取。
2. 在新隔离安装及新测试隧道验证 raw TCP 直通至 TLS 端口。不能把现有明文应用端口改名为 HTTPS；不能让公共 TCP 入口仍直达代理的私有 HTTP 后端。
3. 若采用反向代理，先证明后端连接隔离、客户端转发头全部剥离再由受控代理产生必要元数据。当前发行物没有完成这条可信代理链配置，不启用 framework/native 信任策略代替证明。
4. 若采用 EMS 内置 TLS，必须先适配并测试启动器的健康探测、状态/日志 URL 和浏览器打开协议。现有启动器仍使用 HTTP，本轮没有宣称可直接通过环境变量切换。手工启动 JVM 的 TLS 成功不等于 BAT 就绪检查通过。
5. 在真实第二设备验证证书域名、链、有效期和系统信任；不使用忽略证书错误、关闭主机名验证或浏览器强行继续作为通过证据。验证 HTTPS Cookie 的 Secure/HttpOnly/SameSite、登录及注销、缺 CSRF 拒绝、旧会话写入拒绝、SSE 即时失效与断线补查、三角色数据库管理权限。
6. 从未加密入口和伪造 Forwarded/X-Forwarded-Proto 客户端验证管理操作拒绝；验证公共入口无法绕过 TLS 到达私有明文后端。记录不含密码、Cookie、重置码和备份口令的状态摘要。
7. 只有独立候选端到端通过并获得用户授权后，才由用户决定真实服务/证书/隧道切换。保留原配置及回滚步骤，不在此任务中停止任何现有实例。

结论：代理头防护和本机真实 TLS 边界已具备测试；**真实 Sakura HTTPS 适配仍属待完成的部署验收，不宣称已支持当前自动 HTTPS 拓扑下的远程数据库管理**。当前可在服务器本机使用原本受保护的管理入口；真实敏感数据的公网使用继续受正式发布安全门禁约束。

## 2026-11-01 兼容模式 + 边缘 TLS（用户授权的方案 1）

现场复现：Sakura 自动 HTTPS 隧道下，登录页可打开（GET/HEAD/OPTIONS 不参与 Origin 校验），但 `POST /login` 返回 `403 {"reason":"origin"}` —— 应用侧连接是明文 HTTP，`Rc2NetworkPolicy` 用 `http://<Host>` 推出 `actual`，浏览器按 `https://<Host>` 发送 `Origin`，协议差异使严格相等必然失败。

决策：在**兼容模式**内新增显式开关 `edgeTls`（环境变量 `EMS_RC2_EDGE_TLS`，网络设置窗口有复选框），打开后同源校验只忽略协议差异，host 与端口仍必须完全一致。理由：Origin 校验防的是 CSRF（跨站时 host 不同仍被拒），scheme 对 CSRF 无防护价值；而 TLS 在应用之外终结时，应用结构上无法自证浏览器是否走了 HTTPS。

边界（不宣称的部分）：该开关不证明传输安全，不改变 `forward-headers-strategy: none`，不信任 `X-Forwarded-Proto`；伪造转发头、`cross-site`、重复 Cookie、跨入口 SID、非回环来源仍按原规则拒绝。仅适用于虚构或脱敏数据，继续沿用正式发布门禁对真实敏感数据公网使用的限制。

验证：`Rc2NetworkPolicyTest` 新增 2 个用例（`edgeTlsTunnelOnlyIgnoresTheSchemeNotTheHostOrPort`、`edgeTlsStaysOffByDefaultAndOutsideCompatibilityMode`），覆盖跨协议放行、跨 host/端口拒绝、`cross-site` 拒绝、非回环拒绝、默认关闭、安全模式与非法布尔值启动失败；启动器侧 `Resolve-Rc2Network` 对 `edgeTls` 做白名单、布尔与"仅兼容模式"校验。真实 Sakura 隧道复测由用户在其环境中进行。
