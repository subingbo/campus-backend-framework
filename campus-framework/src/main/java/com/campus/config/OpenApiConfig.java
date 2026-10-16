package com.campus.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME = "BearerAuth";

    private static final String[] FRAMEWORK_PATHS = {"/api/auth/**", "/api/admin/user/**"};

    @Bean
    public OpenAPI campusOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Campus API")
                        .description("Campus 后端基础设施 API")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }

    @Bean
    public GroupedOpenApi frameworkOpenApi() {
        return GroupedOpenApi.builder()
                .group("脚手架基础设施")
                .pathsToMatch(FRAMEWORK_PATHS)
                .build();
    }

    @Bean
    public GroupedOpenApi businessOpenApi() {
        return GroupedOpenApi.builder()
                .group("校园业务")
                .pathsToExclude(FRAMEWORK_PATHS)
                .build();
    }
}
