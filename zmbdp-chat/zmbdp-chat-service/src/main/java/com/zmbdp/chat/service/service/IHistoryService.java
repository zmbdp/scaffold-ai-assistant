package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.history.domain.vo.HistoryDetailVO;
import com.zmbdp.chat.api.history.domain.vo.HistoryVO;
import com.zmbdp.chat.service.domain.entity.SysAiConversation;
import com.zmbdp.common.domain.domain.vo.BasePageVO;

/**
 * 对话历史服务
 * <p>
 * 对话历史管理，提供历史列表查询、会话详情查询、会话删除；
 * 持久化对话记录到 MySQL（sys_ai_conversation 表）。
 * <p>
 * <b>核心依赖</b>：SysAiConversationMapper、{@link IChatMemoryService}、SnowflakeIdService。
 * <p>
 * <b>二级存储</b>：
 * <ul>
 *     <li>Redis List：实时对话记忆（{@link IChatMemoryService} 维护）</li>
 *     <li>MySQL sys_ai_conversation 表：对话记录持久化（is_deleted 软删除）</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
public interface IHistoryService {

    /*=============================================    前端调用    =============================================*/

    /**
     * 按 user_id 分页查询会话列表
     *
     * @param userId   用户ID
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @return 历史会话列表分页结果
     */
    BasePageVO<HistoryVO> getHistoryList(Long userId, Integer pageNo, Integer pageSize);

    /**
     * 获取会话详情
     * <p>
     * 委托给 {@link IChatMemoryService#getHistory} 获取会话完整对话记录。
     * <p>
     * <b>归属校验</b>：调用前需通过 {@link #checkSessionOwnership} 校验 sessionId 归属当前用户，
     * 校验不通过时本方法直接抛 {@link com.zmbdp.common.domain.exception.ServiceException}。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（用于归属校验）
     * @return 会话详情
     */
    HistoryDetailVO getSessionHistory(String sessionId, Long userId);

    /**
     * 删除会话
     * <p>
     * 删除 Redis 对话记忆缓存 + MySQL 软删除（is_deleted=1）。
     * <p>
     * <b>归属校验</b>：调用前需通过 {@link #checkSessionOwnership} 校验 sessionId 归属当前用户，
     * 校验不通过时本方法直接抛 {@link com.zmbdp.common.domain.exception.ServiceException}。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（用于归属校验）
     */
    void deleteSession(String sessionId, Long userId);

    /**
     * 校验会话归属
     * <p>
     * 判断 sessionId 是否属于指定用户。
     * <p>
     * <b>判定规则</b>：
     * <ul>
     *     <li>sessionId 在数据库中不存在（新会话）→ 返回 true（允许使用）</li>
     *     <li>sessionId 存在且 user_id 匹配 → 返回 true</li>
     *     <li>sessionId 存在但 user_id 不匹配 → 返回 false（拒绝）</li>
     * </ul>
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return true 表示属于当前用户或为新会话；false 表示属于其他用户
     */
    boolean checkSessionOwnership(Long userId, String sessionId);

    /*=============================================    内部调用    =============================================*/

    /**
     * 持久化对话记录到 MySQL
     * <p>
     * 将对话记录写入 sys_ai_conversation 表。
     *
     * @param conversation 对话记录实体
     */
    void saveConversation(SysAiConversation conversation);
}
