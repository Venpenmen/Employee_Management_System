# Third-party components in EMS v1.1.1

The release contains the following unmodified runtime components. Their license and notice files are retained alongside the binaries.

- Eclipse Temurin OpenJDK JRE 17.0.20.1+1, Windows x64. GPLv2 with Classpath Exception and bundled third-party notices. License material: runtime/java/legal/ and runtime/java/NOTICE. Runtime checksums: bootstrap/runtime-sha256.json; upstream binary source: https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.20.1%2B1 . OpenJDK sources: https://github.com/adoptium/jdk17u .
- MySQL Community Server and client tools 8.0.46, Windows x64. GPLv2 and included third-party notices: runtime/mysql/LICENSE and runtime/mysql/docs/. Official distribution and corresponding source downloads: https://dev.mysql.com/downloads/mysql/8.0.html and https://downloads.mysql.com/archives/community/ .
- Microsoft Visual C++ x64 app-local CRT 14.40.33807. Supplied from the Visual Studio REDIST directory; redistribution terms are included at runtime/mysql/VISUAL_CPP_REDIST_LICENSE.txt. Documentation: https://learn.microsoft.com/cpp/windows/redistributing-visual-cpp-files .
- Spring Boot and application dependencies retain their original META-INF license/notice files inside app/ems-1.2.0-upgrade-v3.1.jar.

bootstrap/runtime-sha256.json records the SHA256 of every included runtime file.
This candidate contains no employee/account/business snapshot or host MySQL system accounts. Fresh business tables are created from trusted Flyway migrations; the first executive is created locally. User-created encrypted backups are not included in either public ZIP.

## 本修正版新增
Flyway Core和Flyway MySQL 10.20.1（由Spring Boot 3.4.5依赖管理固定），许可证与归属信息保留于依赖JAR的META-INF；发布JAR包含这些原始依赖，不剥离许可证。备份加密使用JDK JCA。
