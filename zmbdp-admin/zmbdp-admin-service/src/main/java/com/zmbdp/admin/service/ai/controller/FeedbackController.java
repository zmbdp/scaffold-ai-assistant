package com.zmbdp.admin.service.ai.controller;

import com.zmbdp.chat.api.feedback.domain.vo.FeedbackAdminVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.admin.service.ai.service.IAiAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B端反馈管理 Controller
 * <p>
 * 作为B端反馈管理的统一入口，通过 Feign 调用 chat-service 的 {@code FeedbackApi}。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code GET /feedback/list}：B端反馈明细分页查询（含对话问答摘要）</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /admin/feedback/list} → gateway StripPrefix=1 → 本 Controller {@code /feedback/list}
 * <p>
 * <b>职责边界</b>：
 * <ul>
 *     <li>本接口为 B 端管理员查看用户反馈明细，单条记录同时返回反馈信息 + 对话问答摘要</li>
 *     <li>C 端用户反馈提交 / 查询 / 撤销由 portal-service 的 FeedbackPortalController 负责</li>
 *     <li>反馈聚合统计（点赞率 / 点踩率 / 原因分布）由 StatisticsController.getFeedbackStatistics() 负责</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    /**
     * B端 AI 管理业务编排服务
     */
    @Autowired
    private IAiAdminService aiAdminService;

    /**
     * B 端反馈明细分页查询
     * <p>
     * 单条记录同时返回反馈信息 + 对话问答摘要（question / answerSummary / model / sources），
     * 管理员可一眼看到"用户问了什么、AI 答了什么、为什么点踩"。
     * <p>
     * <b>answer 截断</b>：sys_ai_conversation.answer 为 LONGTEXT，列表页 SQL 层用 LEFT(answer, 200)
     * 截断 200 字避免响应过大；完整回答通过会话详情接口 GET /portal/history/{sessionId} 查看。
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
    public Result<BasePageVO<FeedbackAdminVO>> listFeedbacks(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestParam(value = "feedbackType", required = false) String feedbackType,
            @RequestParam(value = "dislikeReason", required = false) String dislikeReason,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "startDate", required = false) Long startDate,
            @RequestParam(value = "endDate", required = false) Long endDate) {
        return Result.success(aiAdminService.listFeedbacks(
                pageNo, pageSize, feedbackType, dislikeReason, userId, startDate, endDate));
    }
}
