package com.campus.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SendMessageRequest {

    @NotBlank
    @Size(max = 1000)
    private String content;

    /** 可选：客户端随消息上送的实时定位快照（GCJ-02），仅注入本轮 Agent 工具语境 */
    private AiUserLocation userLocation;

    /**
     * 可选：本轮参考的共享地图。若选中一张自己加入的群地图，
     * 后端会把该群作为本轮群语境（成员忙闲、冲突分析、计划盖章均生效）。
     */
    private List<Long> referenceMapIds;
}
