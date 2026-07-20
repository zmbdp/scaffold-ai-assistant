package com.zmbdp.chat.service;

import com.zmbdp.admin.api.config.feign.ArgumentServiceApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * chat 服务启动类
 *
 * @author 稚名不带撇
 */
@Slf4j
@SpringBootApplication
@EnableFeignClients(clients = {
        ArgumentServiceApi.class
})
public class ZmbdpChatServiceApplication {

    /**
     * 启动方法
     *
     * @param args 参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ZmbdpChatServiceApplication.class, args);
        log.info("chat 服务启动成功......");
    }
}