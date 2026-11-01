# 数据库兼容第一批 P0 结果与候选交付

日期：2026-10-28。当前网络模式基线73697ab；未覆盖用户原有未提交BAT与交接文档。范围仅为诊断和旧库向新版升级，不实现降级或扩展字段往返。

## 根因与实现

10bab680-0b4a-4c5a-95b4-7c7a92b76d23对应的演示备份合法，原失败点为department金额字符串摘要：`5000`写入DECIMAL后读回`5000.00`，旧代码误判内容不同。缺失明细ID及原生旧备份ISO时间表达也需显式兼容。底层错误被子进程和脚本包装后丢失，只剩RuntimeException与编号。具体复现、字段策略及失败边界见DATABASE_COMPATIBILITY_P0_20261028.md。

修改：

- RestoreMapping.java：可信目标类型、严格精度、日期时间、必填/默认及允许生成ID的明细白名单。
- Snapshot.java：原始文件SHA复核，逐行参数化导入/按实际主键读回、字段精确比较、语义摘要和映射报告；行数与关键业务关系校验保留；回滚/收尾不覆盖主异常。
- JobDiagnostics.java、MaintenanceMain.java：统一阶段/安全错误码/SQLState/vendorCode/异常类别链/建议与活动库状态，failure.json及result.json映射记录；不输出SQL或记录值。
- DatabaseJobs.java、server/database-job.ps1、frontend/static/js/database.js：子进程诊断贯穿异步状态与页面，切换失败状态不谎报UNCHANGED；自动回退记录按实际持久表数量统计。
- 新增RestoreMappingTest、LegacyDemoImportIT及test-compatibility-p0.ps1，扩展原数据库HTTP测试；更新打包白名单、README、升级说明和进度索引。

## 实际执行证据

证据目录均为`.release-evidence/1.2.1/<任务ID>/`，不是设计预期。

|检查|结果|证据|
|---|---|---|
|最终完整Maven test/package|67项，失败/错误/跳过0，构建成功|compat-p0-final-build.log、backend/target/surefire-reports|
|修复前真实复现|用保留旧JAR、校验SHA后在新schema复现department错误|20261028-230812-0b8a58e6eb8e/demo-compatibility.log|
|同一官方demo→新版|17表/48员工/40账号，原字段、哈希、状态保留；明细生成ID有审计|20261028-233315-028b56078679/demo-compatibility.log、field-mapping-results.json|
|原账号及三角色|40个原演示账号实际认证/事务签发会话/条件注销，覆盖员工、经理、高层|同上；没有登录真实管理员或原服务|
|新版导出再恢复新版|升级后的备份在另一新schema恢复成功，关联校验通过|同上；不是新版向旧版降级|
|历史113/120兼容|原有30项检查通过，保留账号、金额、档案、自增及结构拒绝|20261028-232235-9c963334eb0d/demo-compatibility.log|
|Server/Portable迁移|每模式111项＋3项桥接，真实事务22项通过|20261028-230925-ae780d4fa17b，含rc2-migration.log、portable-rc2-migration.log、rc2-transactions.log|
|受影响完整业务回归|通过；HTTP112、业务28、数据库HTTP60、会话HTTP38、Chrome及Excel检查|20261028-225851-69e98ceb6080|
|跨进程诊断及隐私|Java→PowerShell→异步状态传递原因/阶段；任务绑定、UNCHANGED；口令/URL/路径排除|同任务database-http-results.json，新增3项实际HTTP断言|
|精度/时间/未知列/缺主键/诊断注入/收尾失败|均通过，没有移除断言或跳过测试|RestoreMappingTest，包含在67项中|

完整业务回归验证切换/失败回退、真实broker中断恢复、三模式、端口冲突、中文空格目录和持久化，Excel3条工资/30个数值单元格保持精度。最终收尾保留主异常的小修另由专门单测及最终迁移故障注入覆盖；不声称每次小修都重跑完整浏览器回归。

### 已发现并修复/重测的失败

- 首轮新时间类型解析拒绝旧库LocalDateTime的ISO T表示，隔离历史测试失败；补充无损解析和省略秒断言后30项通过。
- 三套隔离任务并行首次初始化竞争16607端口，其中一套安全失败。未停止占用者或改变端口，改为串行运行，最终迁移通过；该并发分配限制仍记录。
- 原始错误复现及所有失败摘要保留，不把失败记录改写成通过。

## 发行与保护

本批交付：`dist/database-compat-p0-20261028-final/`，包含便携、服务器、源码ZIP及SHA256SUMS。buildId保持rc2/schema3，本批身份由独立目录、源码提交与JAR/ZIP哈希绑定，不覆盖原包。用户原BAT工作区修复随候选保留但不代为提交，已记录原文件哈希。

最终包解压结果在冻结ZIP后写入该目录`PACKAGE_ACCEPTANCE.json`；没有该记录或passed不为true时不能仅凭本报告宣布最终包验收通过。包内本报告不预报尚未执行的最后解压结果。源码与构建输入回执、迁移原始哈希和当前候选文件哈希分别核验。

原10bab任务input/status/failed-target文件哈希保持，目标库未连接或删除；保留历史ZIP，未触碰活动数据与FRP。保护清单位于`.release-evidence/compatibility-p0/protected-files.json`。所有结束的测试实例仅按自有身份停止/回收。

## 风险与验收边界

已验证原官方合成演示备份及登记旧结构，不承诺未知定制列/表可导入。大规模性能、真实用户备份抽查及生产切换尚未实测；没有为获得通过放松精度、关联、会话或二次验证。并行首次数据库初始化仍建议串行执行。继承的内部测试网络模式不是生产安全承诺，本批不改Sakura配置、不执行公网或新账户功能验收。

当前为本地独立候选，最终包状态以附属记录为准，不自动正式发布。下一阶段建议先独立复核本批诊断/升级/回滚与真实旧备份隔离预览；通过后再确认P1降级、扩展伴随包及双向修改冲突策略，不提前承诺无损往返。

## 发行前补充审查

发现并修复了既有create_time回填可能触发ON UPDATE改写历史update_time的问题，显式保留update_time原值；最终demo测试增加48条历史时间（2001年）的真实恢复断言。首次审查包保留在dist/database-compat-p0-20261028，标记REJECTED.json，不能交付使用；最终目录见文首-final独立目录；r2同样保留为审查中未放行产物。最后这一条回填SQL修复由最终67项测试、最终demo和历史113/120定向复测覆盖，不以未触及该分支的之前MIG总数替代。

补充路径保护：诊断写入和维护标记清理仅在任务路径与锁归属验证完成后执行，避免错误参数把failure.json写到任务边界外。最终任务20261028-233315-028b56078679验证越界哨兵零修改、合法任务错误诊断仍为BACKUP_INVALID/decrypt/UNCHANGED，并再次通过完整demo、40账号及历史时间检查；见child-diagnostics-results.json。

## 冻结后最终验收与结论

最终源码提交 **edc6923**，包括ea33300的升级/诊断实现与677a980的历史时间保留修复。JAR与134项主编译文件/资源逐项一致，V1/V2/V3未改。工作区原有BAT修改和未跟踪交接文档保留，不代为提交。

最终交付目录：**dist/database-compat-p0-20261028-final**。便携/服务器包各133,440,990字节，源码包420,687字节；两运行包同载荷，通过不同入口选择模式。SHA256SUMS.txt与PACKAGE_ACCEPTANCE.json并列；本补记不回写已冻结ZIP，包内报告要求的附属验收记录已存在且通过。

最终包任务 **20261028-233758-9176591bb568** 返回0，两包中文/空格解压、初始化取消后重试、重复初始化拒绝、实际登录、CSRF页面、正常停止和移动后重启通过，临时实例全部回收。

额外以**实际候选JAR＋PowerShell维护链**完成原错误同一密文的真实导入：任务 **20261028-234459-32eb9f8436ea** 返回0，48员工/40账号进入PREVIEW_READY，原演示高层凭证验证成功，测试当前库员工仍为0，**没有执行commit切换**。可复核脚本保存在.release-evidence/compatibility-p0/verify-demo-pipeline.ps1，仅创建自有夹具；结果见该任务demo-pipeline-results.json。首次补充验收的测试脚本漏挂MySQL中文路径别名，查询步骤失败；补齐既有Mount-EmsNativeRoot调用后重测通过，产品代码未为此修改，失败证据保留。

本批P0在已定义的本机隔离范围内验收通过，未发现未解决的本批发布阻断问题。真实数据/大规模性能/生产切换未验证，未知定制字段仍拒绝；不等同正式发布批准。下一阶段仅建议独立审查P0及真实旧备份隔离预览，通过并由用户确认后再开展P1；本次没有实现降级或扩展往返功能。
