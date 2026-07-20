package com.zmbdp.chat.service.mq;

import com.zmbdp.chat.service.constant.AiCacheConstants;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 缓存失效广播消费者
 * <p>
 * 接收 MQ 广播的缓存失效消息，删除本地 L1 Caffeine 缓存
 * <p>
 * <b>关键设计</b>：使用 {@code @RabbitListener(bindings = @QueueBinding(...))} 在代码中自动声明
 * 交换机、<b>匿名队列</b>和绑定关系，<b>无需在 RabbitMQ 管理页面手动创建</b>。
 * 每个 chat-service 实例启动时自动创建独占的匿名队列（{@code exclusive=true, autoDelete=true}），
 * 绑定到 Fanout 交换机，实现广播语义（消息会推送到所有绑定的队列）。
 * <p>
 * <b>为何不能用命名持久队列</b>：Fanout 交换机下，多个实例若共享同一命名队列会<b>竞争消费</b>
 * （每条消息只被一个实例消费），无法实现广播。匿名队列每实例独占，才能确保每条消息被所有实例各消费一次。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
public class CacheInvalidateConsumer {

    /**
     * L1 Caffeine 缓存实例（由 zmbdp-common-cache 的 CaffeineConfig 注册）
     */
    @Autowired
    @Qualifier("caffeineCache")
    private Cache<String, Object> caffeineCache;

    /**
     * 处理缓存失效消息
     * <p>
     * 根据 {@code cacheKey} 是否为空区分两种失效语义：
     * <ul>
     *     <li>{@code cacheKey} 非空：删除单个缓存条目（L1 Caffeine key 格式为 {@code {cacheName}:{cacheKey}}）</li>
     *     <li>{@code cacheKey} 为空：清除整个缓存空间的所有条目（Caffeine 不支持按前缀删除，
     *     需要遍历 keySet 按前缀过滤后逐个删除）</li>
     * </ul>
     * <p>
     * <b>@QueueBinding 说明</b>：
     * <ul>
     *     <li>{@code exchange}：Fanout 类型交换机，名字提取到 {@link AiCacheConstants#CACHE_INVALIDATE_EXCHANGE} 常量</li>
     *     <li>{@code queue}：{@code @Queue} 不写 value 属性 → Spring AMQP 自动生成匿名队列
     *     （{@code exclusive=true, autoDelete=true}），每个实例独占一个队列，实现 Fanout 广播</li>
     *     <li>{@code key}：路由 key 提取到 {@link AiCacheConstants#CACHE_INVALIDATE_ROUTING_KEY} 常量
     *     （Fanout 类型会忽略此值，定义仅为规范和可扩展性）</li>
     * </ul>
     * 交换机名、队列绑定关系由 Spring AMQP 在应用启动时自动声明到 RabbitMQ，
     * 无需在 RabbitMQ 管理页面手动创建交换机和队列。
     *
     * @param message 缓存失效消息
     */
    @RabbitListener(bindings = @QueueBinding(
            exchange = @Exchange(value = AiCacheConstants.CACHE_INVALIDATE_EXCHANGE, type = ExchangeTypes.FANOUT),
            value = @Queue,
            key = AiCacheConstants.CACHE_INVALIDATE_ROUTING_KEY
    ))
    public void handleCacheInvalidate(CacheInvalidateMessage message) {
        try {
            String cacheName = message.getCacheName();
            String cacheKey = message.getCacheKey();

            if (cacheKey == null) {
                // cacheKey 为空 → 清除整个缓存空间的所有条目
                // Caffeine 不支持按前缀删除，需要遍历 keySet 按前缀过滤后逐个删除
                String prefix = cacheName + ":";
                caffeineCache.asMap().keySet().stream()
                        .filter(key -> key.startsWith(prefix))
                        .forEach(caffeineCache::invalidate);
                log.info("清除 Caffeine 缓存空间：{}", cacheName);
            } else {
                // 删除单个缓存条目（Caffeine key 格式：{cacheName}:{cacheKey}）
                String l1Key = cacheName + ":" + cacheKey;
                caffeineCache.invalidate(l1Key);
                log.debug("清除 Caffeine 缓存条目：{}", l1Key);
            }
        } catch (Exception e) {
            log.error("处理缓存失效消息异常：{}", message, e);
        }
    }
}