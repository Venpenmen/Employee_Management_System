# EMS v1.2.1 Complete Candidate RC2 本地部署与调试

候选标识 `v1.2.1-complete-rc2`，Maven 版本 `1.2.1`。本文件说明已实现入口；是否验收通过以最终 RC2 回执为准，当前仍在验证。不会自动安装证书、开放端口、修改 FRP 或替换历史安装。

## 独立目录

RC2 使用 `app/ems-1.2.1-complete-rc2.jar`、`app/version-complete-rc2.json`、`server-complete-rc2/`、`data/complete-rc2/<mode>/` 和 `logs/complete-rc2/<mode>/`。原 complete/RC1 路径及已有数据保持独立。数据库仍使用本模式命名和隔离实例；禁止复制原 MySQL 数据文件或复用凭据。导入旧数据应使用数据库管理中的加密备份和隔离恢复流程。

## 安全模式（默认）

在完整解压的新安装目录内运行 `server-complete-rc2/start-server.ps1`。默认 HTTP 仅监听 `127.0.0.1:8483`，也接受同端口 localhost。首次通过本机控制台设置高层账号。端口冲突明确失败，不停止其他实例。

安装目录的完整路径不要超过 120 个字符（推荐如 `F:\ems-rc2`）。Windows 单条路径上限为 260 字符；路径过深时实例数据文件（`data/complete-rc2/<mode>/...`）会超过上限，表现为「未能找到路径的一部分」。`runtime-common.ps1` 现在会在加载时直接拒绝并提示移动目录，不再让错误出现在中途。已初始化且已停止的安装整体移动后，`subst` 映射会在下次启动时重建，数据不丢失。

在另一个本机交互控制台运行 `server-complete-rc2/local-entry-code.ps1`，在本机浏览器 `/local-entry` 输入控制台显示的单次代码（90秒）。代码不代替账号密码，不出现在 URL、日志和安装包。代码工具拒绝重定向输出。退出或会话超时后需要重新进行本机入口授权。

该授权是为了区分直接本机访问与被隧道转发的回环 HTTP：不能仅凭回环地址、Host 或 X-Forwarded-* 认定来自本机。禁止将本机 HTTP 当成远程安全入口。

远程入口默认关闭。如需启用，在本安装的 `server-complete-rc2/network-mode.json` 配置 format=1、mode=safe、generation（合法UUID）、httpsEnabled=true、httpsPort=8484、准确的 HTTPS secureOrigin，以及位于 `data/complete-rc2/server/` 内的 keyStore（PKCS12）与 trustCertificate（证书/证书链）相对路径。密钥口令仅通过本次启动进程环境 `EMS_TLS_STORE_PASSWORD` 提供，不写配置或打包。远程必须保持到原生 HTTPS Connector 的加密连接；不把代理头当作 TLS 证明。

证书、信任链、域名验证或远程端口占用失败时，`network-runtime.json` 记录远程 FAILED 和错误编号；本机入口仍需单次授权。损坏/未知配置本身拒绝应用。不会降级开放远程 HTTP。

## 兼容模式（仅虚构或脱敏数据）

使用独立 RC2 网络设置窗口，选择兼容模式并明确勾选测试数据确认即可，**不需要预先填写外部地址**；模式为 compat，保存时使用锁、进程/租约检查、原子替换并生成新 generation，只在停止后保存，重启生效。历史配置中的 `testOrigin` 字段仍可保留但仅作部署提示，不参与准入判定。准入规则：请求仍必须来自本机回环（Frp 客户端与服务器同机），非只读请求的 `Origin`（缺省时用 `Referer`）必须与该请求的 `Host` 严格一致，`Sec-Fetch-Site: cross-site`、重复 Cookie、跨入口 SID 一律拒绝；伪造 `X-Forwarded-*` 不改变入口身份。因此换域名、换外部端口、换 Frp 节点都无需改配置。

此模式允许完整业务与数据库管理，但仍要求登录、三级权限、当前有效会话、CSRF、操作重新授权、预览绑定及恢复确认。它不会创建隧道或扩大本机监听。由于接受任意域名，它无法抵御 DNS rebinding 一类攻击，真实敏感数据不得通过远程明文 HTTP 使用。

### 边缘 TLS（由代理终结 HTTPS）

当隧道在外部终结 TLS（Sakura Frp 的自动 HTTPS、Cloudflare Tunnel、nginx 反代等），应用侧连接始终是明文 HTTP，浏览器却按 `https://` 发送 `Origin`/`Referer`，严格的协议比对会把正常请求判成 `origin`/`referer` 拒绝。为此提供显式开关：

- 配置键 `edgeTls`（`server-complete-rc2/network-mode.json` 内，布尔），等价环境变量 `EMS_RC2_EDGE_TLS`；网络设置窗口的「边缘 TLS」复选框；只在 `mode=compat` 下可用，安全模式或非兼容模式写入即启动失败。
- 打开后，写请求的 `Origin`（缺省 `Referer`）与由 `Host` 推出的来源**只忽略 `http:`/`https:` 协议差异**，host 与端口仍必须完全一致；`cross-site`、重复 Cookie、跨入口 SID、非回环来源、伪造 `X-Forwarded-*` 的判定不变。
- 该开关只是承认"协议在应用之外已终结"，**不构成传输安全证明**：应用无法自证浏览器到代理之间确实是 TLS。因此仍只适用于虚构或脱敏数据，远端必须由使用者自己确保证书链、域名与隧道归属；不要因为打开它就把 `forward-headers-strategy` 或 `X-Forwarded-Proto` 当作可信输入。

### 入口拒绝的原因码

入口拒绝返回 `{"code":403|401,"reason":"<码>","msg":"入口或会话不符合当前网络配置","hint":"<中文建议>","request":{"method":"…","path":"…","host":"…","remote":"…","port":8483,"mode":"safe|compat","edgeTls":true|false,"expected":"…","headers":{"origin":"…","referer":"…","fetchSite":"…"}},"data":null}`。实现位置：`backend/src/main/java/com/example/ems/network/Rc2NetworkPolicy.java` 的 `refusal(...)` 产生原因码，`EntryIsolation.java` 的 `reject(...)` 输出响应体。原因码与判定顺序：

| 顺序 | reason | 判定 | 安全模式 | 兼容模式 |
| --- | --- | --- | --- | --- |
| 1 | `entry-port` | 连接的 Connector 端口/安全标志不属于本实例（含代次不匹配） | 拒绝 | 拒绝 |
| 2 | `remote-address` | 来源不是 `127.0.0.1`/`::1`/`0:0:0:0:0:0:0:1` | 拒绝 | 拒绝 |
| 3 | `host-count` | `Host` 头数量不为 1 | 拒绝 | 拒绝 |
| 4 | `header-format` | `Host` 不是合法的 `host[:port]` | 拒绝 | 拒绝 |
| 5 | `secure-origin` | HTTPS 入口的请求 Origin 不等于配置的 `secureOrigin` | 拒绝 | 拒绝 |
| 6 | `host` | HTTP 入口的 Host 不在本模式允许范围（安全模式仅 `http://127.0.0.1:<httpPort>`、`http://localhost:<httpPort>`） | 拒绝 | 接受任意 Host |
| 7 | `cross-site` | 写请求带 `Sec-Fetch-Site: cross-site` | 拒绝 | 拒绝 |
| 8 | `origin` / `referer` | 写请求的 `Origin`（缺省用 `Referer`）与 Host 推出的 Origin 不一致 | 拒绝 | 拒绝；`edgeTls` 打开时只忽略协议、host:port 仍须一致 |
| — | `cookie-duplicate` / `realm-mismatch` | 入口 Cookie 重复，或会话属于其他入口/代次 | 401 | 401 |

原因码只暴露判定结果与本次请求的可见头信息，不含凭据、Cookie 值、会话标识或路径；`request.expected` 给出当前模式下合法的示例地址。

## 入口隔离与停止

Cookie 采用 `EMS121RC2_<MODE>_<SAFE|COMPAT>_HTTP/TLS`，Host-only、HttpOnly、SameSite=Lax；只有原生 TLS 入口的 Cookie 使用 Secure。服务端在容器层绑定启动实例、配置代际和原生 Connector，跨入口改名 SID、预认证会话、CSRF、授权与 SSE 不能继承身份。拒绝重放不撤销合法入口原会话。另一入口成功进行一次新登录时，同账号旧会话按既有合同失效。

使用本安装的 `server-complete-rc2/stop-server.ps1`，只处理本安装的 PID、启动时间、可执行路径及状态。不按名称/端口批量结束进程。无法核验时保留并报错。IDEA 直接 main 的租约仍被持有时，不删除其就绪文件，也不关闭其数据库。

## IDEA 与 Portable 源码能力

先离线执行 Maven 编译和 classpath 生成，使用 RC2 初始化脚本 `initialize-server.ps1 -Mode dev` 准备独立开发库。仅本地生成的 `data/complete-rc2/dev/idea.env` 包含开发凭据，禁止提交/分享。

在 IDEA 打开 backend/pom.xml，运行 EmsApplication.main，将该本地环境文件的变量加入运行配置；不要导入旧模式凭据。dev 默认 HTTP 8482；冲突时显式配置自己的可用端口，不停止其他服务。RC2 cookie、控制目录和 enabled 标记必须一起配置。IDEA 调试界面本身属于人工验收项，主类启动与浏览器证据单独记录。

对于 IDEA 直接 main，没有启动器 app PID。可在本机交互控制台运行 `local-entry-code.ps1 -Mode dev -DevJavaExe <当前IDEA运行所用java.exe的完整路径>`。工具验证带MAC的本安装状态、PID、启动时间、明确提供的可执行路径、就绪 PID 和应用租约，确认后才签发代码；不会停止 IDE 进程。

Portable 源码入口使用同目录脚本 `-Mode portable`，默认 HTTP 8481，并使用独立数据、Cookie 和状态。本轮统一发行服务器运行包与完整源码，不删除这些开发能力，也不覆盖历史便携包。

## 数据库页面

默认基础备份/恢复。高级兼容与回程区域集中配对导出、扩展包与回程证明；切换清空表单，不发送隐藏高级字段。恢复仍是整体替换，不合并；预览只展示版本、表数量/记录数、扩展和冲突计数等结构摘要，详情默认折叠并内部滚动。变更文件或条件后重新校验。过期、篡改、错配及安全冲突不得确认切换。

Flyway V1/V2/V3 与既有 Snapshot 编码器保持原始内容和校验。加密备份编码器中的历史 buildId 是历史格式标签，不作为本次安装身份；候选身份以 RC2 version/build-manifest、JAR SHA-256 和验收回执为准。

## 人工边界

真实设备、纯净 Windows、交互控制台与默认浏览器、IDEA GUI、真实证书与实际 FRP、公网和生产切换须按最终人工表核验。任何本机回环测试均不替代这些证据；未最终人工验收不得正式发布。
