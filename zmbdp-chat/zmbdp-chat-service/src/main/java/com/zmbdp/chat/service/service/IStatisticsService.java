package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.ConversationStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.FeedbackStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.ToolStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UsageItemVO;
import com.zmbdp.chat.api.statistics.domain.vo.UsageSummaryVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import com.zmbdp.common.domain.domain.vo.BasePageVO;

import java.util.List;

/**
 * 统计服务
 * <p>
 * 结合 sys_ai_operation_log 表（AI 调用链路追踪）+ sys_ai_conversation 表（对话聚合）
 * + sys_ai_feedback 表（满意度）实现 AI 调用追踪和评估。
 * <p>
 * <b>核心依赖</b>：SysAiConversationMapper、SysAiOperationLogMapper、SysAiFeedbackMapper、
 * RedisService（缓存 5 分钟）。
 *
 * @author 稚名不带撇
 */
public interface IStatisticsService {

    /*=============================================    前端调用    =============================================*/

    /**
     * 对话统计
     * <p>
     * 返回对话总量、今日量、平均响应时间、活跃用户数、趋势等聚合指标。
     *
     * @return 对话统计 VO
     */
    ConversationStatisticsVO getConversationStats();

    /**
     * 热门问题 TOP N
     *
     * @param limit 返回数量
     * @return 热门问题 VO 列表
     */
    List<HotQuestionVO> getTopQuestions(int limit);

    /**
     * 用户统计
     * <p>
     * 返回活跃用户数、使用频次、Top 活跃用户等指标，活跃用户统计窗口由 days 控制
     * （为 null 时默认近 7 天）。
     *
     * @param days 活跃用户统计窗口（近 N 天，含当天；为 null 时默认 7 天）
     * @return 用户统计 VO
     */
    UserStatisticsVO getUserStats(Integer days);

    /**
     * 工具使用统计
     * <p>
     * 返回各工具调用次数、最后使用时间等指标。
     *
     * @return 工具使用统计 VO
     */
    ToolStatisticsVO getToolUsageStats();

    /**
     * AI 调用指标
     * <p>
     * 返回 token 消耗、成功率、平均耗时、Top 模型等指标。
     *
     * @return AI 调用指标 VO
     */
    AiMetricsVO getAiCallMetrics();

    /**
     * 回答满意度统计
     * <p>
     * 返回点赞率、点踩率、反馈率、点踩原因分布等指标，支持按日期范围过滤
     * （startDate/endDate 为 null 时不加日期过滤，返回全量统计）。
     *
     * @param startDate 起始日期（YYYYMMDD 格式 Long 值，可空）
     * @param endDate   结束日期（YYYYMMDD 格式 Long 值，可空）
     * @return 回答满意度统计 VO
     */
    FeedbackStatisticsVO getFeedbackStats(Long startDate, Long endDate);

    /*=============================================    内部调用    =============================================*/

    /**
     * 查询单次 AI 调用详情
     * <p>
     * 含完整 Prompt、响应、工具调用链路、Token 消耗。
     *
     * @param operationId AI 操作日志ID
     * @return 操作日志实体
     */
    SysAiOperationLog getOperationDetail(Long operationId);

    /*=============================================    用户级统计（C 端用量页）    =============================================*/

    /**
     * 获取指定用户的用量汇总
     * <p>
     * 返回该用户的累计/今日 Token 消耗、对话数、活跃天数、首次使用时间、近 7 天 Token 趋势。
     * 数据来源：sys_ai_operation_log 表按 userId 过滤聚合。
     *
     * @param userId 用户ID（由调用方从 JWT 提取）
     * @return 用户用量汇总 VO
     */
    UsageSummaryVO getUserUsageSummary(Long userId);

    /**
     * 分页获取指定用户的 AI 调用明细
     * <p>
     * 按 create_time 倒序分页返回该用户的 AI 调用记录。
     *
     * @param userId   用户ID（由调用方从 JWT 提取）
     * @param pageNo   页码（默认 1）
     * @param pageSize 每页数量（默认 10）
     * @return 用量明细分页结果
     */
    BasePageVO<UsageItemVO> getUserUsageList(Long userId, Integer pageNo, Integer pageSize);
}
