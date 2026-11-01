# 数据库管理与独立候选实施记录

日期：2026-10-29。当前记录是实施证据，不是 S3 或整体验收通过声明。

## 已实现

- 原有标准加密备份保留；兼容目标仅接受 current / 1.2.0。
- 配对包只含加密基础、加密扩展、公开摘要、校验值及操作说明。
- 可选扩展、回程证明固定名称接收；禁止客户端文件名决定路径，限制单文件 512 MiB、总量 1 GiB，失败清除部分输入。
- 隔离预览支持 CURRENT、BASE_ONLY、ORIGINAL_PAIR、VERIFIED_RETURN；安全冲突不分配恢复库，不切换活动指针。
- 确认绑定 15 分钟预览、报告 SHA、全部输入 SHA、明确的基础升级 / 跳过冲突选择；一次授权和 broker 在切换前重检。
- 维护子进程以 employee → account → security → active session 顺序检查当前高层、冻结、临时密码与原请求摘要。
- 请求排空取得读锁后再检查维护标志，防止排队请求越过已确认的排空点。
- 新候选另用 server-complete、app/*-complete.json / ems-1.2.1-complete.jar、data/complete/<mode>、logs/complete/<mode>，不修改原 RC2 启停入口或状态。
- 应用连接前核验完整候选标识、模式、Cookie、活动库指针及自有 MySQL 的 PID / 启动时间 / 可执行文件。端口默认 8481 / 8482 / 8483。

## 实际证据与未完成项

- 定向输入与确认测试通过，实际隔离 SQL 预览 34 项通过；无活动库切换。
- 首次独立完整构建 130 项 Maven 测试通过（0 失败、错误、跳过）。
- HTTP 已实际验证三角色登录、独立 Cookie、角色限制、CSRF 和页面控件；维护导出子进程出现 Windows PowerShell CommandNotFoundException，前几次用例失败并已安全回收副本。失败不是成功证据。
- 已定位本机脚本栈至 Read-EmsSecrets 中的安全模块命令。Java 子进程不具备 PowerShell 原生跨版本启动时的模块路径修正；正在验证仅清理子进程 PSModulePath 的修复，不修改系统环境。
- S3 仍需通过全部 HTTP、实际切换及浏览器交互；S4–S9 继续执行，不能据此标为候选就绪。

## 后续实际结果

子进程模块路径修复后，20261029-181026-ab84eecbac7d 的完整 HTTP 52 项通过：标准与配对下载、SHA、原配对与基础升级预览、损坏扩展拒绝、预览文件篡改拒绝、授权对象绑定、真实切换、旧 Session 失效、BCrypt 账号再登录和恢复点保留。所有任务自有实例已停止并回收。

浏览器首轮在隔离上下文的下载策略处超时，未计通过；测试已显式绑定 browserContextId，仍需重测。S4 定向 Maven 15 项通过，包含真实 TLS 和双向校验证书的隔离 HTTPS 反向代理；这不是公网、真实 LAN 设备或生产证书验收。网络策略和新脚本语法检查通过，正式运行网络未改变。

实际 Chrome 重测：20261029-182418-caaf6f96d31b/database-browser-results.json 共9项通过。S3 模块已接受，证据 .codex-continuation/s3-module-results.json；S4–S9 不在此放行范围内。
