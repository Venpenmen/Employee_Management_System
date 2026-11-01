# EMS v1.2.1 完整本地候选测试台账

实际执行2026-10-29；最终源码、JAR与发行SHA见构建清单。统计分场景，不把重复Maven运行相加。DONE仅针对已定义本地门禁；正式部署另需人工验收。

|阶段|实际执行结果|证据|
|---|---|---|
|S0|断点、故障模拟、资源边界已验收|s0结果与STATE引用|
|S1|登记版本精确映射及密文扩展已验收|s1-results.json|
|S2|真实旧HTTP33、跟踪SQL32、冲突6/故障5、完整用户工具往返|s2-module-results.json|
|S3|HTTP52、Chrome9；SQL证据清理缺口修复后重验|s3-module-results.json|
|S4|原生TLS/独立HTTPS代理13、同库切回local4、现有Private虚拟接口本机11|s4-module-results.json|
|S5|完整Maven140；恢复SQL49、实际控制台读回23；安全SQL22、HTTP47、Chrome15|s5-module-results.json|
|S6|业务HTTP112、扩展28、恢复52、关联SQL15、3工资条30金额逐值一致|s6-module-results.json|
|S7|Server/Portable迁移各111，真实切换故障60、日志恢复10、资源安全26、证据/模块环境6；最终离线Maven140，0失败/错误/跳过|s7-module-results.json|
|S8|结构10+HTTP80，实际旧程序33/完整回程；三模式9+HTTP87及独立停止/持久化通过|s8-module-results.json|
|S9|归档前226保护/198源码/9迁移/1870条目通过；最终回执独立生成|preflight-audit-results.json|

## 失败及纠正

- 历史Mockito问题与普通开发缺陷分别保留原日志；最终全量不得跳过测试或断言。
- SQL夹具曾先写安全行再建账号违反FK，纠正夹具顺序后49项通过；未改变真实约束。
- 首次Chrome下载策略缺上下文，修正后真实9项通过。
- 故障重启测试Node/PS7继承模块路径导致WinPS内置Get-FileHash不可用；逐次失败均留证据，子进程按大小写无关移除路径后重测，不改系统策略。
- 证据清理器未识别模块内SQL引用导致一份历史SQL证据被回收；检查点拒绝继续把它当通过，增加引用保护并重验。收尾并发更新引用被断点验证拒绝，已按命令退出后重新核验，今后不并发变更状态/证据。

## 未执行/人工限制

真实Sakura Frp、生产切换、真实第二设备、纯净Windows标准用户和IDEA GUI未自动执行。已实际执行独立Chrome上下文/TLS及本机接口，不冒充公网或双设备。待人工项见COMPLETE_MANUAL_ACCEPTANCE.md。

最终归档审计通过：226保护、198源码、9迁移、1875条目、992实测载荷文件相同；证据ACCEPTANCE_EVIDENCE.zip，回执FINAL_ACCEPTANCE.json。完整本地候选通过，人工限制未冒充通过。
