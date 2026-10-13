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
