package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;
import com.zmbdp.chat.api.feedback.feign.FeedbackApi;
import com.zmbdp.chat.service.service.IFeedbackService;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.idempotent.annotation.Idempotent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回答反馈 Controller（chat-service 端）
 * <p>
 * 实现 {@link FeedbackApi} Feign 接口，提供 C 端用户对 AI 回答的反馈能力
 * <p>
 * <b>@Idempotent 注解说明</b>：IdempotentAspect 切面只匹配目标类自己声明的方法上的注解，
 * 不会从接口继承。因此 {@link #submitFeedback} 方法上必须显式添加 {@code @Idempotent} 注解，
 * 用于防止前端重复点击（同一 idempotent-token 在过期时间内只允许一次请求）。
 * <p>
 * <b>覆盖语义</b>：同一用户对同一对话重复提交反馈时，
 * Service 层采用"先删后插"覆盖上一次反馈（如从点赞切换为点踩）。前端切换反馈类型时
 * 应生成新的 idempotent-token，以避免被 @Idempotent 拦截。
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
    @Idempotent(message = "请勿重复提交反馈")
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
}