CREATE TABLE IF NOT EXISTS department (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    department_name VARCHAR(50) NOT NULL UNIQUE,
    manager_id BIGINT NULL,
    base_salary DECIMAL(12,2) NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0筹建 1正常 2停用',
    description VARCHAR(255),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS position_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    department_id BIGINT NOT NULL,
    position_name VARCHAR(50) NOT NULL,
    position_salary DECIMAL(12,2) NOT NULL DEFAULT 0,
    is_manager_position TINYINT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_position_department_name (department_id, position_name),
    KEY idx_position_department (department_id),
    CONSTRAINT fk_position_department FOREIGN KEY (department_id) REFERENCES department(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS employee (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_no CHAR(6) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL,
    identity_number VARCHAR(64) NOT NULL UNIQUE,
    birth_date DATE NOT NULL,
    phone VARCHAR(20) NULL UNIQUE,
    department_id BIGINT NULL,
    position_id BIGINT NULL,
    user_type TINYINT NOT NULL DEFAULT 0 COMMENT '0员工 1经理 2高层',
    hire_date DATE NOT NULL,
    leave_date DATE NULL,
    employment_status TINYINT NOT NULL DEFAULT 0 COMMENT '0待注册 1在职 2停用 3离职',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_employee_department (department_id),
    KEY idx_employee_position (position_id),
    KEY idx_employee_status (employment_status),
    CONSTRAINT fk_employee_department FOREIGN KEY (department_id) REFERENCES department(id),
    CONSTRAINT fk_employee_position FOREIGN KEY (position_id) REFERENCES position_info(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    account_status TINYINT NOT NULL DEFAULT 1,
    failed_login_count INT NOT NULL DEFAULT 0,
    locked_until DATETIME NULL,
    last_login_time DATETIME NULL,
    password_update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_account_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS employee_change_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    change_type VARCHAR(30) NOT NULL,
    old_department_id BIGINT NULL,
    new_department_id BIGINT NULL,
    old_position_id BIGINT NULL,
    new_position_id BIGINT NULL,
    reason VARCHAR(255),
    operator_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_change_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS salary_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    salary_month CHAR(7) NOT NULL,
    department_name_snapshot VARCHAR(50) NOT NULL,
    position_name_snapshot VARCHAR(50) NOT NULL,
    department_base_salary DECIMAL(12,2) NOT NULL DEFAULT 0,
    position_salary DECIMAL(12,2) NOT NULL DEFAULT 0,
    reward DECIMAL(12,2) NOT NULL DEFAULT 0,
    subsidy DECIMAL(12,2) NOT NULL DEFAULT 0,
    overtime_pay DECIMAL(12,2) NOT NULL DEFAULT 0,
    other_income DECIMAL(12,2) NOT NULL DEFAULT 0,
    deduction DECIMAL(12,2) NOT NULL DEFAULT 0,
    other_deduction DECIMAL(12,2) NOT NULL DEFAULT 0,
    payable_salary DECIMAL(12,2) NOT NULL DEFAULT 0,
    actual_salary DECIMAL(12,2) NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0草稿 1已计算 2已确认 3已发布 4已作废',
    operator_id BIGINT NOT NULL,
    generated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_time DATETIME NULL,
    UNIQUE KEY uk_salary_employee_month (employee_id, salary_month),
    KEY idx_salary_month (salary_month),
    CONSTRAINT fk_salary_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NULL,
    operation_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(30),
    target_id BIGINT NULL,
    summary VARCHAR(500),
    result VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_log_operator (operator_id),
    KEY idx_log_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dictionary_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dict_type VARCHAR(50) NOT NULL,
    item_value VARCHAR(50) NOT NULL,
    item_label VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    UNIQUE KEY uk_dict_type_value (dict_type, item_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS attendance_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_name VARCHAR(50) NOT NULL,
    work_start_time TIME NOT NULL,
    work_end_time TIME NOT NULL,
    late_grace_minutes INT NOT NULL DEFAULT 5,
    early_grace_minutes INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS attendance_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL,
    check_in_time DATETIME NULL,
    check_out_time DATETIME NULL,
    check_in_ip VARCHAR(45) NULL,
    check_out_ip VARCHAR(45) NULL,
    attendance_status TINYINT NOT NULL DEFAULT 0 COMMENT '0未完成 1正常 2迟到 3早退 4迟到且早退 5缺卡 6请假 7旷工 8已修正',
    late_minutes INT NOT NULL DEFAULT 0,
    early_leave_minutes INT NOT NULL DEFAULT 0,
    correction_reason VARCHAR(255) NULL,
    corrected_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_attendance_employee_date (employee_id, attendance_date),
    KEY idx_attendance_date_status (attendance_date, attendance_status),
    CONSTRAINT fk_attendance_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS leave_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_no VARCHAR(24) NOT NULL UNIQUE,
    employee_id BIGINT NOT NULL,
    leave_type VARCHAR(30) NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    leave_hours DECIMAL(8,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1待审批 2已通过 3已驳回 4已撤回 5已取消',
    current_approver_id BIGINT NULL,
    submit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_leave_employee_time (employee_id, start_time, end_time),
    KEY idx_leave_approver_status (current_approver_id, status),
    CONSTRAINT fk_leave_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS leave_approval_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    leave_request_id BIGINT NOT NULL,
    approver_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    before_status TINYINT NULL,
    after_status TINYINT NOT NULL,
    opinion VARCHAR(500) NULL,
    action_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_leave_approval_request (leave_request_id, action_time),
    CONSTRAINT fk_leave_approval_request FOREIGN KEY (leave_request_id) REFERENCES leave_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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


CREATE TABLE IF NOT EXISTS attendance_rule_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    effective_date DATE NOT NULL,
    work_start TIME NOT NULL,
    work_end TIME NOT NULL,
    late_warning INT NOT NULL,
    early_warning INT NOT NULL,
    check_in_exception INT NOT NULL,
    check_out_exception INT NOT NULL,
    earliest_check_in TIME NOT NULL,
    latest_check_out TIME NOT NULL,
    KEY idx_attendance_rule_effective (effective_date,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS attendance_calendar_exception (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    calendar_date DATE NOT NULL,
    workday TINYINT NOT NULL,
    UNIQUE KEY uk_attendance_calendar_date (calendar_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS attendance_punch_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    record_id BIGINT NOT NULL,
    rule_version_id BIGINT NULL,
    legacy_rule_id BIGINT NULL,
    direction VARCHAR(12) NOT NULL,
    punched_at DATETIME NOT NULL,
    punched_ip VARCHAR(45) NULL,
    deviation_minutes INT NOT NULL,
    UNIQUE KEY uk_attendance_punch_direction (record_id,direction),
    CONSTRAINT fk_attendance_punch_record FOREIGN KEY (record_id) REFERENCES attendance_record(id),
    CONSTRAINT fk_attendance_punch_rule FOREIGN KEY (rule_version_id) REFERENCES attendance_rule_version(id),
    CONSTRAINT fk_attendance_punch_legacy FOREIGN KEY (legacy_rule_id) REFERENCES attendance_rule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS attendance_exception_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    punch_id BIGINT NOT NULL,
    applicant_id BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    submitted_at DATETIME NOT NULL,
    decision VARCHAR(16) NULL,
    reviewer_id BIGINT NULL,
    opinion VARCHAR(500) NULL,
    decided_at DATETIME NULL,
    read_at DATETIME NULL,
    UNIQUE KEY uk_attendance_exception_punch (punch_id),
    KEY idx_attendance_exception_applicant (applicant_id,status),
    KEY idx_attendance_exception_status (status,submitted_at),
    CONSTRAINT fk_attendance_exception_punch FOREIGN KEY (punch_id) REFERENCES attendance_punch_event(id),
    CONSTRAINT fk_attendance_exception_applicant FOREIGN KEY (applicant_id) REFERENCES employee(id),
    CONSTRAINT fk_attendance_exception_reviewer FOREIGN KEY (reviewer_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO dictionary_item(dict_type,item_value,item_label,sort_order,status)
VALUES('__attendance_internal','workflow_start',CAST(UNIX_TIMESTAMP() AS CHAR),0,0)
ON DUPLICATE KEY UPDATE item_label=item_label;
