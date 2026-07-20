package com.zmbdp.chat.service.mq;

import com.zmbdp.chat.service.constant.AiCacheConstants;
import com.zmbdp.common.redis.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 缓存失效广播生产者
 * <p>
 * 通过 RabbitMQ Fanout 交换机广播缓存失效消息到所有 chat-service 实例，
 * 通知各实例删除本地 L1 Caffeine 缓存。
 * <p>
 * <b>三个方法对应两种缓存空间的不同失效语义</b>：
 * <ul>
 *     <li>{@link #invalidate} / {@link #invalidateAll}：用于 {@link AiCacheConstants#AI_CONFIG} 场景
 *     （MySQL 为数据源、Redis 为 L2 缓存），先删 L2 Redis，再广播删所有实例的 L1 Caffeine</li>
 *     <li>{@link #invalidateL1Only}：用于 {@link AiCacheConstants#CHAT_MEMORY} 场景
 *     （Redis List 即数据存储，已通过 lPush 更新或由 clearHistory 删除，无需再删 Redis），
 *     只广播删所有实例的 L1 Caffeine</li>
 * </ul>
 * <p>
 * <b>交换机声明</b>：Fanout 交换机由 {@code CacheMqConfig} 通过 {@code @Bean} 声明，
 * 队列和绑定关系由 {@code CacheInvalidateConsumer} 的
 * {@code @RabbitListener(bindings=@QueueBinding(...))} 自动声明（匿名队列，每实例独占），
 * 无需手动在 RabbitMQ 管理台创建。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
public class CacheInvalidateProducer {

    /**
     * RabbitMQ 操作模板
     */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * Redis 服务（用于删 L2 Redis 缓存，AI_CONFIG 场景使用）
     */
    @Autowired
    private RedisService redisService;

    /**
     * 失效单个缓存条目（删 L2 Redis + 广播删 L1 Caffeine）
     * <p>
     * 适用场景：{@link AiCacheConstants#AI_CONFIG} 缓存空间的单条配置失效
     * （如管理员修改某个工具启用状态）。
     *
     * @param cacheName 缓存空间名（{@link AiCacheConstants#AI_CONFIG}）
     * @param redisKey  完整的 Redis key（含前缀，如 {@code ai:config:ai.temperature}）
     * @param cacheKey  Caffeine 中的 key（通常与 redisKey 相同，Consumer 端构造 L1 key 为 {@code {cacheName}:{cacheKey}}）
     */
    public void invalidate(String cacheName, String redisKey, String cacheKey) {
        // 1. 删除 L2 Redis 缓存
        redisService.deleteObject(redisKey);
        // 2. 发送 MQ 消息广播删除 L1 Caffeine 缓存
        CacheInvalidateMessage message = new CacheInvalidateMessage(cacheName, cacheKey);
        rabbitTemplate.convertAndSend(AiCacheConstants.CACHE_INVALIDATE_EXCHANGE, "", message);
        log.info("缓存失效广播（L2+L1）：cacheName = {}, cacheKey = {}", cacheName, cacheKey);
    }

    /**
     * 失效整个缓存空间（删 L2 Redis 通配符 key + 广播删 L1 Caffeine 整空间）
     * <p>
     * 适用场景：{@link AiCacheConstants#AI_CONFIG} 缓存空间的整空间失效
     * （如管理员批量修改配置）。
     *
     * @param cacheName      缓存空间名（{@link AiCacheConstants#AI_CONFIG}）
     * @param redisKeyPattern Redis key 通配符（如 {@code "ai:config:*"}）
     */
    public void invalidateAll(String cacheName, String redisKeyPattern) {
        // 1. 删除 L2 Redis 中该空间的所有 key
        Collection<String> keys = redisService.keys(redisKeyPattern);
        int deletedCount = 0;
        if (keys != null && !keys.isEmpty()) {
            redisService.deleteObject(keys);
            deletedCount = keys.size();
        }
        // 2. 发送 MQ 消息广播删除 L1 Caffeine 该空间的所有条目（cacheKey=null 表示清除整个空间）
        CacheInvalidateMessage message = new CacheInvalidateMessage(cacheName, null);
        rabbitTemplate.convertAndSend(AiCacheConstants.CACHE_INVALIDATE_EXCHANGE, "", message);
        log.info("缓存空间失效广播（L2+L1）：cacheName = {}, 删除Redis key数量 = {}", cacheName, deletedCount);
    }

    /**
     * 仅失效 L1 Caffeine（不删 L2 Redis）
     * <p>
     * 适用场景：{@link AiCacheConstants#CHAT_MEMORY} 缓存空间
     * （Redis List 即数据存储，已通过 lPush 更新或由 clearHistory 删除，
     * 无需再删 Redis，只需让其他实例的 L1 Caffeine 失效，下次读时从 Redis List 重新加载）。
     *
     * @param cacheName 缓存空间名（{@link AiCacheConstants#CHAT_MEMORY}）
     * @param cacheKey  Caffeine 中的 key（通常是完整的 Redis 数据存储 key，如 {@code ai:chat:memory:{sessionId}}）
     */
    public void invalidateL1Only(String cacheName, String cacheKey) {
        // 只发送 MQ 消息广播删除 L1 Caffeine 缓存，不删除 Redis
        CacheInvalidateMessage message = new CacheInvalidateMessage(cacheName, cacheKey);
        rabbitTemplate.convertAndSend(AiCacheConstants.CACHE_INVALIDATE_EXCHANGE, "", message);
        log.info("缓存失效广播（仅L1）：cacheName = {}, cacheKey = {}", cacheName, cacheKey);
    }
}