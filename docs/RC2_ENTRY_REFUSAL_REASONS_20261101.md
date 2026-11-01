# RC2 入口拒绝原因码（2026-11-01）

## 目的

用户报告：在本机 `http://127.0.0.1:8483/local-entry` 被拒时，浏览器只显示

```json
{"code":403,"msg":"入口或会话不符合当前网络配置，请重新登录","data":null}
```

无法判断是哪一条准入规则不合法。本次改动让拒绝响应自带原因码与处置建议，便于在现场（含无痕窗口）直接定位，不再需要翻日志。

## 判定顺序与原因码

实现位置：`backend/src/main/java/com/example/ems/network/Rc2NetworkPolicy.java`（`refusal(...)` 产生原因码，`permits(...)` 退化为 `refusal(...)==null`）与 `backend/src/main/java/com/example/ems/network/EntryIsolation.java`（`reject(...)` 输出响应体）。

| 顺序 | reason | 判定 | 安全模式 | 兼容模式 |
| --- | --- | --- | --- | --- |
| 1 | `entry-port` | Connector 端口/安全标志不属于本实例（含代次不匹配） | 拒绝 | 拒绝 |
| 2 | `remote-address` | 来源不是 `127.0.0.1` / `::1` / `0:0:0:0:0:0:0:1` | 拒绝 | 拒绝 |
| 3 | `host-count` | `Host` 头数量不为 1 | 拒绝 | 拒绝 |
| 4 | `header-format` | `Host` 不是合法 `host[:port]` | 拒绝 | 拒绝 |
| 5 | `secure-origin` | HTTPS 入口请求 Origin ≠ 配置的 `secureOrigin` | 拒绝 | 拒绝 |
| 6 | `host` | HTTP 入口 Host 不在本模式允许范围 | 拒绝（仅回环两种写法） | 接受任意 Host |
| 7 | `cross-site` | 写请求带 `Sec-Fetch-Site: cross-site` | 拒绝 | 拒绝 |
| 8 | `origin` / `referer` | 写请求 `Origin`（缺省用 `Referer`）与 Host 推出的 Origin 不一致 | 拒绝 | 拒绝 |
| — | `cookie-duplicate`（401） | 同一入口 Cookie 出现多份 | 拒绝 | 拒绝 |
| — | `realm-mismatch`（401） | 会话属于其他入口或其他代次 | 拒绝 | 拒绝 |

判定顺序与改动前完全一致：本次只把布尔结果换成原因码，没有放宽或收紧任何一条规则。

## 响应格式

```json
{
  "code": 403,
  "reason": "host",
  "msg": "入口或会话不符合当前网络配置",
  "hint": "安全模式只接受 http://127.0.0.1:8483 或 http://localhost:8483；远程访问请改用兼容模式。",
  "request": {
    "host": "127.0.0.1:8483",
    "remote": "127.0.0.1",
    "port": 8483,
    "mode": "safe",
    "expected": "http://127.0.0.1:8483"
  },
  "data": null
}
```

`request` 只回显本次请求可见的 Host、来源地址、Connector 端口、当前模式与合法示例；不含 Cookie 值、会话标识、账号、SQL、路径或凭据。因此可以安全截图。

## 安全影响

- 原因码暴露的是"哪条规则不合法"，这在攻击者侧本就可通过二分探测得到；不新增可利用信息。
- 判定逻辑、入口隔离、会话代次、CSRF 与权限校验均未改动。
- 兼容模式仍只接受回环来源；`X-Forwarded-*` 依旧不参与判定。

## 改造中发现并修复的回归

首轮改造把 `entry` 传给了 `reject(...)`，而 `reject(...)` 对**所有**拒绝都下发清除入口 Cookie 的 `Set-Cookie`。这使一次 403（Host/Origin 规则）也会顺手注销用户会话，`tools/test-rc2-entries.cjs` 的「Forwarded headers cannot change a native TLS entry domain」因此在第二次全量回归中失败（该检查在跨源 403 之后复用同一会话请求 `/api/me`，期望 200）。

修复：`backend/src/main/java/com/example/ems/network/EntryIsolation.java` 的 `reject(...)` 只在 `status==401`（Cookie 重复 / 会话代次不符）时清除入口 Cookie，403 只返回诊断体，不再触碰会话。该用例本身即回归守卫：若 403 再次清 Cookie，紧接着的 200 断言会立即失败。

## 验证

- `backend/src/test/java/com/example/ems/network/Rc2NetworkPolicyTest.java` 新增 `refusalsCarryDiagnosableReasons()`，覆盖 `entry-port`、`remote-address`、`host-count`、`host`、`origin`、`cross-site`、`header-format` 与放行时返回 `null`。
- 离线全量：`mvn -o -B -f backend/pom.xml test` → `Tests run: 165, Failures: 0, Errors: 0, Skipped: 0`。
- 既有 socket 级断言（`EntryIsolationContainerTest`、`tools/test-rc2-boundary.cjs`、`tools/test-rc2-entries.cjs`）只校验状态码，不受文案影响；本次仍完整重跑。

## 未覆盖

- 未在真实 Frp 隧道、真实第二设备或纯净断网 Windows 上复现；原因码的现场效果需由用户复测确认。
- 浏览器直接显示的是 JSON 文本，未额外渲染 HTML 页面（保持响应体为机器可读格式）。
