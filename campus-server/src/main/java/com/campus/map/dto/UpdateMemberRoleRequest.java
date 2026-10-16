package com.campus.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "更新地图成员角色")
public class UpdateMemberRoleRequest {

    @NotBlank
    private String role;
}
