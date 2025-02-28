package com.xyz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 启动类
@SpringBootApplication
public class demo1Application {

    public static void main(String[] args) {
        SpringApplication.run(demo1Application.class, args);
        System.out.println("项目启动成功！");
    }

}
