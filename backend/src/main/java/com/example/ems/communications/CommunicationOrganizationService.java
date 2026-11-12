package com.example.ems.communications;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class CommunicationOrganizationService {
    public static final int ROLE_MEMBER = 0;
    public static final int ROLE_ADMIN = 1;
    public static final int ROLE_OWNER = 2;

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public CommunicationOrganizationService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public long ensureDepartmentGroup(Long departmentId) {
        Map<String, Object> department = jdbc.queryForMap(
                "SELECT id,department_name,manager_id,status FROM department WHERE id=? FOR UPDATE", departmentId);
        if (((Number) department.get("status")).intValue() == 2) {
            throw new IllegalArgumentException("已解散部门不能创建活动部门群");
        }
        Long existing = jdbc.query(
                "SELECT conversation_id FROM comm_group WHERE department_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, departmentId);
        if (existing != null) {
            jdbc.update("UPDATE comm_group SET title=?,group_status=0,update_time=NOW(6) WHERE conversation_id=?",
                    department.get("department_name"), existing);
            jdbc.update("UPDATE comm_conversation SET status=0 WHERE id=?", existing);
            return existing;
        }
        jdbc.update("INSERT INTO comm_conversation(conversation_type,status) VALUES(1,0)");
        Long conversationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
            INSERT INTO comm_group(conversation_id,group_type,title,description,owner_employee_id,
                                   department_id,group_status,created_by)
            VALUES(?,1,?,?,NULL,?,0,NULL)
            """, conversationId, department.get("department_name"),
                department.get("description") == null ? null : String.valueOf(department.get("description")),
                departmentId);
        publish(conversationId, "department-group.created", Map.of("departmentId", departmentId));
        return conversationId;
    }

    @Transactional
    public void ensureMembership(Long conversationId, Long employeeId, int role) {
        jdbc.queryForList("SELECT id FROM comm_conversation WHERE id=? FOR UPDATE", conversationId);
        Map<String, Object> existing;
        try {
            existing = jdbc.queryForMap(
                    "SELECT id,member_role FROM comm_membership_period WHERE conversation_id=? AND employee_id=? AND open_marker=1",
                    conversationId, employeeId);
        } catch (EmptyResultDataAccessException absent) {
            Long joinedAfter = jdbc.queryForObject(
                    "SELECT next_message_seq FROM comm_conversation WHERE id=?", Long.class, conversationId);
            jdbc.update("""
                INSERT INTO comm_membership_period(conversation_id,employee_id,member_role,
                                                   joined_after_seq,open_marker,joined_at)
                VALUES(?,?,?,?,1,NOW(6))
                """, conversationId, employeeId, role, joinedAfter);
            jdbc.update("""
                INSERT INTO comm_conversation_user_state(conversation_id,employee_id,last_read_seq)
                VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE last_read_seq=VALUES(last_read_seq)
                """, conversationId, employeeId, joinedAfter);
            publish(conversationId, "membership.joined",
                    Map.of("employeeId", employeeId, "role", role));
            return;
        }
        if (((Number) existing.get("member_role")).intValue() != role) {
            jdbc.update("UPDATE comm_membership_period SET member_role=? WHERE id=?",
                    role, existing.get("id"));
            publish(conversationId, "membership.role-changed",
                    Map.of("employeeId", employeeId, "role", role));
        }
    }

    @Transactional
    public void closeMembership(Long conversationId, Long employeeId) {
        Long nextSeq = jdbc.queryForObject(
                "SELECT next_message_seq FROM comm_conversation WHERE id=? FOR UPDATE", Long.class, conversationId);
        int changed = jdbc.update("""
            UPDATE comm_membership_period
               SET left_after_seq=?,left_at=NOW(6),open_marker=NULL
             WHERE conversation_id=? AND employee_id=? AND open_marker=1
            """, nextSeq, conversationId, employeeId);
        if (changed > 0) {
            publish(conversationId, "membership.left", Map.of("employeeId", employeeId));
        }
    }

    @Transactional
    public void synchronizeEmployee(Long employeeId) {
        Map<String, Object> employee = jdbc.queryForMap(
                "SELECT id,department_id,employment_status,user_type FROM employee WHERE id=? FOR UPDATE", employeeId);
        Long targetDepartment = employee.get("department_id") == null
                ? null : ((Number) employee.get("department_id")).longValue();
        boolean effective = ((Number) employee.get("employment_status")).intValue() == 1;
        List<Map<String, Object>> current = jdbc.queryForList("""
            SELECT g.conversation_id,g.department_id
              FROM comm_group g
              JOIN comm_membership_period m ON m.conversation_id=g.conversation_id
             WHERE g.group_type=1 AND m.employee_id=? AND m.open_marker=1
            """, employeeId);
        for (Map<String, Object> row : current) {
            Long department = row.get("department_id") == null ? null : ((Number) row.get("department_id")).longValue();
            if (!effective || targetDepartment == null || !targetDepartment.equals(department)) {
                closeMembership(((Number) row.get("conversation_id")).longValue(), employeeId);
            }
        }
        if (!effective || targetDepartment == null) return;
        long conversationId = ensureDepartmentGroup(targetDepartment);
        Long managerId = jdbc.query(
                "SELECT manager_id FROM department WHERE id=?",
                rs -> rs.next() ? rs.getObject(1, Long.class) : null, targetDepartment);
        ensureMembership(conversationId, employeeId,
                managerId != null && managerId.equals(employeeId) ? ROLE_ADMIN : ROLE_MEMBER);
        publish(conversationId, "department-membership.synced",
                Map.of("employeeId", employeeId, "departmentId", targetDepartment));
    }

    @Transactional
    public void synchronizeDepartmentManagers(Long departmentId, Long oldManagerId, Long newManagerId) {
        long conversationId = ensureDepartmentGroup(departmentId);
        if (oldManagerId != null && !oldManagerId.equals(newManagerId)) {
            Integer stillMember = jdbc.queryForObject("""
                SELECT COUNT(*) FROM employee
                 WHERE id=? AND department_id=? AND employment_status=1
                """, Integer.class, oldManagerId, departmentId);
            if (stillMember != null && stillMember > 0) ensureMembership(conversationId, oldManagerId, ROLE_MEMBER);
        }
        if (newManagerId != null) ensureMembership(conversationId, newManagerId, ROLE_ADMIN);
        publish(conversationId, "department-group.managers-changed",
                Map.of("departmentId", departmentId));
    }

    @Transactional
    public void renameDepartment(Long departmentId, String title) {
        long conversationId = ensureDepartmentGroup(departmentId);
        jdbc.update("UPDATE comm_group SET title=? WHERE conversation_id=?", title, conversationId);
        publish(conversationId, "group.updated", Map.of("departmentId", departmentId));
    }

    @Transactional
    public void archiveDepartment(Long departmentId, Long managerId) {
        Long conversationId = jdbc.query(
                "SELECT conversation_id FROM comm_group WHERE department_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, departmentId);
        if (conversationId == null) return;
        if (managerId != null) closeMembership(conversationId, managerId);
        jdbc.update("UPDATE comm_group SET group_status=2 WHERE conversation_id=?", conversationId);
        jdbc.update("UPDATE comm_conversation SET status=1 WHERE id=?", conversationId);
        publish(conversationId, "department-group.archived", Map.of("departmentId", departmentId));
    }

    @Transactional
    public void closeAllForEmployee(Long employeeId) {
        List<Long> conversations = jdbc.queryForList("""
            SELECT conversation_id FROM comm_membership_period
             WHERE employee_id=? AND open_marker=1
            """, Long.class, employeeId);
        for (Long conversationId : conversations) closeMembership(conversationId, employeeId);
    }

    @Transactional
    public void handleEmployeeDeactivation(Long employeeId) {
        List<Map<String, Object>> owned = jdbc.queryForList("""
            SELECT g.conversation_id,g.id
              FROM comm_group g
             WHERE g.group_type=0 AND g.group_status=0 AND g.owner_employee_id=?
             FOR UPDATE
            """, employeeId);
        for (Map<String, Object> group : owned) {
            long conversationId = ((Number) group.get("conversation_id")).longValue();
            var successor = jdbc.queryForList("""
                SELECT employee_id FROM comm_membership_period
                 WHERE conversation_id=? AND employee_id<>? AND open_marker=1
                 ORDER BY member_role DESC,joined_at,id LIMIT 1
                 FOR UPDATE
                """, conversationId, employeeId);
            if (successor.isEmpty()) {
                jdbc.update("UPDATE comm_group SET group_status=1 WHERE id=?", group.get("id"));
                jdbc.update("UPDATE comm_conversation SET status=1 WHERE id=?", conversationId);
            } else {
                Long next = ((Number) successor.get(0).get("employee_id")).longValue();
                jdbc.update("UPDATE comm_group SET owner_employee_id=? WHERE id=?", next, group.get("id"));
                jdbc.update("""
                    UPDATE comm_membership_period SET member_role=?
                     WHERE conversation_id=? AND employee_id=? AND open_marker=1
                    """, ROLE_OWNER, conversationId, next);
            }
            jdbc.update("""
                INSERT INTO comm_audit(actor_id,action,target_type,target_id,summary)
                VALUES(?,'GROUP_OWNER_DEACTIVATED','CONVERSATION',?,?)
                """, employeeId, conversationId, successor.isEmpty() ? "群主离职且无成员，群聊归档" : "群主离职后自动转让");
        }
        closeAllForEmployee(employeeId);
    }

    @Transactional
    public void bootstrapAll() {
        List<Long> departments = jdbc.queryForList(
                "SELECT id FROM department WHERE status<>2 ORDER BY id", Long.class);
        for (Long departmentId : departments) ensureDepartmentGroup(departmentId);
        List<Long> employees = jdbc.queryForList(
                "SELECT id FROM employee WHERE employment_status=1 AND department_id IS NOT NULL ORDER BY id",
                Long.class);
        for (Long employeeId : employees) synchronizeEmployee(employeeId);
    }

    public int currentRole(Long conversationId, Long employeeId) {
        List<Integer> roles = jdbc.queryForList("""
            SELECT member_role FROM comm_membership_period
             WHERE conversation_id=? AND employee_id=? AND open_marker=1
            """, Integer.class, conversationId, employeeId);
        return roles.isEmpty() ? -1 : roles.get(0);
    }

    private void publish(Long conversationId, String type, Map<String, Object> payload) {
        events.publishEvent(new CommunicationChangedEvent(conversationId, type, payload));
    }
}
