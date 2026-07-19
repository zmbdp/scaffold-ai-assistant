package com.zmbdp.chat.service.service;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 对话记忆服务
 * <p>
 * 基于 Redis List 存储对话记忆；实现类 {@code ChatMemoryServiceImpl} 同时实现 Spring AI 的
 * {@code ChatMemory} 接口（覆盖 add/get/clear 方法，内部委托给本接口的自定义方法），
 * 供 Spring AI ChatClient 自动注入使用。
 * <p>
 * <b>二级缓存设计</b>：
 * <ul>
 *     <li>数据存储：Redis List（key={@code ai:chat:memory:{sessionId}}，TTL=7天，每会话最多100条）</li>
 *     <li>L1 Caffeine：key={@code CHAT_MEMORY:ai:chat:memory:{sessionId}}，TTL=5分钟，最大1000条</li>
 *     <li>失效策略：addMessage/clearHistory 通过 MQ 广播删 L1 Caffeine（invalidateL1Only），
 *         不删 Redis List（数据存储，addMessage 通过 lPush 已更新为最新）</li>
 * </ul>
 * <p>
 * <b>DB 降级</b>：Redis 不可用时降级查 MySQL sys_ai_conversation 表
 * （直接查 SysAiConversationMapper，不经过 IHistoryService 避免循环依赖）。
 *
 * @author 稚名不带撇
 */
public interface IChatMemoryService {

    /*=============================================    内部调用    =============================================*/

    /**
     * 获取会话的对话历史
     * <p>
     * 查询顺序：L1 Caffeine → Redis List → DB（SysAiConversationMapper）。
     * 返回按时间正序排列的消息列表。
     *
     * @param sessionId 会话ID
     * @return 对话历史列表（按时间正序）
     */
    List<Message> getHistory(String sessionId);

    /**
     * 添加一条消息到对话历史
     * <p>
     * 序列化消息 → lPush 到 Redis List 头部 → 检查长度裁剪到100条 → 刷新TTL(7天)
     * → MQ 广播删 L1 Caffeine（invalidateL1Only，不删 Redis List）。
     *
     * @param sessionId 会话ID
     * @param message   消息对象（含 role、content、timestamp）
     */
    void addMessage(String sessionId, Message message);

    /**
     * 批量添加消息到对话历史
     * <p>
     * 内部循环调用 {@link #addMessage}，每条消息单独 lPush（保持时间正序）。
     *
     * @param sessionId 会话ID
     * @param messages  消息列表
     */
    void addMessages(String sessionId, List<Message> messages);

    /**
     * 清除会话的对话历史
     * <p>
     * 删除 Redis List 数据存储 + MQ 广播删 L1 Caffeine。
     *
     * @param sessionId 会话ID
     */
    void clearHistory(String sessionId);
}
