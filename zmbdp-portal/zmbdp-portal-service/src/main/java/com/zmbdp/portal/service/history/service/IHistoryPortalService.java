package com.zmbdp.portal.service.history.service;

import com.zmbdp.chat.api.history.domain.vo.HistoryDetailVO;
import com.zmbdp.chat.api.history.domain.vo.HistoryVO;
import com.zmbdp.common.domain.domain.vo.BasePageVO;

/**
 * C端对话历史业务编排服务
 * <p>
 * portal-service 通过 Feign 调用 chat-service 的 {@code HistoryApi}，
 * 为 C端用户提供对话历史列表、会话详情、删除会话等能力。
 * <p>
 * <b>userId 来源</b>：由 portal-service 从 JWT 解析后传递给 chat-service，
 * C端用户只能查看/删除自己的对话历史（chat-service 根据 userId 过滤）。
 * <p>
 *
 * @author 稚名不带撇
 */
public interface IHistoryPortalService {

    /**
     * 获取对话历史列表（分页）
     * <p>
     * 按 sessionId 聚合，每个会话返回最后一条消息摘要。
     * 内部从 JWT 提取 userId，传给 chat-service 进行过滤。
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 20
     * @return 历史列表分页结果
     */
    BasePageVO<HistoryVO> getHistoryList(Integer pageNo, Integer pageSize);

    /**
     * 获取会话详情
     * <p>
     * 返回该会话下的所有消息（按时间正序），含用户提问和 AI 回答。
     *
     * @param sessionId 会话ID
     * @return 会话详情 VO
     */
    HistoryDetailVO getSessionHistory(String sessionId);

    /**
     * 删除会话
     * <p>
     * 同时删除 MySQL 记录（软删除）+ Redis 对话记忆缓存。
     *
     * @param sessionId 会话ID
     */
    void deleteSession(String sessionId);
}
