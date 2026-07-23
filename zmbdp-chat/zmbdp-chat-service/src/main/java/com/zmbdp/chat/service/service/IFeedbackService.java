package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackAdminVO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;
import com.zmbdp.common.domain.domain.vo.BasePageVO;

/**
 * 回答反馈服务
 * <p>
 * 提供反馈提交、查询、删除以及满意度统计（基于 sys_ai_feedback 表），
 * 以及 B 端反馈明细分页查询（联表 sys_ai_conversation）。
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

    /*=============================================    B端调用    =============================================*/

    /**
     * B 端反馈明细分页查询
     * <p>
     * 用于 B 端管理员查看用户反馈明细，单条记录同时返回反馈信息 + 对话问答摘要
     * （question / answerSummary / model / sources）。
     * <p>
     * <b>数据来源</b>：sys_ai_feedback 表 LEFT JOIN sys_ai_conversation 表。
     * <b>answer 截断</b>：SQL 层用 LEFT(answer, 200) 截断 200 字避免响应过大。
     *
     * @param pageNo        页码（兜底默认 1）
     * @param pageSize      每页数量（兜底默认 20）
     * @param feedbackType  反馈类型过滤（LIKE/DISLIKE，可空）
     * @param dislikeReason 点踩原因过滤（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，可空）
     * @param userId        用户ID过滤（可空）
     * @param startDate     起始日期（格式：20260712，可空）
     * @param endDate       结束日期（格式：20260712，可空）
     * @return 反馈明细分页结果
     */
    BasePageVO<FeedbackAdminVO> listFeedbacks(Integer pageNo, Integer pageSize,
                                               String feedbackType, String dislikeReason,
                                               Long userId, Long startDate, Long endDate);
}
