# EMS v1.2.2 发行说明

通讯支持私聊、群聊、附件和已读回执。本版本修复了 WebSocket 重连补拉和重复已读广播，并包含考勤异常处理、请假管理和公告功能。

冻结 Windows x64 候选 ZIP SHA-256

1134686d766d607a51ca5c7fcf3487c5c097c7c23b8dfc6cae8901a1eed2b4ed

内置 JAR SHA-256

0aea93ad410a37353dbcf6f58838a99c3eac8a7686530aeace00b0d151d1d4d5

发行候选验收 13 项通过、3 项条件验证。条件验证为本机菜单及一次性入口码、真实局域网 TLS 通讯、真实外网 TLS 通讯，均待实测。

冻结 JAR 的 243 项输入中有两份布局快照，因 Maven 资源通配规则而被复制进 JAR。源码仓库纳入其余 241 项，按已验收清单逐项匹配 SHA-256。以下快照有意排除，运行入口和源码构建不依赖它们。

- frontend/static/css/app.css.pre-layout.bak
- frontend/templates/attendance-my.html.pre-layout.bak

冻结 ZIP 与 JAR 保持原始字节。由当前源码重新构建的 JAR 不包含上述快照。
