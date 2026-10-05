CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    nickname VARCHAR(64) DEFAULT NULL,
    avatar VARCHAR(512) DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    code VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    code VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_role (id, name, code) VALUES
(1, '普通用户', 'USER'),
(2, '管理员', 'ADMIN');

INSERT INTO sys_permission (id, name, code) VALUES
(1, '查看地点', 'place:view'),
(2, '创建地点', 'place:create'),
(3, '创建帖子', 'post:create'),
(4, '创建评论', 'comment:create'),
(5, '删除地点', 'place:delete'),
(6, '删除帖子', 'post:delete'),
(7, '封禁用户', 'user:ban'),
(8, '处理举报', 'report:handle');

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 1), (1, 2), (1, 3), (1, 4),
(2, 1), (2, 2), (2, 3), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8);

INSERT INTO sys_user (id, username, password, nickname, status) VALUES
(1, 'admin', '$2a$10$kszLIbA5uh3DlempUJQSYOvES1gXsxortyR2HfC4.r3UZJiH0Le.S', '管理员', 1);

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 2);
