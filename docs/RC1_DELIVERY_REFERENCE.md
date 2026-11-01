# EMS v1.2.1-next 最终交付报告

结论：本地完整候选就绪；没有公网发布，没有操作远程Git。外部环境验证未完成，不等于正式上线验收通过。

## 完整成果

工程employee-management-system_v1.2.1-next保留全部后端/前端、员工/部门/考勤/请假/薪资/个人档案、三级权限、Flyway V1/V2、数据库维护、加密备份恢复和代际切换、初始化/启停/测试/打包及三模式。仅继承指定当前本地120，未声称多人最终整合。

新增修复见CHANGELOG及V121_IMPLEMENTATION_SCOPE：隔离身份、模式绑定、LocalSetup安全目标与密码字节边界、事务摘要当前读、注销finally、本人写入guardPersonal、终态切库日志PID异常及PowerShell编码。没有新增业务表、公共接口、JWT或设备指纹。

## 构建与证据

基线40项、最终46项Maven通过；真实MySQL会话26项、Servlet14项、HTTP业务112项、扩大业务28项、数据库维护57项、会话HTTP38项、会话浏览器7项。完整集成三模式、故障恢复、移动重启、备份恢复及Excel通过；恢复定向10项通过；两份最终ZIP独立解压、取消/初始化/实际登录/停止通过。细节及失败历史见TEST_REPORT和.release-evidence。

原120冻结的189个文件最终SHA256一致，未连接原库或启停旧服务，旧121仅只读参考。Git只在新目录本地提交。完整源资产未遗漏（仅不复制历史login.html.backup）。

## 交付位置

- app/ems-1.2.1-next-rc1.jar
- dist/EMS员工管理系统_v1.2.1-next_便携版_Windows_x64.zip
- dist/EMS员工管理系统_v1.2.1-next_服务器版_Windows_x64.zip
- dist/EMS员工管理系统_v1.2.1-next_源码.zip
- dist/SHA256SUMS.txt，以及根目录SHA256SUMS.txt

运行包包含相同完整能力，区别为推荐入口；空业务库、无固定账号/密码。运行库独立且全部校验，运行不依赖原工程。源码包需本机开发依赖，可从匹配运行包校验提取运行库。

## 未验证与边界

真实双设备、公网FRP异常根因、HTTPS/等效加密、纯净无依赖Windows标准账号及IDEA GUI未实测。dev主类启动已验证，IDEA配置见IDEA调试说明。未进行大规模压力或所有跨业务死锁形式化验证；同Cookie切换账号的全部并发交错未穷举，旧页面无主动关闭。

历史关闭时监听器可能留下清理警告；Servlet会话不持久化，重启旧Cookie拒绝测试通过。已发生两次故障重启失败后定位修复，失败证据保留，不伪称首轮通过。

## 续工与正式发布

用户可按README独立初始化和启动。候选未替换旧版本。正式发布前另行完成真实网络与安全访问控制验证并取得发布授权。源代码提交与构建输入哈希保存在app/build-manifest.json及.release-evidence/rc1/build-receipt.json，最终文档提交通过git log查看。
