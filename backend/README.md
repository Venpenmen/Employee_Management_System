# 后端（backend）

本目录保存 EMS V1.1.3 的 Spring Boot 后端、数据库脚本和 Maven 构建配置。

## 目录

- `src/main/java/com/example/ems/`
  - `web/`：页面与 API 控制器、登录拦截器、全局错误处理。
  - `service/`：员工、部门、薪资、考勤、请假、Excel 等业务服务。
  - `profile/`：完整个人档案（表单、校验、CSRF、服务与控制器）。
  - `portable/`：便携版生命周期接口（仅 `portable` 配置启用）。
  - `common/`：分页、列表返回条件、展示格式 `@viewFormats`、业务异常。
- `src/main/resources/application.yml`：开发配置（IDEA / `mvn spring-boot:run`）。
- `src/main/resources/application-portable.yml`：Windows 免安装包配置，端口、口令和实例令牌来自启动器环境变量。
- `src/main/resources/schema.sql`：原 12 张业务表。
- `src/main/resources/db/migration/profile-v1.1.1.sql`：个人档案 5 张增量表，可重复执行。

## 启动

先为 IDEA Run Configuration 或启动进程设置 `EMS_DB_URL`、`EMS_DB_USER`、`EMS_DB_PASSWORD`；密码没有默认值。URL 未设置时连接本机 `3306/ems_db`，用户名默认 `ems_app`。验收必须显式指定独立测试实例的 URL，不能使用业务库。IDEA 无需额外 profile；命令行从相同环境继承配置。

```bash
mvn spring-boot:run
```

或在 IntelliJ IDEA 中直接运行 `EmsApplication.main()`，无需额外 profile。开发启动会自动执行 `schema.sql` 和 `profile-v1.1.1.sql`（只新增不存在的表），共 17 张表。

## 构建与测试

```bash
mvn test                                    # 单元及 MVC 行为测试，不需要 MySQL
mvn clean package                           # 开发 JAR：employee-management-system-1.1.3.jar
mvn clean package -Pportable-release -DskipTests # 发布 JAR：不包含开发 application.yml；先单独运行默认配置测试
```

发布流程由仓库根目录 `launcher/build-release.ps1` 执行，详见根目录 README。

## v1.1.1–v1.1.3 后端变更

- **完整个人档案**：基本信息、联系方式、教育经历、工作经历、技能、证书、修改记录。
  - 本人可维护联系方式及扩展档案；姓名、身份证、工号、角色、部门、职位、入职日期、工资标准只读。
  - 高层可在员工详情中只读查看他人完整档案；经理和员工不能访问他人档案。
  - 联系方式使用版本号，旧页面提交返回 409，不覆盖较新资料；手机号不能与他人重复。
  - 档案表单校验 CSRF（`_csrf`），只绑定白名单字段；明细编辑、删除校验记录归属。
  - 写入与审计日志同一事务；审计只记录操作和字段名称，不记录联系方式、地址或个人文字。
- **员工列表分页**：20 / 50 / 100 条，总数与列表共用部门权限条件，页码越界自动修正，非数字参数返回 400；调动、调整职位、离职和详情返回原查询。
- **安全**：注册校验六位工号、身份证后六位、11 位手机号并清理首尾空格（密码保留空格）；登录成功轮换 Session ID，会话仅通过 Cookie 传递。
- **工资字段权限**：员工和经理的部门、职位选项及首页部门概览不返回工资字段；本人档案工资标准照常显示。
- **错误处理**：区分 400、403、404、405、409、500；未登录访问 API 返回 401 JSON；非预期异常只显示错误编号，详细信息写服务端日志。
- **展示格式**：模板通过 `@viewFormats` 统一日期、时间、人民币金额和上海时间问候；数据库数值、工资计算及 Excel 导出不变。
- **便携版**：`/internal/portable/status`、`/internal/portable/stop` 需携带启动器生成的 `X-EMS-Token`，仅监听 127.0.0.1。
