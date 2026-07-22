package com.zmbdp.admin.service;

import com.zmbdp.chat.api.ai.feign.AiConfigApi;
import com.zmbdp.chat.api.ai.feign.ToolsApi;
import com.zmbdp.chat.api.feedback.feign.FeedbackApi;
import com.zmbdp.chat.api.knowledge.feign.KnowledgeApi;
import com.zmbdp.chat.api.operationlog.feign.OperationLogApi;
import com.zmbdp.chat.api.statistics.feign.StatisticsApi;
import com.zmbdp.chat.api.system.feign.SystemApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * admin 服务启动类
 * <p>
 * 注册 AI 管理相关的 Feign 客户端，通过 Feign 调用 chat-service 的B端管理接口：
 * <ul>
 *     <li>{@link KnowledgeApi}：知识源管理（CRUD、文档管理、知识同步、召回测试）</li>
 *     <li>{@link AiConfigApi}：AI 配置管理（查询/更新、模型列表、连接测试）</li>
 *     <li>{@link ToolsApi}：工具管理（列表、配置更新、测试）</li>
 *     <li>{@link OperationLogApi}：AI 调用链路日志查询</li>
 *     <li>{@link StatisticsApi}：统计分析（对话统计、热门问题、用户统计等）</li>
 * </ul>
 * <p>
 * <b>说明</b>：admin-service 作为B端统一入口，chat-service 不直接暴露给前端，
 * 所有B端管理请求经 Gateway → admin-service → Feign → chat-service。
 *
 * @author 稚名不带撇
 */
@Slf4j
@EnableScheduling
@EnableFeignClients(clients = {
        KnowledgeApi.class,
        AiConfigApi.class,
        ToolsApi.class,
        OperationLogApi.class,
        StatisticsApi.class,
        SystemApi.class,
        FeedbackApi.class
})
@SpringBootApplication
public class ZmbdpAdminServiceApplication {

    /**
     * 启动方法
     *
     * @param args 参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ZmbdpAdminServiceApplication.class, args);
        log.info("admin 服务启动成功......");
    }
}