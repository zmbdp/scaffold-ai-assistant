package com.zmbdp.chat.api.statistics.feign;

import com.zmbdp.chat.api.operationlog.domain.vo.OperationLogVO;
import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.ConversationStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.FeedbackStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.ToolStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.common.domain.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 统计服务远程调用 Api
 * <p>
 * 提供对话统计、热门问题、用户统计、工具使用统计、AI 调用指标、单次调用详情、回答满意度统计等能力。
 * 数据来源：sys_ai_conversation / sys_ai_operation_log / sys_ai_feedback 表的聚合查询。
 *
 * @author 稚名不带撇
 */
@FeignClient(contextId = "statisticsApi", name = "zmbdp-chat-service", path = "/statistics")
public interface StatisticsApi {

    /**
     * 获取对话统计
     * <p>
     * 含总量、今日量、平均响应时间、活跃用户数、趋势数据。
     *
     * @return 对话统计 VO
     */
    @GetMapping("/conversations")
    Result<ConversationStatisticsVO> getConversationStatistics();

    /**
     * 获取热门问题
     * <p>
     * 从 sys_ai_conversation 表按 question 分组统计（过滤 is_deleted=0），按被问次数降序排序。
     *
     * @param limit 返回数量，默认 10
     * @return 热门问题 VO 列表
     */
    @GetMapping("/questions")
    Result<List<HotQuestionVO>> getHotQuestions(@RequestParam(value = "limit", defaultValue = "10") Integer limit);

    /**
     * 获取用户统计
     *
     * @param days 统计天数，默认 7
     * @return 用户统计 VO
     */
    @GetMapping("/users")
    Result<UserStatisticsVO> getUserStatistics(@RequestParam(value = "days", defaultValue = "7") Integer days);

    /**
     * 获取工具使用统计
     * <p>
     * 从 sys_ai_operation_log 的 tool_calls 字段解析工具调用记录，按工具名称分组统计。
     *
     * @return 工具使用统计 VO
     */
    @GetMapping("/tools")
    Result<ToolStatisticsVO> getToolStatistics();

    /**
     * 获取 AI 调用指标（聚合）
     * <p>
     * 含总调用数、成功率、Token 消耗、延迟、Top 模型。
     *
     * @return AI 调用指标 VO
     */
    @GetMapping("/ai-metrics")
    Result<AiMetricsVO> getAiMetrics();

    /**
     * 查看单次 AI 调用详情
     * <p>
     * 返回完整链路（Prompt、LLM 响应、工具调用链路、Token 消耗），
     * 数据来源：sys_ai_operation_log 表的显式字段。
     *
     * @param operationId AI 操作日志ID（sys_ai_operation_log.id）
     * @return 操作日志 VO（含完整字段）
     */
    @GetMapping("/ai-metrics/{operationId}")
    Result<OperationLogVO> getOperationDetail(@PathVariable("operationId") Long operationId);

    /**
     * 获取回答满意度统计
     * <p>
     * 含点赞率、点踩率、反馈率、点踩原因分布。
     * 数据来源：sys_ai_feedback 表。
     *
     * @param startDate 起始日期（格式：20260712，可选，不传默认最近 7 天）
     * @param endDate   结束日期（格式：20260712，可选）
     * @return 回答满意度统计 VO
     */
    @GetMapping("/feedback")
    Result<FeedbackStatisticsVO> getFeedbackStatistics(@RequestParam(value = "startDate", required = false) Long startDate,
                                                       @RequestParam(value = "endDate", required = false) Long endDate);
}
