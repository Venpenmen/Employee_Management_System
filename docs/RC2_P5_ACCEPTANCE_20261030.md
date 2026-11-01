# RC2-P5 本地验收记录（2026-10-30）

- 任务：RC2-P5 Bound restore summary and confirmation
- 分支：`feature/v121-test-network-mode`
- 提交：`4737fd91d297f5031667c6694b4158f0df182112`（feat: layer database restore UI and preserve approved RC2 evidence groups）
- 证据保留组：`complete-rc2`（用户已批准的独立保留组，最多 20 次、全局 ≤200MiB）
- 结论：**P5 本地验收通过**（仅限本机隔离候选运行环境；不等于正式发布，亦不等于完成人工验收清单）

## 一、运行证据

| 运行目录（`.release-evidence/1.2.1/`） | kind | 通过项 | evidence.json SHA256 |
| --- | --- | --- | --- |
| `20261030-174124-cd17230d5b9e` | complete-database（HTTP 全流程，含真实切换） | 52 | `214384e73f61d4050f40a9a2efe03aa72594d79ac31bc5200ef2bb9b4eeefd7c` |
| `20261030-174551-45a7f27850f4` | complete-database（真实 Chrome 界面） | 16 | `56befe8cd35af77ad0f418545fa1280fef94eef78aaade33fc78799f2da41a37` |
| `20261030-164651-651b7b3a7af9` | complete-database-faults（故障矩阵） | 60 | `9847faeafacbc67f4825c64104a0099d4ea2ab0b5c849d12e95d632aac5f241a` |

三次运行均由受管续做运行器发起（`.codex-continuation/evidence/RC2-P5-*.log|.json`，`EMS_DEVELOPMENT_TASK_ID=22fdc41e-7291-493d-bc9c-c2310aaba015`），使用 `server-complete-rc2` 隔离运行时、`data/complete-rc2` 独立命名空间与合成夹具数据，运行结束 `cleanup=removed`（回收约 540MB），未接触真实业务库。

前一次 `20261030-174124` 与 `20261030-174551` 运行发生在 `540693a`（双入口 JDBC 会话）之后，因此覆盖了最新网络入口改动；`20261030-164651` 故障矩阵同样晚于 `540693a`。

## 二、P5 八项核验对照

1. **展示当前操作对象和目标**：预览摘要显示备份版本、表数量/记录数、识别版本、结构描述、恢复模式与逐表计数（`Browser shows paired preview summary, closed details and structural whitelist without field hashes`；`Browser defaults to standard backup`）。
2. **绑定真实校验结果**：确认授权对象绑定 `任务ID:receiptDigest:BASE|FULL:SKIP|ALL`，服务端 `ConfirmationBinding.validate` 复核（`Current preview has bound receipt`、`Bound report and choices accepted for cutover`、`Changed input cannot confirm an old preview`、`Unbound legacy confirmation grant cannot commit bound preview`）。
3. **区分校验成功/失败/未执行**：摘要区分「存在阻断冲突，不能切换」「仅基础升级，需明确确认」「隔离校验通过，尚未切换」；`Corrupt companion fails without fallback or switch`、`Missing extension explicitly identified as base-only`、`All preview and failure cases leave active pointer unchanged`。
4. **明确覆盖/迁移/切换风险**：提交前显式提示整体替换数据库并注销所有会话，需勾选确认基础升级/跳过冲突扩展（`Browser requires unchecked explicit base-only acknowledgement`、`Base-only without acknowledgement denied`）。
5. **冗余信息控制**：不适用的冲突/丢失选项不展示且不出现在请求中（`Browser does not request irrelevant loss/skip choices`、`Hidden inapplicable conflict decision is disabled and excluded`）。
6. **次要详情折叠**：结构详情默认折叠、展开后内部可滚动（`Browser shows paired preview summary, closed details ...`、`Expanded preview is internally scrollable`）。
7. **状态过期 / 重复提交 / 错误确认**：客户端丢弃过期预览，取消不进入保存态，错误确认不破坏会话，导出许可不可重放，陈旧回滚日志不能覆盖新代际（`Expired client preview is discarded before authorization/confirmation`、`Cancelling actual confirmation does not enter saving state`、`Cancelled confirmation keeps current login and active database`、`Refused confirmation keeps active session`、`Consumed export grant cannot be replayed`、`Old rollback journal cannot overwrite a newer committed generation`）。
8. **保留既有权限与服务端验证**：员工/经理被拒、CSRF 必需、二级密码校验、服务端最终确认（`Employee database page denied`、`Manager database API denied`、`Employee direct database API denied`、`CSRF required`、`Incorrect secondary password refused`）。

## 三、真实切换与失败恢复

- 真实切换：`Bound report and choices accepted for cutover`、`Successful switch invalidates prior executive session`、`Successful switch invalidates prior employee session`、`Restored BCrypt identity remains usable`、`Verified pre-switch recovery backup retained`。
- 失败恢复：`Injected switch failure restores previous database pointer`、`Application works after controlled rollback`、`Startup recovers original pointer after actual broker termination`（真实终止 broker）、`Interrupted operation has explicit recovered result`。
- 未破坏 P4 分层：`Basic view disables and excludes advanced upload parameters`、`Switching to advanced clears prior form state and never submits`、`Switching back to basic removes stale uploads and advanced parameters`。

## 四、未覆盖与限制

- 未在纯净 Windows（断网、非开发机）环境执行；未使用真实生产数据；未验证真实第二设备、真实公网/FRP 证书。
- 本次浏览器脚本 scope 明示 `actual Chrome UI; cancel and acknowledgement; no active switch`——真实切换能力由 HTTP 组覆盖，两者互补而非重复。
- 人工验收清单（`docs/RC2_ACCEPTANCE_CHECKLIST.md`）仍未执行，评审后才可宣称正式发布。
