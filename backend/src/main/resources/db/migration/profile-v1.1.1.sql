CREATE TABLE IF NOT EXISTS employee_profile (
 employee_id BIGINT PRIMARY KEY,
 email VARCHAR(100), address VARCHAR(255), emergency_name VARCHAR(50),
 emergency_relation VARCHAR(30), emergency_phone VARCHAR(20), biography VARCHAR(1000),
 gender CHAR(1), version BIGINT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 CONSTRAINT fk_profile_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS employee_education (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, employee_id BIGINT NOT NULL,
 school VARCHAR(100) NOT NULL, degree VARCHAR(30) NOT NULL, major VARCHAR(100),
 start_month CHAR(7) NOT NULL, end_month CHAR(7), current_entry TINYINT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_education_employee (employee_id),
 CONSTRAINT fk_education_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS employee_experience (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, employee_id BIGINT NOT NULL,
 company VARCHAR(100) NOT NULL, department VARCHAR(100), position VARCHAR(100),
 start_month CHAR(7) NOT NULL, end_month CHAR(7), current_entry TINYINT NOT NULL DEFAULT 0,
 description VARCHAR(1000),
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_experience_employee (employee_id),
 CONSTRAINT fk_experience_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS employee_skill (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, employee_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL, proficiency VARCHAR(10) NOT NULL, notes VARCHAR(500),
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_skill_employee (employee_id),
 CONSTRAINT fk_skill_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS employee_certificate (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, employee_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL, issuer VARCHAR(100) NOT NULL, certificate_number VARCHAR(100),
 obtained_date DATE NOT NULL, expiry_date DATE,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_certificate_employee (employee_id),
 CONSTRAINT fk_certificate_employee FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
