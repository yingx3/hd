package com.xyz.model;


import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("管理员数据")
public class AdminUserLoginDTO {
    @ApiModelProperty("手机号")
    private String phone;

    @ApiModelProperty("密码，明文")
    private String password;
}
