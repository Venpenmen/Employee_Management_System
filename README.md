# EMS 员工管理系统

> 正式版 V1.0 · Spring Boot + Thymeleaf + MySQL 的前后端分目录员工管理系统

EMS 是一个面向企业内部的人力资源管理项目，提供员工、部门、职位、招聘、薪资、考勤、请假和操作日志等功能。V1.0 是项目首个正式发布版本，代码在仓库根目录中明确划分为 `frontend` 与 `backend` 两部分。

## 项目结构

```text
employee-management-system/
├─ frontend/                   # 前端页面与样式
│  ├─ templates/              # Thymeleaf HTML 模板
│  ├─ static/css/             # CSS 静态资源
│  └─ README.md
├─ backend/                    # Spring Boot 后端
│  ├─ src/main/java/          # Java 源代码
│  ├─ src/main/resources/     # 配置与数据库脚本
│  ├─ pom.xml                 # Maven 构建配置
│  └─ README.md
├─ launcher/                   # Windows 启停脚本实现
├─ 启动员工管理系统.bat        # Windows 一键启动
├─ 停止员工管理系统.bat        # Windows 一键停止
├─ 初始员工导入列表.xlsx        # Excel 导入示例
└─ README.md
```

前端与后端在源码层面分目录管理。构建时，Maven 会将 `frontend/templates` 和 `frontend/static` 自动复制到 Spring Boot classpath，最终仍输出一个可直接部署的应用，因此不改变原有页面路由、接口或启动体验。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.4.5、Spring MVC、JdbcTemplate |
| 前端 | Thymeleaf、HTML、CSS、原生 JavaScript |
| 数据库 | MySQL 8.0 |
| 文件处理 | Apache POI 5.4.1 |
| 构建工具 | Maven 3.8+ |

## 功能

- 工号密码登录、招聘后注册、个人资料与修改密码。
- 员工、部门、职位、招聘和离职管理。
- 高层、部门经理、普通员工三级权限控制。
- 单个招聘与 Excel 批量招聘。
- 薪资生成、调整、发布、查询和导出。
- 上下班打卡、迟到早退判定及考勤修正。
- 请假申请、撤回、分级审批和审批自动转交。
- 操作日志与系统数据字典。
- Windows 双击启动、后台运行、自动打开浏览器和日志记录。

## 权限概览

| 操作 | 普通员工 | 部门经理 | 系统高层 |
| --- | :---: | :---: | :---: |
| 查看个人资料与工资条 | ✓ | ✓ | ✓ |
| 查看同部门员工列表 | ✓ | ✓ | ✓ |
| 调整本部门普通员工职位 |  | ✓ |  |
| 跨部门调动与离职办理 |  |  | ✓ |
| 任命或更换部门经理 |  |  | ✓ |
| 管理部门、职位、薪资和日志 |  |  | ✓ |

个人档案中的身份证号、手机号、出生日期和工资等隐私资料仅本人及系统高层可查看。

## 环境要求

- JDK 17
- Maven 3.8+
- MySQL 8.0
- Windows 一键启动，或使用 IntelliJ IDEA / 命令行运行

## 快速开始

### 1. 配置数据库

打开 `backend/src/main/resources/application.yml`，按本机 MySQL 环境修改：

```yaml
spring:
  datasource:
    username: root
    password: "你的 MySQL 密码"
```

默认连接 `localhost:3306`。数据库 `ems_db`、数据表及演示数据会在首次启动时自动创建。

### 2. Windows 一键启动

双击仓库根目录的：

```text
启动员工管理系统.bat
```

启动器会寻找系统 Maven；若未找到，会继续尝试 IntelliJ IDEA 自带的 Maven。8080 端口就绪后，默认浏览器会自动访问 <http://localhost:8080>。

停止系统时双击 `停止员工管理系统.bat`。运行日志保存在根目录的 `logs/` 中。

### 3. IntelliJ IDEA 启动

1. 使用 IDEA 打开仓库中的 `backend` 文件夹或 `backend/pom.xml`。
2. 等待 Maven 完成依赖下载。
3. 修改 `backend/src/main/resources/application.yml` 中的数据库密码。
4. 运行 `backend/src/main/java/com/example/ems/EmsApplication.java`。

程序就绪后会自动打开浏览器。如果系统阻止自动打开，请手动访问 <http://localhost:8080>。

### 4. 命令行启动

```bash
cd backend
mvn spring-boot:run
```

### 5. 打包

```bash
cd backend
mvn clean package
java -jar target/employee-management-system-1.0.0.jar
```

## 演示账号

| 身份 | 工号 | 初始密码 |
| --- | --- | --- |
| 系统高层 | `260001` | `Admin123` |
| A 部门经理 | `260002` | `Manager123` |
| 普通员工 | `260005` | `Employee123` |

演示环境使用完成后请及时修改初始密码。

## Excel 导入

仓库根目录提供 `初始员工导入列表.xlsx`。导入文件必须保持以下列顺序：

```text
姓名、身份证号、出生日期、入职日期、部门名称、职位名称
```

同一份示例数据请勿重复导入。

## 常见问题

### 启动后网页没有自动打开

等待控制台出现启动成功信息，然后手动访问 <http://localhost:8080>。Windows 启动器的日志位于 `logs/launcher.log`，应用错误位于 `logs/application-error.log`。

### 8080 端口被占用

修改 `backend/src/main/resources/application.yml`：

```yaml
server:
  port: 8081
```

使用一键启动脚本时建议继续使用默认 8080 端口，因为启动器会检测该端口并打开对应网页。

### 重置演示数据

在 MySQL 中删除 `ems_db` 数据库后重新启动，系统会自动重建结构和演示数据。执行前请先备份需要保留的数据。

## 版本说明

### V1.0

- 作为项目首个正式发布版本。
- 在仓库根目录显式拆分 `frontend` 与 `backend`。
- 固化完整业务功能、权限规则和前端样式。
- Maven 构建自动整合前端资源，继续提供单应用运行方式。
- 调整一键启动脚本、构建路径、版本标识与项目文档。

## 开源说明

当前仓库未附带开源许可证。在添加许可证前，代码默认不授予他人复制、修改和分发的权利。如计划开放协作，请根据需要补充 `LICENSE` 文件。
