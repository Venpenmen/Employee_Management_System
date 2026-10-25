-- EMS v1.1.1 完整个人档案：增量新增 5 张表（12 -> 17），可重复执行。
-- 不修改、不重建原有业务表；旧员工缺少扩展档案时页面显示“未填写”。
-- 开发启动由 spring.sql.init 自动执行；便携启动由 launcher 在后端启动前执行同一文件。

CREATE TABLE IF NOT EXISTS employee_profile (
    employee_id BIGINT PRIMARY KEY,
    email VARCHAR(100) NULL,
    address VARCHAR(255) NULL,
    emergency_name VARCHAR(50) NULL,
    emergency_relation VARCHAR(30) NULL,
    emergency_phone VARCHAR(20) NULL,
    gender CHAR(1) NULL COMMENT '0女 1男，NULL未填写',
    biography VARCHAR(1000) NULL,
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，防止旧页面覆盖较新资料',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_profile_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS employee_education (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    school VARCHAR(100) NOT NULL,
    degree VARCHAR(30) NOT NULL,
    major VARCHAR(100) NULL,
    start_month CHAR(7) NOT NULL COMMENT 'YYYY-MM',
    end_month CHAR(7) NULL COMMENT 'YYYY-MM，在读时为空',
    current_entry TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_education_employee (employee_id),
    CONSTRAINT fk_education_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS employee_experience (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    company VARCHAR(100) NOT NULL,
    department VARCHAR(100) NULL,
    position VARCHAR(100) NULL,
    start_month CHAR(7) NOT NULL COMMENT 'YYYY-MM',
    end_month CHAR(7) NULL COMMENT 'YYYY-MM，在职时为空',
    current_entry TINYINT NOT NULL DEFAULT 0,
    description VARCHAR(1000) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_experience_employee (employee_id),
    CONSTRAINT fk_experience_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS employee_skill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    proficiency VARCHAR(10) NOT NULL COMMENT '了解/熟悉/熟练/精通',
    notes VARCHAR(500) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_skill_employee (employee_id),
    CONSTRAINT fk_skill_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS employee_certificate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    issuer VARCHAR(100) NOT NULL,
    certificate_number VARCHAR(100) NULL,
    obtained_date DATE NOT NULL,
    expiry_date DATE NULL COMMENT '长期有效时为空',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_certificate_employee (employee_id),
    CONSTRAINT fk_certificate_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
