# 完整本地候选操作指南（RC2）

本文对应发行包标识 `v1.2.1-complete-rc2`（`app/version-complete-rc2.json` 的 `buildId`）。运行包只交付**服务器模式**入口脚本；源码开发使用的便携/开发模式不随运行包交付，需要时在源码目录用 `server-complete-rc2` 下的脚本加 `-Mode portable|dev` 自建。

## 1. 入口一览

| 文件 | 用途 |
| --- | --- |
| `RC2_Initialize_Server.bat` | 首次初始化：起本机 MySQL、建库、创建首个高层账号 |
| `RC2_Start_Server.bat` | 启动服务器实例（默认 `http://127.0.0.1:8483`），窗口不可关 |
| `RC2_Local_Entry_Code.bat` | 另开控制台取 90 秒一次性本机入口码 |
| `RC2_Network_Mode.bat` | 切换安全/兼容模式（需先停实例），保存后下次启动生效 |
| `RC2_Status.bat` | 查看实例状态（版本/模式/端口/进程/就绪） |
| `RC2_View_Logs.bat` | 查看本实例日志 |
| `RC2_Stop_Server.bat` | 停止本实例（只停本安装，不影响其它副本） |
| `启动端口监控.bat` | 实时端口/健康监控（`-Interval`、`-Once` 可选） |

## 2. 首次初始化

运行 `RC2_Initialize_Server.bat`，按提示填写：6 位工号、姓名、18 位身份证、11 位手机、出生日期、入职日期、密码（≥8 位，含字母与数字，两次一致）。输入「取消」可退出。库内已有有效高层账号时会拒绝重复初始化。

## 3. 启动与登录

1. `RC2_Start_Server.bat`，等到输出 `EMS v1.2.1 server ready: http://127.0.0.1:8483`。
2. **另开一个控制台**运行 `RC2_Local_Entry_Code.bat`，取得一次性入口码（90 秒内有效，只能用一次）。
3. 浏览器打开 `http://127.0.0.1:8483/local-entry`，输入入口码 → 跳到登录页 → 用第 2 步创建的账号登录。

每次启动都会更新实例 bootId 与入口代次，旧标签页里的会话作废，需要重新走一次 `/local-entry`。

## 4. 安全模式与兼容模式

先 `RC2_Stop_Server.bat` 停止实例，再运行 `RC2_Network_Mode.bat`：

- **安全模式**（默认）：只监听 `127.0.0.1`；本机 HTTP 访问需一次性入口码；远程只走原生 HTTPS。
- **兼容模式**：勾选确认后即可保存，**不需要预先填写外部地址**；允许经 Sakura Frp 等代理从任意域名与端口访问；如需由代理在外部终结 HTTPS，勾选「边缘 TLS」；登录、权限、CSRF、Host↔Origin 同源校验仍然生效。仅限虚构或脱敏数据（同源校验不防 DNS rebinding）。

窗口只写配置，不启停实例、不改 Frp / 防火墙 / 证书。切换后重启实例生效。

## 5. 被拒绝时怎么看（403 / 401）

拒绝响应用 JSON 说明原因，字段 `reason` 是判定码，`hint` 是处置建议，`request` 回显实际收到的 `host`、`remote`、`port`、`mode`、`expected` 与 `headers.origin` / `headers.referer` / `headers.fetchSite`。

常见码：

| reason | 含义 |
| --- | --- |
| `entry-port` | 请求打到的端口不是本实例的入口（旧标签页/旧端口） |
| `host` | 安全模式下 Host 不是 `127.0.0.1:8483` 或 `localhost:8483` |
| `host-count` | Host 头重复（多为代理或扩展改写） |
| `host-format` / `origin-format` / `referer-format` | 头格式不合法（带路径、多个端口、字面量 `null` 之外的不透明值） |
| `origin` / `referer` | 写请求的 Origin/Referer 与本请求 Host 不同源 |
| `cross-site` | 请求被标记为跨站 |
| `cookie-duplicate` / `realm-mismatch` | 入口 Cookie 重复或会话不属于当前入口 |
| `proof-*` | 本机入口码相关（`proof-expired` 过期、`proof-replay` 已用过、`proof-instance` 实例不匹配等） |

## 6. 数据、日志与状态

- 数据：`data\complete-rc2\<mode>`
- 日志：`logs\complete-rc2\<mode>`
- 运行状态：`server-complete-rc2\network-runtime.json`；网络模式配置：`server-complete-rc2\network-mode.json`

这些目录不得删除，也不得与旧安装混用。

## 7. 端口监控

根目录 `启动端口监控.bat`，或直接运行 `tools\port-monitor.ps1 -Interval 2`（`-Once` 只刷新一次，`-InstanceRoot` 指定实例目录）。监控只读：显示本实例预期端口、外来占用（含 PID 与启动时间）、RC2 端口段的扫描结果（含其它副本残留）、`/health` 与已建立连接数。

## 8. 受控旧版副本

运行包内 `compatibility/EMS_v1.2.0_Windows_Portable.zip` 带固定 SHA 校验，工具不读取外部旧安装，也不改旧程序源码。在解压根目录执行：

```powershell
.\tools\controlled-legacy-copy.ps1 -Action Create -BaseFile <base-v120.emsbk路径> -ExtensionFile <extensions.emsbk路径>
.\tools\controlled-legacy-copy.ps1 -Action Start -CopyId <返回编号>
.\tools\controlled-legacy-copy.ps1 -Action Status -CopyId <返回编号>
.\tools\controlled-legacy-copy.ps1 -Action ExportReturn -CopyId <返回编号>
.\tools\controlled-legacy-copy.ps1 -Action Stop -CopyId <返回编号>
```

副本默认 HTTP 8485，端口冲突直接拒绝。副本文件位于工具输出的受控目录，`CopyId` 格式为 `yyyyMMdd-HHmmss-<12 位十六进制>`，保留编号便于排障；它不是自动清理的临时目录，创建失败也会保留必要状态。秘密用本机 Windows 用户 DPAPI 保存，整个运行副本不能简单交给其他 Windows 账号。回程包位置由工具输出；请保存原 extensions 与口令，把返回的基础包及证明上传到新版。不要用普通旧导出替代回程证明。

## 9. 诊断命令

```powershell
.\server-complete-rc2\check-server.ps1 -Mode server
.\server-complete-rc2\view-logs.ps1 -Mode server
.\server-complete-rc2\check-network.ps1
```

## 10. 本机二级凭证恢复

停本安装的应用后运行 `.\server-complete-rc2\recover-secondary.ps1 -Mode server`。需要线下审批编号、目标高层与另一名独立有效高层的不同主密码；脚本先生成加密备份并做真实隔离恢复，再明确确认唯一请求号。缺独立复核、账号冻结/临时状态、备份失败或存在活动应用租约时会拒绝。只清目标二级凭证，不改主密码与角色。停机本身会结束浏览器会话。

## 11. 边界

- 不要把数据、日志、凭据、备份或员工 Excel 上传到外部服务。
- 出错时保留 CopyId / 任务编号与 `logs\complete-rc2\<mode>` 的日志尾部。
- 用 `SHA256SUMS.txt` 核对发行 ZIP；身份链以 `app\build-manifest-complete-rc2.json`、`app\version-complete-rc2.json` 与候选目录 `BUILD_INVENTORY.json` 为准。
- 本候选是**本地候选**，不是正式发布版本；公网隧道、真实第二设备、纯净断网 Windows 等仍需人工验收。
