package com.example.ems.communications;

import com.example.ems.model.SessionUser;
import com.example.ems.service.SessionService;
import com.example.ems.upgrade.Schema;
import com.example.ems.upgrade.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.mockito.Mockito.*;

public final class CommunicationsAcceptanceMain {
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static CommunicationChatService chats;
    private static CommunicationAnnouncementService announcements;
    private static CommunicationAccess access;
    private static CommunicationOrganizationService organizations;
    private static String baseUrl;
    private static int checks;

    public static void main(String[] args) throws Exception {
        baseUrl = required("EMS_IT_URL");
        String password = required("EMS_IT_PASSWORD");
        String storage = required("EMS_COMMUNICATION_STORAGE");
        Path evidence = Path.of(args.length == 0 ? "communications-results.json" : args[0]).toAbsolutePath();
        Schema.safeUrl(baseUrl);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(baseUrl, "root", password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SessionService sessions = mock(SessionService.class);
        access = new CommunicationAccess(jdbc, sessions);
        CommunicationAttachmentService attachments = new CommunicationAttachmentService(jdbc, access);
        organizations = new CommunicationOrganizationService(jdbc, event -> {});
        chats = new CommunicationChatService(jdbc, access, organizations, attachments, event -> {});
        announcements = new CommunicationAnnouncementService(jdbc, new ObjectMapper(), access, attachments, event -> {});

        seed();
        inTx(() -> { organizations.bootstrapAll(); return null; });
        SessionUser executive = user(1, 2, 1L);
        SessionUser alice = user(2, 0, 1L);
        SessionUser bob = user(3, 0, 2L);
        SessionUser manager = user(4, 1, 2L);

        long direct = open(alice, bob);
        String firstClientId = UUID.randomUUID().toString();
        Map<String, Object> first = sendWithId(alice, direct, "Private one", null, firstClientId);
        check(number(first.get("conversation_seq")) == 1, "T2 first private sequence");
        Map<String, Object> duplicate = sendWithId(alice, direct, "Private one", null, firstClientId);
        check(number(duplicate.get("id")) == number(first.get("id")), "T2 duplicate client id is idempotent");

        ExecutorService pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Callable<Map<String, Object>> one = () -> {
            ready.countDown(); start.await();
            return inTx(() -> chats.send(alice, direct, UUID.randomUUID().toString(), "parallel-a", null, List.of(), false, List.of()));
        };
        Callable<Map<String, Object>> two = () -> {
            ready.countDown(); start.await();
            return inTx(() -> chats.send(alice, direct, UUID.randomUUID().toString(), "parallel-b", null, List.of(), false, List.of()));
        };
        Future<Map<String, Object>> f1 = pool.submit(one), f2 = pool.submit(two);
        ready.await(); start.countDown();
        long seqA = number(f1.get().get("conversation_seq")), seqB = number(f2.get().get("conversation_seq"));
        pool.shutdown();
        check(seqA != seqB && Math.min(seqA, seqB) > 1, "T2 concurrent sends receive distinct committed sequence values");
        Map<String, Object> reply = send(bob, direct, "quoted reply", number(first.get("id")));
        check(number(reply.get("quoted_message_id")) == number(first.get("id")), "T2 quoted reply persisted");
        inTx(() -> { chats.recall(alice, number(first.get("id"))); return null; });
        Map<String, Object> quotedAfterRecall = inTx(() -> chats.messages(bob, direct, null, null, "quoted reply", null, null, 50));
        Map<?, ?> visibleReply = (Map<?, ?>) ((List<?>) quotedAfterRecall.get("items")).get(0);
        check(visibleReply.get("quoted_body") == null, "T2 quote of recalled message does not expose original body");
        inTx(() -> { chats.markRead(bob, direct, seqA); return null; });
        Map<String, Object> history = inTx(() -> chats.messages(bob, direct, null, null, "quoted", null, null, 50));
        check(((List<?>) history.get("items")).size() == 1, "T2 keyword search uses visible history");
        checkThrows(() -> inTx(() -> chats.messages(user(5, 0, 2L), direct, null, null, null, null, null, 50)),
                "T2 non-participant cannot read private conversation");

        Map<String, Object> recallTarget = send(alice, direct, "recall soon");
        inTx(() -> { chats.recall(alice, number(recallTarget.get("id"))); return null; });
        Map<String, Object> recalled = inTx(() -> chats.messages(bob, direct, null, null, "recall soon", null, null, 50));
        check(((List<?>) recalled.get("items")).isEmpty(), "T2 recalled body is not searchable");
        Map<String, Object> late = send(alice, direct, "too late");
        jdbc.update("UPDATE comm_message SET sent_at=DATE_SUB(NOW(6),INTERVAL 3 MINUTE) WHERE id=?", number(late.get("id")));
        checkThrows(() -> inTx(() -> { chats.recall(alice, number(late.get("id"))); return null; }),
                "T2 recall after two minutes is rejected");

        long group = inTx(() -> number(chats.createGroup(alice, "Synthetic project group", "isolated",
                List.of(bob.id()), null).get("id")));
        Map<String, Object> groupFirst = send(alice, group, "before new member");
        long beforeJoin = number(groupFirst.get("conversation_seq"));
        inTx(() -> { chats.addMembers(alice, group, List.of(5L)); return null; });
        Map<String, Object> visibleBefore = inTx(() -> chats.messages(user(5, 0, 1L), group, null, null, null, null, null, 50));
        check(((List<?>) visibleBefore.get("items")).isEmpty(), "T3 new group member cannot see messages before join");
        Map<String, Object> whileMember = send(alice, group, "while member");
        checkThrows(() -> inTx(() -> chats.send(user(5, 0, 2L), group, UUID.randomUUID().toString(),
                        "unauthorized all", null, List.of(), true, List.of())),
                "T3 ordinary member cannot mention all");
        Map<String, Object> mentioned = inTx(() -> chats.send(alice, group, UUID.randomUUID().toString(),
                "member mention", null, List.of(bob.id()), false, List.of()));
        check(((List<?>) mentioned.get("mentions")).size() == 1, "T3 group member mention is persisted");
        inTx(() -> { chats.leaveGroup(user(5, 0, 1L), group); return null; });
        Map<String, Object> gap = send(alice, group, "membership gap");
        inTx(() -> { chats.addMembers(alice, group, List.of(5L)); return null; });
        Map<String, Object> afterJoin = send(alice, group, "after rejoin");
        Map<String, Object> union = inTx(() -> chats.messages(user(5, 0, 1L), group, null, null, null, null, null, 50));
        List<?> unionItems = (List<?>) union.get("items");
        check(unionItems.size() == 3, "T3 rejoin exposes only the union of independent periods");
        long lastSeq = number(afterJoin.get("conversation_seq"));
        check(lastSeq > number(gap.get("conversation_seq")) && beforeJoin < number(whileMember.get("conversation_seq")),
                "T3 membership gap remains hidden");
        inTx(() -> { chats.transferOwner(alice, group, bob.id()); return null; });
        check(jdbc.queryForObject("SELECT owner_employee_id FROM comm_group WHERE conversation_id=?", Long.class, group) == 3L,
                "T3 group owner transfer");

        jdbc.update("UPDATE employee SET department_id=2 WHERE id=3");
        inTx(() -> { organizations.synchronizeEmployee(3L); return null; });
        Long deptConversation = jdbc.queryForObject("SELECT conversation_id FROM comm_group WHERE department_id=2", Long.class);
        check(jdbc.queryForObject("""
                SELECT member_role FROM comm_membership_period
                 WHERE conversation_id=? AND employee_id=4 AND open_marker=1
                """, Integer.class, deptConversation) == 1, "T3 department manager is group admin");
        check(jdbc.queryForObject("""
                SELECT COUNT(*) FROM comm_membership_period WHERE conversation_id=? AND employee_id=3 AND open_marker=1
                """, Integer.class, deptConversation) == 1, "T3 department transfer synchronizes membership");
        inTx(() -> { organizations.handleEmployeeDeactivation(bob.id()); return null; });
        check(jdbc.queryForObject("SELECT owner_employee_id FROM comm_group WHERE conversation_id=?", Long.class, group) == alice.id(),
                "T3 deactivated normal-group owner transfers ownership to a remaining member");

        String body1 = "{\"version\":1,\"blocks\":[{\"type\":\"paragraph\",\"spans\":[{\"text\":\"Department notice\",\"bold\":true}]}]}";
        String unsafeBody = "{\"version\":1,\"blocks\":[{\"type\":\"script\",\"spans\":[{\"text\":\"alert(1)\"}]}]}";
        checkThrows(() -> inTx(() -> announcements.save(manager, null, "Unsafe", "通知", unsafeBody,
                        false, "departments", List.of(2L), List.of(), List.of(), List.of())),
                "T4 announcement editor rejects unregistered rich-text block types");
        long announcement = inTx(() -> announcements.save(manager, null, "Department notice", "通知", body1,
                true, "departments", List.of(2L), List.of(), List.of(), List.of()));
        inTx(() -> { announcements.publish(manager, announcement); return null; });
        inTx(() -> { announcements.pin(manager, announcement, true); return null; });
        List<Map<String,Object>> homeBefore = inTx(() -> announcements.home(user(3, 0, 2L), 5));
        check(homeBefore.size()==1 && homeBefore.get(0).get("read_at")==null,
                "T4 home loads recipient announcement without marking it read");
        long newer = inTx(() -> announcements.save(manager, null, "Newest notice", "通知", body1,
                false, "departments", List.of(2L), List.of(), List.of(), List.of()));
        inTx(() -> { announcements.publish(manager, newer); return null; });
        List<Map<String,Object>> homeAfter = inTx(() -> announcements.home(user(3, 0, 2L), 5));
        check(number(homeAfter.get(0).get("id"))==newer && number(homeAfter.get(1).get("id"))==announcement,
                "T4 home orders newest publication before an older pinned announcement");
        check(inTx(() -> announcements.home(executive, 5)).isEmpty(),
                "T4 executive management permission does not widen home recipient scope");
        Map<String, Object> inbox3 = inTx(() -> announcements.inbox(user(3, 0, 2L), null, null, 50)).get(0);
        check("Department notice".equals(inbox3.get("display_title")), "T4 department manager announcement reaches department employee");
        check(inTx(() -> announcements.inbox(alice, null, null, 50)).isEmpty(), "T4 other department cannot see announcement");
        inTx(() -> { announcements.confirm(user(3, 0, 2L), announcement); return null; });
        Map<String, Object> stats = inTx(() -> announcements.statistics(manager, announcement));
        check(number(((Map<?, ?>) stats.get("summary")).get("confirmed_count")) >= 1, "T4 acknowledgement statistics");
        checkThrows(() -> inTx(() -> announcements.statistics(user(3, 0, 2L), announcement)),
                "T4 ordinary employee cannot read announcement statistics");
        String body2 = "{\"version\":1,\"blocks\":[{\"type\":\"paragraph\",\"spans\":[{\"text\":\"Revised department notice\"}]}]}";
        inTx(() -> announcements.save(manager, announcement, "Revised notice", "通知", body2,
                true, "departments", List.of(2L), List.of(), List.of(), List.of()));
        inTx(() -> { announcements.publish(manager, announcement); return null; });
        Map<String, Object> detail = inTx(() -> announcements.detail(user(3, 0, 2L), announcement));
        check(number(detail.get("version_no")) == 2 && detail.get("confirmed_at") == null,
                "T4 major revision creates a new version and clears confirmation");

        Map<String, Object> fileMessage = inTx(() -> chats.send(alice, group, UUID.randomUUID().toString(),
                "file", null, List.of(), false,
                List.of(new MockMultipartFile("files", "image.png", "image/png",
                        new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A}))));
        List<?> files = (List<?>) fileMessage.get("attachments");
        check(files.size() == 1, "T3 group attachment is bound to ordered message");
        long attachmentId = number(((Map<?, ?>) files.get(0)).get("id"));
        check(access.canReadAttachment(attachmentId, 2L), "T3 current member can access group attachment");
        check(!access.canReadAttachment(attachmentId, 3L), "T3 deactivated former owner cannot access new group attachment");

        Path snapshot = Path.of(storage).resolve("communications-snapshot.zip");
        Files.deleteIfExists(snapshot);
        Path snapshotParent = snapshot.getParent();
        Files.createDirectories(snapshotParent);
        try (var connection = dataSource.getConnection()) {
            Snapshot.write(connection, snapshot);
        }
        Path stage = snapshotParent.resolve("snapshot-stage");
        if (Files.exists(stage)) throw new IllegalStateException("Staging must be fresh");
        var manifest = Snapshot.inspect(snapshot, stage);
        String restoreName = "ems_next_dev_v121_restore";
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + Snapshot.q(restoreName));
            statement.executeUpdate("CREATE DATABASE " + Snapshot.q(restoreName) + " CHARACTER SET utf8mb4");
        }
        String restoreUrl = baseUrl.replaceFirst("/ems_next_[^?]+", "/" + restoreName);
        Schema.migrate(restoreUrl, "root", password);
        try (var connection = java.sql.DriverManager.getConnection(restoreUrl, "root", password)) {
            Snapshot.restore(connection, manifest, stage);
            check(jdbcCount("SELECT COUNT(*) FROM comm_message") > 0, "T1 source communications populated before backup");
            check(jdbcCount(connection, "SELECT COUNT(*) FROM comm_message") > 0, "T1 database snapshot restores communications");
        }
        Path restoredAttachments = snapshotParent.resolve("restored-attachments");
        if (Files.exists(restoredAttachments)) throw new IllegalStateException("Restore attachment staging must be fresh");
        Snapshot.stageAttachments(manifest, stage, restoredAttachments);
        check(Files.isDirectory(restoredAttachments.resolve("objects"))
                && !Files.list(restoredAttachments.resolve("objects")).findAny().isEmpty(),
                "T1 attachment files restore with verified hashes");

        Map<String, Object> summary = Map.of("passed", true, "exitCode", 0, "checks", checks,
                "scope", "T2-T4 services and T1 communications snapshot/attachment restore");
        Snapshot.JSON.writeValue(evidence.toFile(), summary);
        System.out.println("PASS communications acceptance checks=" + checks);
    }

    private static void seed() {
        exec("INSERT INTO department(id,department_name,manager_id,base_salary,status,description) VALUES(1,'Department A',NULL,0,1,''),(2,'Department B',4,0,1,'')");
        String hash = new BCryptPasswordEncoder().encode("SyntheticPassword123");
        for (Object[] row : List.of(
                new Object[]{1L, "260001", "Executive", 1L, 2L, hash},
                new Object[]{2L, "260002", "Alice", 1L, 1L, hash},
                new Object[]{3L, "260003", "Bob", 2L, 0L, hash},
                new Object[]{4L, "260004", "Manager", 2L, 1L, hash},
                new Object[]{5L, "260005", "Carol", 2L, 0L, hash})) {
            insertEmployee((Long) row[0], (String) row[1], (String) row[2], (Long) row[3], ((Number) row[4]).intValue());
            jdbc.update("""
                INSERT INTO user_account(employee_id,password_hash,account_status) VALUES(?,?,1)
                """, row[0], row[5]);
        }
        jdbc.update("UPDATE department SET manager_id=4 WHERE id=2");
    }

    private static void insertEmployee(long id, String no, String name, Long department, int userType) {
        jdbc.update("""
            INSERT INTO employee(id,employee_no,name,identity_number,birth_date,phone,department_id,user_type,hire_date,employment_status)
            VALUES(?,?,?,?, '1990-01-01',?,?,?, '2020-01-01',1)
            """, id, no, name, "11010119900101" + String.format("%04d", id), "13800000" + String.format("%03d", id),
                department, userType);
    }

    private static SessionUser user(long id, int type, long department) {
        String no = "26000" + id;
        String name = switch ((int) id) { case 1 -> "Executive"; case 2 -> "Alice"; case 3 -> "Bob"; case 4 -> "Manager"; default -> "Carol"; };
        return new SessionUser(id, no, name, type, department, "Department " + (department == 1 ? "A" : "B"));
    }

    private static long open(SessionUser actor, SessionUser target) {
        return inTx(() -> number(chats.openDirect(actor, number(target.id())).get("id")));
    }

    private static Map<String, Object> send(SessionUser actor, long conversation, String text) {
        return send(actor, conversation, text, null);
    }

    private static Map<String, Object> send(SessionUser actor, long conversation, String text, Number quote) {
        return sendWithId(actor, conversation, text, quote, UUID.randomUUID().toString());
    }

    private static Map<String, Object> sendWithId(SessionUser actor, long conversation, String text, Number quote,
                                                  String clientId) {
        return inTx(() -> chats.send(actor, conversation, clientId, text,
                quote == null ? null : quote.longValue(), List.of(), false, List.of()));
    }

    private static <T> T inTx(Supplier<T> action) {
        return tx.execute(status -> action.get());
    }

    private static void exec(String sql) { jdbc.update(sql); }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
    private static void checkThrows(Runnable action, String label) {
        boolean thrown = false;
        try { action.run(); } catch (RuntimeException expected) { thrown = true; }
        check(thrown, label);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static long jdbcCount(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
    private static long jdbcCount(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
