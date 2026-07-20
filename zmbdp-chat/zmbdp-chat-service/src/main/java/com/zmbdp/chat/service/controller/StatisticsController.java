package com.zmbdp.chat.service.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zmbdp.chat.api.operationlog.domain.vo.OperationLogVO;
import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.ConversationStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.FeedbackStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.ToolStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.chat.api.statistics.feign.StatisticsApi;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import com.zmbdp.chat.service.service.IStatisticsService;
import com.zmbdp.common.core.utils.BeanCopyUtil;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.domain.domain.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 统计服务 Controller（chat-service 端）
 * <p>
 * 实现 {@link StatisticsApi} Feign 接口，提供对话统计、热门问题、用户统计、工具使用统计、
 * AI 调用指标、单次调用详情、回答满意度统计等能力
 * <p>
 * <b>缓存策略</b>：所有统计接口走 Redis 缓存（TTL=300 秒），不走 L1 Caffeine 缓存
 * （统计数据更新频率低，无需多实例广播失效）。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/statistics")
public class StatisticsController implements StatisticsApi {

    /**
     * 默认热门问题返回数量
     */
    private static final int DEFAULT_HOT_QUESTION_LIMIT = 10;

    /**
     * 统计服务
     */
    @Autowired
    private IStatisticsService statisticsService;

    /**
     * 获取对话统计
     * <p>
     * 返回对话总量、今日量、平均响应时间、活跃用户数、趋势等聚合指标。
     *
     * @return 对话统计 VO
     */
    @Override
    public Result<ConversationStatisticsVO> getConversationStatistics() {
        log.info("获取对话统计");
        ConversationStatisticsVO vo = statisticsService.getConversationStats();
        return Result.success(vo);
    }

    /**
     * 获取热门问题
     *
     * @param limit 返回数量（为空时使用默认值 10）
     * @return 热门问题 VO 列表
     */
    @Override
    public Result<List<HotQuestionVO>> getHotQuestions(Integer limit) {
        int queryLimit = limit != null && limit > 0 ? limit : DEFAULT_HOT_QUESTION_LIMIT;
        log.info("获取热门问题：limit = {}", queryLimit);
        List<HotQuestionVO> list = statisticsService.getTopQuestions(queryLimit);
        return Result.success(list);
    }

    /**
     * 获取用户统计
     * <p>
     * <b>参数说明</b>：当前 IStatisticsService.getUserStats 不接受 days 参数
     * （固定返回近 7 天活跃用户，与设计文档 7.2.19 节一致），days 参数暂未实现自定义窗口
     * （后续如需扩展，可增强 Service 层方法签名）。
     *
     * @param days 统计天数（暂未实现，预留）
     * @return 用户统计 VO
     */
    @Override
    public Result<UserStatisticsVO> getUserStatistics(Integer days) {
        log.info("获取用户统计：days = {}（当前固定近 7 天，参数预留）", days);
        UserStatisticsVO vo = statisticsService.getUserStats();
        return Result.success(vo);
    }

    /**
     * 获取工具使用统计
     *
     * @return 工具使用统计 VO
     */
    @Override
    public Result<ToolStatisticsVO> getToolStatistics() {
        log.info("获取工具使用统计");
        ToolStatisticsVO vo = statisticsService.getToolUsageStats();
        return Result.success(vo);
    }

    /**
     * 获取 AI 调用指标
     * <p>
     * 返回 token 消耗、成功率、平均耗时、Top 模型等指标。
     *
     * @return AI 调用指标 VO
     */
    @Override
    public Result<AiMetricsVO> getAiMetrics() {
        log.info("获取 AI 调用指标");
        AiMetricsVO vo = statisticsService.getAiCallMetrics();
        return Result.success(vo);
    }

    /**
     * 查看单次 AI 调用详情
     * <p>
     * 返回完整字段（含 prompt/response/toolCalls），用于排查 AI 调用链路问题。
     * toolCalls 字段从 JSON 字符串解析为 {@link OperationLogVO.ToolCallDetail} 列表。
     *
     * @param operationId AI 操作日志ID
     * @return 操作日志 VO（含完整链路信息）
     */
    @Override
    public Result<OperationLogVO> getOperationDetail(Long operationId) {
        log.info("查看单次 AI 调用详情：operationId = {}", operationId);
        SysAiOperationLog logEntity = statisticsService.getOperationDetail(operationId);
        if (logEntity == null) {
            return Result.success(null);
        }
        OperationLogVO vo = convertToOperationLogVO(logEntity);
        return Result.success(vo);
    }

    /**
     * 获取回答满意度统计
     * <p>
     * <b>参数说明</b>：当前 IStatisticsService.getFeedbackStats 不接受日期范围参数
     * （返回全量反馈统计，与设计文档 7.2.19 节一致），startDate/endDate 参数暂未实现自定义窗口
     * （后续如需扩展，可增强 Service 层方法签名）。
     *
     * @param startDate 起始日期（暂未实现，预留）
     * @param endDate   结束日期（暂未实现，预留）
     * @return 回答满意度统计 VO
     */
    @Override
    public Result<FeedbackStatisticsVO> getFeedbackStatistics(Long startDate, Long endDate) {
        log.info("获取回答满意度统计：startDate = {}, endDate = {}（当前返回全量统计，参数预留）", startDate, endDate);
        FeedbackStatisticsVO vo = statisticsService.getFeedbackStats();
        return Result.success(vo);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 将 SysAiOperationLog 转换为 OperationLogVO
     * <p>
     * 详情页返回完整字段，包括解析 toolCalls JSON 字符串为 {@link OperationLogVO.ToolCallDetail} 列表。
     * toolCalls JSON 格式：{@code [{"name":"ReadFileTool","success":true,"duration":45,"summary":"..."}]}
     *
     * @param logEntity AI 操作日志实体
     * @return 操作日志 VO（含完整链路信息）
     */
    private OperationLogVO convertToOperationLogVO(SysAiOperationLog logEntity) {
        OperationLogVO vo = new OperationLogVO();
        BeanCopyUtil.copyProperties(logEntity, vo);
        // toolCalls 字段类型不同（String → List<ToolCallDetail>），需要单独解析
        vo.setToolCalls(parseToolCalls(logEntity.getToolCalls()));
        return vo;
    }

    /**
     * 解析 toolCalls JSON 字符串为 ToolCallDetail 列表
     * <p>
     * JSON 格式：{@code [{"name":"ReadFileTool","success":true,"duration":45,"summary":"..."}]}
     * 解析失败时返回空列表（不影响其他字段展示）。
     *
     * @param toolCallsJson toolCalls JSON 字符串
     * @return ToolCallDetail 列表（解析失败返回空列表）
     */
    private List<OperationLogVO.ToolCallDetail> parseToolCalls(String toolCallsJson) {
        if (!StringUtils.hasText(toolCallsJson)) {
            return new ArrayList<>();
        }
        try {
            List<OperationLogVO.ToolCallDetail> list = JsonUtil.jsonToClass(toolCallsJson,
                    new TypeReference<List<OperationLogVO.ToolCallDetail>>() {});
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.warn("解析 toolCalls JSON 失败：toolCalls = {}", toolCallsJson, e);
            return new ArrayList<>();
        }
    }
}