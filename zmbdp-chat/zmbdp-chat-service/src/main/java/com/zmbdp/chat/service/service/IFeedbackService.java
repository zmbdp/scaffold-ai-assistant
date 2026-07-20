package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;

/**
 * 回答反馈服务
 * <p>
 * 提供反馈提交、查询、删除以及满意度统计（基于 sys_ai_feedback 表）。
 * <p>
 * <b>覆盖语义</b>：submitFeedback 采用"先删后插"实现覆盖，
 * 同一用户对同一对话重复提交反馈时覆盖上一次的反馈（如从点赞切换为点踩）。
 *
 * @author 稚名不带撇
 */
public interface IFeedbackService {

    /*=============================================    前端调用    =============================================*/

    /**
     * 提交回答反馈（点赞 / 点踩，覆盖语义）
     * <p>
     * 同一用户对同一对话重复提交反馈时覆盖上一次的反馈（先删后插）。
     * feedbackType=DISLIKE 时，dislikeReason 必填。
     *
     * @param dto      反馈请求
     * @param userId   用户ID（从 Header 获取）
     * @param userFrom 用户来源（从 Header 获取，sys/app）
     * @return 反馈 VO
     */
    FeedbackVO submitFeedback(FeedbackReqDTO dto, Long userId, String userFrom);

    /**
     * 查询用户对某对话已提交的反馈
     * <p>
     * 用户未反馈时返回 null（非错误）。
     *
     * @param conversationId 对话记录ID
     * @param userId         用户ID
     * @return 反馈 VO（未反馈时为 null）
     */
    FeedbackVO getFeedback(Long conversationId, Long userId);

    /**
     * 撤销反馈（物理删除记录）
     *
     * @param conversationId 对话记录ID
     * @param userId         用户ID
     */
    void deleteFeedback(Long conversationId, Long userId);
}
