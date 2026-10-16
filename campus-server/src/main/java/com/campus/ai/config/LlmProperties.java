package com.campus.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "campus.llm")
public class LlmProperties {

    /** OpenAI 兼容协议端点；DashScope 兼容模式 / 任意厂商可经配置切换 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey = "";
    private String model = "qwen-plus";
    /** 单次回复 token 上限（预算控制） */
    private int maxTokens = 2048;
}
