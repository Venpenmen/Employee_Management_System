# 数据库兼容第一批 P0：诊断、单向升级与独立候选

## 范围和保护

执行依据：DATABASE_COMPATIBILITY_HANDOFF_20261028.md 及用户本轮明确要求。基线为73697ab，保留网络模式功能及用户尚未提交的「网络运行模式设置.bat」修复。未修改原RC2安装、活动数据库、Sakura FRP或历史发行ZIP；没有重启现有实例。测试仅连接任务新建的MySQL，不读取旧安装凭据或连接旧活动库。

本批不开发 v1.2.1→v1.2.0 降级、扩展字段伴随包或往返合并。不新增业务表/迁移，V1/V2/V3原文件不变；保持EMSBK001和逻辑恢复至新schema的架构。

## 10bab680-0b4a-4c5a-95b4-7c7a92b76d23 的真实根因

原失败任务input.emsbk为28,778字节，SHA256为e94cb6f0be8d56c0750d58839af42bccd737600591eca74456cc0cd8ddd2c582，与保留v1.2.0官方DemoData.zip中的备份相同。状态FAILED，目标FAILED_RETAINED；没有改写原任务文件或连接该目标数据库。

在新隔离schema用修复前代码实际复现，错误为 `Restored primary keys or field digest mismatch: department`，证据任务20261028-224459-144ae012b181。备份格式1、schemaVersion2、programVersion1.2.0合法，所有原始表文件SHA有效，不是单凭版本号判定不兼容。

演示备份将字段描述为VARCHAR，并以字符串保存金额，如`5000`；目标DECIMAL读回为`5000.00`。旧校验直接拿读回序列化摘要与原始字节摘要比较，数值相等但字符串不同即失败。该备份还省略部分明细主键；旧代码按employee_id排序校验，不能保证同一员工多条明细的顺序。原生旧库备份还可能用LocalDateTime的ISO `T` 和省略秒表示时间，必须与SQL日期时间按类型精确比较。

诊断丢失链为：Java抛出具体错误→Invoke-EmsTool包装子进程stderr→database-job.ps1仅保留RuntimeException→DatabaseJobs仅返回Maintenance failed和编号。因此原restore.log不足以定位数值差异。没有把泛化日志当作数据损坏证据。

## 单向字段映射和默认值

所有已支持字段均**同名映射**，不自动重命名、不忽略未知列。以可信目标schema的JDBC类型及information_schema时间精度为准，不信任上传文件自称的VARCHAR。字段清单见备份manifest；每次成功恢复的result.json额外保存mapping：行数、生成ID数量、规范化字段值数量、目标默认字段及已验证语义摘要，不保存字段原文。

|规则|处理|
|---|---|
|DECIMAL/NUMERIC|BigDecimal按目标精度/小数位精确规范化，禁止舍入或截断；超限拒绝|
|整数/数字主键|精确整数表示；保留既有引用身份，业务工号等CHAR/VARCHAR不作数字化|
|DATE/DATETIME/TIMESTAMP|允许SQL空格或ISO T表示相同本地时间；秒可按ISO规则省略；不改时区，超目标小数秒精度拒绝|
|TIME|支持已验证的日内业务时间；非零小数秒或不支持的区间拒绝，不猜测|
|文本/可空值|原文保存，NULL与空字符串不同；超限/必填缺失拒绝|
|缺失非主键字段|仅允许可信目标的明确DEFAULT或NULL；没有默认值的必填字段拒绝|
|缺失create_time|已有update_time时沿用它；两者都缺失使用目标初始化默认时间，明确是迁移时间，不声称历史时间|
|旧账号缺少失败计数/锁定/改密时间|沿用目标默认：计数0、锁定NULL、改密时间迁移默认；不改密码哈希、角色、状态|
|新安全表缺失|不生成冻结或临时密码；高层未设置二级密码，仍可正常登录。已有V3安全数据保留原恢复安全规范化|

仅以下无被引用身份的明细允许生成缺失id：employee_change_record、salary_record、operation_log、attendance_record、leave_approval_record、employee_education、employee_experience、employee_skill、employee_certificate。employee_profile必须提供employee_id；employee、department、position_info、user_account、leave_request等身份主键不可猜造。生成ID只在全新目标中进行，不覆盖既有员工或账号。

## 完整性与失败保护

1. 解密验证AES-GCM和文件格式；检查原始逐表文件SHA；restore入口在任何DML前再次核对SHA。
2. 逐行按目标类型形成预期值，参数化INSERT，再按保留或新生成主键读回，逐字段精确比较，不依赖源文件行顺序或金额字符串形式。
3. 核对文件行数与目标实际行数，记录已验证语义摘要，再检查外键、员工部门职位、经理任命、审批流及薪资/考勤/请假关联。
4. 同一目标的数据恢复事务失败回滚；迁移DDL可能隐式提交，失败目标继续标记FAILED_RETAINED，禁止自动切换、DROP或repair。只清理任务自有临时解密文件；清理失败不能覆盖原始错误。
5. V3备份必须包含完整持久表；会话、一次性授权不恢复，TEMP→REISSUE、APPROVED→EXPIRED，保留二级哈希、冻结、审计和通知。

原始文件哈希与语义读回是两项独立验证，没有关闭完整性检查。原始金额、主键和业务字段的语义差异仍会拒绝，而不是仅凭行数放行。

## 错误诊断链

failure.json统一记录任务编号、阶段、稳定错误码、中文原因/建议、SQLState、vendorCode、异常类别链、允许的表/字段名和活动库状态。消息来自固定目录，禁止直接输出异常message、SQL原文、连接URL、口令、原始行值和绝对路径。Java、PowerShell、异步状态和前端textContent显示同一编号。预览/导出失败明确UNCHANGED；切换恢复失败不能伪称原库未变。

持久化诊断自身失败时明确记录“诊断保存不可用”，保留任务；不得自动清空或对活动数据库重试。

## 自动化与交付

真实演示升级测试：launcher/test-compatibility-p0.ps1；历史113/120测试：同脚本加-Historical；修复前复现：加-ReproduceOriginal，只读加载保留旧包中经哈希验证的旧Snapshot代码，在全新schema执行。测试默认读取保留的官方演示ZIP，缺失时明确失败；不属于运行时依赖。

完整结果和最终任务编号见DATABASE_COMPATIBILITY_P0_RESULTS_20261028.md。最终候选输出独立目录dist/database-compat-p0-20261028-final，不覆盖rc1、rc2或network-mode-test。候选保留当前未提交BAT修复，Git提交标识之外还以ZIP和文件SHA绑定实际交付。

运行包先在新目录解压，用独立浏览器配置与空闲端口测试，不从已有RC2复制data/或凭据。正式升级仍走“旧版导出加密备份→新安装隔离预览→核对→经用户确认切换”；本轮没有授权生产切换。

## 风险和下一阶段

尚不支持未知扩展列或不在登记范围的表，必须拒绝而非丢弃；大体量备份的性能/超时仍需专项验收。并行首次初始化可能竞争同一空闲MySQL端口，当前会安全失败；本轮改为串行重测，没有调整现有实例端口。

第一批通过后，建议先由独立审查核验诊断脱敏、真实旧备份的隔离预览和业务关系，再讨论P1降级/伴随扩展包/双向修改冲突策略。未实现这些功能，不承诺旧程序普通导出能无损携回新版扩展信息。FRP和账户新功能验收不在本批开展。
