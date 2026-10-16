-- 2026-10-15 多站活动行程：activity_stop
--
-- 执行方式（对已初始化的既有库，initdb.d 脚本不会重跑）：
--   docker compose exec -T mysql mysql -ucampus -pcampus123 campus < 本文件
-- 全新初始化无需手动执行：schema.sql 已同步包含该表。
-- 坐标契约：longitude/latitude 一律 GCJ-02，与 place 表保持同一基准。

USE campus;

CREATE TABLE IF NOT EXISTS activity_stop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '行程站点ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    seq INT NOT NULL COMMENT '站点顺序，从 1 递增',
    name VARCHAR(128) NOT NULL COMMENT '站点名称',
    place_id BIGINT DEFAULT NULL COMMENT '关联地点ID，可空（外部 POI 坐标直存）',
    longitude DECIMAL(10, 7) DEFAULT NULL COMMENT '经度 GCJ-02',
    latitude DECIMAL(10, 7) DEFAULT NULL COMMENT '纬度 GCJ-02',
    start_time DATETIME DEFAULT NULL COMMENT '到达/开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '离开/结束时间',
    transport_mode VARCHAR(16) DEFAULT NULL COMMENT '前往本站的交通方式：WALK 步行 RIDE 骑行 DRIVE 驾车 TRANSIT 公交',
    note VARCHAR(256) DEFAULT NULL COMMENT '站点备注',
    UNIQUE KEY uk_activity_seq (activity_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多站活动行程站点';
