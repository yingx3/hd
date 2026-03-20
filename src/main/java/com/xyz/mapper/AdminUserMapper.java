package com.xyz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyz.model.AdminUserPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUserPO> {
}
