package com.example.ems.communications;

import com.example.ems.model.SessionUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/communications")
public class CommunicationController {
    private final CommunicationChatService chats;
    private final CommunicationAnnouncementService announcements;

    public CommunicationController(CommunicationChatService chats, CommunicationAnnouncementService announcements) {
        this.chats = chats;
        this.announcements = announcements;
    }

    @GetMapping("/contacts")
    public Map<String, Object> contacts(HttpSession session, @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Long departmentId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return ok(chats.contacts(keyword, departmentId, limit));
    }

    @GetMapping("/conversations")
    public Map<String, Object> conversations(HttpSession session) {
        return ok(chats.conversations(user(session)));
    }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> conversation(HttpSession session, @PathVariable long id) {
        return ok(chats.conversation(id, user(session).id()));
    }

    @PostMapping("/conversations/direct")
    public Map<String, Object> direct(HttpSession session, @RequestParam long employeeId) {
        return ok(chats.openDirect(user(session), employeeId));
    }

    @GetMapping("/conversations/{id}/messages")
    public Map<String, Object> messages(HttpSession session, @PathVariable long id,
                                        @RequestParam(required = false) Long beforeSeq,
                                        @RequestParam(required = false) Long afterSeq,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(defaultValue = "30") int size) {
        return ok(chats.messages(user(session), id, beforeSeq, afterSeq, keyword, from, to, size));
    }

    @PostMapping("/conversations/{id}/messages")
    public Map<String, Object> send(HttpSession session, @PathVariable long id,
                                    @RequestParam String clientMessageId,
                                    @RequestParam(required = false) String content,
                                    @RequestParam(required = false) Long quotedMessageId,
                                    @RequestParam(required = false) List<Long> mentionIds,
                                    @RequestParam(defaultValue = "false") boolean mentionAll,
                                    @RequestParam(required = false, name = "files") List<MultipartFile> files) {
        return ok(chats.send(user(session), id, clientMessageId, content, quotedMessageId,
                mentionIds, mentionAll, files));
    }

    @PostMapping("/messages/{id}/recall")
    public Map<String, Object> recall(HttpSession session, @PathVariable long id) {
        chats.recall(user(session), id);
        return ok(Map.of("recalled", true));
    }

    @PostMapping("/conversations/{id}/read")
    public Map<String, Object> read(HttpSession session, @PathVariable long id, @RequestParam long sequence) {
        chats.markRead(user(session), id, sequence);
        return ok(Map.of("sequence", sequence));
    }

    @PostMapping("/conversations/{id}/pin")
    public Map<String, Object> pin(HttpSession session, @PathVariable long id,
                                   @RequestParam(defaultValue = "true") boolean pinned) {
        chats.pin(user(session), id, pinned);
        return ok(Map.of("pinned", pinned));
    }

    @PostMapping("/conversations/{id}/hide")
    public Map<String, Object> hide(HttpSession session, @PathVariable long id) {
        chats.hide(user(session), id);
        return ok(Map.of("hidden", true));
    }

    @PostMapping("/conversations/{id}/remove")
    public Map<String, Object> removeFromList(HttpSession session, @PathVariable long id) {
        chats.removeFromList(user(session), id);
        return ok(Map.of("removed", true));
    }

    @GetMapping("/conversations/{id}/members")
    public Map<String, Object> members(HttpSession session, @PathVariable long id) {
        return ok(chats.members(id, user(session).id()));
    }

    @GetMapping("/conversations/{id}/files")
    public Map<String, Object> files(HttpSession session, @PathVariable long id) {
        return ok(chats.groupFiles(user(session), id));
    }

    @PostMapping("/groups")
    public Map<String, Object> createGroup(HttpSession session, @RequestParam String title,
                                           @RequestParam(required = false) String description,
                                           @RequestParam(required = false) List<Long> memberIds,
                                           @RequestParam(required = false, name = "avatar") MultipartFile avatar) {
        return ok(chats.createGroup(user(session), title, description, memberIds, avatar));
    }

    @PostMapping("/groups/{id}/profile")
    public Map<String, Object> updateGroup(HttpSession session, @PathVariable long id,
                                           @RequestParam(required = false) String title,
                                           @RequestParam(required = false) String description,
                                           @RequestParam(required = false, name = "avatar") MultipartFile avatar) {
        chats.updateGroup(user(session), id, title, description, avatar);
        return ok(Map.of("updated", true));
    }

    @PostMapping("/groups/{id}/members")
    public Map<String, Object> addMembers(HttpSession session, @PathVariable long id,
                                          @RequestParam List<Long> employeeIds) {
        chats.addMembers(user(session), id, employeeIds);
        return ok(Map.of("updated", true));
    }

    @PostMapping("/groups/{id}/members/{employeeId}/remove")
    public Map<String, Object> removeMember(HttpSession session, @PathVariable long id,
                                            @PathVariable long employeeId) {
        chats.removeMember(user(session), id, employeeId);
        return ok(Map.of("updated", true));
    }

    @PostMapping("/groups/{id}/leave")
    public Map<String, Object> leave(HttpSession session, @PathVariable long id) {
        chats.leaveGroup(user(session), id);
        return ok(Map.of("left", true));
    }

    @PostMapping("/groups/{id}/admins/{employeeId}")
    public Map<String, Object> setAdmin(HttpSession session, @PathVariable long id,
                                        @PathVariable long employeeId,
                                        @RequestParam boolean admin) {
        chats.setAdmin(user(session), id, employeeId, admin);
        return ok(Map.of("updated", true));
    }

    @PostMapping("/groups/{id}/owner")
    public Map<String, Object> transferOwner(HttpSession session, @PathVariable long id,
                                             @RequestParam long employeeId) {
        chats.transferOwner(user(session), id, employeeId);
        return ok(Map.of("updated", true));
    }

    @PostMapping("/groups/{id}/dissolve")
    public Map<String, Object> dissolve(HttpSession session, @PathVariable long id) {
        chats.dissolveGroup(user(session), id);
        return ok(Map.of("dissolved", true));
    }

    @GetMapping("/announcements")
    public Map<String, Object> announcementInbox(HttpSession session,
                                                 @RequestParam(defaultValue = "inbox") String mode,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "50") int limit) {
        SessionUser user = user(session);
        return ok("mine".equals(mode) ? announcements.mine(user, keyword, limit) : "manage".equals(mode)
                ? announcements.manage(user, keyword, status, limit)
                : announcements.inbox(user, keyword, category, limit));
    }

    @GetMapping("/announcements/home")
    public Map<String, Object> announcementHome(HttpSession session,
                                                @RequestParam(defaultValue = "5") int limit) {
        return ok(announcements.home(user(session), limit));
    }

    @GetMapping("/announcements/{id}")
    public Map<String, Object> announcement(HttpSession session, @PathVariable long id) {
        return ok(announcements.detail(user(session), id));
    }

    @PostMapping("/announcements")
    public Map<String, Object> saveAnnouncement(
            HttpSession session,
            @RequestParam(required = false) Long id,
            @RequestParam String title,
            @RequestParam String category,
            @RequestParam String bodyJson,
            @RequestParam(defaultValue = "false") boolean requiresAck,
            @RequestParam(defaultValue = "all") String scopeType,
            @RequestParam(required = false) List<Long> departmentIds,
            @RequestParam(required = false) List<Long> employeeIds,
            @RequestParam(required = false, name = "files") List<MultipartFile> files,
            @RequestParam(required = false) List<Long> removeAttachmentIds) {
        long announcementId = announcements.save(user(session), id, title, category, bodyJson, requiresAck,
                scopeType, departmentIds, employeeIds, files, removeAttachmentIds);
        return ok(Map.of("id", announcementId));
    }

    @PostMapping("/announcements/{id}/publish")
    public Map<String, Object> publish(HttpSession session, @PathVariable long id) {
        announcements.publish(user(session), id);
        return ok(Map.of("published", true));
    }

    @PostMapping("/announcements/{id}/read")
    public Map<String, Object> announcementRead(HttpSession session, @PathVariable long id) {
        announcements.read(user(session), id);
        return ok(Map.of("read", true));
    }

    @PostMapping("/announcements/{id}/confirm")
    public Map<String, Object> confirm(HttpSession session, @PathVariable long id) {
        announcements.confirm(user(session), id);
        return ok(Map.of("confirmed", true));
    }

    @PostMapping("/announcements/{id}/pin")
    public Map<String, Object> pinAnnouncement(HttpSession session, @PathVariable long id,
                                               @RequestParam(defaultValue = "true") boolean pinned) {
        announcements.pin(user(session), id, pinned);
        return ok(Map.of("pinned", pinned));
    }

    @PostMapping("/announcements/{id}/recall")
    public Map<String, Object> recallAnnouncement(HttpSession session, @PathVariable long id) {
        announcements.recall(user(session), id);
        return ok(Map.of("recalled", true));
    }

    @PostMapping("/announcements/{id}/archive")
    public Map<String, Object> archiveAnnouncement(HttpSession session, @PathVariable long id) {
        announcements.archive(user(session), id);
        return ok(Map.of("archived", true));
    }

    @GetMapping("/announcements/{id}/handover/candidates")
    public Map<String,Object> handoverCandidates(HttpSession session,@PathVariable long id) {
        return ok(announcements.handoverCandidates(user(session),id));
    }

    @PostMapping("/announcements/{id}/handover")
    public Map<String,Object> authorizeHandover(HttpSession session,@PathVariable long id,
                @RequestParam long managerId,@RequestParam String reason) {
        announcements.authorizeHandover(user(session),id,managerId,reason);
        return ok(Map.of("authorized",true));
    }

    @PostMapping("/announcements/{id}/handover/revoke")
    public Map<String,Object> revokeHandover(HttpSession session,@PathVariable long id,
                @RequestParam String reason) {
        announcements.revokeHandover(user(session),id,reason);
        return ok(Map.of("revoked",true));
    }

    @GetMapping("/announcements/{id}/stats")
    public Map<String, Object> announcementStats(HttpSession session, @PathVariable long id) {
        return ok(announcements.statistics(user(session), id));
    }

    private static SessionUser user(HttpSession session) {
        return (SessionUser) session.getAttribute("user");
    }

    private static Map<String, Object> ok(Object data) {
        return Map.of("code", 200, "msg", "操作成功", "data", data);
    }
}
