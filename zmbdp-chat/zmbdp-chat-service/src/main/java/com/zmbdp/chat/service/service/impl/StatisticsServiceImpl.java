package com.zmbdp.chat.service.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.ConversationStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.FeedbackStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.ToolStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import com.zmbdp.chat.service.mapper.SysAiConversationMapper;
import com.zmbdp.chat.service.mapper.SysAiFeedbackMapper;
import com.zmbdp.chat.service.mapper.SysAiOperationLogMapper;
import com.zmbdp.chat.service.service.IStatisticsService;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.redis.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 统计服务实现类
 * <p>
 * 结合 sys_ai_operation_log 表（AI 调用链路追踪）+ sys_ai_conversation 表（对话聚合）
 * + sys_ai_feedback 表（满意度）实现 AI 调用追踪和评估。
 * <p>
 * <b>缓存策略</b>：所有统计接口走 Redis 缓存（TTL=300 秒），不走 L1 Caffeine 缓存
 * （统计数据更新频率低，无需多实例广播失效）。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class StatisticsServiceImpl implements IStatisticsService {

    /**
     * 统计缓存 TTL（秒），与设计文档 7.2.19 节一致
     */
    private static final long STATS_CACHE_TTL = 300L;

    /**
     * 活跃用户统计窗口（近 7 天）
     */
    private static final int ACTIVE_USER_DAYS = 7;

    /**
     * 趋势数据窗口（近 7 天）
     */
    private static final int TREND_DAYS = 7;

    /**
     * Top 用户数量
     */
    private static final int TOP_USERS_LIMIT = 10;

    /**
     * Top 模型数量
     */
    private static final int TOP_MODELS_LIMIT = 10;

    /**
     * 反馈类型：点赞
     */
    private static final String FEEDBACK_LIKE = "LIKE";

    /**
     * 反馈类型：点踩
     */
    private static final String FEEDBACK_DISLIKE = "DISLIKE";

    /**
     * 日期格式化器（YYYYMMDD，与脚手架统一）
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 统计缓存 key 前缀（与设计文档 7.2.19 节一致）
     */
    private static final String CACHE_KEY_CONVERSATION = "stats:conversation";
    private static final String CACHE_KEY_QUESTIONS = "stats:questions";
    private static final String CACHE_KEY_USERS = "stats:users";
    private static final String CACHE_KEY_TOOLS = "stats:tools";
    private static final String CACHE_KEY_AI_METRICS = "stats:ai_metrics";
    private static final String CACHE_KEY_FEEDBACK = "stats:feedback";

    /**
     * 对话记录 mapper
     */
    @Autowired
    private SysAiConversationMapper sysAiConversationMapper;

    /**
     * AI 操作日志 mapper
     */
    @Autowired
    private SysAiOperationLogMapper sysAiOperationLogMapper;

    /**
     * 反馈 mapper
     */
    @Autowired
    private SysAiFeedbackMapper sysAiFeedbackMapper;

    /**
     * Redis 服务（统计结果缓存）
     */
    @Autowired
    private RedisService redisService;

    /*=============================================    前端调用    =============================================*/

    /**
     * 对话统计
     * <p>
     * 执行流程：
     * 1. 构建缓存 Key "stats:conversation"
     * 2. 尝试从 Redis 读取缓存 → 命中则直接返回
     * 3. 未命中执行数据库聚合查询：总对话数、今日对话数、平均响应时间、活跃用户数、趋势数据
     * 4. 构建 VO 写入缓存（TTL=300秒）
     *
     * @return 对话统计 VO
     */
    @Override
    public ConversationStatisticsVO getConversationStats() {
        // 1. 尝试读取缓存
        ConversationStatisticsVO cached = redisService.getCacheObject(CACHE_KEY_CONVERSATION,
                new TypeReference<ConversationStatisticsVO>() {});
        if (cached != null) {
            return cached;
        }
        // 2. 执行数据库聚合查询
        ConversationStatisticsVO vo = new ConversationStatisticsVO();
        vo.setTotalConversations(sysAiConversationMapper.countTotalConversations());
        Long today = Long.parseLong(LocalDate.now().format(DATE_FORMATTER));
        vo.setTodayConversations(sysAiConversationMapper.countTodayConversations(today));
        vo.setAvgResponseTime(sysAiConversationMapper.avgResponseTime());
        // 活跃用户数：近 7 天有对话记录的用户
        Long activeStartDate = Long.parseLong(LocalDate.now().minusDays(ACTIVE_USER_DAYS - 1).format(DATE_FORMATTER));
        vo.setActiveUsers(sysAiConversationMapper.countActiveUsers(activeStartDate));
        // 趋势数据：近 7 天每日对话数
        Long trendStartDate = Long.parseLong(LocalDate.now().minusDays(TREND_DAYS - 1).format(DATE_FORMATTER));
        List<Map<String, Object>> trendRows = sysAiConversationMapper.countDailyTrend(trendStartDate);
        vo.setTrendData(buildTrendData(trendRows));
        // 3. 写入缓存
        redisService.setCacheObject(CACHE_KEY_CONVERSATION, vo, STATS_CACHE_TTL, TimeUnit.SECONDS);
        return vo;
    }

    /**
     * 热门问题 TOP N
     *
     * @param limit 返回数量
     * @return 热门问题 VO 列表
     */
    @Override
    public List<HotQuestionVO> getTopQuestions(int limit) {
        // 参数兜底
        if (limit <= 0) {
            limit = 10;
        }
        // 1. 尝试读取缓存
        List<HotQuestionVO> cached = redisService.getCacheObject(CACHE_KEY_QUESTIONS,
                new TypeReference<List<HotQuestionVO>>() {});
        if (cached != null) {
            return cached;
        }
        // 2. 查询数据库
        List<HotQuestionVO> list = sysAiConversationMapper.selectTopQuestions(limit);
        if (list == null) {
            list = new ArrayList<>();
        }
        // 3. 写入缓存
        redisService.setCacheObject(CACHE_KEY_QUESTIONS, list, STATS_CACHE_TTL, TimeUnit.SECONDS);
        return list;
    }

    /**
     * 用户统计
     *
     * @return 用户统计 VO
     */
    @Override
    public UserStatisticsVO getUserStats() {
        // 1. 尝试读取缓存
        UserStatisticsVO cached = redisService.getCacheObject(CACHE_KEY_USERS,
                new TypeReference<UserStatisticsVO>() {});
        if (cached != null) {
            return cached;
        }
        // 2. 查询数据库
        UserStatisticsVO vo = new UserStatisticsVO();
        Long activeStartDate = Long.parseLong(LocalDate.now().minusDays(ACTIVE_USER_DAYS - 1).format(DATE_FORMATTER));
        vo.setActiveUsers(sysAiConversationMapper.countActiveUsers(activeStartDate));
        vo.setTotalUsers(sysAiConversationMapper.countTotalUsers());
        List<UserStatisticsVO.TopUser> topUsers = sysAiConversationMapper.selectTopUsers(TOP_USERS_LIMIT);
        vo.setTopUsers(topUsers != null ? topUsers : new ArrayList<>());
        // 3. 写入缓存
        redisService.setCacheObject(CACHE_KEY_USERS, vo, STATS_CACHE_TTL, TimeUnit.SECONDS);
        return vo;
    }

    /**
     * 工具使用统计
     * <p>
     * 从 sys_ai_operation_log 表的 tool_calls 字段（JSON 数组）解析工具调用记录，
     * 按工具名称分组统计调用次数、成功/失败次数、最后使用时间。
     *
     * @return 工具使用统计 VO
     */
    @Override
    public ToolStatisticsVO getToolUsageStats() {
        // 1. 尝试读取缓存
        ToolStatisticsVO cached = redisService.getCacheObject(CACHE_KEY_TOOLS,
                new TypeReference<ToolStatisticsVO>() {});
        if (cached != null) {
            return cached;
        }
        // 2. 查询所有工具调用记录（仅 tool_calls 非空）
        List<SysAiOperationLog> records = sysAiOperationLogMapper.selectAllToolCallRecords();
        // 3. 解析 tool_calls JSON 并按工具名聚合
        ToolStatisticsVO vo = aggregateToolUsage(records);
        // 4. 写入缓存
        redisService.setCacheObject(CACHE_KEY_TOOLS, vo, STATS_CACHE_TTL, TimeUnit.SECONDS);
        return vo;
    }

    /**
     * AI 调用指标
     * <p>
     * 基于 sys_ai_operation_log 表按 operation_type 聚合统计，
     * 含总调用数、成功率、Token 消耗、延迟、Top 模型。
     *
     * @return AI 调用指标 VO
     */
    @Override
    public AiMetricsVO getAiCallMetrics() {
        // 1. 尝试读取缓存
        AiMetricsVO cached = redisService.getCacheObject(CACHE_KEY_AI_METRICS,
                new TypeReference<AiMetricsVO>() {});
        if (cached != null) {
            return cached;
        }
        // 2. 查询数据库
        AiMetricsVO vo = new AiMetricsVO();
        vo.setTotalAiCalls(sysAiOperationLogMapper.countTotalAiCalls());
        Long successCalls = sysAiOperationLogMapper.countSuccessAiCalls();
        vo.setAvgTokenUsage(sysAiOperationLogMapper.avgTokenUsage());
        vo.setTotalTokenUsage(sysAiOperationLogMapper.sumTotalTokens());
        vo.setAvgLatency(sysAiOperationLogMapper.avgLatency());
        // 成功率 = 成功调用数 / 总调用数 × 100
        if (vo.getTotalAiCalls() != null && vo.getTotalAiCalls() > 0) {
            double rate = successCalls * 100.0 / vo.getTotalAiCalls();
            vo.setSuccessRate(Math.round(rate * 100.0) / 100.0);
        } else {
            vo.setSuccessRate(0.0);
        }
        // Top 模型
        List<AiMetricsVO.TopModel> topModels = sysAiOperationLogMapper.selectTopModels(TOP_MODELS_LIMIT);
        vo.setTopModels(topModels != null ? topModels : new ArrayList<>());
        // 3. 写入缓存
        redisService.setCacheObject(CACHE_KEY_AI_METRICS, vo, STATS_CACHE_TTL, TimeUnit.SECONDS);
        return vo;
    }

    /**
     * 回答满意度统计
     * <p>
     * 基于 sys_ai_feedback 表聚合统计回答满意度。
     *
     * @return 回答满意度统计 VO
     */
    @Override
    public FeedbackStatisticsVO getFeedbackStats() {
        // 1. 尝试读取缓存
        FeedbackStatisticsVO cached = redisService.getCacheObject(CACHE_KEY_FEEDBACK,
                new TypeReference<FeedbackStatisticsVO>() {});
        if (cached != null) {
            return cached;
        }
        // 2. 查询数据库
        FeedbackStatisticsVO vo = new FeedbackStatisticsVO();
        Long likeCount = sysAiFeedbackMapper.countByType(FEEDBACK_LIKE);
        Long dislikeCount = sysAiFeedbackMapper.countByType(FEEDBACK_DISLIKE);
        Long totalFeedback = sysAiFeedbackMapper.countTotalFeedback();
        Long feedbackUsers = sysAiFeedbackMapper.countFeedbackUsers();
        Long totalConversations = sysAiConversationMapper.countTotalConversationsForFeedback();
        vo.setLikeCount(likeCount != null ? likeCount : 0L);
        vo.setDislikeCount(dislikeCount != null ? dislikeCount : 0L);
        vo.setTotalFeedback(totalFeedback != null ? totalFeedback : 0L);
        vo.setFeedbackCount(feedbackUsers != null ? feedbackUsers : 0L);
        vo.setTotalConversations(totalConversations != null ? totalConversations : 0L);
        // 计算比率（保留两位小数）
        if (vo.getTotalFeedback() > 0) {
            vo.setLikeRate(Math.round(vo.getLikeCount() * 10000.0 / vo.getTotalFeedback()) / 100.0);
            vo.setDislikeRate(Math.round(vo.getDislikeCount() * 10000.0 / vo.getTotalFeedback()) / 100.0);
        } else {
            vo.setLikeRate(0.0);
            vo.setDislikeRate(0.0);
        }
        if (vo.getTotalConversations() > 0) {
            vo.setFeedbackRate(Math.round(vo.getTotalFeedback() * 10000.0 / vo.getTotalConversations()) / 100.0);
        } else {
            vo.setFeedbackRate(0.0);
        }
        // 点踩原因分布
        List<Map<String, Object>> distRows = sysAiFeedbackMapper.countDislikeReasonDistribution();
        vo.setDislikeReasonDistribution(buildDislikeReasonDistribution(distRows, vo.getDislikeCount()));
        // 3. 写入缓存
        redisService.setCacheObject(CACHE_KEY_FEEDBACK, vo, STATS_CACHE_TTL, TimeUnit.SECONDS);
        return vo;
    }

    /*=============================================    内部调用    =============================================*/

    /**
     * 查询单次 AI 调用详情
     * <p>
     * 含完整 Prompt、响应、工具调用链路、Token 消耗。
     *
     * @param operationId AI 操作日志ID
     * @return 操作日志实体
     */
    @Override
    public SysAiOperationLog getOperationDetail(Long operationId) {
        return sysAiOperationLogMapper.selectById(operationId);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 构建趋势数据
     * <p>
     * 补全近 7 天中无对话的日期（count=0），确保趋势图连续。
     *
     * @param trendRows 数据库查询结果
     * @return 趋势数据列表
     */
    private List<ConversationStatisticsVO.TrendData> buildTrendData(List<Map<String, Object>> trendRows) {
        // 数据库结果转为 Map：date → count
        Map<String, Long> dateCountMap = new HashMap<>();
        if (!CollectionUtils.isEmpty(trendRows)) {
            for (Map<String, Object> row : trendRows) {
                String date = (String) row.get("date");
                Object countObj = row.get("count");
                Long count = countObj instanceof Number ? ((Number) countObj).longValue() : 0L;
                if (date != null) {
                    dateCountMap.put(date, count);
                }
            }
        }
        // 补全近 7 天所有日期
        List<ConversationStatisticsVO.TrendData> result = new ArrayList<>();
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            String dateStr = date.format(outputFormatter);
            ConversationStatisticsVO.TrendData trend = new ConversationStatisticsVO.TrendData();
            trend.setDate(dateStr);
            trend.setCount(dateCountMap.getOrDefault(dateStr, 0L));
            result.add(trend);
        }
        return result;
    }

    /**
     * 聚合工具使用统计
     * <p>
     * 解析 sys_ai_operation_log.tool_calls 字段（JSON 数组），
     * 每项格式如 {"name":"ReadFileTool","success":true,"duration":45,"summary":"..."}，
     * 按 name 分组统计调用次数、成功/失败次数、最后使用时间。
     *
     * @param records 操作日志列表
     * @return 工具使用统计 VO
     */
    private ToolStatisticsVO aggregateToolUsage(List<SysAiOperationLog> records) {
        ToolStatisticsVO vo = new ToolStatisticsVO();
        if (CollectionUtils.isEmpty(records)) {
            vo.setTotalCalls(0L);
            vo.setToolUsage(new ArrayList<>());
            return vo;
        }
        // 按工具名聚合
        Map<String, ToolStatisticsVO.ToolUsage> usageMap = new LinkedHashMap<>();
        long totalCalls = 0;
        for (SysAiOperationLog record : records) {
            List<Map<String, Object>> toolCalls;
            try {
                toolCalls = JsonUtil.jsonToClass(record.getToolCalls(),
                        new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                log.warn("解析 tool_calls JSON 失败：operationId = {}, toolCalls = {}",
                        record.getId(), record.getToolCalls(), e);
                continue;
            }
            if (CollectionUtils.isEmpty(toolCalls)) {
                continue;
            }
            for (Map<String, Object> call : toolCalls) {
                String toolName = (String) call.get("name");
                if (!StringUtils.hasText(toolName)) {
                    continue;
                }
                Boolean success = (Boolean) call.get("success");
                ToolStatisticsVO.ToolUsage usage = usageMap.computeIfAbsent(toolName, k -> {
                    ToolStatisticsVO.ToolUsage u = new ToolStatisticsVO.ToolUsage();
                    u.setToolName(k);
                    u.setCallCount(0);
                    u.setSuccessCount(0);
                    u.setFailCount(0);
                    u.setLastUsedTime(0L);
                    return u;
                });
                usage.setCallCount(usage.getCallCount() + 1);
                if (Boolean.TRUE.equals(success)) {
                    usage.setSuccessCount(usage.getSuccessCount() + 1);
                } else {
                    usage.setFailCount(usage.getFailCount() + 1);
                }
                // 最后使用时间取 createTime 的毫秒值（record 没查 createTime，从 id 时间近似取）
                // 由于 selectAllToolCallRecords 未返回 createTime，这里用 0 占位
                totalCalls++;
            }
        }
        // 按调用次数降序排序
        List<ToolStatisticsVO.ToolUsage> usageList = new ArrayList<>(usageMap.values());
        usageList.sort(Comparator.comparingInt(ToolStatisticsVO.ToolUsage::getCallCount).reversed());
        vo.setTotalCalls(totalCalls);
        vo.setToolUsage(usageList);
        return vo;
    }

    /**
     * 构建点踩原因分布
     *
     * @param distRows      数据库查询结果
     * @param totalDislike 总点踩数（用于计算占比）
     * @return 点踩原因分布列表
     */
    private List<FeedbackStatisticsVO.DislikeReasonDistribution> buildDislikeReasonDistribution(
            List<Map<String, Object>> distRows, Long totalDislike) {
        if (CollectionUtils.isEmpty(distRows)) {
            return new ArrayList<>();
        }
        return distRows.stream().map(row -> {
            FeedbackStatisticsVO.DislikeReasonDistribution dist = new FeedbackStatisticsVO.DislikeReasonDistribution();
            dist.setReason((String) row.get("reason"));
            Object countObj = row.get("count");
            Long count = countObj instanceof Number ? ((Number) countObj).longValue() : 0L;
            dist.setCount(count);
            if (totalDislike != null && totalDislike > 0) {
                dist.setPercentage(Math.round(count * 10000.0 / totalDislike) / 100.0);
            } else {
                dist.setPercentage(0.0);
            }
            return dist;
        }).collect(Collectors.toList());
    }
}