# 网络模式实施与实际证据

当前为本地候选开发，未修改任何既有 FRP、证书库、防火墙或系统网络配置。

## 实现

- 完整候选的 local / lan / proxy 配置与入口位于 server-complete；原用户的网络 BAT 保留。
- local 默认回环；lan 必须明确选择已有 Private 网络 IPv4，不自动监听通配地址或改变网络类别；proxy 只监听回环，要求原生 TLS、显式 HTTPS 公开 Origin、包内受保护密钥库和信任证书。
- Host 为显式允许地址；修改请求的 Origin/Referer 必须与实际 Host 和原生协议一致，拒绝 cross-site。转发头既不能使 HTTP 变 HTTPS，也不能变更允许来源。
- TLS 健康检查只连接记录的数字地址，验证配置证书链与预期公开主机名，不依赖公开 DNS，不关闭验证，不修改系统信任库。
- TLS Cookie 使用独立名称、Secure、HttpOnly、SameSite=Lax；HTTP 本地模式不强制 Secure。
- 切换需正常停止本候选后保存并重启；无热切换或自动降级为不安全 HTTP。错误模式拒绝启动并保留原库。

## 已实际通过

- 完整离线 Maven 138 项，0 失败/错误/跳过，包含真实 TLS、证书不受信拒绝、主机名不匹配拒绝、隔离双 TLS 代理与 Host/Origin 定向测试。
- .release-evidence/1.2.1/20261029-183839-fa4731b88a5b/tls-results.json：13 项完整应用与独立 HTTPS 代理检查通过，包括 TLS 登录、Cookie、CSRF、错误 Origin、Host、伪造转发头、明文拒绝、维护任务授权及加密备份下载。
- 同目录 mode-restart-results.json：4 项模式切换后本地登录与档案访问通过；无账号重新初始化。
- 错误 compatibility 模式启动实际被拒绝；随后使用同一数据目录恢复 local 并正常登录。
- 配置/脚本语法测试已执行；命令证据位于 .codex-continuation/evidence/。

## 仍待执行 / 人工验收

- 已补齐：20261029-194531-f0db5cbabdbd，本机明确选择既有Private虚拟接口，原生HTTP11项通过，无网络类别/防火墙变更。Private物理接口及真实第二设备仍待人工。
- 真正 Sakura Frp 公网证书、隧道切换、移动设备和网络访问控制未获授权，必须由负责人按最终指南单独验收。
- 当前客户端地址采用原生连接地址；代理模式不把客户端自带 X-Forwarded-For 当作真实终端 IP。
- 应用租约与恢复工具已由S5单独验收；本报告不冒充其验收证据。
