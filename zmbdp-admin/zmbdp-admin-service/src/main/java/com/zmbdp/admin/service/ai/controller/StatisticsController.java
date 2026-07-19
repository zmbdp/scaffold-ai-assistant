package com.zmbdp.admin.service.ai.controller;

import com.zmbdp.chat.api.operationlog.domain.vo.OperationLogVO;
import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.ConversationStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.FeedbackStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.ToolStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.admin.service.ai.service.IAiAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B端统计分析 Controller
 * <p>
 * 作为B端统计分析的统一入口，通过 Feign 调用 chat-service 的 {@code StatisticsApi}。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code GET /statistics/conversations}：获取对话统计</li>
 *     <li>{@code GET /statistics/questions}：获取热门问题</li>
 *     <li>{@code GET /statistics/users}：获取用户统计</li>
 *     <li>{@code GET /statistics/tools}：获取工具使用统计</li>
 *     <li>{@code GET /statistics/ai-metrics}：获取 AI 调用指标</li>
 *     <li>{@code GET /statistics/ai-metrics/{operationId}}：查看单次 AI 调用详情</li>
 *     <li>{@code GET /statistics/feedback}：获取回答满意度统计</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /admin/statistics/conversations} → gateway StripPrefix=1 → 本 Controller {@code /statistics/conversations}
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/statistics")
public class StatisticsController {

    /**
     * B端 AI 管理业务编排服务
     */
    @Autowired
    private IAiAdminService aiAdminService;

    /**
     * 获取对话统计
     * <p>
     * 含总量、今日量、平均响应时间、活跃用户数、趋势数据。
     *
     * @return 对话统计 VO
     */
    @GetMapping("/conversations")
    public Result<ConversationStatisticsVO> getConversationStatistics() {
        return Result.success(aiAdminService.getConversationStatistics());
    }

    /**
     * 获取热门问题
     * <p>
     * 从 sys_ai_conversation 表按 question 分组统计，按被问次数降序排序。
     *
     * @param limit 返回数量，默认 10
     * @return 热门问题 VO 列表
     */
    @GetMapping("/questions")
    public Result<List<HotQuestionVO>> getHotQuestions(
            @RequestParam(value = "limit", defaultValue = "10") Integer limit) {
        return Result.success(aiAdminService.getHotQuestions(limit));
    }

    /**
     * 获取用户统计
     *
     * @param days 统计天数，默认 7
     * @return 用户统计 VO
     */
    @GetMapping("/users")
    public Result<UserStatisticsVO> getUserStatistics(
            @RequestParam(value = "days", defaultValue = "7") Integer days) {
        return Result.success(aiAdminService.getUserStatistics(days));
    }

    /**
     * 获取工具使用统计
     * <p>
     * 从 sys_ai_operation_log 的 tool_calls 字段解析工具调用记录，按工具名称分组统计。
     *
     * @return 工具使用统计 VO
     */
    @GetMapping("/tools")
    public Result<ToolStatisticsVO> getToolStatistics() {
        return Result.success(aiAdminService.getToolStatistics());
    }

    /**
     * 获取 AI 调用指标（聚合）
     * <p>
     * 含总调用数、成功率、Token 消耗、延迟、Top 模型。
     *
     * @return AI 调用指标 VO
     */
    @GetMapping("/ai-metrics")
    public Result<AiMetricsVO> getAiMetrics() {
        return Result.success(aiAdminService.getAiMetrics());
    }

    /**
     * 查看单次 AI 调用详情
     * <p>
     * 返回完整链路（Prompt、LLM 响应、工具调用链路、Token 消耗）。
     *
     * @param operationId AI 操作日志ID（sys_ai_operation_log.id）
     * @return 操作日志 VO（含完整字段）
     */
    @GetMapping("/ai-metrics/{operationId}")
    public Result<OperationLogVO> getOperationDetail(@PathVariable("operationId") Long operationId) {
        return Result.success(aiAdminService.getOperationDetail(operationId));
    }

    /**
     * 获取回答满意度统计
     * <p>
     * 含点赞率、点踩率、反馈率、点踩原因分布。
     *
     * @param startDate 起始日期（格式：20260712，可选，不传默认最近 7 天）
     * @param endDate   结束日期（格式：20260712，可选）
     * @return 回答满意度统计 VO
     */
    @GetMapping("/feedback")
    public Result<FeedbackStatisticsVO> getFeedbackStatistics(
            @RequestParam(value = "startDate", required = false) Long startDate,
            @RequestParam(value = "endDate", required = false) Long endDate) {
        return Result.success(aiAdminService.getFeedbackStatistics(startDate, endDate));
    }
}