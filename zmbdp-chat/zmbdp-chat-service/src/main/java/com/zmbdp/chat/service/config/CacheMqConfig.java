package com.zmbdp.chat.service.config;

import com.zmbdp.chat.service.constant.AiCacheConstants;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存失效广播 MQ 配置
 * <p>
 * 声明 Fanout 交换机 Bean（队列和绑定关系由 {@code CacheInvalidateConsumer} 的
 * {@code @RabbitListener(bindings=@QueueBinding(...))} 自动声明）。
 * <p>
 * <b>关于交换机重复声明</b>：Fanout 交换机在此处用 {@code @Bean} 声明一次，在消费者的
 * {@code @RabbitListener(bindings=@QueueBinding(...))} 的 {@code @Exchange} 中又会声明一次。
 * Spring AMQP 对此<b>幂等处理</b>（属性一致不会报错），这是 Spring AMQP 推荐的做法：
 * 生产者端通过 {@code @Bean} 确保交换机存在（生产者启动时即可声明），
 * 消费者端通过 {@code @QueueBinding} 确保绑定关系存在（消费者启动时声明匿名队列并绑定）。
 * 两处引用同一个常量 {@link AiCacheConstants#CACHE_INVALIDATE_EXCHANGE}，属性一致，无需担心冲突。
 *
 * @author 稚名不带撇
 */
@Configuration
public class CacheMqConfig {

    /**
     * 缓存失效广播交换机（Fanout 类型，持久化，不自动删除）
     * <p>
     * 消费者端通过 {@code @RabbitListener(bindings=@QueueBinding)} 自动声明匿名队列并绑定到此交换机
     * （每实例独占匿名队列，实现 Fanout 广播语义）。
     *
     * @return Fanout 交换机实例
     */
    @Bean
    public FanoutExchange cacheInvalidateExchange() {
        return new FanoutExchange(AiCacheConstants.CACHE_INVALIDATE_EXCHANGE, true, false);
    }
}