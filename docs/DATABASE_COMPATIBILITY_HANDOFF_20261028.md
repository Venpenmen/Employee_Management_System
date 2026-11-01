# EMS v1.2.1 数据库兼容诊断与开发交接（2026-10-28）

## 现场事实
- 开发分支：feature/v121-test-network-mode。
- 当前有一处未提交的网络运行模式设置 BAT 修复，后续操作不得覆盖。
- 隔离测试实例：dist/network-mode-test/EMS_v1.2.1_network_mode_isolated。
- 导入失败任务：10bab680-0b4a-4c5a-95b4-7c7a92b76d23。
- 任务目录：隔离实例/data/portable/database-jobs/上述编号。
- 输入备份 input.emsbk 为 28778 字节；状态 FAILED；failed-target.json 标记 FAILED_RETAINED，活动数据库未切换。
- restore.log 仅显示 database-job.ps1 RuntimeException；不足以断定旧版格式不兼容。
- 不允许在原 RC2、当前隔离实例活动数据库上进行破坏性验证；保留失败任务与恢复目标。

## 源码发现
1. DatabaseJobs.run 捕获异常后只返回泛化消息与编号；invoke 对子进程非零退出只抛 Maintenance failed。
2. server/database-job.ps1 catch 只输出异常类型，不输出阶段与脱敏原因。
3. MaintenanceMain.diagnostic 已输出异常类型、SQLState、vendorCode 和 upgrade/Flyway 调用栈，但外层脚本丢失诊断细节。
4. Snapshot.inspect 校验备份格式 1、schemaVersion <=3、表名及 SHA256；Snapshot.restore 采用备份字段名直接 INSERT，字段未知会拒绝。
5. SchemaRegistry 内含 113.sql、120.sql、V1/V3 的结构定义，但这不等于已经实现旧版到新版的完整字段迁移。
6. Snapshot.write 的版本标记依据 flyway_schema_history，旧版无历史表时为 legacy-unversioned。
7. 数据库切换在 restore 预览和额外校验通过之后进行，失败目标保留供诊断。

## 给 Codex 的第一批任务（先 P0，再 P1）
- P0：以本任务的隔离备份和恢复日志为证据，复现真实失败原因；先修复子进程错误链丢失，记录阶段、脱敏原因、SQLState/错误码、建议、活动库状态。前端异步轮询及时显示。不得泄漏凭据、SQL 原文、敏感字段或绝对路径。
- P0：确认 v1.2.0 demo 备份的格式/结构差异；制定明确字段映射与默认值策略；旧版导入新版本在新建隔离 schema 中执行；先通过自动化测试，再执行任何实际恢复。
- P1：实现可审计的 v1.2.1 到 v1.2.0 兼容导出，必须区分旧版可读取的基础数据和扩展数据；若旧程序无法保存扩展数据，则采用伴随包或新版工具保管，禁止承诺仅靠旧版普通导出就能无损恢复。
- P1：双向往返测试包括旧版 demo -> 新版 -> 旧版 -> 新版，记录数、关系、账号、时间字段、新字段、旧版修改冲突，禁止无条件覆盖旧版修改。
- 安全约束：不关闭会话/二次验证，不直接修改原 RC2，不重启生产实例，不删除失败恢复目标，不提交任何密钥或真实数据。
- 交付：变更文件清单、单元/集成测试结果、真实故障根因、风险与回滚说明、独立候选包；在全部通过之前不开展 Frp 与账户新功能验收。

## 后续 Work 验收
独立审查迁移/降级/往返恢复及异常回滚，验证诊断信息脱敏与可读性，形成缺陷清单交 Codex 修复。

## Chat + RDC 后续验收
仅在独立测试实例验证本地导入导出成功，再验证双网络模式的 Frp 访问；随后验收单会话、账户风险警告与冻结、员工密码重置、二级密码操作。
