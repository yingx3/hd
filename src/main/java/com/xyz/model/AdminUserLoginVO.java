package com.xyz.model;


import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("管理员数据")
public class AdminUserLoginVO {

    @ApiModelProperty("id")
    private Long id;

    @ApiModelProperty("用户名")
    private String userName;
    @ApiModelProperty("手机号")
    private String phone;
    @ApiModelProperty("注册时间")
    private Data registerTime;
}
