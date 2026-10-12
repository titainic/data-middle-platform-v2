package tech.qiantong.qdata.module.mc.controller.admin.metadata.vo;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
public class ToggleStatusVO {

    @NotNull(message = "id null")
    private Long id;

    @NotBlank(message = "status blank")
    @jakarta.validation.constraints.Pattern(regexp="[01]",message="状态只能为0或1")
    private String status;

}
