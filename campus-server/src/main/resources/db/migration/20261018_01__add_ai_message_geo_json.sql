-- AI 助手消息附带的结构化地理事件（地图回放）：MEDIUMTEXT JSON，可空。
-- 存量库迁移；全新库见 db/schema.sql，测试库见 src/test/resources/db/test-init.sql（三处需同步）。
ALTER TABLE ai_message
    ADD COLUMN geo_json MEDIUMTEXT DEFAULT NULL COMMENT '助手消息附带的地理事件JSON（地图回放）' AFTER content;
