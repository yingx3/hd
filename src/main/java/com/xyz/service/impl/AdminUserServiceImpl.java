package com.xyz.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xyz.mapper.AdminUserMapper;
import com.xyz.model.AdminUserLoginDTO;
import com.xyz.model.AdminUserLoginVO;
import com.xyz.model.AdminUserPO;
import com.xyz.service.AdminUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminUserServiceImpl implements AdminUserService {

    @Autowired
    private AdminUserMapper adminUserMapper;

    @Override
    public AdminUserLoginVO passLogin(AdminUserLoginDTO dto){
        //todo 数据校验
        LambdaQueryWrapper<AdminUserPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdminUserPO::getPhone,dto.getPhone())
                .eq(AdminUserPO::getIsCancellation,false);
        AdminUserPO po = adminUserMapper.selectOne(wrapper);
        if(po == null){
            throw new RuntimeException("手机号未注册");

        }
        if(!po.getPassword().equals(dto.getPassword())){
            throw new RuntimeException("密码错误");
        }



        AdminUserLoginVO vo  = new AdminUserLoginVO();
        vo.setId(po.getId());
        vo.setPhone(po.getPhone());
        vo.setUserName(po.getUserName());
        vo.setRegisterTime(po.getRegisterTime());
        return vo;

    }
}
