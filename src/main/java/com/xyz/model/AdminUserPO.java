package com.xyz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data//用于简化java类的标准方法，如toString（）、equals()、hasCode()、getter和setter
@TableName("admin_user")
@ApiModel("管理员表")
public class AdminUserPO {
    @TableId(value = "id",type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("用户名")
    private String userName;

    @ApiModelProperty("手机号")
    private String phone;

    @ApiModelProperty("密码，密文")
    private String password;

    @ApiModelProperty("注册时间")
    private Data registerTime;

    @ApiModelProperty("是否注销")
    private Boolean isCancellation;

    @Version
    private Long version;//乐观锁，用于数据同步


}
