# 前端（frontend）

本目录保存 EMS V1.2.0 的界面资源，使用 Thymeleaf 服务端模板。

## 目录

- `templates/`：Thymeleaf HTML 页面与公共片段。
- `static/css/`：全局样式文件。

前端资源由 `backend/pom.xml` 在构建时复制到 Spring Boot 的 classpath，无需单独安装 Node.js 或启动前端开发服务器。
