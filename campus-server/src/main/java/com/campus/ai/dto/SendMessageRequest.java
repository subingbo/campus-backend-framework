package com.campus.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SendMessageRequest {

    @NotBlank
    @Size(max = 1000)
    private String content;

    /** 可选：客户端随消息上送的实时定位快照（GCJ-02），仅注入本轮 Agent 工具语境 */
    private AiUserLocation userLocation;
}
