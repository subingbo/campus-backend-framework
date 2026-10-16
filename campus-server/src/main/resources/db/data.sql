USE campus;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO sys_role (id, name, code) VALUES
(1, '普通用户', 'USER'),
(2, '管理员', 'ADMIN')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO sys_permission (id, name, code) VALUES
(1, '查看地点', 'place:view'),
(2, '创建地点', 'place:create'),
(3, '创建帖子', 'post:create'),
(4, '创建评论', 'comment:create'),
(5, '删除地点', 'place:delete'),
(6, '删除帖子', 'post:delete'),
(7, '封禁用户', 'user:ban'),
(8, '处理举报', 'report:handle')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 1), (1, 2), (1, 3), (1, 4),
(2, 1), (2, 2), (2, 3), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- admin / admin123 (BCrypt)
INSERT INTO sys_user (id, username, password, nickname, status) VALUES
(1, 'admin', '$2a$10$kszLIbA5uh3DlempUJQSYOvES1gXsxortyR2HfC4.r3UZJiH0Le.S', '管理员', 1)
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname);

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 2)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO place_category (id, name, icon, sort, status) VALUES
(1, '美食', NULL, 1, 1),
(2, '学习', NULL, 2, 1),
(3, '运动', NULL, 3, 1),
(4, '购物', NULL, 4, 1),
(5, '生活服务', NULL, 5, 1),
(6, '交通', NULL, 6, 1),
(7, '娱乐', NULL, 7, 1),
(8, '其他', NULL, 8, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);
