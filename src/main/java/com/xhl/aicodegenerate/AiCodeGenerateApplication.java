package com.xhl.aicodegenerate;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true) //解决内部调用事务失效
@MapperScan("com.xhl.aicodegenerate.mapper")
public class AiCodeGenerateApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodeGenerateApplication.class, args);
    }

}
