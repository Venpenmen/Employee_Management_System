> **develop 分支：成员 A 的 EMS v1.2.0 开发基线**
>
> 本分支只包含 A 分工包：工程框架、公共配置/模型、必要依赖闭包、部分已实现功能以及服务器/发布工具源码。
> 当前 EmsApplication 是无数据库框架入口；完整前端、其余后端功能尚未在本分支交付。
> 下方原版安装/发布说明作为项目背景保留，不表示此分支已包含完整发行物或已发布 Release。
>
> **构建**：JDK 17 与 Maven，在 backend 目录执行 mvn clean verify。
> **框架运行**：在项目根运行 java -jar backend/target/employee-management-system-1.2.0.jar --server.address=127.0.0.1 --server.port=0 --ems.browser-auto-open=false。
> 从日志读取随机端口，访问 /health；预期 status=UP、mode=A-framework、database=DISABLED。
> 详见 [成员 A 交接说明](A_HANDOFF.md)。请勿为验证框架而连接真实 MySQL。

# EMS 员工管理系统 v1.2.0

基于 Spring Boot 3.4.5、Thymeleaf、MySQL 8 和 Java 17，提供员工与个人档案、部门职位、考勤、请假审批、工资及日志管理。三级角色为员工、经理、高层。本仓库提供 v1.2.0 正式版的安装、升级与开发说明。

## 普通 Windows 用户
下载 Releases 附件 `EMS_v1.2.0_Windows_Portable.zip`，核对 SHA256 后完整解压到普通用户可写的文件夹。内置 Java、MySQL 和运行库，无需安装 JDK/MySQL/Maven。不要直接在 ZIP 中运行。

- 双击“启动服务器.bat”：首次自动建立空业务库，在本机窗口输入高层资料与密码；输入“取消”退出，下次可继续。完成后等待显示访问地址，用浏览器登录。
- 服务器默认仅本机 `http://127.0.0.1:8283`，不自动打开浏览器。停止使用“停止服务器.bat”。
- 单机便携使用“启动员工管理系统.bat”，默认 8281，完成首次设置后自动打开网页；使用对应停止 BAT。
- 已初始化数据库不会重复创建高层。数据保存在各模式 data 下，停止后仍保留。
- 局域网测试须显式指定本机 IPv4；程序不修改防火墙、VPN、FRP 或系统服务。数据库管理仅允许直接本机 HTTP 或实际 HTTPS。

初始化只包含结构、字典和规则，无员工、演示账号或已知默认密码。服务器、便携、开发使用独立库、Cookie、状态、日志和凭据。MySQL 仅回环监听，独立端口 16506–16530；拒绝历史 8083/8183 及旧数据库地址。

## 旧版升级与可选示例
v1.1.3 用户按 [升级指南](UPGRADE_v1.1.3_to_v1.2.0.md) 操作，不能直接覆盖整个旧目录或复制运行中的 MySQL 数据。
`EMS_v1.2.0_DemoData.zip` 是独立可选的合成演示库，不自动安装。导入会整体替换当前模式数据库，先导出备份，详见 [演示指南](DEMO_DATABASE_GUIDE.md)。

## 源码与开发
GitHub Code → Download ZIP / Source code ZIP 是源码，不能当成免安装运行包。源码 ZIP 为 `EMS_v1.2.0_Source.zip`，不含 runtime、私有数据、日志或凭据。

使用完整 JDK17 与 Maven 3.9：
1. 从同版已校验便携 ZIP 取得运行库：`powershell -File launcher/prepare-runtime.ps1 -RuntimeZip 路径`。
2. `mvn -f backend/pom.xml test package dependency:build-classpath -Dmdep.outputFile=target/dev-classpath.txt`。
3. `powershell -File launcher/stage-app.ps1`。
4. “准备开发数据库.bat”创建独立开发实例；将 data/dev/idea.env 变量和 EMS_UPGRADE_ROOT=本工程路径加入 IDEA Run Configuration。
5. 打开 backend/pom.xml，以完整 JDK17 运行 com.example.ems.EmsApplication，默认回环端口 8282，不加 server/portable Profile。IDEA 图形调试限制见 docs/IDEA调试说明.md。

完整构建：`powershell -File launcher/build-release.ps1 -MavenExe Maven可执行路径`。脚本运行全部 Maven 测试、生成匹配 JAR/清单并按白名单打包。生成包不等于自动通过所有部署环境验证。
源码主要目录：backend（Java、模板、迁移和测试）、frontend（静态资源来源）、server（生命周期/升级/备份）、launcher（构建校验）、bootstrap（干净结构和运行库哈希）、app（构建清单及历史迁移）。

## 备份、恢复与常见问题
高层“数据库管理”每次需二次验证当前密码，导出使用独立备份口令；导入先预览、核对备份内高层账号，再确认切换。成功后重新登录。自设口令遗失不能解密；不要公开自己的备份或主机 DPAPI 文件。
- 端口占用：释放本版占用或显式选择空闲端口；不停止未知进程。
- 初始化取消/校验失败：不会创建半个账号，重新启动继续；已有数据不覆盖。
- 提示构建不一致：完整解压正确发行包，不关闭校验。
- 旧数据识别失败：保留现场，按升级指南检查；不要删除 data 强制新建。

更多版本信息请阅读 [v1.2.0 发布说明](RELEASE_NOTES_v1.2.0.md)、[安装与升级说明](docs/安装与升级.md) 和 [验收记录](RELEASE_VALIDATION_v1.2.0.md)。已完成的安装、升级及自动化验收不等于覆盖所有环境：纯净 Windows、真实第二设备、IDEA 图形界面及正式 HTTPS 部署仍需在实际使用环境中确认。公网访问需自行配置安全的 HTTPS 与访问控制，不应直接公开含真实员工敏感数据的测试服务。

开发工作目录中的 `.release-preserved`、`.release-evidence`、数据库、日志与凭据不属于公开交付内容。发布时应使用 `dist` 内按白名单生成的发行 ZIP 和对应 `SHA256SUMS.txt`，不要直接打包整个本地工程。
