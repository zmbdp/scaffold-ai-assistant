package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackAdminVO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;
import com.zmbdp.chat.api.feedback.feign.FeedbackApi;
import com.zmbdp.chat.service.service.IFeedbackService;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回答反馈 Controller（chat-service 端）
 * <p>
 * 实现 {@link FeedbackApi} Feign 接口，提供 C 端用户对 AI 回答的反馈能力，
 * 以及 B 端管理员的反馈明细分页查询。
 * <p>
 * <b>幂等校验</b>：由 portal-service 的 FeedbackPortalController.submitFeedback() 在入口层完成，
 * 本内部服务不再重复校验（避免 Feign 调用需要透传 Idempotent-Token header）。
 * <p>
 * <b>覆盖语义</b>：同一用户对同一对话重复提交反馈时，
 * Service 层采用"先删后插"覆盖上一次反馈（如从点赞切换为点踩）。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/feedback")
public class FeedbackController implements FeedbackApi {

    /**
     * 回答反馈服务
     */
    @Autowired
    private IFeedbackService feedbackService;

    /**
     * 提交回答反馈（点赞 / 点踩，覆盖语义）
     * <p>
     * 业务规则：同一用户对同一对话重复提交反馈时覆盖上一次的反馈（先删后插）。
     * feedbackType=DISLIKE 时，dislikeReason 必填（由 Service 层校验）。
     *
     * @param request  反馈请求
     * @param userId   用户ID（从 Header 获取）
     * @param userFrom 用户来源（从 Header 获取，sys/app）
     * @return 反馈 VO
     */
    @Override
    public Result<FeedbackVO> submitFeedback(@Validated FeedbackReqDTO request, Long userId, String userFrom) {
        log.info("提交回答反馈：conversationId = {}, userId = {}, feedbackType = {}",
                request.getConversationId(), userId, request.getFeedbackType());
        FeedbackVO vo = feedbackService.submitFeedback(request, userId, userFrom);
        return Result.success(vo);
    }

    /**
     * 查询用户对某对话已提交的反馈
     * <p>
     * 用户未反馈时返回 null（非错误）。
     *
     * @param conversationId 对话记录ID
     * @param userId         用户ID（从 Header 获取）
     * @return 反馈 VO（未反馈时为 null）
     */
    @Override
    public Result<FeedbackVO> getFeedback(Long conversationId, Long userId) {
        log.info("查询用户反馈：conversationId = {}, userId = {}", conversationId, userId);
        FeedbackVO vo = feedbackService.getFeedback(conversationId, userId);
        return Result.success(vo);
    }

    /**
     * 撤销反馈（物理删除记录）
     *
     * @param conversationId 对话记录ID
     * @param userId         用户ID（从 Header 获取）
     * @return 操作结果
     */
    @Override
    public Result<Void> deleteFeedback(Long conversationId, Long userId) {
        log.info("撤销反馈：conversationId = {}, userId = {}", conversationId, userId);
        feedbackService.deleteFeedback(conversationId, userId);
        return Result.success();
    }

    /**
     * B 端反馈明细分页查询
     * <p>
     * 用于 B 端管理员查看用户反馈明细，单条记录同时返回反馈信息 + 对话问答摘要
     * （question / answerSummary / model / sources）。
     *
     * @param pageNo        页码，默认 1
     * @param pageSize      每页数量，默认 20
     * @param feedbackType  反馈类型过滤（LIKE/DISLIKE，可选）
     * @param dislikeReason 点踩原因过滤（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，可选）
     * @param userId        用户ID过滤（可选）
     * @param startDate     起始日期（格式：20260712，可选）
     * @param endDate       结束日期（格式：20260712，可选）
     * @return 反馈明细分页结果
     */
    @Override
    public Result<BasePageVO<FeedbackAdminVO>> listFeedbacks(Integer pageNo, Integer pageSize,
                                                              String feedbackType, String dislikeReason,
                                                              Long userId, Long startDate, Long endDate) {
        log.info("B端反馈明细分页查询：pageNo = {}, pageSize = {}, feedbackType = {}, dislikeReason = {}, userId = {}, startDate = {}, endDate = {}",
                pageNo, pageSize, feedbackType, dislikeReason, userId, startDate, endDate);
        BasePageVO<FeedbackAdminVO> vo = feedbackService.listFeedbacks(
                pageNo, pageSize, feedbackType, dislikeReason, userId, startDate, endDate);
        return Result.success(vo);
    }
}