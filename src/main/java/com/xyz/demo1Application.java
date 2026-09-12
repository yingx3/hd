package com.xyz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

// 启动类
@SpringBootApplication
public class demo1Application extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(demo1Application.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(demo1Application.class, args);
        System.out.println("项目启动成功！");
    }

}