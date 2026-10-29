# IDEA调试
1. 使用完整JDK17打开backend/pom.xml，Maven和测试JRE也选完整JDK，不能使用runtime里的JRE运行Mockito测试。pom使用显式Mockito agent，不增加skipTests。
2. 准备开发数据库.bat为dev建立独立实例、Flyway结构及首位高层。应用不持有管理员DDL权限。
3. 在本机Run Configuration使用data/dev/idea.env中的EMS_DEV_DB_PORT、EMS_DEV_DB_PASSWORD等配置；如有active-schema，还需EMS_DEV_DB_NAME指向本模式当前数据库。环境文件不能提交或打包。
4. 运行com.example.ems.EmsApplication，不加server/portable Profile。默认127.0.0.1:8282、Cookie EMS120U_DEV，默认打开网页；EMS_BROWSER_AUTO_OPEN=false可关闭弹窗。
5. 数据库管理任务需要EMS_UPGRADE_ROOT为本工程绝对路径；缺安装上下文时拒绝管理操作。调试断点可设在SessionService、DatabaseAccess、Snapshot；不得打印密码、原始令牌或Session ID。
6. 手工IDEA进程不被脚本自动停止。要验证整库切换，使用server/start-server.ps1 -Mode dev由运行器启动主类；手工Debug环境可测试导出/预览，切换需停止手工实例后交由运行器。
7. 用本版数据库和端口，不读取旧版凭据。三模式可并行；停止便携不能停止服务器或dev实例。
IDEA界面实际点击Debug及真实设备测试须单独记录，脚本主类启动不冒充GUI验收。

源码ZIP首次使用：运行Maven test package dependency:build-classpath -Dmdep.outputFile=target/dev-classpath.txt后，再运行launcher/stage-app.ps1准备匹配的JAR与构建清单；运行库取自同版运行包。
