package com.zmbdp.portal.service.feedback.controller;

import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.portal.service.feedback.service.IFeedbackPortalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C端回答反馈入口 Controller
 * <p>
 * 作为 C端回答反馈的统一入口，接收前端请求后委托给 {@link IFeedbackPortalService}
 * 通过 Feign 调用 chat-service 的 FeedbackApi。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code POST /feedback}：提交回答反馈（点赞 / 点踩）</li>
 *     <li>{@code GET /feedback/{conversationId}}：查询用户已提交的反馈</li>
 *     <li>{@code DELETE /feedback/{conversationId}}：撤销反馈</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /portal/feedback} → gateway StripPrefix=1 → 本 Controller {@code /feedback}
 * <p>
 * <b>认证</b>：需要 JWT Token，Service 内部从 JWT 提取 userId/userFrom 传给 chat-service 进行数据隔离。
 * <p>
 * <b>覆盖语义</b>：submitFeedback 接口在 chat-service 端采用"先删后插"实现覆盖，
 * 同一用户对同一对话重复提交反馈时覆盖上一次的反馈。
 * @Idempotent 注解实际生效位置在 chat-service 的 FeedbackController 实现类方法上，
 * 用于防止前端重复点击，与覆盖语义不冲突（切换反馈类型时前端传不同 idempotent-token）。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/feedback")
public class FeedbackPortalController {

    /**
     * C端回答反馈业务编排服务
     */
    @Autowired
    private IFeedbackPortalService feedbackPortalService;

    /**
     * 提交回答反馈（点赞 / 点踩，覆盖语义）
     * <p>
     * 业务规则：同一用户对同一对话重复提交反馈时覆盖上一次的反馈
     * （chat-service 端"先删后插"）。
     *
     * @param request 反馈请求（含 conversationId、feedbackType、dislikeReason、comment）
     * @return 反馈 VO
     */
    @PostMapping
    public Result<FeedbackVO> submitFeedback(@Validated @RequestBody FeedbackReqDTO request) {
        return Result.success(feedbackPortalService.submitFeedback(request));
    }

    /**
     * 查询用户对某对话已提交的反馈
     * <p>
     * 用户未反馈时返回 {@code data: null}（非错误）。
     *
     * @param conversationId 对话记录ID
     * @return 反馈 VO（未反馈时为 null）
     */
    @GetMapping("/{conversationId}")
    public Result<FeedbackVO> getFeedback(@PathVariable("conversationId") Long conversationId) {
        return Result.success(feedbackPortalService.getFeedback(conversationId));
    }

    /**
     * 撤销反馈（物理删除记录）
     * <p>
     * 物理删除反馈记录（不软删除），用户撤销后可重新提交反馈。
     *
     * @param conversationId 对话记录ID
     * @return 操作结果
     */
    @DeleteMapping("/{conversationId}")
    public Result<Void> deleteFeedback(@PathVariable("conversationId") Long conversationId) {
        feedbackPortalService.deleteFeedback(conversationId);
        return Result.success();
    }
}