# API与权限
## 保留接口
保留原登录注册、员工、部门职位、薪资、考勤、请假、档案、字典和日志入口，详见功能追踪矩阵。工号/姓名/部门/状态筛选、分页、工资Excel、招聘xlsx模板及档案乐观锁继续使用。
员工与经理按现有部门范围访问；完整扩展档案仅本人或高层可读；核心身份和薪资由既有管理入口维护。经理不能自批或跨部门审批。
未新增“部门清空”或手动“审批转交”；保留部门解散、员工调部门及经理更换中的自动审批转交，并修复员工升任经理后本人待审批申请应转高层的情况。
高层接口不只依赖菜单，所有敏感请求后端核对身份、角色、状态及归属。403写操作不再被控制器吞成成功跳转。

## 新增数据库接口
前缀/api/admin/database；必须是有效高层会话，直接本机HTTP或可信HTTPS；写请求使用会话CSRF（表单_csrf或X-CSRF-Token）。响应维持code/msg/data。
|方法与路径|字段/说明|
|---|---|
|GET /admin/database|高层页面|
|POST /reauth|password、action(export/inspect/commit)、object(确认时任务ID)；返回单次grant|
|POST /export|grant、backupPassword；返回data.task|
|POST /import|multipart file、grant、backupPassword；返回预览任务ID|
|POST /import/{id}/confirm|grant、restoredEmployeeNo、restoredPassword；确认已校验任务|
|GET /jobs/{id}|最新状态、提示和预览记录数|
|GET /jobs/{id}/download|完成的加密导出文件，不下载本机密钥|

任务状态：QUEUED、RUNNING、PREVIEW_READY、SWITCHING、SUCCEEDED、FAILED、ROLLED_BACK、RECOVERY_REQUIRED。任务提交返回HTTP200及任务ID；客户端轮询，不需要WebSocket。
401重新登录；403权限/CSRF/授权无效；409业务冲突；429二次验证限流；503维护中；非预期500仅提供通用说明及错误编号。
后台网页维护使用强制高层有效会话入口；本机迁移入口只处理Flyway，不接收远程停止或任意命令。
