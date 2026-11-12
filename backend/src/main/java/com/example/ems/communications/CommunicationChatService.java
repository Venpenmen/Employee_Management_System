package com.example.ems.communications;

import com.example.ems.model.SessionUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class CommunicationChatService {
    private final JdbcTemplate jdbc;
    private final CommunicationAccess access;
    private final CommunicationOrganizationService organizations;
    private final CommunicationAttachmentService attachments;
    private final ApplicationEventPublisher events;

    public CommunicationChatService(JdbcTemplate jdbc, CommunicationAccess access,
                                    CommunicationOrganizationService organizations,
                                    CommunicationAttachmentService attachments,
                                    ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.access = access;
        this.organizations = organizations;
        this.attachments = attachments;
        this.events = events;
    }

    public List<Map<String, Object>> contacts(String keyword, Long departmentId, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT e.id,e.employee_no,e.name,d.id department_id,d.department_name,p.position_name
              FROM employee e
              JOIN user_account a ON a.employee_id=e.id
              LEFT JOIN department d ON d.id=e.department_id

              LEFT JOIN position_info p ON p.id=e.position_id
             WHERE e.employment_status=1 AND a.account_status=1 AND (d.id IS NULL OR d.status<>2)
            """);
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (e.name LIKE ? OR e.employee_no LIKE ? OR d.department_name LIKE ?)");
            String value = "%" + keyword.trim() + "%";
            args.add(value); args.add(value); args.add(value);
        }
        if (departmentId != null) {
            sql.append(" AND e.department_id=?");
            args.add(departmentId);
        }
        sql.append(" ORDER BY d.id,e.employee_no LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> conversations(SessionUser user) {
        List<Map<String, Object>> base = jdbc.queryForList("""
            SELECT c.id,c.conversation_type,c.status,g.id group_id,g.group_type,g.group_status,
                   g.title,g.description,g.owner_employee_id,g.department_id,
                   (SELECT a.id FROM comm_attachment a WHERE a.bound_group_id=g.id
                     AND a.attachment_kind=2 AND a.attachment_status=1 ORDER BY a.id DESC LIMIT 1) avatar_attachment_id,
                   s.last_read_seq,s.pinned_at,s.hidden_at,s.hidden_before_seq
              FROM comm_conversation c
              LEFT JOIN comm_group g ON g.conversation_id=c.id
              JOIN comm_conversation_user_state s ON s.conversation_id=c.id AND s.employee_id=?
             WHERE EXISTS (SELECT 1 FROM comm_membership_period p
                            WHERE p.conversation_id=c.id AND p.employee_id=?)
             ORDER BY (s.pinned_at IS NOT NULL) DESC,s.pinned_at DESC,c.update_time DESC
            """, user.id(), user.id());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : base) {
            long conversationId = number(row.get("id"));
            Map<String, Object> latest = latestVisible(conversationId, user.id());
            long latestSeq = latest == null ? 0 : number(latest.get("conversation_seq"));
            Object hiddenAt = row.get("hidden_at");
            long hiddenBefore = row.get("hidden_before_seq") == null ? 0 : number(row.get("hidden_before_seq"));
            if (hiddenAt != null && latestSeq <= hiddenBefore) continue;
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("latest", latest);
            item.put("unread_count", unread(conversationId, user.id()));
            item.put("current_role", access.currentRole(conversationId, user.id()));
            if (((Number) row.get("conversation_type")).intValue() == 0) {
                Map<String, Object> peer = jdbc.queryForMap("""
                    SELECT e.id,e.employee_no,e.name,d.department_name,pinfo.position_name
                      FROM comm_conversation c
                      JOIN comm_membership_period p ON p.conversation_id=c.id AND p.employee_id<>?
                      JOIN employee e ON e.id=p.employee_id
                      LEFT JOIN department d ON d.id=e.department_id
                   LEFT JOIN position_info pinfo ON pinfo.id=e.position_id
                     WHERE c.id=? ORDER BY p.id LIMIT 1
                    """, user.id(), conversationId);
                item.put("title", peer.get("name"));
                item.put("peer", peer);
            }
            result.add(item);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> openDirect(SessionUser user, long targetId) {
        access.guardWrite(user.id());
        if (targetId == user.id()) throw new IllegalArgumentException("不能与自己创建私聊");
        Integer target = jdbc.queryForObject("""
            SELECT COUNT(*) FROM employee e JOIN user_account a ON a.employee_id=e.id
             WHERE e.id=? AND e.employment_status=1 AND a.account_status=1
            """, Integer.class, targetId);
        if (target == null || target == 0) throw new IllegalArgumentException("目标员工当前不可通讯");
        long min = Math.min(user.id(), targetId);
        long max = Math.max(user.id(), targetId);
        String directKey = min + ":" + max;
        List<Long> found = jdbc.queryForList(
                "SELECT id FROM comm_conversation WHERE direct_key=? FOR UPDATE", Long.class, directKey);
        long conversationId;
        if (found.isEmpty()) {
            jdbc.update("INSERT INTO comm_conversation(conversation_type,direct_key,status) VALUES(0,?,0)", directKey);
            conversationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            organizations.ensureMembership(conversationId, user.id(), CommunicationOrganizationService.ROLE_MEMBER);
            organizations.ensureMembership(conversationId, targetId, CommunicationOrganizationService.ROLE_MEMBER);
            events.publishEvent(new CommunicationChangedEvent(conversationId, "conversation.created",
                    Map.of("targetIds", List.of(user.id(), targetId))));
        } else conversationId = found.get(0);
        jdbc.update("UPDATE comm_conversation_user_state SET hidden_at=NULL WHERE conversation_id=? AND employee_id=?",
                conversationId, user.id());
        return conversation(conversationId, user.id());
    }

    public Map<String, Object> conversation(long conversationId, long userId) {
        access.requireReadable(conversationId, userId);
        Map<String, Object> detail = new LinkedHashMap<>(jdbc.queryForMap("""
            SELECT c.*,g.id group_id,g.group_type,g.group_status,g.title,g.description,g.owner_employee_id,g.department_id,
                   s.last_read_seq,s.pinned_at,s.hidden_at,s.hidden_before_seq
              FROM comm_conversation c LEFT JOIN comm_group g ON g.conversation_id=c.id
              JOIN comm_conversation_user_state s ON s.conversation_id=c.id AND s.employee_id=?
             WHERE c.id=?
            """, userId, conversationId));
        if (number(detail.get("conversation_type")) == 0) {
            Map<String, Object> peer = jdbc.queryForMap("""
                SELECT e.id,e.employee_no,e.name,d.department_name,pinfo.position_name
                  FROM comm_membership_period p
                  JOIN employee e ON e.id=p.employee_id
                  LEFT JOIN department d ON d.id=e.department_id
                   LEFT JOIN position_info pinfo ON pinfo.id=e.position_id
                 WHERE p.conversation_id=? AND p.employee_id<>?
                 ORDER BY p.id LIMIT 1
                """, conversationId, userId);
            detail.put("peer", peer);
            detail.put("title", peer.get("name"));
        }
        return detail;
    }

    public Map<String, Object> messages(SessionUser user, long conversationId, Long beforeSeq, Long afterSeq,
                                        String keyword, String from, String to, int requestedSize) {
        access.requireReadable(conversationId, user.id());
        int size = requestedSize == 30 || requestedSize == 50 || requestedSize == 100 ? requestedSize : 30;
        StringBuilder sql = new StringBuilder("""
            SELECT m.id,m.conversation_id,m.conversation_seq,m.sender_id,s.name sender_name,m.client_message_id,
                   m.message_type,m.body_text,m.mention_all,m.quoted_message_id,m.status,m.sent_at,m.recalled_at,
                   q.body_text quoted_body,q.status quoted_status,q.conversation_seq quoted_seq,
                   (SELECT COUNT(DISTINCT p.employee_id) FROM comm_membership_period p
                     WHERE p.conversation_id=m.conversation_id AND p.employee_id<>m.sender_id
                       AND m.conversation_seq>p.joined_after_seq
                       AND (p.left_after_seq IS NULL OR m.conversation_seq<=p.left_after_seq)) recipients,
                   (SELECT COUNT(DISTINCT p.employee_id) FROM comm_membership_period p
                     JOIN comm_conversation_user_state st ON st.conversation_id=p.conversation_id
                                                       AND st.employee_id=p.employee_id
                     WHERE p.conversation_id=m.conversation_id AND p.employee_id<>m.sender_id
                       AND m.conversation_seq>p.joined_after_seq
                       AND (p.left_after_seq IS NULL OR m.conversation_seq<=p.left_after_seq)
                       AND st.last_read_seq>=m.conversation_seq) read_count
              FROM comm_message m
              JOIN employee s ON s.id=m.sender_id
              LEFT JOIN comm_message q ON q.id=m.quoted_message_id
             WHERE m.conversation_id=?
               AND EXISTS (SELECT 1 FROM comm_membership_period p
                            WHERE p.conversation_id=m.conversation_id AND p.employee_id=?
                              AND m.conversation_seq>p.joined_after_seq
                              AND (p.left_after_seq IS NULL OR m.conversation_seq<=p.left_after_seq))
            """);
        List<Object> args = new ArrayList<>(List.of(conversationId, user.id()));
        if (beforeSeq != null) { sql.append(" AND m.conversation_seq<?"); args.add(beforeSeq); }
        if (afterSeq != null) { sql.append(" AND m.conversation_seq>?"); args.add(afterSeq); }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND m.body_text LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        if (from != null && !from.isBlank()) {
            sql.append(" AND m.sent_at>=?");
            args.add(time(from));
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND m.sent_at<=?");
            args.add(time(to));
        }
        sql.append(" ORDER BY m.conversation_seq DESC LIMIT ?");
        args.add(size + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, size));
        for (Map<String, Object> row : rows) {
            if (((Number) row.get("status")).intValue() == 1) {
                row.put("body_text", null);
                row.put("quoted_body", null);
                row.put("attachments", List.of());
            } else {
                row.put("attachments", attachments.forMessage(number(row.get("id"))));
            }
            if (row.get("quoted_status") != null && ((Number) row.get("quoted_status")).intValue() == 1) {
                row.put("quoted_body", null);
            }
            row.put("mentions", jdbc.queryForList("""
                SELECT e.id,e.name FROM comm_message_mention mm JOIN employee e ON e.id=mm.employee_id
                 WHERE mm.message_id=? ORDER BY e.name
                """, number(row.get("id"))));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("has_more", hasMore);
        result.put("next_before_seq", rows.isEmpty() ? null : rows.get(rows.size() - 1).get("conversation_seq"));
        return result;
    }

    @Transactional
    public Map<String, Object> send(SessionUser user, long conversationId, String clientMessageId,
                                    String content, Long quotedMessageId, List<Long> mentionIds,
                                    boolean mentionAll, List<MultipartFile> files) {
        access.guardWrite(user.id());
        if (clientMessageId == null || !clientMessageId.matches("[a-fA-F0-9-]{36}")) {
            throw new IllegalArgumentException("客户端消息标识无效");
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM comm_message WHERE sender_id=? AND client_message_id=?",
                user.id(), clientMessageId);
        if (!existing.isEmpty()) return messageById(number(existing.get(0).get("id")), user.id());
        access.requireWritable(conversationId, user.id());
        Integer privateConversation = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comm_conversation WHERE id=? AND conversation_type=0", Integer.class, conversationId);
        if (privateConversation != null && privateConversation == 1) {
            Integer unavailable = jdbc.queryForObject("""
                SELECT COUNT(*) FROM comm_membership_period p
                  LEFT JOIN employee e ON e.id=p.employee_id
                  LEFT JOIN user_account a ON a.employee_id=e.id
                 WHERE p.conversation_id=? AND p.open_marker=1
                   AND (e.id IS NULL OR e.employment_status<>1 OR a.account_status<>1)
                """, Integer.class, conversationId);
            if (unavailable != null && unavailable > 0) {
                throw new IllegalArgumentException("对方当前已离职或不可通讯，不能继续发送新消息");
            }
        }
        jdbc.queryForList("SELECT id FROM comm_conversation WHERE id=? FOR UPDATE", conversationId);
        existing = jdbc.queryForList("SELECT id FROM comm_message WHERE sender_id=? AND client_message_id=?",
                user.id(), clientMessageId);
        if (!existing.isEmpty()) return messageById(number(existing.get(0).get("id")), user.id());
        String text = content == null ? "" : content.trim();
        if (text.length() > 4000) throw new IllegalArgumentException("消息不能超过4000字");
        List<MultipartFile> uploads = files == null ? List.of() : files.stream()
                .filter(file -> file != null && !file.isEmpty()).toList();
        if (uploads.size() > CommunicationAttachmentService.MAX_MESSAGE_FILES) {
            throw new IllegalArgumentException("每条消息最多5个附件");
        }
        long total = uploads.stream().mapToLong(MultipartFile::getSize).sum();
        if (total > CommunicationAttachmentService.MAX_REQUEST_BYTES) throw new IllegalArgumentException("附件总大小超过限制");
        if (text.isEmpty() && uploads.isEmpty()) throw new IllegalArgumentException("消息正文和附件不能同时为空");
        if (quotedMessageId != null) {
            List<Map<String, Object>> quote = jdbc.queryForList("""
                SELECT conversation_id,conversation_seq,status FROM comm_message WHERE id=?
                """, quotedMessageId);
            if (quote.isEmpty() || number(quote.get(0).get("conversation_id")) != conversationId
                    || !access.canSeeSequence(conversationId, user.id(), number(quote.get(0).get("conversation_seq")))) {
                throw new IllegalArgumentException("引用的消息不存在或无权查看");
            }
        }
        boolean group = jdbc.queryForObject(
                "SELECT conversation_type FROM comm_conversation WHERE id=?", Integer.class, conversationId) == 1;
        if (mentionAll && (!group || access.currentRole(conversationId, user.id()) < CommunicationOrganizationService.ROLE_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有群主或管理员可以@全体成员");
        }
        List<Long> mentions = validateMentions(conversationId, mentionIds);
        List<CommunicationAttachmentService.Staged> staged = new ArrayList<>();
        try {
            for (MultipartFile file : uploads) staged.add(attachments.stage(file));
            jdbc.update("""
                UPDATE comm_conversation SET next_message_seq=LAST_INSERT_ID(next_message_seq+1) WHERE id=?
                """, conversationId);
            long seq = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            int type = uploads.isEmpty() ? 0 : 1;
            jdbc.update("""
                INSERT INTO comm_message(conversation_id,conversation_seq,sender_id,client_message_id,
                                         message_type,body_text,mention_all,quoted_message_id,status,sent_at)
                VALUES(?,?,?,?,?,?,?,?,0,NOW(6))
                """, conversationId, seq, user.id(), clientMessageId.toLowerCase(Locale.ROOT),
                    type, text.isEmpty() ? null : text, mentionAll ? 1 : 0, quotedMessageId);
            long messageId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            for (CommunicationAttachmentService.Staged item : staged) {
                attachments.bindMessage(messageId, user.id(), item);
            }
            for (Long mention : mentions) {
                jdbc.update("INSERT INTO comm_message_mention(message_id,employee_id) VALUES(?,?)", messageId, mention);
            }
            jdbc.update("""
                UPDATE comm_conversation_user_state SET last_read_seq=GREATEST(last_read_seq,?),hidden_at=NULL
                 WHERE conversation_id=? AND employee_id=?
                """, seq, conversationId, user.id());
            events.publishEvent(new CommunicationChangedEvent(conversationId, "message.created",
                    Map.of("messageId", messageId, "sequence", seq, "senderId", user.id())));
            return messageById(messageId, user.id());
        } catch (RuntimeException error) {
            attachments.discard(staged);
            throw error;
        }
    }

    @Transactional
    public void recall(SessionUser user, long messageId) {
        access.guardWrite(user.id());
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id,conversation_id,sender_id,status,sent_at FROM comm_message WHERE id=? FOR UPDATE
            """, messageId);
        if (rows.isEmpty()) throw new IllegalArgumentException("消息不存在");
        Map<String, Object> row = rows.get(0);
        if (number(row.get("sender_id")) != user.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能撤回自己发送的消息");
        if (((Number) row.get("status")).intValue() == 1) return;
        Integer allowed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comm_message WHERE id=? AND sent_at>=DATE_SUB(NOW(6),INTERVAL 2 MINUTE)",
                Integer.class, messageId);
        if (allowed == null || allowed == 0) throw new IllegalArgumentException("消息发送超过两分钟，不能撤回");
        long conversationId = number(row.get("conversation_id"));
        jdbc.update("""
            UPDATE comm_message SET status=1,body_text=NULL,mention_all=0,recalled_at=NOW(6),recalled_by=? WHERE id=?
            """, user.id(), messageId);
        jdbc.update("UPDATE comm_attachment SET attachment_status=2 WHERE message_id=?", messageId);
        jdbc.update("DELETE FROM comm_message_mention WHERE message_id=?", messageId);
        events.publishEvent(new CommunicationChangedEvent(conversationId, "message.recalled",
                Map.of("messageId", messageId, "senderId", user.id())));
    }

    @Transactional
    public void markRead(SessionUser user, long conversationId, long sequence) {
        access.guardWrite(user.id());
        access.requireReadable(conversationId, user.id());
        int updated = jdbc.update("""
            UPDATE comm_conversation_user_state SET last_read_seq=?
             WHERE conversation_id=? AND employee_id=? AND last_read_seq<?
            """, sequence, conversationId, user.id(), sequence);
        if (updated > 0) {
            events.publishEvent(new CommunicationChangedEvent(conversationId, "message.read",
                    Map.of("employeeId", user.id(), "sequence", sequence)));
        }
    }

    @Transactional
    public void pin(SessionUser user, long conversationId, boolean pinned) {
        access.guardWrite(user.id());
        access.requireReadable(conversationId, user.id());
        jdbc.update("UPDATE comm_conversation_user_state SET pinned_at=" + (pinned ? "NOW(6)" : "NULL")
                + " WHERE conversation_id=? AND employee_id=?", conversationId, user.id());
    }

    @Transactional
    public void hide(SessionUser user, long conversationId) {
        access.guardWrite(user.id());
        access.requireReadable(conversationId, user.id());
        Long seq = jdbc.queryForObject("SELECT next_message_seq FROM comm_conversation WHERE id=?", Long.class, conversationId);
        jdbc.update("""
            UPDATE comm_conversation_user_state SET hidden_at=NOW(6),hidden_before_seq=?
             WHERE conversation_id=? AND employee_id=?
            """, seq, conversationId, user.id());
    }

    @Transactional
    public void removeFromList(SessionUser user, long conversationId) {
        access.guardWrite(user.id());
        access.requireReadable(conversationId, user.id());
        Long seq = jdbc.queryForObject(
                "SELECT next_message_seq FROM comm_conversation WHERE id=?", Long.class, conversationId);
        jdbc.update("""
            UPDATE comm_conversation_user_state
               SET hidden_at=NOW(6),hidden_before_seq=?,pinned_at=NULL
             WHERE conversation_id=? AND employee_id=?
            """, seq, conversationId, user.id());
    }

    @Transactional
    public Map<String, Object> createGroup(SessionUser user, String title, String description,
                                           List<Long> memberIds, MultipartFile avatar) {
        access.guardWrite(user.id());
        String name = required(title, "群名称不能为空", 80);
        jdbc.update("INSERT INTO comm_conversation(conversation_type,status) VALUES(1,0)");
        long conversationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
            INSERT INTO comm_group(conversation_id,group_type,title,description,owner_employee_id,group_status,created_by)
            VALUES(?,0,?,?, ?,0,?)
            """, conversationId, name, description == null ? null : cut(description, 500), user.id(), user.id());
        organizations.ensureMembership(conversationId, user.id(), CommunicationOrganizationService.ROLE_OWNER);
        Set<Long> unique = memberIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(memberIds);
        unique.remove(user.id());
        for (Long employeeId : unique) {
            requireActiveEmployee(employeeId);
            organizations.ensureMembership(conversationId, employeeId, CommunicationOrganizationService.ROLE_MEMBER);
        }
        if (avatar != null && !avatar.isEmpty()) {
            var staged = attachments.stage(avatar);
            if (!staged.contentType().startsWith("image/")) {
                attachments.discard(List.of(staged));
                throw new IllegalArgumentException("群头像必须是 PNG、JPEG、GIF 或 WebP 图片");
            }
            long groupId = jdbc.queryForObject("SELECT id FROM comm_group WHERE conversation_id=?", Long.class, conversationId);
            attachments.bindGroupAvatar(groupId, user.id(), staged);
        }
        jdbc.update("INSERT INTO comm_audit(actor_id,action,target_type,target_id,summary) VALUES(?,'GROUP_CREATE','CONVERSATION',?,?)",
                user.id(), conversationId, "创建普通群 " + name);
        events.publishEvent(new CommunicationChangedEvent(conversationId, "group.created",
                Map.of("ownerId", user.id())));
        return conversation(conversationId, user.id());
    }

    @Transactional
    public void updateGroup(SessionUser user, long conversationId, String title, String description, MultipartFile avatar) {
        Map<String, Object> group = requireGroupAdmin(user, conversationId, false);
        boolean department = ((Number) group.get("group_type")).intValue() == 1;
        String currentTitle = String.valueOf(group.get("title"));
        if (title != null && !title.isBlank() && !title.trim().equals(currentTitle)) {
            if (department) throw new IllegalArgumentException("部门群名称由系统部门名称同步");
            jdbc.update("UPDATE comm_group SET title=? WHERE conversation_id=?", required(title, "群名称不能为空", 80), conversationId);
        }
        if (description != null) jdbc.update("UPDATE comm_group SET description=? WHERE conversation_id=?",
                cut(description, 500), conversationId);
        if (avatar != null && !avatar.isEmpty()) {
            long groupId = number(group.get("id"));
            var staged = attachments.stage(avatar);
            if (!staged.contentType().startsWith("image/")) {
                attachments.discard(List.of(staged));
                throw new IllegalArgumentException("群头像必须是 PNG、JPEG、GIF 或 WebP 图片");
            }
            attachments.bindGroupAvatar(groupId, user.id(), staged);
        }
        audit(user.id(), "GROUP_UPDATE", conversationId, "修改群资料");
        events.publishEvent(new CommunicationChangedEvent(conversationId, "group.updated", Map.of()));
    }

    @Transactional
    public void addMembers(SessionUser user, long conversationId, List<Long> employeeIds) {
        Map<String, Object> group = requireGroupAdmin(user, conversationId, false);
        if (((Number) group.get("group_type")).intValue() == 1) throw new IllegalArgumentException("部门群成员由组织关系自动同步");
        if (employeeIds == null) return;
        for (Long employeeId : new LinkedHashSet<>(employeeIds)) {
            requireActiveEmployee(employeeId);
            organizations.ensureMembership(conversationId, employeeId, CommunicationOrganizationService.ROLE_MEMBER);
        }
        audit(user.id(), "GROUP_MEMBER_ADD", conversationId, "邀请成员");
    }

    @Transactional
    public void removeMember(SessionUser user, long conversationId, long employeeId) {
        Map<String, Object> group = requireGroupAdmin(user, conversationId, false);
        if (((Number) group.get("group_type")).intValue() == 1) throw new IllegalArgumentException("部门群成员由组织关系自动同步");
        int actorRole = access.currentRole(conversationId, user.id());
        int targetRole = access.currentRole(conversationId, employeeId);
        if (targetRole < 0) throw new IllegalArgumentException("该员工不在当前群周期中");
        if (targetRole == CommunicationOrganizationService.ROLE_OWNER) throw new IllegalArgumentException("不能移除群主");
        if (actorRole < CommunicationOrganizationService.ROLE_OWNER && targetRole >= CommunicationOrganizationService.ROLE_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员不能移除其他管理员");
        }
        organizations.closeMembership(conversationId, employeeId);
        audit(user.id(), "GROUP_MEMBER_REMOVE", conversationId, "移除成员 " + employeeId);
        events.publishEvent(new CommunicationChangedEvent(conversationId, "group.members-changed", Map.of()));
    }

    @Transactional
    public void leaveGroup(SessionUser user, long conversationId) {
        Map<String, Object> group = requireGroupMember(conversationId, user.id());
        if (((Number) group.get("group_type")).intValue() == 1) throw new IllegalArgumentException("部门群由组织关系自动维护，不能手动退出");
        if (access.currentRole(conversationId, user.id()) == CommunicationOrganizationService.ROLE_OWNER) {
            throw new IllegalArgumentException("请先转让群主后再退出");
        }
        organizations.closeMembership(conversationId, user.id());
        audit(user.id(), "GROUP_LEAVE", conversationId, "退出群聊");
    }

    @Transactional
    public void setAdmin(SessionUser user, long conversationId, long employeeId, boolean admin) {
        Map<String, Object> group = requireGroupAdmin(user, conversationId, true);
        if (((Number) group.get("group_type")).intValue() == 1) throw new IllegalArgumentException("部门群管理员由部门经理自动维护");
        if (access.currentRole(conversationId, employeeId) < 0) throw new IllegalArgumentException("成员不在当前群周期中");
        jdbc.update("""
            UPDATE comm_membership_period SET member_role=?
             WHERE conversation_id=? AND employee_id=? AND open_marker=1 AND member_role<>2
            """, admin ? CommunicationOrganizationService.ROLE_ADMIN : CommunicationOrganizationService.ROLE_MEMBER,
                conversationId, employeeId);
        audit(user.id(), "GROUP_ADMIN_CHANGE", conversationId, (admin ? "设置管理员 " : "取消管理员 ") + employeeId);
        events.publishEvent(new CommunicationChangedEvent(conversationId, "group.members-changed", Map.of()));
    }

    @Transactional
    public void transferOwner(SessionUser user, long conversationId, long employeeId) {
        Map<String, Object> group = requireGroupAdmin(user, conversationId, true);
        if (((Number) group.get("group_type")).intValue() == 1) throw new IllegalArgumentException("部门群不能转让所有权");
        if (access.currentRole(conversationId, employeeId) < 0) throw new IllegalArgumentException("新群主必须是当前成员");
        jdbc.update("UPDATE comm_membership_period SET member_role=? WHERE conversation_id=? AND employee_id=? AND open_marker=1",
                CommunicationOrganizationService.ROLE_ADMIN, conversationId, user.id());
        jdbc.update("UPDATE comm_membership_period SET member_role=? WHERE conversation_id=? AND employee_id=? AND open_marker=1",
                CommunicationOrganizationService.ROLE_OWNER, conversationId, employeeId);
        jdbc.update("UPDATE comm_group SET owner_employee_id=? WHERE conversation_id=?", employeeId, conversationId);
        audit(user.id(), "GROUP_OWNER_TRANSFER", conversationId, "群主转让给 " + employeeId);
        events.publishEvent(new CommunicationChangedEvent(conversationId, "group.members-changed", Map.of()));
    }

    @Transactional
    public void dissolveGroup(SessionUser user, long conversationId) {
        Map<String, Object> group = requireGroupAdmin(user, conversationId, true);
        if (((Number) group.get("group_type")).intValue() == 1) throw new IllegalArgumentException("部门群不能由员工解散");
        jdbc.update("UPDATE comm_group SET group_status=1 WHERE conversation_id=?", conversationId);
        jdbc.update("UPDATE comm_conversation SET status=1 WHERE id=?", conversationId);
        audit(user.id(), "GROUP_DISSOLVE", conversationId, "解散普通群");
        events.publishEvent(new CommunicationChangedEvent(conversationId, "group.dissolved", Map.of()));
    }

    public List<Map<String, Object>> members(long conversationId, long userId) {
        access.requireReadable(conversationId, userId);
        return jdbc.queryForList("""
            SELECT e.id,e.employee_no,e.name,p.member_role,p.joined_after_seq,p.left_after_seq,p.joined_at,p.left_at
              FROM comm_membership_period p JOIN employee e ON e.id=p.employee_id
             WHERE p.conversation_id=? ORDER BY (p.open_marker IS NOT NULL) DESC,p.member_role DESC,e.name
            """, conversationId);
    }

    public List<Map<String, Object>> groupFiles(SessionUser user, long conversationId) {
        access.requireReadable(conversationId, user.id());
        return jdbc.queryForList("""
            SELECT a.id,a.original_name,a.content_type,a.byte_size,a.sha256,m.id message_id,m.sent_at,e.name sender_name
              FROM comm_attachment a
              JOIN comm_message m ON m.id=a.message_id
              JOIN employee e ON e.id=m.sender_id
             WHERE m.conversation_id=? AND m.status=0 AND a.attachment_status=1
               AND EXISTS (SELECT 1 FROM comm_membership_period p
                            WHERE p.conversation_id=m.conversation_id AND p.employee_id=?
                              AND m.conversation_seq>p.joined_after_seq
                              AND (p.left_after_seq IS NULL OR m.conversation_seq<=p.left_after_seq))
             ORDER BY m.conversation_seq DESC,a.id
            """, conversationId, user.id());
    }

    private Map<String, Object> latestVisible(long conversationId, long employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT m.id,m.conversation_seq,m.sender_id,e.name sender_name,m.message_type,m.body_text,m.status,m.sent_at
              FROM comm_message m JOIN employee e ON e.id=m.sender_id
             WHERE m.conversation_id=?
               AND EXISTS (SELECT 1 FROM comm_membership_period p
                            WHERE p.conversation_id=m.conversation_id AND p.employee_id=?
                              AND m.conversation_seq>p.joined_after_seq
                              AND (p.left_after_seq IS NULL OR m.conversation_seq<=p.left_after_seq))
             ORDER BY m.conversation_seq DESC LIMIT 1
            """, conversationId, employeeId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("status")).intValue() == 1) row.put("body_text", "消息已撤回");
        return row;
    }

    private long unread(long conversationId, long employeeId) {
        Map<String, Object> state = jdbc.queryForMap("""
            SELECT last_read_seq FROM comm_conversation_user_state
             WHERE conversation_id=? AND employee_id=? AND EXISTS
                   (SELECT 1 FROM comm_membership_period p WHERE p.conversation_id=? AND p.employee_id=? AND p.open_marker=1)
            """, conversationId, employeeId, conversationId, employeeId);
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM comm_message m
             WHERE m.conversation_id=? AND m.sender_id<>? AND m.conversation_seq>?
               AND EXISTS (SELECT 1 FROM comm_membership_period p
                            WHERE p.conversation_id=m.conversation_id AND p.employee_id=? AND p.open_marker=1
                              AND m.conversation_seq>p.joined_after_seq)
            """, Long.class, conversationId, employeeId, number(state.get("last_read_seq")), employeeId);
    }

    private Map<String, Object> messageById(long messageId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT m.*,e.name sender_name FROM comm_message m JOIN employee e ON e.id=m.sender_id WHERE m.id=?
            """, messageId);
        if (rows.isEmpty()) throw new IllegalArgumentException("消息不存在");
        Map<String, Object> row = rows.get(0);
        long conversationId = number(row.get("conversation_id"));
        long seq = number(row.get("conversation_seq"));
        if (!access.canSeeSequence(conversationId, userId, seq)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该消息");
        boolean recalled = ((Number) row.get("status")).intValue() == 1;
        if (recalled) row.put("body_text", null);
        row.put("attachments", recalled ? List.of() : attachments.forMessage(messageId));
        row.put("mentions", jdbc.queryForList("""
            SELECT e.id,e.name FROM comm_message_mention mm JOIN employee e ON e.id=mm.employee_id
             WHERE mm.message_id=? ORDER BY e.name
            """, messageId));
        return row;
    }

    private List<Long> validateMentions(long conversationId, List<Long> mentionIds) {
        if (mentionIds == null || mentionIds.isEmpty()) return List.of();
        List<Long> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(mentionIds)) {
            if (access.currentRole(conversationId, id) < 0) throw new IllegalArgumentException("@成员必须是当前群成员");
            out.add(id);
        }
        return out;
    }

    private Map<String, Object> requireGroupMember(long conversationId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT g.* FROM comm_group g JOIN comm_conversation c ON c.id=g.conversation_id
             WHERE g.conversation_id=? AND c.conversation_type=1
            """, conversationId);
        if (rows.isEmpty()) throw new IllegalArgumentException("群聊不存在");
        if (access.currentRole(conversationId, userId) < 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不是当前群成员");
        return rows.get(0);
    }

    private Map<String, Object> requireGroupAdmin(SessionUser user, long conversationId, boolean ownerOnly) {
        access.guardWrite(user.id());
        Map<String, Object> group = requireGroupMember(conversationId, user.id());
        if (((Number) group.get("group_status")).intValue() != 0) throw new IllegalArgumentException("群聊已解散或归档");
        int role = access.currentRole(conversationId, user.id());
        if (ownerOnly && role != CommunicationOrganizationService.ROLE_OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有群主可以执行此操作");
        }
        if (!ownerOnly && role < CommunicationOrganizationService.ROLE_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有群主或管理员可以执行此操作");
        }
        if (ownerOnly && ((Number) group.get("group_type")).intValue() == 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "部门群由系统管理");
        }
        return group;
    }

    private void requireActiveEmployee(long employeeId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM employee e JOIN user_account a ON a.employee_id=e.id
             WHERE e.id=? AND e.employment_status=1 AND a.account_status=1
            """, Integer.class, employeeId);
        if (count == null || count == 0) throw new IllegalArgumentException("所选员工当前不可通讯");
    }

    private void audit(long actor, String action, long conversationId, String summary) {
        jdbc.update("INSERT INTO comm_audit(actor_id,action,target_type,target_id,summary) VALUES(?,?, 'CONVERSATION',?,?)",
                actor, action, conversationId, summary);
    }

    private static String required(String value, String message, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("内容超过长度限制");
        return normalized;
    }

    private static String cut(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static long number(Object value) { return ((Number) value).longValue(); }

    private static java.sql.Timestamp time(String value) {
        try {
            return java.sql.Timestamp.valueOf(LocalDateTime.parse(value));
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("时间范围格式不正确");
        }
    }

    private static LocalDateTime toTime(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return (LocalDateTime) value;
    }
}
