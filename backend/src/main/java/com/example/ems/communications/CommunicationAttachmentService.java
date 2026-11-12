package com.example.ems.communications;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@Service
public class CommunicationAttachmentService {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_MESSAGE_FILES = 5;
    public static final long MAX_REQUEST_BYTES = 100L * 1024 * 1024;

    public enum Kind { CHAT(0), ANNOUNCEMENT(1), GROUP_AVATAR(2); final int code; Kind(int code){this.code=code;} }
    public record Staged(String storageKey, String originalName, String contentType, long size, String sha256, Path path) {}

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt", "text/plain"), Map.entry("csv", "text/csv"), Map.entry("zip", "application/zip")
    );

    private final JdbcTemplate jdbc;
    private final CommunicationAccess access;
    private final SecureRandom random = new SecureRandom();

    public CommunicationAttachmentService(JdbcTemplate jdbc, CommunicationAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Staged stage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("附件为空");
        if (file.getSize() > MAX_FILE_BYTES) throw new IllegalArgumentException("单个附件不能超过20 MiB");
        String original = safeName(file.getOriginalFilename());
        String extension = extension(original);
        String contentType = TYPES.get(extension);
        if (contentType == null) throw new IllegalArgumentException("不支持该附件类型");
        byte[] header;
        try (InputStream input = file.getInputStream()) {
            header = input.readNBytes(16);
        } catch (Exception error) {
            throw new IllegalArgumentException("附件读取失败", error);
        }
        if (!matches(extension, header)) throw new IllegalArgumentException("附件内容与扩展名不匹配");
        String key = randomKey();
        Path directory = storageRoot().resolve("objects");
        Path target = directory.resolve(key);
        Path partial = directory.resolve(key + ".partial");
        try {
            Files.createDirectories(directory);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream input = file.getInputStream(); var output = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_FILE_BYTES) throw new IllegalArgumentException("单个附件不能超过20 MiB");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (size == 0) throw new IllegalArgumentException("附件为空");
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(partial, target);
            }
            return new Staged(key, original, contentType, size, HexFormat.of().formatHex(digest.digest()), target);
        } catch (IllegalArgumentException error) {
            deleteQuietly(partial);
            deleteQuietly(target);
            throw error;
        } catch (Exception error) {
            deleteQuietly(partial);
            deleteQuietly(target);
            throw new IllegalStateException("附件保存失败", error);
        }
    }

    public long bindMessage(Long messageId, Long uploaderId, Staged staged) {
        jdbc.update("""
            INSERT INTO comm_attachment(uploader_id,storage_key,original_name,content_type,byte_size,sha256,
                                        attachment_kind,message_id,attachment_status)
            VALUES(?,?,?,?,?,?,?,?,1)
            """, uploaderId, staged.storageKey(), staged.originalName(), staged.contentType(), staged.size(),
                staged.sha256(), Kind.CHAT.code, messageId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public long bindAnnouncementVersion(Long versionId, Long uploaderId, Staged staged) {
        jdbc.update("""
            INSERT INTO comm_attachment(uploader_id,storage_key,original_name,content_type,byte_size,sha256,
                                        attachment_kind,announcement_version_id,attachment_status)
            VALUES(?,?,?,?,?,?,?,?,1)
            """, uploaderId, staged.storageKey(), staged.originalName(), staged.contentType(), staged.size(),
                staged.sha256(), Kind.ANNOUNCEMENT.code, versionId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public long bindGroupAvatar(Long groupId, Long uploaderId, Staged staged) {
        jdbc.update("UPDATE comm_attachment SET attachment_status=2 WHERE bound_group_id=? AND attachment_kind=2 AND attachment_status=1", groupId);
        jdbc.update("""
            INSERT INTO comm_attachment(uploader_id,storage_key,original_name,content_type,byte_size,sha256,
                                        attachment_kind,bound_group_id,attachment_status)
            VALUES(?,?,?,?,?,?,?,?,1)
            """, uploaderId, staged.storageKey(), staged.originalName(), staged.contentType(), staged.size(),
                staged.sha256(), Kind.GROUP_AVATAR.code, groupId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void discard(Collection<Staged> staged) {
        for (Staged item : staged) deleteQuietly(item.path());
    }

    public void discard(long attachmentId) {
        List<String> keys = jdbc.queryForList("SELECT storage_key FROM comm_attachment WHERE id=?", String.class, attachmentId);
        jdbc.update("UPDATE comm_attachment SET attachment_status=2 WHERE id=?", attachmentId);
        if (!keys.isEmpty()) deleteQuietly(storageRoot().resolve("objects").resolve(keys.get(0)));
    }

    public ResponseEntity<Resource> download(long attachmentId, long employeeId) {
        if (!access.canReadAttachment(attachmentId, employeeId)) {
            return ResponseEntity.status(403).build();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT storage_key,original_name,content_type,byte_size
              FROM comm_attachment WHERE id=? AND attachment_status=1
            """, attachmentId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> row = rows.get(0);
        Path file = storageRoot().resolve("objects").resolve(String.valueOf(row.get("storage_key"))).normalize();
        if (!file.startsWith(storageRoot().resolve("objects").normalize()) || !Files.isRegularFile(file)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GONE, "附件文件不可用");
        }
        String contentType = String.valueOf(row.get("content_type"));
        boolean preview = contentType.startsWith("image/png") || contentType.startsWith("image/jpeg")
                || contentType.startsWith("image/gif") || contentType.startsWith("image/webp");
        ContentDisposition disposition = ContentDisposition.builder(preview ? "inline" : "attachment")
                .filename(String.valueOf(row.get("original_name")), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(((Number) row.get("byte_size")).longValue())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }

    public List<Map<String, Object>> forMessage(long messageId) {
        return jdbc.queryForList("""
            SELECT id,original_name,content_type,byte_size,sha256
              FROM comm_attachment WHERE message_id=? AND attachment_status=1 ORDER BY id
            """, messageId);
    }

    public List<Map<String, Object>> forAnnouncementVersion(long versionId) {
        return jdbc.queryForList("""
            SELECT id,original_name,content_type,byte_size,sha256
              FROM comm_attachment WHERE announcement_version_id=? AND attachment_status=1 ORDER BY id
            """, versionId);
    }

    public Path storageRoot() {
        return com.example.ems.upgrade.Snapshot.attachmentStorageRoot();
    }

    private static String safeName(String input) {
        String name = input == null ? "附件" : input.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) name = "附件";
        if (name.length() > 180) {
            String ext = extension(name);
            name = name.substring(0, Math.min(170, name.length() - ext.length() - 1)) + (ext.isEmpty() ? "" : "." + ext);
        }
        return name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String extension, byte[] h) {
        if (extension.equals("png")) return h.length >= 8 && (h[0] & 255) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47;
        if (extension.equals("jpg") || extension.equals("jpeg")) return h.length >= 3 && (h[0] & 255) == 0xFF && (h[1] & 255) == 0xD8 && (h[2] & 255) == 0xFF;
        if (extension.equals("gif")) return h.length >= 6 && new String(h, 0, 4, StandardCharsets.US_ASCII).equals("GIF8");
        if (extension.equals("webp")) return h.length >= 12 && new String(h, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(h, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
        if (extension.equals("pdf")) return h.length >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F';
        if (Set.of("docx", "xlsx", "pptx", "zip").contains(extension)) return h.length >= 4 && h[0] == 'P' && h[1] == 'K';
        if (Set.of("doc", "xls", "ppt").contains(extension)) return h.length >= 4 && (h[0] & 255) == 0xD0 && (h[1] & 255) == 0xCF;
        if (extension.equals("txt") || extension.equals("csv")) {
            for (byte value : h) if ((value & 0xFF) == 0) return false;
            return true;
        }
        return false;
    }

    private String randomKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void deleteQuietly(Path path) {
        try { if (path != null) Files.deleteIfExists(path); } catch (Exception ignored) { }
    }
}
