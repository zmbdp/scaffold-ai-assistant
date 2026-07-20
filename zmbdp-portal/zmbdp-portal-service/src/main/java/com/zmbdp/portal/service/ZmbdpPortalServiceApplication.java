package com.zmbdp.portal.service;

import com.zmbdp.admin.api.appuser.feign.AppUserApi;
import com.zmbdp.chat.api.chat.feign.ChatApi;
import com.zmbdp.chat.api.feedback.feign.FeedbackApi;
import com.zmbdp.chat.api.history.feign.HistoryApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 门户服务启动类
 * <p>
 * 注册 C端 AI 对话相关的 Feign 客户端：
 * <ul>
 *     <li>{@link AppUserApi}：C端用户信息查询（脚手架已有）</li>
 *     <li>{@link ChatApi}：RAG 检索（AI 对话业务编排使用）</li>
 *     <li>{@link HistoryApi}：对话历史查询/删除（C端历史接口使用）</li>
 *     <li>{@link FeedbackApi}：回答反馈提交/查询/撤销（C端反馈接口使用）</li>
 * </ul>
 * <p>
 * <b>说明</b>：流式对话（SSE）不通过 Feign（Feign 不支持 Flux 返回），
 * 由 {@code IChatPortalService} 通过 WebClient 调用 chat-service 的 SSE 端点。
 *
 * @author 稚名不带撇
 */
@Slf4j
@SpringBootApplication
@EnableFeignClients(clients = {
        AppUserApi.class,
        ChatApi.class,
        HistoryApi.class,
        FeedbackApi.class
})
public class ZmbdpPortalServiceApplication {

    /**
     * 启动方法
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ZmbdpPortalServiceApplication.class, args);
        log.info("门户服务启动成功......");
    }
}