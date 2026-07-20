package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.ConversationStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.FeedbackStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.ToolStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;

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
     * 返回活跃用户数、使用频次、Top 活跃用户等指标。
     *
     * @return 用户统计 VO
     */
    UserStatisticsVO getUserStats();

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
     * 返回点赞率、点踩率、反馈率、点踩原因分布等指标。
     *
     * @return 回答满意度统计 VO
     */
    FeedbackStatisticsVO getFeedbackStats();

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
}
