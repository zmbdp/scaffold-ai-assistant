package com.zmbdp.chat.service.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.zmbdp.chat.service.constant.AiCacheConstants;
import com.zmbdp.chat.service.domain.entity.SysAiConversation;
import com.zmbdp.chat.service.mapper.SysAiConversationMapper;
import com.zmbdp.chat.service.mq.CacheInvalidateProducer;
import com.zmbdp.chat.service.service.IChatMemoryService;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.redis.service.RedisService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话记忆服务实现类
 * <p>
 * 基于 Redis List 存储对话记忆，L1 Caffeine 缓存对象副本。
 * 实现类同时实现 Spring AI 的 ChatMemory 接口（add/get/clear 委托给本类自定义方法）。
 * <p>
 * <b>二级缓存设计</b>：
 * <ul>
 *     <li>数据存储：Redis List（key=ai:chat:memory:{sessionId}，TTL=7天，每会话最多100条）</li>
 *     <li>L1 Caffeine：key=CHAT_MEMORY:ai:chat:memory:{sessionId}，TTL=5分钟，最大1000条</li>
 *     <li>失效策略：addMessage/clearHistory 通过 MQ 广播删 L1 Caffeine（invalidateL1Only）</li>
 * </ul>
 * <p>
 * <b>序列化方案</b>：Message 接口无法直接反序列化，采用 Map 中间格式：
 * 存储时 Message → Map（含 messageType + content）→ JSON；读取时 JSON → Map → Message（按 messageType 还原）。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class ChatMemoryServiceImpl implements IChatMemoryService, ChatMemory {

    /**
     * Redis 操作服务
     */
    @Autowired
    private RedisService redisService;

    /**
     * L1 Caffeine 缓存实例
     */
    @Autowired
    private Cache<String, Object> caffeineCache;

    /**
     * 对话记录 Mapper（DB 降级时直接查询，不经过 IHistoryService 避免循环依赖）
     */
    @Autowired
    private SysAiConversationMapper sysAiConversationMapper;

    /**
     * 缓存失效广播生产者
     */
    @Autowired
    private CacheInvalidateProducer cacheInvalidateProducer;

    /**
     * 消息类型字段名（Map 中间格式的 key）
     */
    private static final String FIELD_MESSAGE_TYPE = "messageType";

    /**
     * 消息内容字段名
     */
    private static final String FIELD_CONTENT = "content";

    /*=============================================    内部调用    =============================================*/

    /**
     * 获取会话的对话历史
     * <p>
     * 查询顺序：L1 Caffeine → Redis List → DB（SysAiConversationMapper）。
     *
     * @param sessionId 会话ID
     * @return 对话历史列表（按时间正序）
     */
    @Override
    public List<Message> getHistory(String sessionId) {
        return doGetHistory(sessionId);
    }

    /*=============================================    Spring AI ChatMemory 接口实现    =============================================*/

    /**
     * Spring AI ChatMemory.add 实现
     * <p>
     * 委托给 {@link #addMessages} 写入 Redis List 并失效 L1 Caffeine。
     *
     * @param conversationId 会话ID（Spring AI 术语，等同本服务的 sessionId）
     * @param messages       消息列表
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        addMessages(conversationId, messages);
    }

    /**
     * Spring AI ChatMemory.get 实现
     * <p>
     * 委托给 {@link #doGetHistory} 返回会话完整对话历史（按时间正序）。
     * <p>
     * <b>说明</b>：Spring AI 1.0.0 GA 的 {@link ChatMemory#get(String)} 接口签名只接收 conversationId，
     * 不含 lastN 参数；轮数由 chat-service 内部根据 {@code scaffold.rag.memory-rounds} 截取。
     *
     * @param conversationId 会话ID（Spring AI 术语，等同本服务的 sessionId）
     * @return 对话历史列表（按时间正序）
     */
    @Override
    public List<Message> get(String conversationId) {
        return doGetHistory(conversationId);
    }

    /**
     * Spring AI ChatMemory.clear 实现
     * <p>
     * 委托给 {@link #clearHistory} 删除 Redis List 并失效 L1 Caffeine。
     *
     * @param conversationId 会话ID（Spring AI 术语，等同本服务的 sessionId）
     */
    @Override
    public void clear(String conversationId) {
        clearHistory(conversationId);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 获取会话的对话历史（实际实现）
     * <p>
     * 查询顺序：L1 Caffeine → Redis List → DB（SysAiConversationMapper）。
     *
     * @param sessionId 会话ID
     * @return 对话历史列表（按时间正序）
     */
    private List<Message> doGetHistory(String sessionId) {
        // 构建 Redis 数据存储 key
        String redisKey = AiCacheConstants.CHAT_MEMORY_REDIS_PREFIX + sessionId;
        // 构建 L1 Caffeine key
        String l1Key = AiCacheConstants.CHAT_MEMORY + ":" + redisKey;

        // 1. 查 L1 Caffeine
        Object cached = caffeineCache.getIfPresent(l1Key);
        if (cached instanceof List) {
            @SuppressWarnings("unchecked")
            List<Message> cachedList = (List<Message>) cached;
            log.debug("对话记忆命中 L1 Caffeine：sessionId = {}, size = {}", sessionId, cachedList.size());
            return cachedList;
        }

        // 2. 查 Redis List
        List<String> jsonList = redisService.getCacheListByRange(redisKey, 0, -1, String.class);
        if (jsonList != null && !jsonList.isEmpty()) {
            // Redis List 是 lPush 头部插入，需反转恢复时间正序
            List<Message> messages = new ArrayList<>(jsonList.size());
            for (int i = jsonList.size() - 1; i >= 0; i--) {
                Message message = deserializeMessage(jsonList.get(i));
                if (message != null) {
                    messages.add(message);
                }
            }
            // 回填 L1 Caffeine
            caffeineCache.put(l1Key, messages);
            log.debug("对话记忆命中 Redis List：sessionId = {}, size = {}", sessionId, messages.size());
            return messages;
        }

        // 3. DB 降级：直接查 SysAiConversationMapper（不经过 IHistoryService 避免循环依赖）
        List<Message> dbMessages = loadFromDatabase(sessionId);
        if (!dbMessages.isEmpty()) {
            // 回填 Redis List（按时间正序逐条 lPush）
            for (Message message : dbMessages) {
                String json = serializeMessage(message);
                redisService.leftPushForList(redisKey, json);
            }
            // 刷新 TTL
            redisService.expire(redisKey, AiCacheConstants.CHAT_MEMORY_REDIS_TTL_SECONDS);
            // 回填 L1 Caffeine
            caffeineCache.put(l1Key, dbMessages);
            log.debug("对话记忆 DB 降级回填：sessionId = {}, size = {}", sessionId, dbMessages.size());
        }
        return dbMessages;
    }

    /**
     * 添加一条消息到对话历史
     * <p>
     * 序列化消息 → lPush 到 Redis List 头部 → 检查长度裁剪到100条 → 刷新TTL(7天)
     * → MQ 广播删 L1 Caffeine（invalidateL1Only，不删 Redis List）。
     *
     * @param sessionId 会话ID
     * @param message   消息对象（含 role、content）
     */
    @Override
    public void addMessage(String sessionId, Message message) {
        // 构建 Redis 数据存储 key
        String redisKey = AiCacheConstants.CHAT_MEMORY_REDIS_PREFIX + sessionId;

        // 序列化消息为 JSON
        String json = serializeMessage(message);

        // lPush 到列表头部
        redisService.leftPushForList(redisKey, json);

        // 检查列表长度，超过 100 条则裁剪
        long size = redisService.getCacheListSize(redisKey);
        if (size > AiCacheConstants.CHAT_MEMORY_MAX_MESSAGES_PER_SESSION) {
            redisService.retainListRange(redisKey, 0, AiCacheConstants.CHAT_MEMORY_MAX_MESSAGES_PER_SESSION - 1);
        }

        // 刷新 TTL（7天）
        redisService.expire(redisKey, AiCacheConstants.CHAT_MEMORY_REDIS_TTL_SECONDS);

        // MQ 广播删 L1 Caffeine（不删 Redis List，因为 lPush 已更新为最新数据）
        cacheInvalidateProducer.invalidateL1Only(AiCacheConstants.CHAT_MEMORY, redisKey);
    }

    /**
     * 批量添加消息到对话历史
     * <p>
     * 内部循环调用 {@link #addMessage}，每条消息单独 lPush（保持时间正序）。
     *
     * @param sessionId 会话ID
     * @param messages  消息列表
     */
    @Override
    public void addMessages(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // 按时间正序逐条 lPush（不要先 reverse 再 lPush，会导致顺序错误）
        for (Message message : messages) {
            addMessage(sessionId, message);
        }
    }

    /**
     * 清除会话的对话历史
     * <p>
     * 删除 Redis List 数据存储 + MQ 广播删 L1 Caffeine。
     *
     * @param sessionId 会话ID
     */
    @Override
    public void clearHistory(String sessionId) {
        // 构建 Redis 数据存储 key
        String redisKey = AiCacheConstants.CHAT_MEMORY_REDIS_PREFIX + sessionId;

        // 删除 Redis List 数据存储
        redisService.deleteObject(redisKey);

        // MQ 广播删 L1 Caffeine
        cacheInvalidateProducer.invalidateL1Only(AiCacheConstants.CHAT_MEMORY, redisKey);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 序列化 Message 为 JSON（Map 中间格式）
     * <p>
     * 将 Message 转换为 Map（含 messageType + content），再序列化为 JSON。
     * 避免 Message 接口无法直接反序列化的问题。
     *
     * @param message 消息对象
     * @return JSON 字符串
     */
    private String serializeMessage(Message message) {
        Map<String, Object> map = new HashMap<>(4);
        // 根据运行时类型确定 messageType
        if (message instanceof UserMessage) {
            map.put(FIELD_MESSAGE_TYPE, "USER");
        } else if (message instanceof AssistantMessage) {
            map.put(FIELD_MESSAGE_TYPE, "ASSISTANT");
        } else if (message instanceof SystemMessage) {
            map.put(FIELD_MESSAGE_TYPE, "SYSTEM");
        } else {
            map.put(FIELD_MESSAGE_TYPE, "USER");
        }
        map.put(FIELD_CONTENT, message.getText());
        return JsonUtil.classToJson(map);
    }

    /**
     * 反序列化 JSON 为 Message
     * <p>
     * 从 JSON 解析 Map，根据 messageType 还原为对应的 Message 实现类。
     *
     * @param json JSON 字符串
     * @return Message 对象
     */
    private Message deserializeMessage(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = JsonUtil.jsonToClass(json, new TypeReference<Map<String, Object>>() {
            });
            if (map == null) {
                return null;
            }
            String messageType = (String) map.get(FIELD_MESSAGE_TYPE);
            String content = (String) map.get(FIELD_CONTENT);
            if (content == null) {
                content = "";
            }
            // 跳过空 content 的 AssistantMessage：失败的对话 answer 为空，加载会污染下一轮上下文
            // （DashScope 看到空助手回复后跟新问题，会直接返回空响应）
            if ("ASSISTANT".equals(messageType) && content.isEmpty()) {
                log.warn("跳过空 content 的 AssistantMessage（可能是之前失败的对话记录）");
                return null;
            }
            // 根据 messageType 还原为对应的 Message 实现类
            if ("ASSISTANT".equals(messageType)) {
                return new AssistantMessage(content);
            } else if ("SYSTEM".equals(messageType)) {
                return new SystemMessage(content);
            } else {
                return new UserMessage(content);
            }
        } catch (Exception e) {
            log.error("反序列化消息失败：{}", json, e);
            return null;
        }
    }

    /**
     * 从数据库加载对话历史（DB 降级）
     * <p>
     * 直接查 SysAiConversationMapper（不经过 IHistoryService 避免循环依赖）。
     *
     * @param sessionId 会话ID
     * @return 对话历史列表（按时间正序）
     */
    private List<Message> loadFromDatabase(String sessionId) {
        try {
            LambdaQueryWrapper<SysAiConversation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysAiConversation::getSessionId, sessionId)
                    .eq(SysAiConversation::getIsDeleted, 0)
                    .orderByAsc(SysAiConversation::getCreateTime);
            List<SysAiConversation> conversations = sysAiConversationMapper.selectList(wrapper);
            if (conversations == null || conversations.isEmpty()) {
                return new ArrayList<>();
            }
            // 将对话记录转换为消息列表（一问一答转为两条消息：UserMessage + AssistantMessage）
            List<Message> messages = new ArrayList<>(conversations.size() * 2);
            for (SysAiConversation conversation : conversations) {
                if (conversation.getQuestion() != null) {
                    messages.add(new UserMessage(conversation.getQuestion()));
                }
                // 跳过空 answer：失败的对话 answer 为空字符串，加载为 AssistantMessage("") 会污染下一轮上下文
                // （DashScope 看到空助手回复后跟新问题，会直接返回空响应）
                String answer = conversation.getAnswer();
                if (answer != null && !answer.isEmpty()) {
                    messages.add(new AssistantMessage(answer));
                }
            }
            return messages;
        } catch (Exception e) {
            log.error("DB 降级查询对话历史失败：sessionId = {}", sessionId, e);
            return new ArrayList<>();
        }
    }
}