# 受控旧版副本工具

用途：在全新独立目录运行登记的 v1.2.0 程序，并安全携回 v1.2.1 扩展。工具不接管、修改或停止已有安装。当前随源码开发，最终运行包仍须通过 S8 验收后使用。

## 准备

需要配对的 `base-v120.emsbk`、`extensions.emsbk` 及备份口令，以及原始 `EMS_v1.2.0_Windows_Portable.zip`。只支持SHA-256为 `1fc499790a29b836442db0451cb74c277b45573b87f084ad7fcfc272b0047203` 的已登记旧程序包。不得用旧安装目录、原数据库底层文件或另一个批次的扩展包替代。

完整候选使用包内 Java 和 `app/ems-1.2.1-complete.jar`；开发工程允许使用已离线编译的 `backend/target/classes` 与 `dev-classpath.txt`。工具不会联网获取依赖。Windows账号需要与创建副本时相同，以解开本机DPAPI凭据。

## 操作

在项目根目录打开 PowerShell，使用独立进程调用。示例路径请替换成实际输入文件路径；不要在命令中填写明文口令。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\controlled-legacy-copy.ps1 -Action Create -BaseFile "D:\演示文件\base-v120.emsbk" -ExtensionFile "D:\演示文件\extensions.emsbk" -LegacyZip "D:\演示文件\EMS_v1.2.0_Windows_Portable.zip"
```

此参数只作用于本次脚本进程，不修改系统执行策略。Create提示输入隐藏口令，并输出独立的 `CopyId`。副本位于项目自己的 `.release-work/runs/<CopyId>/controlled-copy`。即使准备失败也应保存已分配的编号；工具不会覆盖另一个副本或自动重建失败数据库。

将下面的编号替换为实际值：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\controlled-legacy-copy.ps1 -Action Start -CopyId "20261029-130000-0123456789ab"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\controlled-legacy-copy.ps1 -Action Status -CopyId "20261029-130000-0123456789ab"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\controlled-legacy-copy.ps1 -Action ExportReturn -CopyId "20261029-130000-0123456789ab"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\controlled-legacy-copy.ps1 -Action Stop -CopyId "20261029-130000-0123456789ab"
```

Start默认只监听 `http://127.0.0.1:8485`，使用独立Cookie。Create可通过 `-Port` 选择其他本机端口；冲突会失败，不调整其他实例。只启动本工具创建且准备成功的副本。不要直接运行副本内旧BAT或旧启动脚本，以免绕开跟踪预检。

ExportReturn可以在副本运行期间取得一致性快照，每次在 `private/return-<随机编号>/` 新建 `return-v120.emsbk` 和 `return-proof.emsbk`，不会覆盖旧导出。回到新版时同时提供这两个文件及最初的 `extensions.emsbk`。普通旧版备份不含可信血缘，不能被标为完整往返。

## 数据与故障

- Stop只关闭该CopyId绑定的PID、启动时间和可执行文件，不按端口或名称停止进程，保留数据和本机凭据。重复启动和停止仍需通过同一工具。
- 口令经当前Windows用户DPAPI保护存于私有目录；不要单独共享目录中的数据库、凭据、收据或扩展内容。初始配对文件也保存在该私有目录。
- 触发器丢失、跟踪代际改变、存活记录缺少代际、模式/端口/进程不符时，拒绝检查或导出，不尝试修复跟踪或伪造证明。
- 发生SQL或网络错误时保留日志及副本。所有恢复操作先在新版隔离目标验证，不覆盖活动数据库。连接有5秒建立及60秒读超时；较慢操作失败后先检查副本状态，不并行重试。
- 口令错误、文件损坏或错配不会降级为“恢复成功”；需要仅恢复基础数据时，须在新版预览中明确选择并确认，不使用本工具伪造完整证明。
- 删除不复活，新增记录不继承其他人的扩展；主键复用或身份不明阻断安全关联。旧版合法修改和账号限制保持；新密码不被扩展覆盖，原会话和一次性凭证不恢复。
- 副本属于显式保留资源，常规构建清理不会自动删除。移除前应停止并核对数据、导出及恢复点，再由使用者明确决定；没有自动删除命令。

局限：具备本机root维护凭据及扩展密钥的特权人员不属于“旧应用账号不能伪造血缘”的保护范围。工具没有真实公网测试，也不修改FRP、防火墙或系统服务。
