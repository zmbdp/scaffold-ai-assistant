package com.zmbdp.chat.service.mq;

import com.zmbdp.chat.service.constant.AiCacheConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 缓存失效广播消息
 * <p>
 * 通过 MQ Fanout 交换机广播到所有 chat-service 实例，通知删除本地 L1 Caffeine 缓存
 * <p>
 * <b>字段说明</b>：
 * <ul>
 *     <li>{@code cacheName}：缓存空间标识（{@link AiCacheConstants#CHAT_MEMORY} / {@link AiCacheConstants#AI_CONFIG}）</li>
 *     <li>{@code cacheKey}：Caffeine 中的 key（通常是完整的 Redis key，Consumer 端构造 L1 key 格式为 {@code {cacheName}:{cacheKey}}）；
 *     为 null 时表示清除整个缓存空间</li>
 * </ul>
 * <p>
 * <b>失效语义由调用方选择不同的 Producer 方法决定</b>（不在消息中携带标志位）：
 * <ul>
 *     <li>{@link CacheInvalidateProducer#invalidateL1Only}：只删 L1 Caffeine（CHAT_MEMORY 场景，Redis List 是数据存储不删）</li>
 *     <li>{@link CacheInvalidateProducer#invalidate}：删 L2 Redis + 广播删 L1（AI_CONFIG 单条失效场景）</li>
 *     <li>{@link CacheInvalidateProducer#invalidateAll}：删 L2 Redis 通配符 key + 广播删 L1 整空间（AI_CONFIG 批量失效场景）</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@Data
public class CacheInvalidateMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 缓存空间标识（CHAT_MEMORY / AI_CONFIG）
     */
    private String cacheName;

    /**
     * 缓存 key（通常是完整的 Redis key，如 ai:chat:memory:{sessionId}、ai:config:{configKey}）
     * <p>
     * 为 null 时表示清除整个缓存空间的所有条目（Consumer 端遍历 Caffeine 按前缀删除）。
     */
    private String cacheKey;

    public CacheInvalidateMessage() {
    }

    public CacheInvalidateMessage(String cacheName, String cacheKey) {
        this.cacheName = cacheName;
        this.cacheKey = cacheKey;
    }
}