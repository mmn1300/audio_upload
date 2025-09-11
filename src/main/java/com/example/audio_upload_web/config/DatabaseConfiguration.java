package com.example.audio_upload_web.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@MapperScan(basePackages = {
        "com.example.audio_upload_web.audio_upload.mapper"
})
public class DatabaseConfiguration {

}
