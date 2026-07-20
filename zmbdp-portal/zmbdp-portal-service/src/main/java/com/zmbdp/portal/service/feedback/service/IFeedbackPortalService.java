package com.zmbdp.portal.service.feedback.service;

import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;

/**
 * C端回答反馈业务编排服务
 * <p>
 * portal-service 通过 Feign 调用 chat-service 的 {@code FeedbackApi}，
 * 为 C端用户提供回答反馈能力（点赞 / 点踩、查询、撤销）。
 * <p>
 * <b>userId / userFrom 来源</b>：由 portal-service 从 JWT 解析后传递给 chat-service，
 * 确保反馈归属于当前登录用户。
 * <p>
 * <b>覆盖语义</b>：submitFeedback 接口在 chat-service 端采用"先删后插"实现覆盖，
 * 同一用户对同一对话重复提交反馈时覆盖上一次的反馈，
 * portal-service 无需额外处理。
 * <p>
 *
 * @author 稚名不带撇
 */
public interface IFeedbackPortalService {

    /**
     * 提交回答反馈（点赞 / 点踩，覆盖语义）
     * <p>
     * 业务规则：同一用户对同一对话重复提交反馈时覆盖上一次的反馈
     * （chat-service 端"先删后插"）。
     *
     * @param request 反馈请求（含 conversationId、feedbackType、dislikeReason、comment）
     * @return 反馈 VO
     */
    FeedbackVO submitFeedback(FeedbackReqDTO request);

    /**
     * 查询用户对某对话已提交的反馈
     * <p>
     * 用户未反馈时返回 {@code null}（非错误）。
     *
     * @param conversationId 对话记录ID
     * @return 反馈 VO（未反馈时为 null）
     */
    FeedbackVO getFeedback(Long conversationId);

    /**
     * 撤销反馈（物理删除记录）
     * <p>
     * 物理删除反馈记录（不软删除），用户撤销后可重新提交反馈。
     *
     * @param conversationId 对话记录ID
     */
    void deleteFeedback(Long conversationId);
}
