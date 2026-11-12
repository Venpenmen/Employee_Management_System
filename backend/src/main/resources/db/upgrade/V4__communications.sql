CREATE TABLE IF NOT EXISTS comm_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_type TINYINT NOT NULL COMMENT '0 private, 1 group',
    direct_key CHAR(41) CHARACTER SET ascii COLLATE ascii_bin NULL,
    next_message_seq BIGINT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0 active, 1 archived',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_comm_conversation_direct (direct_key),
    KEY idx_comm_conversation_type (conversation_type,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_group (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    group_type TINYINT NOT NULL COMMENT '0 normal, 1 department',
    title VARCHAR(80) NOT NULL,
    description VARCHAR(500) NULL,
    owner_employee_id BIGINT NULL,
    department_id BIGINT NULL,
    group_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 active, 1 dissolved, 2 archived',
    created_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_comm_group_conversation (conversation_id),
    UNIQUE KEY uk_comm_group_department (department_id),
    KEY idx_comm_group_owner (owner_employee_id),
    KEY idx_comm_group_status (group_status),
    CONSTRAINT fk_comm_group_conversation FOREIGN KEY (conversation_id) REFERENCES comm_conversation(id),
    CONSTRAINT fk_comm_group_owner FOREIGN KEY (owner_employee_id) REFERENCES employee(id),
    CONSTRAINT fk_comm_group_department FOREIGN KEY (department_id) REFERENCES department(id),
    CONSTRAINT fk_comm_group_created_by FOREIGN KEY (created_by) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_membership_period (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    member_role TINYINT NOT NULL DEFAULT 0 COMMENT '0 member, 1 admin, 2 owner',
    joined_after_seq BIGINT NOT NULL DEFAULT 0,
    left_after_seq BIGINT NULL,
    open_marker TINYINT NULL DEFAULT 1,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    left_at DATETIME(6) NULL,
    UNIQUE KEY uk_comm_membership_open (conversation_id,employee_id,open_marker),
    KEY idx_comm_membership_employee (employee_id,conversation_id),
    KEY idx_comm_membership_period (conversation_id,joined_after_seq,left_after_seq),
    CONSTRAINT fk_comm_membership_conversation FOREIGN KEY (conversation_id) REFERENCES comm_conversation(id),
    CONSTRAINT fk_comm_membership_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_conversation_user_state (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    last_read_seq BIGINT NOT NULL DEFAULT 0,
    pinned_at DATETIME(6) NULL,
    hidden_at DATETIME(6) NULL,
    hidden_before_seq BIGINT NOT NULL DEFAULT 0,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_comm_user_state (conversation_id,employee_id),
    KEY idx_comm_user_state_employee (employee_id,pinned_at),
    CONSTRAINT fk_comm_user_state_conversation FOREIGN KEY (conversation_id) REFERENCES comm_conversation(id),
    CONSTRAINT fk_comm_user_state_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    conversation_seq BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_type TINYINT NOT NULL DEFAULT 0 COMMENT '0 text, 1 file, 2 system',
    body_text TEXT NULL,
    mention_all TINYINT NOT NULL DEFAULT 0,
    quoted_message_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0 normal, 1 recalled',
    sent_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    recalled_at DATETIME(6) NULL,
    recalled_by BIGINT NULL,
    UNIQUE KEY uk_comm_message_seq (conversation_id,conversation_seq),
    UNIQUE KEY uk_comm_message_client (sender_id,client_message_id),
    KEY idx_comm_message_history (conversation_id,conversation_seq,sent_at),
    KEY idx_comm_message_quote (quoted_message_id),
    CONSTRAINT fk_comm_message_conversation FOREIGN KEY (conversation_id) REFERENCES comm_conversation(id),
    CONSTRAINT fk_comm_message_sender FOREIGN KEY (sender_id) REFERENCES employee(id),
    CONSTRAINT fk_comm_message_quote FOREIGN KEY (quoted_message_id) REFERENCES comm_message(id),
    CONSTRAINT fk_comm_message_recalled_by FOREIGN KEY (recalled_by) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_message_mention (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    UNIQUE KEY uk_comm_message_mention (message_id,employee_id),
    KEY idx_comm_mention_employee (employee_id,message_id),
    CONSTRAINT fk_comm_mention_message FOREIGN KEY (message_id) REFERENCES comm_message(id),
    CONSTRAINT fk_comm_mention_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uploader_id BIGINT NOT NULL,
    storage_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attachment_kind TINYINT NOT NULL DEFAULT 0 COMMENT '0 chat, 1 announcement, 2 group avatar',
    message_id BIGINT NULL,
    announcement_version_id BIGINT NULL,
    bound_group_id BIGINT NULL,
    attachment_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 temporary, 1 active, 2 purged',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_comm_attachment_storage (storage_key),
    KEY idx_comm_attachment_message (message_id),
    KEY idx_comm_attachment_version (announcement_version_id),
    KEY idx_comm_attachment_group (bound_group_id),
    CONSTRAINT fk_comm_attachment_uploader FOREIGN KEY (uploader_id) REFERENCES employee(id),
    CONSTRAINT fk_comm_attachment_message FOREIGN KEY (message_id) REFERENCES comm_message(id),
    CONSTRAINT fk_comm_attachment_group FOREIGN KEY (bound_group_id) REFERENCES comm_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_announcement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    category VARCHAR(40) NOT NULL DEFAULT '通知',
    scope_type TINYINT NOT NULL DEFAULT 0 COMMENT '0 all, 1 departments, 2 employees',
    announcement_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 draft, 1 published, 2 recalled, 3 archived',
    requires_ack TINYINT NOT NULL DEFAULT 0,
    current_version_id BIGINT NULL,
    created_by BIGINT NOT NULL,
    pinned_at DATETIME(6) NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    recalled_at DATETIME(6) NULL,
    archived_at DATETIME(6) NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_comm_announcement_status (announcement_status,pinned_at,published_at),
    KEY idx_comm_announcement_current (current_version_id),
    CONSTRAINT fk_comm_announcement_creator FOREIGN KEY (created_by) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_announcement_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    announcement_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    title VARCHAR(120) NOT NULL,
    category VARCHAR(40) NOT NULL,
    body_json TEXT NOT NULL,
    body_text TEXT NOT NULL,
    requires_ack TINYINT NOT NULL DEFAULT 0,
    version_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 draft, 1 published, 2 superseded',
    editor_id BIGINT NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    UNIQUE KEY uk_comm_announcement_version (announcement_id,version_no),
    CONSTRAINT fk_comm_version_announcement FOREIGN KEY (announcement_id) REFERENCES comm_announcement(id),
    CONSTRAINT fk_comm_version_editor FOREIGN KEY (editor_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_announcement_target (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    target_type TINYINT NOT NULL COMMENT '0 department, 1 employee',
    department_id BIGINT NULL,
    employee_id BIGINT NULL,
    UNIQUE KEY uk_comm_announcement_target (version_id,target_type,department_id,employee_id),
    KEY idx_comm_target_department (department_id),
    KEY idx_comm_target_employee (employee_id),
    CONSTRAINT fk_comm_target_version FOREIGN KEY (version_id) REFERENCES comm_announcement_version(id),
    CONSTRAINT fk_comm_target_department FOREIGN KEY (department_id) REFERENCES department(id),
    CONSTRAINT fk_comm_target_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_announcement_recipient (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    read_at DATETIME(6) NULL,
    confirmed_at DATETIME(6) NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_comm_announcement_recipient (version_id,employee_id),
    KEY idx_comm_recipient_employee (employee_id,read_at,confirmed_at),
    CONSTRAINT fk_comm_recipient_version FOREIGN KEY (version_id) REFERENCES comm_announcement_version(id),
    CONSTRAINT fk_comm_recipient_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comm_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NULL,
    action VARCHAR(60) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NULL,
    summary VARCHAR(500) NOT NULL,
    detail_json TEXT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_comm_audit_time (create_time),
    KEY idx_comm_audit_target (target_type,target_id),
    CONSTRAINT fk_comm_audit_actor FOREIGN KEY (actor_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
