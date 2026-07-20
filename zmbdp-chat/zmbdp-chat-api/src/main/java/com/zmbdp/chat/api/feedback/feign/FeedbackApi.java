package com.zmbdp.chat.api.feedback.feign;

import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.idempotent.annotation.Idempotent;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 回答反馈远程调用 Api
 * <p>
 * 提供 C 端用户对 AI 回答的反馈能力（点赞 / 点踩、查询、撤销）。
 * <p>
 * <b>用户信息来源</b>：userId、userFrom 从 Gateway AuthFilter 设置的 Header 中获取
 * （USER_ID、USER_FROM），由 portal-service 透传给本接口。
 * <p>
 * <b>@Idempotent 注解位置说明</b>：IdempotentAspect 切面的切点定义为
 * {@code @annotation(com.zmbdp.common.idempotent.annotation.Idempotent)}，
 * 只匹配目标类自己声明的方法上的注解，不会从接口继承。因此：
 * <ul>
 *     <li>本接口上的 {@code @Idempotent} 仅作为接口契约声明，不会触发 AOP 切面</li>
 *     <li>实际生效位置：必须在 chat-service 的 FeedbackController.submitFeedback() 方法上显式添加注解</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@FeignClient(contextId = "feedbackApi", name = "zmbdp-chat-service", path = "/feedback")
public interface FeedbackApi {

    /**
     * 提交回答反馈（点赞 / 点踩，覆盖语义）
     * <p>
     * 业务规则：同一用户对同一对话重复提交反馈时覆盖上一次的反馈
     * （chat-service 端"先删后插"）。
     * @Idempotent 用于防止前端重复点击，与覆盖语义不冲突（切换反馈类型时前端传不同 idempotent-token）。
     *
     * @param request  反馈请求
     * @param userId   用户ID（从 Header 获取）
     * @param userFrom 用户来源（从 Header 获取，sys/app）
     * @return 反馈 VO
     */
    @PostMapping
    @Idempotent(message = "请勿重复提交反馈")
    Result<FeedbackVO> submitFeedback(@RequestBody FeedbackReqDTO request,
                                       @RequestHeader("USER_ID") Long userId,
                                       @RequestHeader("USER_FROM") String userFrom);

    /**
     * 查询用户对某对话已提交的反馈
     * <p>
     * 用户未反馈时返回 {@code data: null}（非错误）。
     *
     * @param conversationId 对话记录ID
     * @param userId         用户ID（从 Header 获取）
     * @return 反馈 VO（未反馈时为 null）
     */
    @GetMapping("/{conversationId}")
    Result<FeedbackVO> getFeedback(@PathVariable("conversationId") Long conversationId,
                                    @RequestHeader("USER_ID") Long userId);

    /**
     * 撤销反馈（物理删除记录）
     * <p>
     * 物理删除反馈记录（不软删除），用户撤销后可重新提交反馈。
     *
     * @param conversationId 对话记录ID
     * @param userId         用户ID（从 Header 获取）
     * @return 操作结果
     */
    @DeleteMapping("/{conversationId}")
    Result<Void> deleteFeedback(@PathVariable("conversationId") Long conversationId,
                                 @RequestHeader("USER_ID") Long userId);
}
