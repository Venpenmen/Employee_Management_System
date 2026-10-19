# EMS 员工管理系统 v1.1.3

> **develop 整合状态（2026-10-19）**：本次仅整合 A 负责的文档、启动器及发布/测试脚本。B 的前端和 C 的后端数据库改动尚未整合，当前源码不代表完整可运行的 v1.1.3；以下功能和验收记录描述的是原 v1.1.3 工程，尚未在本分支重新验收。现阶段请勿使用 BAT 或发布脚本验证当前工程。
>
> 本次不提交 `bootstrap/` 业务数据快照、运行环境或生成产物。完整便携发行包须待 B/C 整合并回归后，从获准分发的数据源重新构建；克隆本仓库不等于获得完整便携包。`bootstrap/` 保持忽略，不应通过强制添加将真实业务数据入库。


Spring Boot + Thymeleaf + MySQL 员工管理系统，支持 Windows 免安装分发和 IDEA 开发启动。

## 普通用户：解压即用

使用 dist/EMS员工管理系统_v1.1.3_Windows_x64.zip，完整解压到可写目录。

- 双击“启动员工管理系统.bat”：启动包内数据库及后端，就绪后自动打开网页。
- 双击“停止员工管理系统.bat”：关闭本包进程，保留业务数据。
- 无需安装 JDK、Maven、MySQL 或运行库，无需联网、管理员权限及环境变量。
- 支持 Windows 10/11 x64；每份解压包独立保存数据，默认仅本机访问。
- 保留发布时的业务记录及账号密码，首次启动导入，后续启动不会覆盖。

运行时使用 app/、runtime/、bootstrap/、launcher/；业务数据保存在 data/，日志在 logs/。
网页优先使用 8080，冲突时尝试 8081–8090；数据库优先使用 13306，冲突时尝试至 13320。
实际地址见 logs/launcher.log。首次启动需要等待数据库初始化，不能在 ZIP 内直接运行 BAT。
备份或移动目录前先停止系统，完整保留 data/。详见“便携版使用说明.txt”。

请分发 dist 中经验证的 ZIP，避免将开发目录中的运行日志、个人配置和使用后的 data 一同压缩。

## 开发者：IDEA 启动保持不变

1. 安装 JDK 17、Maven 3.8+ 和 MySQL 8.0。
2. IDEA 打开 backend/pom.xml，等待依赖加载。
3. 根据本机环境配置 backend/src/main/resources/application.yml。
4. 直接运行 EmsApplication.main()，无需额外 profile，默认连接本机 MySQL 并自动打开网页。

命令行开发启动：进入 backend，执行 mvn spring-boot:run。
普通开发构建：mvn clean package，产物为 employee-management-system-1.1.3.jar。
发布脚本结束时会恢复 target/classes 中的开发配置，不影响随后在 IDEA 直接运行主类。

源码按 frontend/ 与 backend/ 分目录，前端是 Thymeleaf 服务端模板，构建后合入同一应用。
IDEA 使用开发配置；BAT 使用 portable 配置及独立数据库。停止 BAT 不关闭 IDEA 或本机 MySQL。

## 发布者：重新生成免安装 ZIP

在开发机器执行 launcher/build-release.ps1。首次构建需要联网下载已固定版本且校验 SHA256 的 Java JRE，并由 Maven解析构建依赖；接收者运行无需联网。

示例（Windows PowerShell）：

    .\launcher\build-release.ps1 -MySqlHome "E:\MySQL\MySQL Server 8.0" -VisualCppRedist "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Redist\MSVC\14.40.33807\x64\Microsoft.VC143.CRT" -VisualCppLicense "C:\Program Files\Microsoft Visual Studio\2022\Community\Licenses\2052\Redist.txt"

MySQL 固定 8.0.46；Java 和运行库版本见 launcher/dependencies-lock.json。源组件目录不得包含修改后的非官方二进制。
源数据库默认 127.0.0.1:3306，root 用户，密码读取本项目开发配置；可通过 EMS_SOURCE_DB_PASSWORD 环境变量覆盖。
其他连接参数为 -SourceHost、-SourcePort、-SourceUser，源库名固定 ems_db。

发布期间暂停源系统业务写入及结构修改。脚本只读取源库，使用单事务逻辑快照；
核对导出前后行数，在临时独立数据库恢复并再次核对全部表，然后验证网页可访问及正常停止。
验证失败不生成新 ZIP。临时源库连接凭据在 finally 中清除，不进入发布包。
发布 JAR 不包含开发 application.yml 中的数据库凭据，数据快照不包含宿主 MySQL 系统账号。

输出 ZIP 和 SHA256 文件在 dist/。包内保留第三方许可证、运行库文件哈希清单和快照校验信息。
.release-work/runs/ 保存本次工作目录，完成后默认回收；精简诊断证据保存到 .release-evidence/，均不参与 ZIP。
重新发布会导出当前本机开发数据库；不会自动合并便携版中产生的数据。

## 功能

三级角色（高层、经理、员工）；招聘、Excel 导入、员工调动和离职；部门、职位与经理任免；
薪资生成、调整、发布及导出；考勤打卡与修正；请假申请、撤回与审批；操作日志和数据字典。

演示初始账号：高层 260001 / Admin123，经理 260002 / Manager123，员工 260005 / Employee123。
如果当前数据库中已修改密码，以原账号实际密码为准。

## v1.1 免安装分发（历史）

新增附带运行环境和数据库的 Windows 免安装包、独立 portable 配置、数据快照恢复、
端口冲突处理、实例身份及就绪验证、正常启停和可重复执行的发布流程。
保持 IDEA 默认开发启动；保留 v1.0 业务功能，本次不改变薪资及考勤业务规则。

中文路径兼容：运行时临时占用一个 G:–Z: 空闲盘符，正常停止后自动移除。该盘符指向解压目录，不复制数据、不安装服务，也不修改已有盘符。

## 回归验收

发布 ZIP 生成后，可执行 launcher/test-portable.ps1 -OpenBrowser。在中文及空格路径中解压独立副本，清除开发工具 PATH，测试端口冲突、首启中断、业务流程、异常恢复及移动后重启。
业务测试只修改临时副本；默认使用现有高层账号的 Admin123 密码，若已变更，可传 -AdminPassword。测试副本保留在 .release-work 供诊断。

## v1.1.1 完整个人档案（历史）

个人资料已扩展为：基本信息、联系方式、教育经历、工作经历、技能、证书、修改记录。
员工、经理和高层均可维护自己的联系方式及扩展档案；经理不能访问他人完整档案，高层可在员工详情查看只读档案。
姓名、身份证、生日、工号、角色、部门、职位、入职日期和工资标准保持只读。身份证脱敏显示。
本版不提供头像、附件上传或重要信息变更审批。

联系方式保存有版本检查；若其他页面已保存，当前页面会提示刷新，不会覆盖较新资料。
所有新表单校验 CSRF，明细编辑和删除校验记录归属，日志仅记录变更字段名称，不写入联系人、地址等完整内容。

数据库升级：
开发启动自动执行 db/migration/profile-v1.1.1.sql；便携启动在后端前使用包内管理账号执行同一份增量建表 SQL。
只新增五张表，旧记录缺失扩展档案时显示未填写；原有员工、工资、考勤、请假等表不重建。
旧版便携用户：先停止并备份完整旧目录，再以新版 app、launcher、runtime 及 BAT 替换程序文件，完整保留旧 data。
不要删除或覆盖旧 data，不要通过重新导入 bootstrap 快照升级已有业务数据库。
新用户使用 dist 中 v1.1.2 ZIP，解压后直接启动。
发布脚本按全部实际业务表导出和核对快照，源库仍为旧版十二张表时也可打包，首启会自动创建扩展表。
## v1.1.2 体验优化与稳定性修补
新增员工分页、档案防丢失和提交保护、统一显示格式、首页快捷入口与待审批数量；加强注册校验、会话轮换、工资字段权限和错误提示。详见 更新说明_v1.1.2.md。无新增数据表，保留 profile-v1.1.1.sql 历史文件名。

## v1.1.3 产物治理
统一发布入口继续为launcher/build-release.ps1，必需的MySQL与VC运行库参数不变。
现在每次执行Maven、候选包、HTTP、浏览器、Excel及开发兼容性检查，再发布正式ZIP。
开发工具通过PATH发现，可传-MavenExe、-JavaExe、-NodeExe、-BrowserExe覆盖；浏览器自动探测Chrome/Edge。
新任务统一在.release-work/runs/；成功失败默认均只保留.release-evidence/中的精简证据。
-KeepArtifacts显式保留完整副本；停止失败或文件占用时保留并报告，不强删。
退出码0完成、1主要任务失败、2主要任务成功但清理未完成。证据默认最多20次/200 MiB，单次10 MiB。
单独运行launcher/test-portable.ps1同样使用独立上下文并执行完整验收，不依赖旧browser-info或残留副本。
历史版本压缩包和SQL备份保留；真实data、运行库与源码不属于垃圾清理范围。
详见更新说明_v1.1.3.md、验收记录_v1.1.3.md及空间审计报告_v1.1.3.md。

空间只读报告：运行 launcher/report-space.ps1；加-RequireTarget时要求工程≤1.5 GiB且比冻结基线减少至少60%。
中断任务默认只报告。显式恢复时使用artifact-common.ps1中的Recover-ArtifactRun，只有旧所有者已退出且路径/锁校验通过才可接管；不可按目录名清理。
