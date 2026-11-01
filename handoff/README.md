# EMS v1.2.1 A 角交付包（索引）

| 文件 | 说明 |
| --- | --- |
| `A-develop.zip` | A 角源码交付包（解压后为 `A-develop\` 单层目录） |
| `handoff\A-develop.md` | 职责范围、构建运行方式、本版新增与未验收边界 |
| `EMS-v1.2.1-A协作交付说明.md` | 交付范围、排除项、验证记录与提交说明 |
| `PACKAGES-SHA256.txt` | 本包内每个交付件的 SHA256 |

快速开始：

```
cd A-develop\backend
mvn -B -o clean verify
```

前置 JDK 17 与 Maven 3.9.16，依赖缓存为空时去掉 `-o`。完整候选运行入口见 `A-develop\server-complete-rc2\start-server.ps1` 与 `handoff\A-develop.md`。

说明：本包由既有工程整理而来，用于仓库提交与验收留痕，不代表三位作者的真实创作历史；源码交付不含真实数据库、日志、凭据和运行状态。
