# EMS v1.2.1 接口契约（A 角交付）

## 0. 本文档的定位

A 角只负责接口：路由、参数、返回值、权限、状态码与错误语义。业务实现由 C 角完成，页面与交互由 B 角完成。

本文件与 `backend/src/main/java/com/example/ems/web/` 下的控制器源码共同构成契约。实现或页面偏离契约时，先改契约、再改代码，不允许在实现里悄悄改变返回结构。

## 1. 通用约定

- 技术栈：Spring Boot 3 + Thymeleaf 服务端渲染；页面型接口返回视图名，异步接口返回 JSON。
- 会话：登录成功后会话属性 `user` 为 `SessionUser`，字段 `id`、`employeeNo`、`name`、`userType`、`departmentId`、`departmentName`。
- 角色：`userType=0` 员工、`1` 经理、`2` 高层，对应 `SessionUser.isManager()`、`isExecutive()`。
- CSRF：页面表单提交携带 `_csrf`；异步请求携带 `X-CSRF-Token`，由 `ProfileCsrfInterceptor` 校验。
- 错误表达：页面型接口用 flash 属性 `success` / `error` 回显，经 `GlobalExceptionHandler` 与 `UserErrors` 转成中文提示；JSON 接口维持 `code` / `msg` / `data`。
- 分页参数：`page` 从 1 开始，`pageSize` 默认 20，返回体含 `items`、`total`、`page`、`pageSize`。
- 状态码：200 成功；401 未登录或会话失效；403 权限或 CSRF 不通过；409 业务冲突；429 二次验证限流；503 维护中；500 通用错误附错误编号。

## 2. 角色与权限矩阵

| 能力 | 员工 | 经理 | 高层 |
|---|---|---|---|
| 登录、注册、退出 | ✔ | ✔ | ✔ |
| 工作台 | ✔ | ✔ | ✔ |
| 员工列表与详情 | 本部门范围可见 | 本部门 | 全部 |
| 招聘新员工 | ✘ | ✘ | ✔ |
| 调部门、调职位、离职 | ✘ | 本部门内，服务层校验 | ✔ |
| 部门与职位管理 | 只读或不可见 | ✘ | ✔ |
| 个人薪资查看 | 本人 | 本人 | 本人 |
| 薪资结算、调整、发布、导出 | ✘ | ✘ | ✔ |
| 考勤打卡 | ✔ | ✔ | ✔ |
| 部门考勤管理 | ✘ | ✔ | ✔ |
| 请假申请 | ✔ | ✔ | ✔ |
| 请假审批 | ✘ | 本部门且不可自批 | 全部 |
| 完整档案查看 | 本人 | 本部门 | 全部 |
| 档案编辑 | 本人（乐观锁） | 本部门 | 全部 |
| 安全设置与二次验证 | 本人 | 本人 | 本人 |
| 数据库维护 `/api/admin/database` | ✘ | ✘ | ✔ 且需二次验证 |
| 操作日志 | ✘ | ✘ | ✔ |

上表是契约级约定；具体边界由服务层按部门归属、会话与角色再次校验，控制器不只是菜单隐藏。

## 3. 接口清单（按控制器）

### `AdminController.java` — 部门、职位、薪资与组织管理

路由前缀：`/admin`

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/admin/departments` | `departments` | — |
| GET | `/admin/departments/new` | `newDepartment` | — |
| POST | `/admin/departments` | `createDepartment` | `name`, `baseSalary` |
| GET | `/admin/positions/new` | `newPosition` | — |
| POST | `/admin/positions` | `createPosition` | `departmentId`, `name`, `salary` |
| GET | `/admin/positions/manage` | `positions` | — |
| POST | `/admin/departments/{id}/manager` | `manager` | `id`, `ra` |
| POST | `/admin/departments/{id}/update` | `updateDepartment` | `id`, `departmentName`, `baseSalary` |
| POST | `/admin/departments/{id}/delete` | `deleteDepartment` | `ra` |
| POST | `/admin/positions/{id}/update` | `updatePosition` | `id`, `positionName`, `ra` |
| POST | `/admin/positions/{id}/delete` | `deletePosition` | `ra` |
| GET | `/admin/salaries` | `salaries` | — |
| POST | `/admin/salaries/settle` | `settle` | `ra` |
| POST | `/admin/salaries/{id}/adjust` | `adjust` | `id`, `reward`, `subsidy`, `overtimePay`, `otherIncome`, `deduction`, `otherDeduction`, `ra` |
| POST | `/admin/salaries/publish` | `publish` | `ra` |
| GET | `/admin/salaries/export` | `export` | `response` |
| GET | `/admin/recruitment` | `recruitment` | — |
| POST | `/admin/recruitment/single` | `recruit` | `name`, `identityNumber` |
| GET | `/admin/import` | `oldImport` | — |
| GET | `/admin/logs` | `logs` | — |

### `ApiController.java` — 通用查询接口

路由前缀：`/api`

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/api/dictionaries/{type}` | `dictionary` | `type` |
| GET | `/api/dictionaries/employee-options` | `employeeOptions` | — |
| GET | `/api/departments/{id}/positions` | `positions` | `session` |
| GET | `/api/me` | `me` | — |

### `AttendanceController.java` — 考勤

路由前缀：`/attendance`

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/attendance/my` | `mine` | — |
| POST | `/attendance/check-in` | `checkIn` | — |
| POST | `/attendance/check-out` | `checkOut` | — |
| GET | `/attendance/manage` | `manage` | — |
| POST | `/attendance/{id}/correct` | `correct` | `id`, `status`, `reason` |
| POST | `/attendance/rule` | `rule` | — |

### `AuthController.java` — 登录、注册、退出与会话

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/` | `root` | — |
| GET | `/login` | `loginPage` | — |
| POST | `/login` | `login` | `employeeNo`, `request` |
| GET | `/register` | `registerPage` | — |
| POST | `/register` | `register` | `employeeNo`, `name`, `identitySuffix`, `phone`, `password`, `model` |
| POST | `/logout` | `logout` | — |
| GET | `/password` | `password` | — |
| POST | `/password` | `password` | `oldPassword`, `newPassword`, `model` |

### `EmployeeController.java` — 员工档案与招聘

路由前缀：`/employees`

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/employees` | `list` | — |
| GET | `/employees/{employeeNo}` | `detail` | `request` |
| POST | `/employees/recruit` | `recruit` | `name`, `identityNumber` |
| POST | `/employees/{employeeNo}/department` | `department` | `employeeNo`, `departmentId`, `request` |
| POST | `/employees/{employeeNo}/position` | `position` | `employeeNo`, `positionId`, `request` |
| POST | `/employees/{employeeNo}/terminate` | `terminate` | `employeeNo`, `request` |

### `HealthController.java` — 健康检查

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/health` | `health` | — |

### `HomeController.java` — 工作台首页

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/dashboard` | `dashboard` | — |

### `LeaveController.java` — 请假

路由前缀：`/leaves`

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/leaves/my` | `mine` | — |
| POST | `/leaves` | `submit` | `leaveType` |
| POST | `/leaves/{requestNo}/withdraw` | `withdraw` | `ra` |
| GET | `/leaves/approvals` | `approvals` | — |
| POST | `/leaves/{requestNo}/approve` | `approve` | `requestNo` |
| POST | `/leaves/{requestNo}/reject` | `reject` | `requestNo`, `ra` |

### `PortableControlController.java` — 便携版运行控制

路由前缀：`/internal/portable`

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/internal/portable/status` | `status` | — |
| POST | `/internal/portable/stop` | `stop` | — |

### `ProfileController.java` — 员工完整档案

路由前缀：`/profile`

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/profile` | `profile` | — |
| GET | `/profile/edit` | `edit` | — |
| POST | `/profile` | `save` | — |
| GET | `/profile/{kind}/new` | `newEntry` | `model` |
| GET | `/profile/{kind}/{id}/edit` | `editEntry` | `kind`, `model` |
| POST | `/profile/{kind}/{id}/delete` | `delete` | `kind`, `redirect` |

### `SalaryController.java` — 个人薪资

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/my-salaries` | `mine` | — |

### `SecurityController.java` — 安全设置与二次验证

| 方法 | 路径 | 处理函数 | 主要参数 |
|---|---|---|---|
| GET | `/security` | `page` | — |
| GET | `/security/reset` | `resetPage` | — |
| GET | `/security/temporary-password` | `temporaryPage` | — |


## 4. 数据库维护接口（异步 JSON）

前缀 `/api/admin/database`，必须是有效高层会话，且只接受本机 HTTP 或可信 HTTPS；写请求使用会话 CSRF（表单 `_csrf` 或 `X-CSRF-Token`）。响应维持 `code` / `msg` / `data`。

| 方法与路径 | 字段与说明 |
|---|---|
| GET `/admin/database` | 高层维护页面 |
| POST `/reauth` | `password`、`action`(export/inspect/commit)、`object`(确认时任务 ID)；返回单次 grant |
| POST `/export` | `grant`、`backupPassword`；返回 `data.task` |
| POST `/import` | multipart `file`、`grant`、`backupPassword`；返回预览任务 ID |
| POST `/import/{id}/confirm` | `grant`、`restoredEmployeeNo`、`restoredPassword`；确认已校验任务 |
| GET `/jobs/{id}` | 最新状态、提示与预览记录数 |
| GET `/jobs/{id}/download` | 完成的加密导出文件，不下载本机密钥 |

任务状态：`QUEUED`、`RUNNING`、`PREVIEW_READY`、`SWITCHING`、`SUCCEEDED`、`FAILED`、`ROLLED_BACK`、`RECOVERY_REQUIRED`。任务提交返回 HTTP 200 与任务 ID，客户端轮询，不使用 WebSocket。

## 5. 控制器、路由前缀与页面归属

| 控制器 | 路由前缀 | 主要页面（B 角维护） |
|---|---|---|
| AuthController | `/login`、`/register`、`/logout` | login.html、register.html |
| HomeController | `/` | dashboard.html |
| EmployeeController | `/employees` | employees.html、employee-detail.html |
| AdminController | `/admin` | departments.html、department-new.html、positions.html、position-new.html、salaries.html、recruitment.html、logs.html |
| ProfileController | `/profile` | profile.html、profile-edit.html、profile-entry.html、profile-sections.html |
| SalaryController | `/salaries` | my-salaries.html |
| AttendanceController | `/attendance` | attendance-my.html、attendance-manage.html |
| LeaveController | `/leaves` | leaves-my.html、leave-approvals.html |
| SecurityController | `/security` | security.html、security-reset.html、security-temporary.html |
| ApiController | `/api` | 异步调用，无独立页面 |
| PortableControlController | `/internal/portable`（仅 portable profile） | 无页面，启动器调用 `/status` 与 `/stop`，需 `X-EMS-Token` |
| HealthController | `/health` | 无页面，探活用 |

模板中的公共片段在 `frontend/templates/fragments.html`，错误页为 `error.html` 与 `error-page.html`。

## 6. 契约变更流程

1. 先改本文档，写明新增或变更的路由、参数、返回与权限。
2. 再改控制器签名与校验，保持与文档一致。
3. 同步通知识 B、C 两方，涉及页面字段时由 B 角确认模板绑定。
4. 涉及权限、会话或 CSRF 的改动补充对应测试。
5. 提交信息说明契约变化，不重写既有提交历史。

## 7. A 包的边界

A 包只含接口层：`web/`（控制器、拦截器、视图与错误辅助）与 `model/`（会话与数据模型），以及本文档和技术资料。

不包含：`service/`、`upgrade/`、`security/`、`config/`、`network/` 的实现，数据库结构与迁移，页面模板与静态资源，运行库与发行脚本。

因此 A 包不单独构建或运行；它需要与 C 包合并后才是可执行系统。这一点在交付说明中同样写明，避免把契约层当作可运行产物验收。

## 8. 表单字段与校验契约

本节把页面表单与 JSON 接口的**字段名、类型、校验规则和失败语义**固定下来。A 负责契约本身（字段名、必填性、格式、错误码），具体校验实现位于 `service/`、`security/` 与 `model/ProfileForms`，由 C 维护；A 不修改这些实现，只在契约变更时同步本节。

### 8.1 通用提交与错误约定

| 项 | 约定 |
|---|---|
| 页面表单 | `POST` + `application/x-www-form-urlencoded`；成功后 `302` 重定向，并用 flash 回显 `success` / `error` 文本 |
| JSON 接口 | 成功 `{"code":200,"msg":"查询成功","data":...}`（`ApiController.ok`）；失败 `{"code":<http 状态码>,"msg":"<中文提示>","data":null}`（`GlobalExceptionHandler`） |
| 未登录 | 页面请求重定向 `GET /login?reason=expired`；`/api/**` 返回 401 + 上述 JSON |
| 校验失败 | 业务校验抛 `IllegalArgumentException` → 400；权限抛 `ResponseStatusException` → 401/403/409；并发冲突 `ConcurrencyFailureException` → 409「并发操作冲突，请刷新后重试」；数据冲突 `DataIntegrityViolationException` → 409「数据已变化或与已有记录冲突，请刷新后重试」 |
| 缺参 / 类型不符 | `MissingServletRequestParameterException`、`BindException`、`ConstraintViolationException`、`MethodArgumentTypeMismatchException` 统一 400「提交的信息不完整或格式不正确」 |
| 服务端异常 | 5xx 统一返回「服务器内部错误」类提示，堆栈只记日志，不外泄给页面 |
| CSRF | 所有 `POST` 需携带 `_csrf`（表单隐藏域）或 `X-CSRF-Token`；个人档案接口由 `ProfileCsrfInterceptor` 统一校验 |
| 表单回填 | 页面型接口失败后回填原值 + `errors`（Spring `BindingResult`），JSON 接口不回填 |

**日期时间格式（全局统一）**：日期 `yyyy-MM-dd`；月份 `yyyy-MM`；日期时间 `yyyy-MM-dd'T'HH:mm`；时刻 `HH:mm`。后端不猜测其他格式，格式不符按 400 处理。

### 8.2 认证与会话（`AuthController`）

| 端点 | 字段 | 类型 / 规则 | 失败提示 |
|---|---|---|---|
| `POST /login` | `employeeNo` | 六位数字（`^[0-9]{6}$`） | 「工号或密码错误」 |
| | `password` | 非空 | 同上（不区分工号错还是密码错） |
| | — | 停用 / 离职账户 | 「账户已停用或员工已离职」；锁定「账户已锁定，请稍后再试」「密码错误次数过多，账户已锁定15分钟」 |
| `POST /register` | `employeeNo` | 六位数字，且为「待注册」状态的在职招聘记录 | 「工号不存在，请确认已由高层招聘」「该工号不是待注册状态」 |
| | `name` | 必须与招聘记录姓名完全一致 | 「姓名与招聘记录不一致」 |
| | `identitySuffix` | 身份证后六位：前五位数字，末位数字或 `X` | 「身份证后六位必须为六位，前五位为数字，末位为数字或X」「身份证后六位不匹配」 |
| | `phone` | `^1[3-9]\d{9}$`，且未被其他账户占用 | 「请输入正确的十一位手机号」「手机号已经被使用」 |
| | `password` | ≥8 位且 ≤72 UTF-8 字节，必须同时含字母与数字 | 「密码至少8位、最多72字节，且必须包含字母和数字」 |
| | `confirmPassword` | 与 `password` 一致 | 两次输入不一致 |
| `POST /password` | `oldPassword` / `newPassword` / `confirmPassword` | 新密码同上策略 | 旧密码错误 / 两次不一致 |
| `POST /logout` | — | 无参数，销毁会话并重定向登录页 | — |

密码策略唯一实现：`security.SecurityPolicy.password(String)`，所有设置密码入口（注册、改密、重置、临时密码、二级密码）共用同一条规则。

### 8.3 员工与招聘（`EmployeeController`）

| 端点 | 字段 | 类型 / 规则 | 权限与失败语义 |
|---|---|---|---|
| `GET /employees` | `keyword`、`departmentId`、`status`、`page`（默认 1）、`pageSize`（默认 20） | 可选查询参数 | 员工只看本部门范围；`page`/`pageSize` 非法 → 400 |
| `POST /employees/recruit` | `name` | 非空 | 仅高层；「只有高层可以招聘员工」 |
| | `identityNumber` | `^[0-9]{17}[0-9Xx]$`，全局唯一 | 「身份证号格式不正确」「身份证号已经存在」 |
| | `birthDate`、`hireDate` | `yyyy-MM-dd` | 格式不符 → 400 |
| | `departmentId`、`positionId` | 可选；**两者必须同时给出或同时为空**；职位须属于该部门且 `status=1` | 「部门和职位必须同时选择」「职位不属于所选部门」「职位已经停用」「所选部门不存在或已经停用」 |
| `POST /employees/{employeeNo}/department` | `departmentId`、`reason` | 均为必填 | 仅高层；经理须先完成经理更换；高层不可调动；目标部门须有经理（存在待审批请假时） |
| `POST /employees/{employeeNo}/position` | `positionId`、`reason` | 均为必填 | 仅部门经理、限本部门普通员工、限在职 |
| `POST /employees/{employeeNo}/terminate` | `reason` | 必填 | 仅高层；经理须先更换；高层不可通过此入口停用；已离职拒绝重复办理 |
| `POST /admin/recruitment/single` | `name`、`identityNumber`、`birthDate`、`hireDate`、`departmentId`（可选） | 管理端旧招聘入口，字段与 `/employees/recruit` 一致（不含 `positionId`），保留以兼容旧页面；新页面统一走 `/employees/recruit` |
| `POST /admin/recruitment/import` | `file`（`multipart/form-data`，必填） | Excel 批量导入员工；解析失败在页面回显 `error`，不留半条数据 |

### 8.4 部门与职位（`AdminController`）

| 端点 | 字段 | 规则 |
|---|---|---|
| `POST /admin/departments` | `name`、`baseSalary`、`description`（可选） | 部门名去空格后唯一：同名 → 「部门名称已经存在」 |
| `POST /admin/departments/{id}/update` | `departmentName`、`baseSalary`、`description`（可选） | 部门不存在 → 「部门不存在」 |
| `POST /admin/departments/{id}/delete` | 路径参数 `id` | 仍有除经理外在职员工 → 「该部门仍有其他员工，请先将除经理外的员工调往其他部门」 |
| `POST /admin/departments/{id}/manager` | `employeeNo` | 六位数字；须在职、同部门、非高层（「不能通过经理任免更改高层身份」「只能任命在职员工」「只能任命本部门员工为经理」）；原经理须先有普通职位（「请先为原经理创建一个普通职位」） |
| `POST /admin/positions` | `departmentId`、`name`、`salary`、`managerPosition`（默认 `false`） | 经理职位不可手工创建（「经理职位由系统自动维护，不能手动创建」）；同部门重名拒绝；经理职位名固定为「部门经理」 |
| `POST /admin/positions/{id}/update` | `positionName`、`positionSalary` | 经理职位名称固定为「部门经理」 |
| `POST /admin/positions/{id}/delete` | 路径参数 `id` | 经理职位不可删；仍有员工使用不可删 |

### 8.5 考勤（`AttendanceController`）

| 端点 | 字段 | 规则 |
|---|---|---|
| `POST /attendance/check-in` | 无 | 服务端按当前用户与规则时间判定；当天已打卡 → 「今天已经完成上班打卡，不能重复打卡」 |
| `POST /attendance/check-out` | 无 | 未上班打卡 → 「请先完成上班打卡」；已完成 → 「今天已经完成下班打卡，不能重复打卡」 |
| `POST /attendance/{id}/correct` | `status`（1–8 整数）、`reason`（非空）、`month`（可选，回跳用） | 仅高层；「考勤状态无效」「修正原因不能为空」「考勤记录不存在」 |
| `POST /attendance/rule` | `workStart`、`workEnd`（`HH:mm`）、`lateGrace`、`earlyGrace`（0–120 分钟） | 仅高层；「下班时间必须晚于上班时间」「允许偏差分钟数必须在0到120之间」 |
| `GET /attendance/my`、`/attendance/manage` | `month`（`yyyy-MM`，默认当月）、`departmentId`、`keyword` | 管理视图仅经理/高层 |

### 8.6 请假（`LeaveController`）

| 端点 | 字段 | 规则 |
|---|---|---|
| `POST /leaves` | `leaveType` | 必须存在于启用中的请假类型：否则「请假类型无效」 |
| | `startTime`、`endTime` | `yyyy-MM-dd'T'HH:mm`，`endTime > startTime`：「结束时间必须晚于开始时间」 |
| | `reason` | 非空：「请假原因不能为空」 |
| | — | 与已有待审批/已通过记录重叠 → 「该时间段与已有待审批或已通过的请假重叠」；本部门无经理 → 「本部门尚未任命经理，暂时无法提交请假」；不能自批 → 「不能审批自己的申请」 |
| `POST /leaves/{requestNo}/withdraw` | 路径参数 | 仅本人（「只能撤回自己的申请」）、仅待审批（「只有待审批申请可以撤回」） |
| `POST /leaves/{requestNo}/approve` | `opinion`（可选） | 仅当前审批人（「您不是当前审批人」） |
| `POST /leaves/{requestNo}/reject` | `opinion`（**必填**） | 「驳回时必须填写审批意见」 |

### 8.7 薪资（`AdminController` / `SalaryController`）

| 端点 | 字段 | 规则 |
|---|---|---|
| `POST /admin/salaries/settle` | `month`（`yyyy-MM`） | 生成当月工资条，返回条数用于提示 |
| `POST /admin/salaries/{id}/adjust` | `reward`、`subsidy`、`overtimePay`、`otherIncome`、`deduction`、`otherDeduction`（均为金额，`BigDecimal`）、`month`（回跳用） | 全部字段必填（不填即 0 由前端保证） |
| `POST /admin/salaries/publish` | `month` | 发布当月工资条 |
| `GET /admin/salaries/export` | `month` | 返回 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，附件名 `{month}工资表.xlsx` |
| `GET /my-salaries` | `month`（可选） | 员工仅看本人 |

金额字段一律以 `BigDecimal` 传输，禁止用浮点字符串；显示格式由 `ViewFormats.money` 统一（两位小数）。

### 8.8 个人档案（`ProfileController` + `model/ProfileForms`）

主档案 `POST /profile`（`@Valid @ModelAttribute("form") ProfileForms.Basic`）：

| 字段 | 校验 |
|---|---|
| `phone` | 非空，`^1[3-9]\d{9}$`：「手机号不能为空」「手机号应为有效的十一位号码」 |
| `email` | 邮箱格式，≤100 字 |
| `address` | ≤255 字 |
| `emergencyName` | ≤50 字 |
| `emergencyRelation` | ≤30 字 |
| `emergencyPhone` | 空或 `^1[3-9]\d{9}$` |
| `biography` | ≤1000 字 |
| `gender` | 空或 `0`/`1`：「性别选项无效」 |
| `version` | **必填**（乐观锁版本号，≤当前值）：缺失「缺少档案版本，请刷新」，冲突按 409 处理 |

子记录（同构：新增不带 `id`，编辑带 `id`，路径 `POST /profile/{kind}` 或 `POST /profile/{kind}/{id}`）：

| 类型 | 字段与限制 |
|---|---|
| `education` | `school` 非空 ≤100；`degree` 非空 ≤30；`major` ≤100；`startMonth` 必填 `YYYY-MM`；`endMonth` 空或 `YYYY-MM` |
| `experience` | `company` 非空 ≤100；`department`、`position` ≤100；`startMonth` 必填；`endMonth` 可空；`description` ≤1000 |
| `skills` | `name` 非空 ≤100；`proficiency` ∈ {了解, 熟悉, 熟练, 精通}；`notes` ≤500 |
| `certificates` | `name` 非空 ≤100；`issuer` 非空 ≤100；`certificateNumber` ≤100；`obtainedDate` 必填；`expiryDate` 可空 |

删除：`POST /profile/{kind}/{id}/delete`，`kind` ∈ {`education`, `experience`, `skills`, `certificates`}；越权或不存在按 403/400 处理。

### 8.9 安全中心（`SecurityController`，全部 `@ResponseBody`）

| 端点 | 字段 | 规则 |
|---|---|---|
| `POST /api/security/reset` | `employeeNo`、`code`、`password`、`confirmPassword` | 未登录可用；按来源 IP 限流；两次密码不一致 → 400 |
| `POST /api/security/temporary-password` | `password`、`confirmPassword` | 登录态内修改，改后使已观测会话失效 |
| `POST /api/security/secondary` | `loginPassword`、`oldSecondary`（首次可空）、`password`、`confirmPassword` | 仅高层；按用户限流 |
| `POST /api/security/authorize` | `target`（用户 id）、`action`、`password` | 返回 `{grant}`，授权码一次性消费 |
| `POST /api/security/requests` | `reason` | 提交重置申请 |
| `POST /api/security/requests/{id}/cancel` | 路径参数 | 仅本人 |
| `POST /api/security/requests/{id}/approve` | `approve`（布尔） | 仅高层；通过时返回 `{secret,label}`，仅展示一次 |
| `POST /api/security/requests/{id}/reissue` | 路径参数 | 仅高层；旧码立即失效 |
| `POST /api/security/accounts/{id}/reset` | `grant` | 仅高层；返回 `{secret,label}`，临时密码 24 小时有效、仅展示一次 |
| `POST /api/security/accounts/{id}/unfreeze` | `grant`、`reason` | 仅高层；解冻必须写明原因 |
| `GET /api/security/notifications` | `type`、`query`、`from`、`until`、`unread` | 当前用户的通知列表 |
| `POST /api/security/notifications/{id}/read` | 路径参数 | 标记已读 |

重置码/临时密码/二级密码等**秘密值只在响应中出现一次**，前端不得写入本地存储或日志。

### 8.10 数据库维护（`upgrade.DatabaseController`，属 C 的实现范围，A 仅登记契约）

| 端点 | 字段 | 说明 |
|---|---|---|
| `GET /admin/database` | — | 数据库管理页面，权限由 `DatabaseAccess.user` 判定 |
| `POST /api/admin/database/reauth` | `password`、`action`、`object`（可空） | 返回一次性 `grant` |
| `POST /api/admin/database/export` | `grant`、`backupPassword`、`targetVersion`（默认 `current`） | 创建导出任务 |
| `POST /api/admin/database/import` | `grant`、`backupPassword`、`file`（必填）、`extension`（可选）、`proof`（可选） | 创建导入预览任务 |
| `POST /api/admin/database/import/{id}/confirm` | `grant`、`restoredEmployeeNo`、`restoredPassword`、`acknowledgeBaseOnly`（默认 `false`）、`skipConflicts`（默认 `false`） | 确认切换 |
| `GET /api/admin/database/jobs/{id}` | 路径参数 | 任务状态；非发起者 → 403「任务不属于当前账号」 |
| `GET /api/admin/database/jobs/{id}/download` | 路径参数 | 下载备份包 `EMS-Backup-{id}.zip`，同样限发起者 |

任务状态机见 `docs/API与权限.md`：`QUEUED → RUNNING → PREVIEW_READY → SWITCHING → SUCCEEDED`，异常分支 `FAILED`、`ROLLED_BACK`、`RECOVERY_REQUIRED`。

### 8.11 状态码语义

| 状态码 | 适用场景 |
|---|---|
| 400 | 字段缺失、格式不符、业务前置条件不满足 |
| 401 | 未登录或会话失效（页面重定向 `/login?reason=expired`，API 返回 JSON） |
| 403 | 角色/部门范围不满足、跨入口会话不继承 |
| 404 | 页面或接口不存在 |
| 409 | 档案版本冲突、并发修改、导入导出任务状态冲突 |
| 429 | 登录/重置/授权等入口限流 |
| 500 | 未预期异常（只记录日志） |
| 503 | 数据库维护模式下服务不可用 |

> 字段契约与上文第 6 节「契约变更流程」、第 7 节「A 包的边界」配合使用。
