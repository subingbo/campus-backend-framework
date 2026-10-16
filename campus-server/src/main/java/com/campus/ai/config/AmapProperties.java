package com.campus.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "campus.amap")
public class AmapProperties {

    /** 高德「Web服务」类型 key；为空时路线走本地估算并在结果中标记 estimated */
    private String webKey = "";
}
