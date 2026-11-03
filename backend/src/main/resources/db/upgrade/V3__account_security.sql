CREATE TABLE IF NOT EXISTS account_security (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 employee_id BIGINT NOT NULL UNIQUE,
 frozen TINYINT NOT NULL DEFAULT 0,
 freeze_reason VARCHAR(200) NULL,
 frozen_at DATETIME(6) NULL,
 temporary_state VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
 temporary_expires_at DATETIME(6) NULL,
 temporary_errors INT NOT NULL DEFAULT 0,
 anomaly_epoch DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 warning_sent TINYINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_security_account FOREIGN KEY(employee_id) REFERENCES user_account(employee_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS secondary_credential (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 employee_id BIGINT NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 credential_version BIGINT NOT NULL DEFAULT 1,
 failed_attempts INT NOT NULL DEFAULT 0,
 locked_until DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_secondary_account FOREIGN KEY(employee_id) REFERENCES user_account(employee_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS password_change_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 employee_id BIGINT NOT NULL,
 active_employee_id BIGINT NULL UNIQUE,
 reason VARCHAR(500) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 approver_id BIGINT NULL,
 handling_method VARCHAR(20) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 approved_at DATETIME(6) NULL,
 expires_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6) NULL,
 KEY idx_password_request_employee(employee_id),
 CONSTRAINT fk_password_request_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
 CONSTRAINT fk_password_request_active FOREIGN KEY(active_employee_id) REFERENCES employee(id),
 CONSTRAINT fk_password_request_approver FOREIGN KEY(approver_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS security_grant (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 employee_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 target_id BIGINT NOT NULL,
 request_id BIGINT NULL,
 action VARCHAR(30) NOT NULL,
 session_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 credential_version BIGINT NOT NULL DEFAULT 0,
 expires_at DATETIME(6) NOT NULL,
 consumed_at DATETIME(6) NULL,
 KEY idx_security_grant_employee(employee_id),
 CONSTRAINT fk_security_grant_account FOREIGN KEY(employee_id) REFERENCES user_account(employee_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS login_security_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 employee_id BIGINT NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 masked_address VARCHAR(80) NULL,
 device_type VARCHAR(30) NULL,
 browser_type VARCHAR(30) NULL,
 summary VARCHAR(500) NOT NULL,
 KEY idx_login_event_employee_time(employee_id,created_at),
 CONSTRAINT fk_login_event_employee FOREIGN KEY(employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS security_notification (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 employee_id BIGINT NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 subject_employee_id BIGINT NOT NULL,
 summary VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 read_at DATETIME(6) NULL,
 KEY idx_security_notice_employee(employee_id),
 CONSTRAINT fk_security_notice_recipient FOREIGN KEY(employee_id) REFERENCES employee(id),
 CONSTRAINT fk_security_notice_subject FOREIGN KEY(subject_employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
