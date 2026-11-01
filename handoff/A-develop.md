# EMS v1.2.1 A 角源码交付说明

本包是由既有工程整理出的 A（项目负责人）交付件，用于提交到代码仓库与课程验收，不代表三位作者的真实创作历史，也不等同于线上发行物。

- 代码基线：`employee-management-system_v1.2.1-next`，提交 `0a2178e`（feat: tolerate edge-terminated TLS in compat origin checks）。
- 版本标识：v1.2.1 完整候选（RC2），构建标识 `v1.2.1-complete-rc2`。
- 本次为**源码交付**：排除真实数据库目录、运行日志、凭据与运行状态、旧 JAR、`dist/`、`runtime/`、`.release-*`、`.codex-continuation/` 等过程材料。原 `README`、`SHA256SUMS.txt` 与发布说明是保留材料，不代表本包是原发行包；本包哈希见上级目录 `PACKAGES-SHA256.txt`。

## 一、A 的职责范围

| 领域 | 内容 |
| --- | --- |
| 工程入口与公共配置 | `EmsApplication`、`config/`、`WebConfig`、会话与升级隔离、`application*.yml`、`bootstrap/` |
| 数据库与升级 | `upgrade/`（导出、恢复、快照、兼容归档、维护入口）、`resources/schema.sql`、`db/legacy`、`db/migration`、`db/upgrade` |
| 网络与准入 | `network/`（入口隔离、会话隔离、动态同源准入、边缘 TLS 兼容） |
| 发布与部署 | `tools/`（构建、打包、冒烟、审计）、`server/`、`server-complete/`、`server-complete-rc2/`、根级 `.bat` |
| 测试与验收 | `backend/src/test`（离线全量 169 项）、`docs/` 验收与部署文档 |

按 v1.2.0 的分工包口径，前端页面交互由 B 负责、业务服务实现由 C 负责。为保证本包可独立构建并通过验收，这两部分源码一并收录，**归属不因收录而改变**；如需拆分提交，以 `frontend/**`、`service/`、`web/` 下的业务控制器为界。

## 二、构建与运行

前置：JDK 17（本机 17.0.12）、Maven 3.9.16，已缓存依赖时用离线模式。

```
cd backend
mvn -B -o clean verify
```

Maven 会把 `../frontend/templates` 与 `static` 打入 JAR，不需要 npm 构建。依赖缓存为空时去掉 `-o`。

本地开发运行（使用已准备的隔离数据库，禁止指向真实库）：

```
java -jar backend/target/employee-management-system-1.2.1.jar --spring.profiles.active=server
```

完整候选的运行入口是 `server-complete-rc2/start-server.ps1`，它会读取 `server-complete-rc2/network-mode.json` 并把网络策略展开成 `EMS_RC2_*` 环境变量；`network-mode.json` 的键白名单为 `format/mode/generation/httpsEnabled/httpsPort/secureOrigin/keyStore/trustCertificate/testOrigin/compatAcknowledged/edgeTls`。健康检查 `GET /health`。

## 三、本版新增（相对 v1.2.0 / 分工包）

1. 数据库三层管理：基础备份恢复、高级兼容导出与回程证明、隔离恢复与最终确认。
2. 双入口会话隔离：会话绑定启动实例、配置代际与 Connector，跨入口不继承身份。
3. 本机一次性入口码：有效期 90 秒，HMAC 绑定安装 PID、bootId 与配置代际，只保存摘要。
4. 动态同源准入：兼容模式不再写死外部地址；针对 Sakura Frp 这类外部终结 HTTPS 的隧道新增显式开关 `EMS_RC2_EDGE_TLS`（配置键 `edgeTls`），只忽略 http/https 协议差异，host 与端口仍须严格一致，伪造转发头与跨站请求继续拒绝。
5. 发布工程治理：构建前后源码输入校验、JAR 与清单 SHA256 一致性校验、证据保留规则、脚本 UTF-8 BOM 校验。

## 四、未验收边界

1. RC2 为本地候选，尚未推送远程仓库、未做正式发布。
2. 外部隧道场景下 HTTPS 由代理终结；`edgeTls` 只承认这一事实，**不构成传输安全证明**，不得据此存放真实生产数据。
3. 真实公网证书、纯净断网环境、真实第二设备、真实生产数据迁移均未验收。
4. 三人联调仍在同一台机器完成，跨设备并发与多用户真实使用尚未覆盖。
