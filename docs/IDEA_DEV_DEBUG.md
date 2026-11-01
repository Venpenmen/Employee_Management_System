# IDEA 开发调试

使用完整源码ZIP，安装本机JDK17和Maven（免安装接收者不需要它们）。打开backend/pom.xml，使用本机缓存离线导入，缺依赖不自动下载。

1. 新完整工程执行 `初始化完整候选开发环境.bat`，此入口只管理data/complete/dev，并在空库本机设置首位高层；已有账号拒绝重复初始化。若只准备数据库供测试，PowerShell运行 `server-complete/initialize-server.ps1 -Mode dev -DatabaseOnly`，真实用户不要用测试seed。
2. IDEA运行配置选择com.example.ems.EmsApplication，工作目录为完整工程，JDK17，不设置portable/server Profile。载入data/complete/dev/idea.env的每个环境变量；该文件有本机随机密码，不要截图、粘到日志、提交Git或随发行包分发。可使用IDE支持的env文件方式或逐项环境变量方式。
3. 默认127.0.0.1:8482，EMS121N_COMPLETE_DEV Cookie；使用独立16606–16630范围MySQL实例及ems_next_dev_v121代际。URL/模式/安装标识/活动代际与MySQL PID身份绑定，连接前拒绝旧模式、历史端口和其他安装。
4. 设置断点，运行主类并访问页面；开发默认自动开浏览器。停止IDEA主类后，可执行 `server-complete/stop-server.ps1 -Mode dev` 关闭本安装的开发数据库。IDEA直接主类有application.lease，不与本机恢复并行；无状态app记录不代表没有开发主类在运行。
5. Portable8481和Server8483独立；停止Portable不应停止IDEA/其他模式。HTTP冲突明确失败，不改正在运行的实例。不要手动解除应用租约或复制其他版本凭据来绕过隔离。

自动化会执行相同默认主类及独立环境、HTTP/资料读写与Portable停止隔离；GUI点运行/断点体验由人工清单单列，不冒充已测。开发和发行使用同一源码，最终JAR对应版本/提交/输入SHA见build-manifest-complete.json。
