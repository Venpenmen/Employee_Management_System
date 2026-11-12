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
