# 2026-10-28 证据保留缺陷及修复

## 实际影响

本轮首次调用 test-package-smoke.ps1，隔离两份包完成运行检查并回收约 1 GiB 临时实例。收尾时 Limit-ArtifactEvidence 发现 .release-evidence 超过 200 MiB：RDC 的 pre-commit-packages 三个历史 ZIP 合计 267,182,240 字节已单独超限。

旧算法仍按时间删除可淘汰任务，试图将总量降至预算，最终删除了 .release-evidence/1.2.1 内既有任务证据及本轮首次包检查证据，随后返回 2。源码、Git、数据库、正式发行物、顶层日志和 RDC prework patch 未被删除。不将控制台中已读到的历史通过项冒充仍完整保存的证据。

## 修复

- 在任何淘汰前计算不可回收文件量；如果它本身超预算，立即报错并保留所有受管理证据。
- 预算可满足时，仍仅清理归属记录有效、非 KeepArtifacts、已结束任务，按最旧优先；既有路径/链接防护保留。
- 新增 test-evidence-retention.ps1：虚构目录验证“不可管理超限时零删除”、结果哈希不变及可满足预算时保留最新任务。
- 首次新增测试因虚构记录混用分隔符而未进入可管理集合，已修正测试路径构造；没有删除断言或降低要求。
- 仅将已逐一核验的 RDC 三个历史构建 ZIP 从证据目录移动至 dist/rc2-history/rdc-precommit，保留字节与 SHA256；未覆盖 dist/rc2 的当前包。移动清单见 .release-evidence/rc2/rdc-archive-relocation.json。这是归档分类，不计为空间回收。

## 证据重建

重新运行完整 rc2 迁移/事务/HTTP/Chrome 和原业务/Excel/三模式验收；最终包检查再次执行。新结果与任务 ID 见 TEST_REPORT.md。不声称恢复已丢失截图或日志；原 RDC 接手记录作为历史陈述保留。此事不涉及真实业务数据恢复或操作旧服务。
