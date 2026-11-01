# EMS 员工管理系统 v1.2.1 完整本地候选（RC2）

候选标识 `v1.2.1-complete-rc2`，Maven 版本 `1.2.1`，数据库结构 Flyway V1/V2/V3。本目录是与历史 complete/RC1 相互隔离的独立安装：独立 JAR、独立数据目录 `data/complete-rc2/`、独立日志、独立 Cookie 命名空间与 MySQL 端口。

包内自带 Java 17 与 MySQL 运行库，无需安装 JDK/Maven/MySQL，无需联网，也不需要管理员权限。程序只监听本机回环地址，不会自动修改防火墙、网卡或开放端口。

## 一、首次启动（服务器版）

1. 把发行 ZIP 完整解压到**独立且可写**的目录，保留目录结构。目录要**浅**：整套路径建议不超过 120 个字符（例如 `F:\ems-rc2`）。Windows 单条路径上限是 260 字符，放太深时实例数据文件会超限，表现为启动或取本机入口代码时报「未能找到路径的一部分」；启动脚本现在会提前拦截并提示。
2. 双击 `RC2_Initialize_Server.bat`：启动本安装的 MySQL、建库并建表；若库里还没有高层账号，会在控制台询问工号（6 位数字）、姓名、身份证号、手机号、出生日期、入职日期和密码（≥8 位且同时含字母与数字，输入两次确认）。输入「取消」可中止，下次继续；已有账号时拒绝重复初始化。
3. 双击 `RC2_Start_Server.bat`：成功后控制台打印 `EMS v1.2.1 server ready: http://127.0.0.1:8483`。**此窗口不要关闭**，关闭即停止服务。
4. 另开一个控制台运行 `RC2_Local_Entry_Code.bat`，取得一次性入口代码（90 秒内有效、只能用一次）。
5. 本机浏览器打开 <http://127.0.0.1:8483>，在 `/local-entry` 输入该代码，再用第 2 步的账号密码登录。

入口代码只用于证明操作者就在本机，不能代替账号登录；代码不会出现在 URL、日志或安装包中。

## 二、网络模式

网络模式只有两种，用 `RC2_Network_Mode.bat` 选择，**保存只在实例停止时生效，重启后才是新模式**。

### 安全模式（默认）

- 仅监听 `127.0.0.1:8483`（同端口 `localhost` 等价），本机 HTTP 必须走上面的单次入口代码。
- 远程访问只能走原生 HTTPS Connector（需自备证书链并在 `server-complete-rc2/network-mode.json` 配置 `secureOrigin`、`httpsPort`、`keyStore`、`trustCertificate`）。

### 兼容模式（仅虚构或脱敏数据）

用于经 Sakura Frp 或其他代理做远程访问：

- **不需要预先填写外部地址**：不再要求把某个域名写死进配置。经代理进来的任意 Host（域名 + 实际外部端口）都能进入，由服务端做 Host 与 Origin 的同源一致性校验。
- 仍然要求：账号登录、三级权限、单有效会话、CSRF、高风险操作的重新授权、数据库恢复的预览绑定与显式确认；伪造 `X-Forwarded-*` 头不会升级入口身份。
- 不会创建隧道、不扩大监听范围、不修改 FRP 或防火墙——隧道由你自己的 Frp 客户端建立。
- 它接受任意域名，也就无法抵御 DNS rebinding 一类攻击，因此**只适用于虚构或脱敏数据**的真实性测试，不得承载真实敏感数据。

开启步骤：停实例 → `RC2_Network_Mode.bat` 选「兼容模式」并勾选确认 → 保存 → 重启 → 用 Frp 客户端把本机 `127.0.0.1:8483` 映射出去 → 浏览器访问映射后的地址直接登录。换域名、换端口、换 Frp 节点都**不需要**改配置。

### 被拒绝时怎么看（403 / 401）

入口拒绝会直接返回 JSON，其中 `reason` 指明是哪条规则不合法，`hint` 给出可执行的处置建议，`request` 记录服务端本次实际看到的值（`host`、`remote`、`port`、`mode`、`expected`）：

| reason | 含义 | 处置 |
| --- | --- | --- |
| `entry-port` | 连接进入的端口或安全标志不属于本实例 | 旧标签页可能停在旧端口，重新打开本实例地址 |
| `remote-address` | 请求来源不是 `127.0.0.1` / `::1` | 代理需把请求转发到本机回环地址 |
| `host-count` | `Host` 头出现多份 | 多为代理或安全软件注入，改用直连或换客户端 |
| `host` | `Host` 不在本模式允许范围 | 安全模式只接受 `http://127.0.0.1:8483` 与 `http://localhost:8483`（端口以 `expected` 为准）；远程访问请用兼容模式 |
| `origin` / `referer` / `cross-site` | 写操作跨源 | 从同一来源打开页面再提交 |
| `secure-origin` | HTTPS 入口的 Origin 与 `secureOrigin` 不一致 | 检查 `network-mode.json` 的 `secureOrigin` |
| `cookie-duplicate` / `realm-mismatch`（401） | Cookie 重复，或会话属于另一个入口世代 | 每次启动都会更换入口代次，重新取一次本机入口代码 |

本机入口代码无效或过期时返回 `{"code":403,"msg":"需要有效的本机入口授权，或代码已过期、已使用"}`，重新运行 `RC2_Local_Entry_Code.bat` 取新代码即可（代码 90 秒有效、只能用一次）。

## 三、数据库管理（/admin/database）

基础模式提供标准加密备份与恢复；高级模式提供 v1.2.0 兼容导出、加密扩展伴随包与受控回程证明。

恢复是**整体替换**而不是合并：先做隔离代际预览，只展示版本、表数量、记录数、扩展与冲突计数等结构摘要（详情默认折叠），确认必须绑定真实校验结果与显式选择；预览过期、文件被篡改、身份错配或存在安全冲突时不得提交。执行切换会注销所有会话，切换前自动保留恢复点。

## 四、状态、日志与停止

- `RC2_Status.bat` 查看版本、模式、监听地址、应用与数据库状态；
- `RC2_View_Logs.bat` 打开本安装日志；
- `RC2_Stop_Server.bat` 只停止本安装、可核验 PID/启动时间/可执行路径的实例，不会按进程名或端口批量结束进程。

## 五、源码与开发

源码发行包包含 `backend/`、`frontend/`、`server-complete-rc2/`、`docs/`、`tools/`。开发机需自备 JDK 17 与 Maven：

```powershell
./server-complete-rc2/initialize-server.ps1 -Mode dev      # 独立开发库
./tools/build-complete-rc2.ps1 -MavenExe <本机 mvn.cmd>    # 离线全量测试并重建 JAR
```

IDEA 打开 `backend/pom.xml`，主类 `com.example.ems.EmsApplication`，运行配置载入本机生成的 `data/complete-rc2/dev/idea.env`（含开发凭据，禁止提交或分享）。开发与便携模式只允许安全本机入口。

## 六、边界

以下项目需要负责人按 `docs/RC2_ACCEPTANCE_CHECKLIST.md` 人工验收，自动化与本机测试不能替代：纯净 Windows 断网环境、真实第二设备、真实公网证书与实际 Frp 隧道、真实生产数据库迁移、IDEA GUI 操作。

本地候选不等于正式发布：未推送远程仓库、未建 Release、未切换公网。

## 七、文档

- 部署与调试：[docs/RC2_DEPLOYMENT.md](docs/RC2_DEPLOYMENT.md)
- 实现与验收台账：[docs/RC2_IMPLEMENTATION_LEDGER.md](docs/RC2_IMPLEMENTATION_LEDGER.md)
- 交付报告：[docs/EMS_v1.2.1_COMPLETE_RC2_DELIVERY_REPORT.md](docs/EMS_v1.2.1_COMPLETE_RC2_DELIVERY_REPORT.md)
- 人工验收清单：[docs/RC2_ACCEPTANCE_CHECKLIST.md](docs/RC2_ACCEPTANCE_CHECKLIST.md)
- 备份与恢复：[docs/备份与恢复.md](docs/备份与恢复.md)
- 已知边界：[docs/已知边界.md](docs/已知边界.md)
