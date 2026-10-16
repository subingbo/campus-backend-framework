package com.campus.security.jwt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "campus.jwt")
public class JwtProperties {

    private String secret;
    private long accessTokenExpiration = 1800;
    private long refreshTokenExpiration = 604800;
}
