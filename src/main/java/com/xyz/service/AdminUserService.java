package com.xyz.service;


import com.xyz.model.AdminUserLoginDTO;
import com.xyz.model.AdminUserLoginVO;

public interface AdminUserService {
    AdminUserLoginVO passLogin(AdminUserLoginDTO dto);
}
