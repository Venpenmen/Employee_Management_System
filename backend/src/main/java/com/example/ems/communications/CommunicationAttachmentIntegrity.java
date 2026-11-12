package com.example.ems.communications;

import com.example.ems.upgrade.BackupCrypto;
import com.example.ems.upgrade.Snapshot;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
@Order(15)
public class CommunicationAttachmentIntegrity implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public CommunicationAttachmentIntegrity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path objects = Snapshot.attachmentStorageRoot().resolve("objects").normalize();
        for (Map<String, Object> row : jdbc.queryForList("""
            SELECT id,storage_key,sha256,byte_size FROM comm_attachment WHERE attachment_status=1
            """)) {
            Path file = objects.resolve(String.valueOf(row.get("storage_key"))).normalize();
            if (!file.startsWith(objects) || !Files.isRegularFile(file)
                    || Files.size(file) != ((Number) row.get("byte_size")).longValue()
                    || !BackupCrypto.sha256(file).equals(String.valueOf(row.get("sha256")))) {
                throw new IllegalStateException("通讯附件代与数据库不一致，禁止启动");
            }
        }
    }
}
