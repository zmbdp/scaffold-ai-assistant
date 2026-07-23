package com.zmbdp.chat.api.feedback.feign;

import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackAdminVO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 回答反馈远程调用 Api
 * <p>
 * 提供 C 端用户对 AI 回答的反馈能力（点赞 / 点踩、查询、撤销），
 * 以及 B 端管理员的反馈明细分页查询。
 * <p>
 * <b>用户信息来源</b>：userId、userFrom 从 Gateway AuthFilter 设置的 Header 中获取
 * （USER_ID、USER_FROM），由 portal-service 透传给本接口。
 * <p>
 * <b>幂等校验</b>：由 portal-service 的 FeedbackPortalController.submitFeedback() 方法上的
 * {@code @Idempotent} 注解在入口层完成，本 Feign 接口和 chat-service 实现层不再重复校验
 * （内部 Feign 调用不需要 Idempotent-Token 透传，避免 header 丢失问题）。
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
     *
     * @param request  反馈请求
     * @param userId   用户ID（从 Header 获取）
     * @param userFrom 用户来源（从 Header 获取，sys/app）
     * @return 反馈 VO
     */
    @PostMapping
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

    /**
     * B 端反馈明细分页查询
     * <p>
     * 用于 B 端管理员查看用户反馈明细，单条记录同时返回反馈信息 + 对话问答摘要
     * （question / answerSummary / model / sources），管理员可一眼看到
     * "用户问了什么、AI 答了什么、为什么点踩"。
     * <p>
     * <b>数据来源</b>：sys_ai_feedback 表 LEFT JOIN sys_ai_conversation 表
     * （ON f.conversation_id = c.id），一对一关系。
     * <p>
     * <b>answer 截断</b>：sys_ai_conversation.answer 为 LONGTEXT，列表页 SQL 层用
     * LEFT(answer, 200) 截断 200 字避免响应过大；完整回答通过会话详情接口查看。
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
    @GetMapping("/list")
    Result<BasePageVO<FeedbackAdminVO>> listFeedbacks(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestParam(value = "feedbackType", required = false) String feedbackType,
            @RequestParam(value = "dislikeReason", required = false) String dislikeReason,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "startDate", required = false) Long startDate,
            @RequestParam(value = "endDate", required = false) Long endDate);
}
