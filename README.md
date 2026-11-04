# EMS 员工管理系统 v1.2.1

本仓库包含 EMS v1.2.1 的集成源码。当前 main 和 develop 均已合并至 v1.2.1，但**源码合并不代表 Windows 发行包已经通过全部验收**。

## 项目结构

- `backend/`：Spring Boot 后端源码。
- `frontend/`：前端源码。
- `server-complete-rc2/`：v1.2.1 RC2 服务器管理脚本。
- `server/`：旧版服务器脚本，保留历史兼容用途；**不得直接用作 RC2 启动入口**。
- `docs/`：部署、升级、安全和验收文档。

## 构建和测试

需要 Java 17、Maven 和 MySQL 8。数据库迁移由 Flyway V1～V3 管理。

在仓库根目录运行：

```powershell
mvn -f backend/pom.xml test
mvn -f backend/pom.xml package
```

构建生成后端 JAR，但它不是独立的 Windows 免安装发行包。

## RC2 运行要求

RC2 脚本需要与构建一致的 `app/ems-1.2.1-complete-rc2.jar`、`app/build-manifest-complete-rc2.json`、Java/MySQL 运行库和完整目录结构。以上二进制文件不随源码仓库提供。

RC2 默认仅在本机回环地址提供服务，默认 HTTP 端口为 8483。安全模式和兼容模式的使用方法参见部署说明。兼容模式仅适用于虚构或脱敏数据，切勿直接公开真实员工信息。

## 安装与升级注意事项

**不要直接覆盖正在运行的 v1.2.0 安装目录或数据库。** 升级前应完成备份、隔离恢复和回滚准备。

- [RC2 部署说明](docs/RC2_DEPLOYMENT.md)
- [v1.2.1 升级指南](docs/UPGRADE_TO_v1.2.1.md)
- [安全运行说明](docs/SECURITY_OPERATIONS.md)
- [版本更新说明](RELEASE_NOTES_v1.2.1.md)
- [集成验证记录](RELEASE_VALIDATION_v1.2.1.md)

## 当前验证状态

前后端源码已整合，数据库任务 API 兼容问题及考勤、请假个人会话检查已修复。Maven 自动化测试通过，但完整 Windows 首次安装、首次高层账号初始化、登录、真实数据升级和回滚、外网 HTTPS/FRP 等流程仍未全部完成验收。

**GitHub main 已包含 v1.2.1 源码，但尚未创建正式 v1.2.1 Release。**

请勿将本地候选 ZIP 误认为已通过正式发行验收的成品。不要向公开仓库提交密码、密钥、数据库备份、运行数据或日志。
