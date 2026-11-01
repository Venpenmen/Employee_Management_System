# EMS v1.2.1 开发指南

## 开发基线与运行范围

develop 分支当前承载 v1.2.1 的开发基线，替代此前的 v1.2.0 基线。v1.2.0 已作为发行版本保留在 main 分支与 v1.2.0 标签上，对应资料不随本基线改写。

本基线包含完整后端源码（87 个主源类）、51 个测试源文件、服务端页面模板与静态资源、本地运行与发布工具（launcher / server / server-complete / server-complete-rc2 / tools），以及 docs 下的技术与验收资料。

与 v1.2.0 基线不同，当前应用入口即为完整业务入口：数据源、Flyway 迁移与业务组件按 profile 装配，不再有“只启动框架控制器、数据库禁用”的裁剪模式。因此构建成功与健康检查通过仍不等于业务验收通过，涉及认证、授权、会话、数据库迁移与页面模型的判断必须逐项验证。

仓库不含 Java/MySQL 运行库、真实数据库目录、日志、私有状态与发行二进制；这些内容由本地初始化与打包工具在仓库之外生成。

## 目录结构

| 目录 | 用途 |
|---|---|
| backend/src/main/java | 应用入口 EmsApplication，以及 config / model / network / security / service / tools / upgrade / web 八个包 |
| backend/src/main/resources | 应用配置（application*.yml）、结构脚本 schema.sql、db/upgrade 与 db/migration 版本化迁移资源 |
| backend/src/test/java | 测试源码（51 个文件，覆盖 network、upgrade、web 等包） |
| backend/src/portable/resources | 便携运行配置来源 |
| frontend/templates | 服务端页面模板，由 Maven 作为后端资源打包 |
| frontend/static | 静态资源，随模板一并入包 |
| bootstrap | 干净数据库结构与运行库校验清单 |
| server | 服务器生命周期、升级、备份与恢复工具 |
| server-complete、server-complete-rc2 | 完整候选的初始化、启动、停止、状态、网络模式与入口码工具 |
| launcher | 构建、资源暂存、测试与发行校验工具 |
| tools | 完整候选的构建、打包、冒烟与发布审计脚本 |
| docs | 安装升级、API 权限、备份恢复、数据库兼容、网络准入、RC2 部署与验收等文档 |

config、security、service、web 之间存在类级依赖：调整模块边界时应检查调用方，不能只按目录删除类。

## 开发环境与构建

需要完整 JDK 17 与 Maven 3.9.16。在仓库根目录执行：

    mvn -f backend/pom.xml clean verify

本机依赖缓存齐全时可加 -o 进行离线构建。Maven 会把 frontend/templates 与 frontend/static 纳入后端 JAR，当前没有独立的 npm 构建步骤。产物为 backend/target/employee-management-system-1.2.1.jar。

## 自动化测试

v1.2.0 基线不包含测试源，v1.2.1 基线补齐了自动化测试：

    mvn -f backend/pom.xml test

按类定向执行（例如网络准入）：

    mvn -f backend/pom.xml test -Dtest=Rc2NetworkPolicyTest

2026-11-01 的离线全量构建记录为 169 项测试通过、跳过 0 项。测试结果随代码演进会失效，引用时应注明执行日期与提交；历史测试报告是历史证据，不能改写为本次执行结果。

## 入口模式与网络准入

服务只监听 127.0.0.1，外部访问由本机隧道或代理转发进来。入口模式二选一：

- 安全模式（默认）：请求必须来自本机回环地址，Host 必须是 http://127.0.0.1:<端口> 或 http://localhost:<端口>。
- 兼容模式：不写死外部地址，按请求自身的 Host 做同源判定，需要显式确认（compatAcknowledged），并配合本机一次性入口码使用。

针对 Sakura Frp 这类“代理在外部终结 HTTPS”的隧道，兼容模式提供边缘 TLS 开关（network-mode.json 的 edgeTls，对应 EMS_RC2_EDGE_TLS）。开启后 Origin 与 Referer 的判定只忽略 http 与 https 的协议差异，host 与端口仍须严格一致；转发头不被信任，跨站请求继续拒绝。该开关只承认“TLS 在应用之外终结”这一事实，不构成传输安全证明。

模式与端口由网络模式窗口（RC2_Network_Mode.bat）写入 network-mode.json，启动器读出后展开为 EMS_RC2_* 环境变量。应用不读取任何 X-Forwarded-* 头（application.yml 中 forward-headers-strategy: none）。

## 数据库与数据保护

业务库为 MySQL 8，包含两层能力：

- 基础层：整库备份与恢复，按实例归属操作。
- 兼容层：历史结构与数据的导入导出、回程证明、隔离恢复与最终确认；恢复采用整体替换，预览绑定真实文件摘要，过期或被篡改的归档一律拒绝。

数据目录（data/）、运行库（runtime/）、构建产物（dist/、app/*.jar）、日志与私有状态不进入仓库。涉及升级、导入或恢复的操作应先读 docs/UPGRADE_TO_v1.2.1.md、docs/备份与恢复.md 与 docs/RC2_DEPLOYMENT.md，并核对目标实例；不要对已有真实数据执行开发验证脚本。

## 候选包构建与发布流水线

完整候选包由四个脚本串联，均要求先设置 EMS_DEVELOPMENT_TASK_ID：

    $env:EMS_DEVELOPMENT_TASK_ID = 'RC2-P7'
    pwsh -File tools/build-complete-rc2.ps1   -MavenExe <mvn>
    pwsh -File tools/package-complete-rc2.ps1 -OutputDirectory <dir>
    pwsh -File tools/smoke-candidate-rc2.ps1  -CandidateDirectory <dir>
    pwsh -File tools/audit-complete-rc2.ps1

构建校验构建前后源码输入未变，并把 JAR、清单与 SHA256 写入 app/ 与 .codex-continuation/；打包生成服务器版与源码两个 ZIP，同时输出 SHA256SUMS.txt 与 BUILD_INVENTORY.json；冒烟在独立解压目录完成解压、初始化、首建账号、启动、健康检查、登录、服务端检查与停止共 9 项；审计核对条目、清单与迁移数量。

改动 JAR 后必须同步重建 app/build-manifest-complete-rc2.json，否则启动器会以“程序文件不完整或来自不同构建”为由拒绝启动。以上产物都留在本地，是否发布需要单独的验收与决策。

## 模块开发与验证流程

1. 修改前确认目标模块、接口契约与当前运行模式，阅读 docs/API与权限.md、docs/已知边界.md 及相关技术说明。
2. 保持 config、security、service 与 web 之间的类型依赖一致；接口变更同步更新调用方、模板与文档。
3. 在独立工作目录构建，确认 Maven 资源正确入包，并运行受影响的测试类。
4. 涉及数据库的验证使用新建隔离实例与合成数据，先确认地址、结构版本与恢复方案。
5. 涉及网络入口的改动同时验证安全模式与兼容模式，以及各类拒绝路径的诊断信息。
6. 记录实际执行过的检查、结果与尚未验证的范围；审查差异后正常提交，遵循现有分支与发布流程，不重写既有提交历史。

真实公网证书、纯净断网环境、第二设备与生产数据迁移属于各自的部署验收，冒烟与框架检查不能替代这些步骤。

## 版本与技术资料

README 的安装与发布章节，以及 RELEASE_NOTES_v1.2.1.md、RELEASE_VALIDATION_v1.2.1.md、docs/UPGRADE_TO_v1.2.1.md 与 docs 中 RC2 系列文档均予保留；v1.2.0 对应资料（RELEASE_NOTES_v1.2.0.md、RELEASE_VALIDATION_v1.2.0.md、UPGRADE_v1.1.3_to_v1.2.0.md）作为历史记录继续留在仓库中。

这些资料各自对应其版本与验证范围，不能据此推断当前开发基线具有完整发行物。SHA256SUMS.txt 中的校验值对应发行二进制而并非当前源码树；整理源码或构建通过本身，不代表创建或发布了 Release。
