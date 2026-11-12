package com.example.ems.communications;

import com.example.ems.model.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class CommunicationAnnouncementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CommunicationAccess access;
    private final CommunicationAttachmentService attachments;
    private final ApplicationEventPublisher events;

    public CommunicationAnnouncementService(JdbcTemplate jdbc, ObjectMapper json, CommunicationAccess access,
                                            CommunicationAttachmentService attachments,
                                            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.attachments = attachments;
        this.events = events;
    }

    public List<Map<String, Object>> inbox(SessionUser user, String keyword, String category, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        StringBuilder sql = new StringBuilder("""
            SELECT a.id,v.title display_title,v.category display_category,a.scope_type,
                   v.requires_ack display_ack,a.pinned_at,a.published_at,
                   v.id version_id,v.version_no,r.read_at,r.confirmed_at
              FROM comm_announcement a
              JOIN comm_announcement_version v ON v.id=a.current_version_id
              JOIN comm_announcement_recipient r ON r.version_id=v.id AND r.employee_id=?
             WHERE a.announcement_status=1
            """);
        List<Object> args = new ArrayList<>(List.of(user.id()));
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (v.title LIKE ? OR v.body_text LIKE ?)");
            String value = "%" + keyword.trim() + "%";
            args.add(value); args.add(value);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND v.category=?");
            args.add(category.trim());
        }
        sql.append(" ORDER BY (a.pinned_at IS NOT NULL) DESC,a.pinned_at DESC,a.published_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * Read-only dashboard projection. It intentionally never calls detail() or read(),
     * because rendering the dashboard must not create a read receipt.
     */
    public List<Map<String, Object>> home(SessionUser user, int requestedLimit) {
        user = fresh(user);
        int limit = Math.max(1, Math.min(requestedLimit, 5));
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT a.id,a.scope_type,a.pinned_at,a.published_at,
                   v.id version_id,v.version_no,v.title,v.category,v.body_json,
                   v.requires_ack,v.published_at version_published_at,
                   r.read_at,r.confirmed_at
              FROM comm_announcement a
              JOIN comm_announcement_version v ON v.id=a.current_version_id
              JOIN comm_announcement_recipient r ON r.version_id=v.id AND r.employee_id=?
             WHERE a.announcement_status=1
             ORDER BY a.published_at DESC,a.id DESC
             LIMIT ?
            """, user.id(), limit);
        for (Map<String, Object> row : rows) {
            row.put("attachments", attachments.forAnnouncementVersion(number(row.get("version_id"))));
        }
        return rows;
    }

    public List<Map<String, Object>> manage(SessionUser user, String keyword, String status, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        StringBuilder sql = new StringBuilder("""
            SELECT a.*,v.id version_id,v.version_no,v.body_text, CASE WHEN v.id IS NULL THEN 0 ELSE 1 END has_draft,
                   (SELECT COUNT(*) FROM comm_announcement_recipient r WHERE r.version_id=v.id) recipients,
                   (SELECT COUNT(*) FROM comm_announcement_recipient r WHERE r.version_id=v.id AND r.read_at IS NOT NULL) read_count,
                   (SELECT COUNT(*) FROM comm_announcement_recipient r WHERE r.version_id=v.id AND r.confirmed_at IS NOT NULL) confirmed_count
              FROM comm_announcement a
              LEFT JOIN comm_announcement_version v ON v.id=(SELECT dv.id FROM comm_announcement_version dv WHERE dv.announcement_id=a.id AND dv.version_status=0 ORDER BY dv.version_no DESC LIMIT 1)
             WHERE 1=1
            """);
        List<Object> args = new ArrayList<>();
        user=fresh(user);
        if (!user.isExecutive()) {
            if (!user.isManager()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权管理公告");
            sql.append("""
                AND (a.created_by=? OR EXISTS (
                    SELECT 1 FROM comm_announcement_handover h
                     WHERE h.announcement_id=a.id AND h.to_manager_id=?
                       AND h.department_id=? AND h.revoked_at IS NULL AND a.scope_type=1
                ))
            """);
            args.add(user.id()); args.add(user.id());
            args.add(user.departmentId()==null?-1L:user.departmentId());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR v.body_text LIKE ?)");
            String value = "%" + keyword.trim() + "%";
            args.add(value); args.add(value);
        }
        if (status != null && !status.isBlank()) {
            int state = switch (status) {
                case "draft" -> 0; case "published" -> 1; case "recalled" -> 2; case "archived" -> 3;
                default -> throw new IllegalArgumentException("公告状态不正确");
            };
            sql.append(" AND a.announcement_status=?");
            args.add(state);
        }
        sql.append(" ORDER BY a.pinned_at DESC,a.update_time DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> mine(SessionUser user, String keyword, int requestedLimit) {
        user = fresh(user);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        StringBuilder sql = new StringBuilder("""
            SELECT a.*,v.id version_id,v.version_no,v.body_text,
                   CASE WHEN v.id IS NULL THEN 0 ELSE 1 END has_draft
              FROM comm_announcement a
              LEFT JOIN comm_announcement_version v ON v.id=(
                 SELECT dv.id FROM comm_announcement_version dv
                  WHERE dv.announcement_id=a.id AND dv.version_status=0
                  ORDER BY dv.version_no DESC LIMIT 1)
             WHERE a.created_by=?
            """);
        List<Object> args = new ArrayList<>(List.of(user.id()));
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR v.body_text LIKE ?)");
            String value = "%" + keyword.trim() + "%";
            args.add(value); args.add(value);
        }
        sql.append(" ORDER BY a.update_time DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }
    public Map<String, Object> detail(SessionUser user, long announcementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT a.*,v.id version_id,v.version_no,v.title version_title,v.category version_category,
                   v.title display_title,v.category display_category,
                   v.body_json,v.body_text,v.requires_ack version_ack,v.published_at version_published_at,
                   r.employee_id recipient_id,r.read_at,r.confirmed_at
              FROM comm_announcement a
              JOIN comm_announcement_version v ON v.id=COALESCE(a.current_version_id, (SELECT dv.id FROM comm_announcement_version dv WHERE dv.announcement_id=a.id AND dv.version_status=0 ORDER BY dv.version_no DESC LIMIT 1))
              LEFT JOIN comm_announcement_recipient r ON r.version_id=v.id AND r.employee_id=?
             WHERE a.id=?
            """, user.id(), announcementId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在");
        Map<String, Object> row = rows.get(0);
        boolean manager = canManage(user, row);
        boolean creator = number(row.get("created_by")) == user.id();
        if (!manager && !creator && row.get("recipient_id") == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "公告不在接收范围内");
        if (((Number) row.get("announcement_status")).intValue() != 1 && !manager && !creator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "公告不可查看");
        }
        if (row.get("read_at") == null && row.get("recipient_id") != null && !manager && ((Number) row.get("announcement_status")).intValue() == 1) {
            jdbc.update("""
                UPDATE comm_announcement_recipient SET read_at=COALESCE(read_at,NOW(6))
                 WHERE version_id=? AND employee_id=?
                """, row.get("version_id"), user.id());
            row.put("read_at", new java.sql.Timestamp(System.currentTimeMillis()));
        }
        row.put("can_manage", manager); row.put("is_creator", creator); row.put("attachments", attachments.forAnnouncementVersion(number(row.get("version_id"))));
        if (manager) {
            row.put("handover_history", handoverHistory(announcementId));
            row.put("targets", targets(number(row.get("version_id"))));
            row.put("history", jdbc.queryForList("""
                SELECT id,version_no,title,category,body_text,requires_ack,version_status,editor_id,create_time,published_at
                  FROM comm_announcement_version WHERE announcement_id=? ORDER BY version_no DESC
                """, announcementId));
            List<Map<String, Object>> draft = jdbc.queryForList("""
                SELECT id,version_no,title,category,body_json,body_text,requires_ack
                  FROM comm_announcement_version WHERE announcement_id=? AND version_status=0
                 ORDER BY version_no DESC LIMIT 1
                """, announcementId);
            row.put("draft", draft.isEmpty() ? null : draft.get(0));
        }
        return row;
    }

    @Transactional
    public long save(SessionUser user, Long announcementId, String title, String category, String bodyJson,
                     boolean requiresAck, String scopeType, List<Long> departmentIds, List<Long> employeeIds,
                     List<MultipartFile> newFiles, List<Long> removeAttachmentIds) {
        user=fresh(user);
        access.guardWrite(user.id());
        List<CommunicationAttachmentService.Staged> staged = new ArrayList<>();
        try {
            for (MultipartFile file : safeFiles(newFiles)) staged.add(attachments.stage(file));
            String normalizedTitle = required(title, "公告标题不能为空", 120);
            String normalizedCategory = required(category, "公告分类不能为空", 40);
            String normalizedBody = validateBody(bodyJson);
            String plain = plainText(normalizedBody);
            if (!user.isExecutive() && !user.isManager()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权创建公告");
            int scope = parseScope(scopeType);
            if (!user.isExecutive() && scope == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "经理不得创建全公司公告");
            List<Long> departments = scope == 1 ? validDepartments(user, departmentIds) : List.of();
            List<Long> employees = scope == 2 ? validEmployees(user, employeeIds) : List.of();
            if (scope != 0 && departments.isEmpty() && employees.isEmpty()) throw new IllegalArgumentException("请选择公告接收范围");

            long id;
            int nextVersion;
            if (announcementId == null) {
                jdbc.update("""
                    INSERT INTO comm_announcement(title,category,scope_type,announcement_status,requires_ack,created_by)
                    VALUES(?,?,?,0,?,?)
                    """, normalizedTitle, normalizedCategory, scope, requiresAck ? 1 : 0, user.id());
                id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                nextVersion = 1;
            } else {
                Map<String, Object> existing = announcement(announcementId, true);
                requireManage(user, existing);
                if (((Number) existing.get("announcement_status")).intValue() == 2) throw new IllegalArgumentException("已撤回公告不能修改");
                id = announcementId;
                List<Map<String, Object>> drafts = jdbc.queryForList("""
                    SELECT id,version_no FROM comm_announcement_version
                     WHERE announcement_id=? AND version_status=0 ORDER BY version_no DESC LIMIT 1
                    """, id);
                if (!drafts.isEmpty()) {
                    long draftId = number(drafts.get(0).get("id"));
                    jdbc.update("""
                        UPDATE comm_announcement_version
                           SET title=?,category=?,body_json=?,body_text=?,requires_ack=?,editor_id=?
                         WHERE id=? AND version_status=0
                        """, normalizedTitle, normalizedCategory, normalizedBody, plain, requiresAck ? 1 : 0, user.id(), draftId);
                    jdbc.update("DELETE FROM comm_announcement_target WHERE version_id=?", draftId);
                    bindTargets(draftId, scope, departments, employees);
                    jdbc.update("""
                        UPDATE comm_announcement SET title=?,category=?,scope_type=?,requires_ack=?,update_time=NOW(6)
                         WHERE id=?
                        """, normalizedTitle, normalizedCategory, scope, requiresAck ? 1 : 0, id);
                    finishAttachments(user, draftId, staged, removeAttachmentIds);
                    return id;
                }
                nextVersion = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(version_no),0)+1 FROM comm_announcement_version WHERE announcement_id=?
                    """, Integer.class, id);
            }
            jdbc.update("""
                INSERT INTO comm_announcement_version(announcement_id,version_no,title,category,body_json,body_text,
                                                       requires_ack,version_status,editor_id)
                VALUES(?,?,?,?,?,?,?,0,?)
                """, id, nextVersion, normalizedTitle, normalizedCategory, normalizedBody, plain,
                    requiresAck ? 1 : 0, user.id());
            long versionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            bindTargets(versionId, scope, departments, employees);
            finishAttachments(user, versionId, staged, removeAttachmentIds);
            audit(user.id(), announcementId == null ? "ANNOUNCEMENT_DRAFT_CREATE" : "ANNOUNCEMENT_DRAFT_REVISE",
                    id, normalizedTitle);
            return id;
        } catch (RuntimeException error) {
            attachments.discard(staged);
            throw error;
        }
    }

    @Transactional
    public void publish(SessionUser user, long announcementId) {
        access.guardWrite(user.id());
        Map<String, Object> announcement = announcement(announcementId, true);
        requireManage(user, announcement);
        List<Map<String, Object>> drafts = jdbc.queryForList("""
            SELECT * FROM comm_announcement_version
             WHERE announcement_id=? AND version_status=0 ORDER BY version_no DESC LIMIT 1 FOR UPDATE
            """, announcementId);
        if (drafts.isEmpty()) throw new IllegalArgumentException("没有可发布的草稿");
        Map<String, Object> draft = drafts.get(0);
        long versionId = number(draft.get("id"));
        jdbc.update("""
            UPDATE comm_announcement_recipient SET confirmed_at=confirmed_at
             WHERE version_id IN (SELECT id FROM comm_announcement_version WHERE announcement_id=?)
            """, announcementId);
        jdbc.update("UPDATE comm_announcement_version SET version_status=2 WHERE announcement_id=? AND version_status=1", announcementId);
        jdbc.update("UPDATE comm_announcement_version SET version_status=1,published_at=NOW(6) WHERE id=?", versionId);
        jdbc.update("DELETE FROM comm_announcement_recipient WHERE version_id=?", versionId);
        int scope = ((Number) announcement.get("scope_type")).intValue();
        if (scope == 0) {
            jdbc.update("""
                INSERT INTO comm_announcement_recipient(version_id,employee_id)
                SELECT ?,e.id FROM employee e JOIN user_account a ON a.employee_id=e.id
                 WHERE e.employment_status=1 AND a.account_status=1
                """, versionId);
        } else {
            jdbc.update("""
                INSERT INTO comm_announcement_recipient(version_id,employee_id)
                SELECT DISTINCT ?,e.id FROM employee e JOIN user_account a ON a.employee_id=e.id
                 WHERE e.employment_status=1 AND a.account_status=1 AND
                       (EXISTS (SELECT 1 FROM comm_announcement_target t
                                 WHERE t.version_id=? AND t.target_type=0 AND t.department_id=e.department_id)
                        OR EXISTS (SELECT 1 FROM comm_announcement_target t
                                    WHERE t.version_id=? AND t.target_type=1 AND t.employee_id=e.id))
                """, versionId, versionId, versionId);
        }
        Integer recipientCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comm_announcement_recipient WHERE version_id=?", Integer.class, versionId);
        if (recipientCount == null || recipientCount == 0) throw new IllegalArgumentException("公告范围没有有效接收人");
        jdbc.update("""
            UPDATE comm_announcement SET current_version_id=?,announcement_status=1,published_at=NOW(6),
                   recalled_at=NULL,archived_at=NULL,update_time=NOW(6)
             WHERE id=?
            """, versionId, announcementId);
        audit(user.id(), "ANNOUNCEMENT_PUBLISH", announcementId, String.valueOf(draft.get("title")));
        Set<Long> recipients = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT employee_id FROM comm_announcement_recipient WHERE version_id=?", Long.class, versionId));
        events.publishEvent(new CommunicationNotificationEvent(recipients, "announcement.published",
                Map.of("announcementId", announcementId, "versionId", versionId)));
    }

    @Transactional
    public void read(SessionUser user, long announcementId) {
        access.guardWrite(user.id());
        Map<String, Object> current = currentRecipient(user.id(), announcementId);
        jdbc.update("UPDATE comm_announcement_recipient SET read_at=COALESCE(read_at,NOW(6)) WHERE id=?", current.get("recipient_id"));
    }

    @Transactional
    public void confirm(SessionUser user, long announcementId) {
        access.guardWrite(user.id());
        Map<String, Object> current = currentRecipient(user.id(), announcementId);
        if (((Number) current.get("requires_ack")).intValue() != 1) throw new IllegalArgumentException("该公告不需要确认回执");
        jdbc.update("""
            UPDATE comm_announcement_recipient SET read_at=COALESCE(read_at,NOW(6)),
                   confirmed_at=COALESCE(confirmed_at,NOW(6)) WHERE id=?
            """, current.get("recipient_id"));
    }

    @Transactional
    public void recall(SessionUser user, long announcementId) {
        Set<Long> recipients = announcementRecipients(announcementId);
        manageState(user, announcementId, 1, 2, "ANNOUNCEMENT_RECALL", "recalled_at=NOW(6)");
        events.publishEvent(new CommunicationNotificationEvent(recipients, "announcement.recalled",
                Map.of("announcementId", announcementId)));
    }

    @Transactional
    public void archive(SessionUser user, long announcementId) {
        Set<Long> recipients = announcementRecipients(announcementId);
        manageState(user, announcementId, 1, 3, "ANNOUNCEMENT_ARCHIVE", "archived_at=NOW(6)");
        events.publishEvent(new CommunicationNotificationEvent(recipients, "announcement.archived",
                Map.of("announcementId", announcementId)));
    }

    @Transactional
    public void pin(SessionUser user, long announcementId, boolean pinned) {
        Map<String, Object> announcement = announcement(announcementId, true);
        requireManage(user, announcement);
        if (((Number) announcement.get("announcement_status")).intValue() != 1) throw new IllegalArgumentException("只有正式公告可以置顶");
        jdbc.update("UPDATE comm_announcement SET pinned_at=" + (pinned ? "NOW(6)" : "NULL") + " WHERE id=?", announcementId);
        audit(user.id(), pinned ? "ANNOUNCEMENT_PIN" : "ANNOUNCEMENT_UNPIN", announcementId, "公告置顶状态变化");
    }

    public Map<String, Object> statistics(SessionUser user, long announcementId) {
        Map<String, Object> announcement = announcement(announcementId, false);
        requireManage(user, announcement);
        long versionId = number(announcement.get("current_version_id"));
        Map<String, Object> summary = jdbc.queryForMap("""
            SELECT COUNT(*) total,
                   SUM(CASE WHEN read_at IS NOT NULL THEN 1 ELSE 0 END) read_count,
                   SUM(CASE WHEN read_at IS NULL THEN 1 ELSE 0 END) unread_count,
                   SUM(CASE WHEN confirmed_at IS NOT NULL THEN 1 ELSE 0 END) confirmed_count,
                   SUM(CASE WHEN confirmed_at IS NULL THEN 1 ELSE 0 END) unconfirmed_count
              FROM comm_announcement_recipient WHERE version_id=?
            """, versionId);
        List<Map<String, Object>> people = jdbc.queryForList("""
            SELECT e.id,e.employee_no,e.name,d.department_name,r.read_at,r.confirmed_at
              FROM comm_announcement_recipient r JOIN employee e ON e.id=r.employee_id
              LEFT JOIN department d ON d.id=e.department_id
             WHERE r.version_id=? ORDER BY e.name,e.employee_no
            """, versionId);
        return Map.of("summary", summary, "recipients", people);
    }

    private void manageState(SessionUser user, long announcementId, int expected, int target,
                             String action, String timestampUpdate) {
        access.guardWrite(user.id());
        Map<String, Object> announcement = announcement(announcementId, true);
        requireManage(user, announcement);
        if (((Number) announcement.get("announcement_status")).intValue() != expected) {
            throw new IllegalArgumentException("当前公告状态不允许此操作");
        }
        jdbc.update("UPDATE comm_announcement SET announcement_status=?," + timestampUpdate + " WHERE id=?",
                target, announcementId);
        audit(user.id(), action, announcementId, String.valueOf(announcement.get("title")));
    }

    private Map<String, Object> currentRecipient(long employeeId, long announcementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT a.announcement_status,v.requires_ack,r.id recipient_id
              FROM comm_announcement a JOIN comm_announcement_version v ON v.id=a.current_version_id
              JOIN comm_announcement_recipient r ON r.version_id=v.id
             WHERE a.id=? AND r.employee_id=?
            """, announcementId, employeeId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("announcement_status")).intValue() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该公告");
        }
        return rows.get(0);
    }

    private Map<String, Object> announcement(long id, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM comm_announcement WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("公告不存在");
        return rows.get(0);
    }

    private void requireManage(SessionUser user, Map<String, Object> announcement) {
        if (!canManage(user, announcement)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权管理该公告");
    }

    private SessionUser fresh(SessionUser user) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT e.user_type,e.department_id,e.employment_status,a.account_status
              FROM employee e JOIN user_account a ON a.employee_id=e.id WHERE e.id=?
            """,user.id());
        if (rows.isEmpty() || number(rows.get(0).get("employment_status")) != 1
                || number(rows.get(0).get("account_status")) != 1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不可用");
        Map<String,Object> r=rows.get(0);
        return new SessionUser(user.id(),user.employeeNo(),user.name(),
            ((Number)r.get("user_type")).intValue(),
            r.get("department_id")==null?null:number(r.get("department_id")),user.departmentName());
    }

    private boolean canManage(SessionUser user, Map<String, Object> announcement) {
        user=fresh(user);
        if (user.isExecutive()) return true;
        if (!user.isManager() || ((Number) announcement.get("scope_type")).intValue()==0) return false;
        if (number(announcement.get("created_by")) == user.id()) return true;
        if (((Number) announcement.get("scope_type")).intValue()!=1 || user.departmentId()==null) return false;
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM comm_announcement_handover
             WHERE announcement_id=? AND to_manager_id=? AND department_id=? AND revoked_at IS NULL
            """,Integer.class,number(announcement.get("id")),user.id(),user.departmentId());
        return count!=null && count>0;
    }

    private void requireExecutive(SessionUser user) {
        if (!fresh(user).isExecutive())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只有高层可以授权或撤销交接");
    }

    private long handoverDepartment(Map<String,Object> ann) {
        if (number(ann.get("scope_type"))!=1)
            throw new IllegalArgumentException("仅可交接按部门定向的公告");
        List<Long> departments=jdbc.queryForList("""
            SELECT DISTINCT t.department_id FROM comm_announcement_target t
             WHERE t.target_type=0 AND t.version_id=COALESCE(
                 (SELECT v.id FROM comm_announcement_version v
                   WHERE v.announcement_id=? AND v.version_status=0
                   ORDER BY v.version_no DESC LIMIT 1),?)
            """,Long.class,number(ann.get("id")),ann.get("current_version_id"));
        if (departments.size()!=1 || departments.get(0)==null)
            throw new IllegalArgumentException("只允许交接单一部门范围的公告");
        return departments.get(0);
    }

    public List<Map<String,Object>> handoverCandidates(SessionUser user,long announcementId) {
        requireExecutive(user);
        Map<String,Object> ann=announcement(announcementId,false);
        long departmentId=handoverDepartment(ann);
        return jdbc.queryForList("""
            SELECT e.id,e.employee_no,e.name,d.department_name
              FROM employee e JOIN user_account a ON a.employee_id=e.id
              LEFT JOIN department d ON d.id=e.department_id
             WHERE e.department_id=? AND e.user_type=1 AND e.id<>?
               AND e.employment_status=1 AND a.account_status=1
             ORDER BY e.name,e.employee_no
            """,departmentId,number(ann.get("created_by")));
    }

    @Transactional
    public void authorizeHandover(SessionUser user,long announcementId,long managerId,String reason) {
        requireExecutive(user);
        access.guardWrite(user.id());
        Map<String,Object> ann=announcement(announcementId,true);
        long departmentId=handoverDepartment(ann);
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM employee e JOIN user_account a ON a.employee_id=e.id
             WHERE e.id=? AND e.user_type=1 AND e.department_id=?
               AND e.employment_status=1 AND a.account_status=1
            """,Integer.class,managerId,departmentId);
        if (count==null || count==0)
            throw new IllegalArgumentException("目标员工不是该部门的在职经理");
        if (managerId==number(ann.get("created_by")))
            throw new IllegalArgumentException("无需向原创建人交接");
        if (reason==null || reason.isBlank() || reason.length()>500)
            throw new IllegalArgumentException("请输入500字以内的交接原因");
        jdbc.update("""
            UPDATE comm_announcement_handover
               SET revoked_at=NOW(6),revoked_by=?,revoke_reason='新授权替换'
             WHERE announcement_id=? AND revoked_at IS NULL
            """,user.id(),announcementId);
        jdbc.update("""
            INSERT INTO comm_announcement_handover
              (announcement_id,department_id,from_manager_id,to_manager_id,authorized_by,reason)
              VALUES(?,?,?,?,?,?)
            """,announcementId,departmentId,number(ann.get("created_by")),managerId,user.id(),reason.trim());
        audit(user.id(),"ANNOUNCEMENT_HANDOVER",announcementId,"接管经理ID="+managerId);
    }

    @Transactional
    public void revokeHandover(SessionUser user,long announcementId,String reason) {
        requireExecutive(user);
        access.guardWrite(user.id());
        if (reason==null || reason.isBlank() || reason.length()>500)
            throw new IllegalArgumentException("请输入撤销原因");
        int count=jdbc.update("""
            UPDATE comm_announcement_handover
               SET revoked_at=NOW(6),revoked_by=?,revoke_reason=?
             WHERE announcement_id=? AND revoked_at IS NULL
            """,user.id(),reason.trim(),announcementId);
        if (count==0) throw new IllegalArgumentException("当前没有有效的管理授权");
        audit(user.id(),"ANNOUNCEMENT_HANDOVER_REVOKE",announcementId,reason.trim());
    }

    private List<Map<String,Object>> handoverHistory(long announcementId) {
        return jdbc.queryForList("""
            SELECT h.*,fm.name creator_name,tm.name manager_name,au.name authorizer_name
              FROM comm_announcement_handover h
              LEFT JOIN employee fm ON fm.id=h.from_manager_id
              LEFT JOIN employee tm ON tm.id=h.to_manager_id
              LEFT JOIN employee au ON au.id=h.authorized_by
             WHERE h.announcement_id=?
             ORDER BY h.authorized_at DESC,h.id DESC
            """,announcementId);
    }

    private int parseScope(String scope) {
        if (scope == null || scope.isBlank() || scope.equals("all")) return 0;
        if (scope.equals("departments")) return 1;
        if (scope.equals("employees")) return 2;
        throw new IllegalArgumentException("公告范围类型不正确");
    }

    private List<Long> validDepartments(SessionUser user, List<Long> ids) {
        List<Long> result = distinct(ids);
        for (Long id : result) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM department WHERE id=? AND status<>2", Integer.class, id);
            if (count == null || count == 0) throw new IllegalArgumentException("公告部门不存在或已解散");
            if (!user.isExecutive() && (user.departmentId() == null || !user.departmentId().equals(id))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "经理只能发布本部门范围的公告");
            }
        }
        return result;
    }

    private List<Long> validEmployees(SessionUser user, List<Long> ids) {
        List<Long> result = distinct(ids);
        for (Long id : result) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.department_id FROM employee e JOIN user_account a ON a.employee_id=e.id
                 WHERE e.id=? AND e.employment_status=1 AND a.account_status=1
                """, id);
            if (rows.isEmpty()) throw new IllegalArgumentException("公告接收员工无效");
            if (!user.isExecutive()) {
                Long department = rows.get(0).get("department_id") == null ? null : number(rows.get(0).get("department_id"));
                if (user.departmentId() == null || !user.departmentId().equals(department)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "经理只能选择本部门员工");
                }
            }
        }
        return result;
    }

    private void bindTargets(long versionId, int scope, List<Long> departments, List<Long> employees) {
        if (scope == 1) for (Long id : departments) {
            jdbc.update("INSERT INTO comm_announcement_target(version_id,target_type,department_id) VALUES(?,0,?)", versionId, id);
        }
        if (scope == 2) for (Long id : employees) {
            jdbc.update("INSERT INTO comm_announcement_target(version_id,target_type,employee_id) VALUES(?,1,?)", versionId, id);
        }
    }

    private void finishAttachments(SessionUser user, long versionId,
                                   List<CommunicationAttachmentService.Staged> staged,
                                   List<Long> removeAttachmentIds) {
        for (CommunicationAttachmentService.Staged item : staged) {
            attachments.bindAnnouncementVersion(versionId, user.id(), item);
        }
        if (removeAttachmentIds != null) {
            for (Long attachmentId : removeAttachmentIds) {
                Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM comm_attachment
                     WHERE id=? AND announcement_version_id=? AND uploader_id=?
                    """, Integer.class, attachmentId, versionId, user.id());
                if (count != null && count == 1) attachments.discard(attachmentId);
            }
        }
    }

    private List<Map<String, Object>> targets(long versionId) {
        return jdbc.queryForList("""
            SELECT t.target_type,t.department_id,d.department_name,t.employee_id,e.name employee_name
              FROM comm_announcement_target t
              LEFT JOIN department d ON d.id=t.department_id
              LEFT JOIN employee e ON e.id=t.employee_id
             WHERE t.version_id=? ORDER BY t.target_type,t.id
            """, versionId);
    }

    private Set<Long> announcementRecipients(long announcementId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
            SELECT r.employee_id FROM comm_announcement a
              JOIN comm_announcement_recipient r ON r.version_id=a.current_version_id
             WHERE a.id=?
            """, Long.class, announcementId));
    }

    private String validateBody(String value) {
        if (value == null || value.isBlank() || value.length() > 65536) throw new IllegalArgumentException("公告正文为空或过长");
        try {
            JsonNode root = json.readTree(value);
            if (root.path("version").asInt() != 1 || !root.path("blocks").isArray()) throw new IllegalArgumentException("公告正文格式不正确");
            JsonNode blocks = root.path("blocks");
            if (blocks.size() > 100) throw new IllegalArgumentException("公告正文段落过多");
            int textLength = 0;
            for (JsonNode block : blocks) {
                String type = block.path("type").asText();
                if (!Set.of("paragraph", "bullet", "numbered").contains(type) || !block.path("spans").isArray()) {
                    throw new IllegalArgumentException("公告正文包含不支持的格式");
                }
                if (block.path("spans").size() > 100) throw new IllegalArgumentException("公告正文片段过多");
                for (JsonNode span : block.path("spans")) {
                    String text = span.path("text").asText("");
                    if (!span.isObject() || text.isEmpty() || text.length() > 5000) throw new IllegalArgumentException("公告正文片段无效");
                    textLength += text.length();
                    for (String flag : List.of("bold", "italic", "underline")) {
                        if (!span.path(flag).isMissingNode() && !span.path(flag).isBoolean()) throw new IllegalArgumentException("公告格式标记无效");
                    }
                }
            }
            if (textLength > 20000) throw new IllegalArgumentException("公告正文不能超过20000字");
            return json.writeValueAsString(root);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("公告正文格式不正确");
        }
    }

    private String plainText(String bodyJson) {
        try {
            StringBuilder text = new StringBuilder();
            for (JsonNode block : json.readTree(bodyJson).path("blocks")) {
                for (JsonNode span : block.path("spans")) text.append(span.path("text").asText());
                text.append('\n');
            }
            return text.toString().trim();
        } catch (Exception error) {
            throw new IllegalArgumentException("公告正文格式不正确");
        }
    }

    private void audit(long actor, String action, long announcementId, String summary) {
        jdbc.update("""
            INSERT INTO comm_audit(actor_id,action,target_type,target_id,summary)
            VALUES(?,?,'ANNOUNCEMENT',?,?)
            """, actor, action, announcementId, summary);
    }

    private static List<MultipartFile> safeFiles(List<MultipartFile> files) {
        return files == null ? List.of() : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
    }

    private static List<Long> distinct(List<Long> values) {
        return values == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static String required(String value, String message, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("内容超过长度限制");
        return normalized;
    }

    private static long number(Object value) { return ((Number) value).longValue(); }
}
