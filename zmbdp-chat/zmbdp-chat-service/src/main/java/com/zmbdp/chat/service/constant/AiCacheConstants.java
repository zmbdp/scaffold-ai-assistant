package com.zmbdp.chat.service.constant;

/**
 * AI 模块缓存常量
 * <p>
 * 统一定义二级缓存（L1 Caffeine + L2 Redis + MQ 广播失效）相关的交换机、路由 key、队列、缓存空间、Redis 前缀等常量。
 * <p>
 * <b>术语区分</b>：
 * <ul>
 *     <li>脚手架"三级缓存"（布隆过滤器 + Caffeine + Redis）是 AI 助手要讲解的业务功能</li>
 *     <li>AI 模块"二级缓存"（L1 Caffeine + L2 Redis + MQ 广播失效）是 AI 模块自身的基础设施</li>
 * </ul>
 * 两者是不同层面的概念，AI 模块缓存复用 CaffeineConfig 和 RedisService 但不直接复用 CacheUtil（因需 MQ 广播失效能力）。
 *
 * @author 稚名不带撇
 */
public class AiCacheConstants {

    /**
     * 缓存失效广播交换机（Fanout 类型）
     */
    public static final String CACHE_INVALIDATE_EXCHANGE = "cache.invalidate.exchange";

    /* ==================== MQ 广播失效配置 ==================== */
    /**
     * 缓存失效广播路由 key
     * <p>
     * Fanout 类型交换机会忽略 routing key，此处定义仅为规范和可扩展性
     */
    public static final String CACHE_INVALIDATE_ROUTING_KEY = "cache.invalidate";

    /**
     * 缓存空间：对话记忆（Redis List 为数据存储，L1 Caffeine 为对象副本）
     * <p>
     * 失效语义：只删 L1 Caffeine（invalidateL1Only），不删 Redis List（数据存储，addMessage 通过 lPush 已更新为最新）
     */
    public static final String CHAT_MEMORY = "CHAT_MEMORY";

    /* ==================== 缓存空间标识 ==================== */
    /**
     * 缓存空间：AI 配置（MySQL 为数据源，L2 Redis + L1 Caffeine 为缓存）
     * <p>
     * 失效语义：删 L2 Redis + 广播删 L1 Caffeine（invalidate / invalidateAll）
     */
    public static final String AI_CONFIG = "AI_CONFIG";

    /**
     * 对话记忆 Redis List 前缀（数据存储，非缓存）
     * <p>
     * 完整 key：ai:chat:memory:{sessionId}
     */
    public static final String CHAT_MEMORY_REDIS_PREFIX = "ai:chat:memory:";

    /* ==================== Redis Key 前缀 ==================== */
    /**
     * AI 配置 Redis 缓存前缀（L2 缓存）
     * <p>
     * 完整 key：ai:config:{configKey}
     */
    public static final String AI_CONFIG_REDIS_PREFIX = "ai:config:";

    /**
     * 对话记忆 Redis List TTL（7 天，单位秒）
     */
    public static final long CHAT_MEMORY_REDIS_TTL_SECONDS = 604800L;

    /* ==================== 对话记忆参数 ==================== */
    /**
     * 对话记忆 L1 Caffeine TTL（5 分钟，单位秒）
     */
    public static final long CHAT_MEMORY_CAFFEINE_TTL_SECONDS = 300L;

    /**
     * 对话记忆 L1 Caffeine 最大条数
     */
    public static final int CHAT_MEMORY_CAFFEINE_MAX_SIZE = 1000;

    /**
     * 每会话最多保存的消息条数（Redis List 裁剪阈值）
     */
    public static final int CHAT_MEMORY_MAX_MESSAGES_PER_SESSION = 100;

    /**
     * Milvus 集合名
     */
    public static final String MILVUS_COLLECTION_NAME = "scaffold_knowledge";

    /* ==================== Milvus 向量库常量 ==================== */
    /**
     * Milvus 向量维度（text-embedding-v1 输出维度）
     */
    public static final int MILVUS_VECTOR_DIMENSION = 1536;

    /**
     * Milvus 向量字段名
     */
    public static final String MILVUS_VECTOR_FIELD = "vector";

    /**
     * Milvus 内容字段名
     */
    public static final String MILVUS_CONTENT_FIELD = "content";

    /**
     * Milvus 文档ID字段名
     */
    public static final String MILVUS_DOCUMENT_ID_FIELD = "document_id";

    /**
     * Milvus 分块索引字段名
     */
    public static final String MILVUS_CHUNK_INDEX_FIELD = "chunk_index";

    /**
     * Milvus 元数据字段名
     */
    public static final String MILVUS_METADATA_FIELD = "metadata";

    /**
     * Milvus 文档ID布隆过滤器名
     * <p>
     * 完整 Redis key：bloom:filter:document_ids
     */
    public static final String BLOOM_FILTER_DOCUMENT_IDS = "document_ids";

    private AiCacheConstants() {
    }
}
