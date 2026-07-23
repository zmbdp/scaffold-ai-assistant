package com.zmbdp.admin.service.ai.config;

import com.zmbdp.chat.api.knowledge.constant.KnowledgeSyncMQConstants;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识同步 MQ 配置（admin-service 发送端）
 * <p>
 * 声明知识同步 Direct 交换机。队列和绑定由 chat-service 消费端通过
 * {@code @RabbitListener(bindings = @QueueBinding(...))} 自动声明。
 * <p>
 * <b>设计说明</b>：admin-service 只声明交换机（确保发消息时交换机存在），
 * 不声明队列（队列由 chat-service 消费端声明，避免 admin-service 下线后队列被删除）。
 *
 * @author 稚名不带撇
 */
@Configuration
public class KnowledgeSyncMqConfig {

    /**
     * 知识同步 Direct 交换机（持久化，不自动删除）
     *
     * @return Direct 交换机实例
     */
    @Bean
    public DirectExchange knowledgeSyncExchange() {
        return new DirectExchange(KnowledgeSyncMQConstants.EXCHANGE, true, false);
    }
}
