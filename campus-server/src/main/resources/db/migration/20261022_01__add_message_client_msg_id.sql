-- 聊天消息幂等：断线重试携带同一 clientMsgId 时，服务端唯一键保证只落一行
ALTER TABLE message
    ADD COLUMN client_msg_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等ID（断线重试去重）' AFTER type,
    ADD UNIQUE KEY uk_client_msg (conversation_id, sender_id, client_msg_id);
