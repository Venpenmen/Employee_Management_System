# 后端（backend）

本目录保存 EMS V1.0 的 Spring Boot 后端、数据库初始化脚本和 Maven 构建配置。

## 目录

- `src/main/java/`：控制器、业务服务、模型和系统配置。
- `src/main/resources/application.yml`：端口与 MySQL 连接配置。
- `src/main/resources/schema.sql`：数据库结构和初始化数据。
- `pom.xml`：依赖、编译与前端资源打包配置。

## 独立启动

```bash
mvn spring-boot:run
```

也可以在 IntelliJ IDEA 中运行 `EmsApplication.main()`。
