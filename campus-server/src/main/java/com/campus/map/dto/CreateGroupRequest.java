package com.campus.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "创建群组与共享地图")
public class CreateGroupRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    @Size(max = 128)
    private String mapName;

    @Schema(description = "MEMBERS 或 PUBLIC，默认 MEMBERS")
    private String visibility;
}
