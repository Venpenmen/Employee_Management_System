package com.example.ems.communications;

import com.example.ems.service.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class CommunicationAccess {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;

    public CommunicationAccess(JdbcTemplate jdbc, SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    public void guardWrite(Long userId) {
        sessions.guardCurrent(userId, 0);
    }

    public boolean hasAnyMembership(Long conversationId, Long employeeId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM comm_membership_period
             WHERE conversation_id=? AND employee_id=?
            """, Integer.class, conversationId, employeeId);
        return count != null && count > 0;
    }

    public int currentRole(Long conversationId, Long employeeId) {
        List<Integer> values = jdbc.queryForList("""
            SELECT member_role FROM comm_membership_period
             WHERE conversation_id=? AND employee_id=? AND open_marker=1
            """, Integer.class, conversationId, employeeId);
        return values.isEmpty() ? -1 : values.get(0);
    }

    public void requireReadable(Long conversationId, Long employeeId) {
        if (!hasAnyMembership(conversationId, employeeId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
    }

    public void requireWritable(Long conversationId, Long employeeId) {
        if (currentRole(conversationId, employeeId) < 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "已退出或无权向该会话发送消息");
        }
        Integer active = jdbc.queryForObject("""
            SELECT COUNT(*) FROM comm_conversation c
             LEFT JOIN comm_group g ON g.conversation_id=c.id
             WHERE c.id=? AND c.status=0 AND (g.id IS NULL OR g.group_status=0)
            """, Integer.class, conversationId);
        if (active == null || active == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "会话已归档或解散，只读查看历史");
        }
    }

    public boolean canSeeSequence(Long conversationId, Long employeeId, Long sequence) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM comm_membership_period
             WHERE conversation_id=? AND employee_id=?
               AND ? > joined_after_seq
               AND (left_after_seq IS NULL OR ? <= left_after_seq)
            """, Integer.class, conversationId, employeeId, sequence, sequence);
        return count != null && count > 0;
    }

    public boolean canReadAttachment(Long attachmentId, Long employeeId) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
            SELECT a.message_id,a.announcement_version_id,a.bound_group_id,a.attachment_status,
                   m.conversation_id,m.conversation_seq,m.status message_status,
                   v.announcement_id,v.version_status,a2.announcement_status,a2.created_by announcement_creator
              FROM comm_attachment a
              LEFT JOIN comm_message m ON m.id=a.message_id
              LEFT JOIN comm_announcement_version v ON v.id=a.announcement_version_id
              LEFT JOIN comm_announcement a2 ON a2.id=v.announcement_id
             WHERE a.id=?
            """, attachmentId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("attachment_status")).intValue() != 1) return false;
        var row = rows.get(0);
        if (row.get("message_id") != null) {
            if (((Number) row.get("message_status")).intValue() != 0) return false;
            return canSeeSequence(((Number) row.get("conversation_id")).longValue(), employeeId,
                    ((Number) row.get("conversation_seq")).longValue());
        }
        if (row.get("announcement_version_id") != null) {
            int versionStatus = ((Number) row.get("version_status")).intValue();
            int announcementStatus = ((Number) row.get("announcement_status")).intValue();
            if (announcementStatus == 1 && versionStatus == 1) {
                Integer recipient = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM comm_announcement_recipient
                     WHERE version_id=? AND employee_id=?
                    """, Integer.class, row.get("announcement_version_id"), employeeId);
                return recipient != null && recipient > 0;
            }
            Integer userType = jdbc.queryForObject("SELECT user_type FROM employee WHERE id=?", Integer.class, employeeId);
            return (userType != null && userType == 2) || number(row.get("announcement_creator")) == employeeId;
        }
        if (row.get("bound_group_id") != null) {
            Long conversationId = jdbc.queryForObject(
                    "SELECT conversation_id FROM comm_group WHERE id=?", Long.class, row.get("bound_group_id"));
            return hasAnyMembership(conversationId, employeeId);
        }
        return false;
    }

    private static long number(Object value) { return ((Number) value).longValue(); }
}
