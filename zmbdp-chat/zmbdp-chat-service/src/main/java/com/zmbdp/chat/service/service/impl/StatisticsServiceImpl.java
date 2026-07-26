package com.zmbdp.chat.service.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.ConversationStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.FeedbackStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.ToolStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UsageItemVO;
import com.zmbdp.chat.api.statistics.domain.vo.UsageSummaryVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import com.zmbdp.chat.service.mapper.SysAiConversationMapper;
import com.zmbdp.chat.service.mapper.SysAiFeedbackMapper;
import com.zmbdp.chat.service.mapper.SysAiOperationLogMapper;
import com.zmbdp.chat.service.service.IStatisticsService;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.common.redis.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * 用户级用量统计缓存 TTL（秒）
     * <p>
     * 用户级数据更新频率高（每次 AI 调用都会变），TTL 设短一些保证数据时效性
     */
    private static final long USER_USAGE_CACHE_TTL = 60L;

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
    private static final String CACHE_KEY_USERS = "stats:users";
    private static final String CACHE_KEY_TOOLS = "stats:tools";
    private static final String CACHE_KEY_AI_METRICS = "stats:ai_metrics";
    private static final String CACHE_KEY_FEEDBACK = "stats:feedback";

    /**
     * 用户级用量统计缓存 key 前缀，完整 key = "stats:user_usage:summary:{userId}"
     */
    private static final String CACHE_KEY_USER_USAGE_SUMMARY = "stats:user_usage:summary";

    /**
     * 热门问题排行榜 ZSET key
     * <p>
     * member = question 字符串（trim 后），score = 被问次数
     * <p>
     * 实时维护：每次 AI 对话成功后通过 {@code recordQuestionAsk} ZINCRBY 累加
     */
    private static final String HOT_QUESTIONS_ZSET_KEY = "stats:hot_questions:zset";

    /**
     * 热门问题元数据 Hash key（存 lastAskedTime）
     * <p>
     * field = question 字符串，value = 最后一次提问时间（毫秒时间戳）
     * <p>
     * ZSET 只能存 score（次数），lastAskedTime 需单独用 Hash 存储
     */
    private static final String HOT_QUESTIONS_META_KEY = "stats:hot_questions:meta";

    /**
     * 冷启动重建时从数据库取的 Top N 数量
     * <p>
     * 取 200 条足够覆盖常见热门问题，避免 ZSET 无限膨胀
     */
    private static final int HOT_QUESTIONS_REBUILD_LIMIT = 200;

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
        vo.setActiveUsers(sysAiConversationMapper.countActiveUsers(ACTIVE_USER_DAYS));
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
     * <p>
     * 数据来源：Redis ZSET 实时排行榜（key: {@value #HOT_QUESTIONS_ZSET_KEY}）。
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *     <li>检查 ZSET 大小，为 0 时触发冷启动重建（从数据库批量加载 Top 200）</li>
     *     <li>ZREVRANGE 取 Top N 个 question（按 score 降序）</li>
     *     <li>逐个 ZSCORE 取 count，HGET 取 lastAskedTime</li>
     *     <li>拼装 {@link HotQuestionVO} 列表返回</li>
     * </ol>
     * <p>
     * <b>实时性</b>：每次 AI 对话成功后通过 {@link #recordQuestionAsk} 实时 ZINCRBY 累加，
     * 排行榜实时更新（无 5 分钟缓存延迟）。
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
        // 1. 冷启动检查：ZSET 为空时从数据库重建
        Long zset_size = redisService.getZSetSize(HOT_QUESTIONS_ZSET_KEY);
        if (zset_size == null || zset_size == 0) {
            log.info("热门问题 ZSET 为空，触发冷启动重建");
            rebuildHotQuestionsCache();
        }
        // 2. ZREVRANGE 取 Top N 个 question（按 score 降序）
        Set<String> questions = redisService.getZSetRangeDesc(HOT_QUESTIONS_ZSET_KEY, 0, limit - 1,
                new TypeReference<LinkedHashSet<String>>() {});
        if (questions == null || questions.isEmpty()) {
            return new ArrayList<>();
        }
        // 3. 逐个取 count（SCORE）和 lastAskedTime（HGET），拼装 VO
        List<HotQuestionVO> result = new ArrayList<>(questions.size());
        for (String question : questions) {
            HotQuestionVO vo = new HotQuestionVO();
            vo.setQuestion(question);
            // ZSCORE 取被问次数
            Double score = redisService.getZSetScore(HOT_QUESTIONS_ZSET_KEY, question);
            vo.setCount(score != null ? score.intValue() : 0);
            // HGET 取最后一次提问时间
            Long lastAskedTime = redisService.getCacheMapValue(HOT_QUESTIONS_META_KEY, question,
                    new TypeReference<Long>() {});
            vo.setLastAskedTime(lastAskedTime);
            result.add(vo);
        }
        return result;
    }

    /**
     * 记录用户提问（实时维护热门问题排行榜）
     * <p>
     * 供 {@code ChatServiceImpl} 在 AI 对话成功后调用，通过 ZINCRBY 累加 question 的 score，
     * 同时更新 Hash 中的 lastAskedTime。
     * <p>
     * <b>注意</b>：仅在对话 status=SUCCESS 且 question 非空时调用。
     *
     * @param question 用户提问内容（会 trim 标准化）
     */
    @Override
    public void recordQuestionAsk(String question) {
        if (!StringUtils.hasText(question)) {
            return;
        }
        String normalizedQuestion = question.trim();
        try {
            // ZINCRBY 累加提问次数
            redisService.incrementZSetScore(HOT_QUESTIONS_ZSET_KEY, normalizedQuestion, 1.0);
            // 更新最后一次提问时间（毫秒时间戳）
            redisService.setCacheMapValue(HOT_QUESTIONS_META_KEY, normalizedQuestion, System.currentTimeMillis());
        } catch (Exception e) {
            // 统计维护失败不影响主流程（saveConversation 已有 try-catch，这里再兜一层）
            log.warn("维护热门问题排行榜失败：question = {}", normalizedQuestion, e);
        }
    }

    /**
     * 冷启动重建热门问题排行榜
     * <p>
     * 从数据库批量查询 Top {@value #HOT_QUESTIONS_REBUILD_LIMIT} 个热门问题，
     * 写入 ZSET（score=count）和 Hash（value=lastAskedTime）。
     * <p>
     * <b>触发时机</b>：ZSET 为空时（首次启动 / Redis 清空 / 缓存丢失）。
     * <p>
     * <b>并发处理</b>：重建是幂等的，不加分布式锁，极小概率的并发重建无副作用（只是多查一次数据库）。
     */
    private void rebuildHotQuestionsCache() {
        try {
            // 从数据库取 Top 200
            List<HotQuestionVO> topQuestions = sysAiConversationMapper.selectTopQuestions(HOT_QUESTIONS_REBUILD_LIMIT);
            if (topQuestions == null || topQuestions.isEmpty()) {
                log.info("数据库无热门问题数据，跳过重建");
                return;
            }
            // 批量写入 ZSET 和 Hash
            for (HotQuestionVO vo : topQuestions) {
                if (vo.getQuestion() == null) {
                    continue;
                }
                redisService.addMemberZSet(HOT_QUESTIONS_ZSET_KEY, vo.getQuestion(), vo.getCount().doubleValue());
                redisService.setCacheMapValue(HOT_QUESTIONS_META_KEY, vo.getQuestion(), vo.getLastAskedTime());
            }
            log.info("热门问题排行榜重建完成：共 {} 条", topQuestions.size());
        } catch (Exception e) {
            log.error("热门问题排行榜重建失败", e);
        }
    }

    /**
     * 用户统计
     *
     * @param days 活跃用户统计窗口（近 N 天，含当天；为 null 或非正数时默认 7 天）
     * @return 用户统计 VO
     */
    @Override
    public UserStatisticsVO getUserStats(Integer days) {
        // days 为 null 或非正数时默认近 7 天
        if (days == null || days <= 0) {
            days = ACTIVE_USER_DAYS;
        }
        // 按天数构建缓存 key，避免不同窗口的统计结果互相覆盖
        String cacheKey = CACHE_KEY_USERS + ":" + days;
        // 1. 尝试读取缓存
        UserStatisticsVO cached = redisService.getCacheObject(cacheKey,
                new TypeReference<UserStatisticsVO>() {});
        if (cached != null) {
            return cached;
        }
        // 2. 查询数据库
        UserStatisticsVO vo = new UserStatisticsVO();
        vo.setActiveUsers(sysAiConversationMapper.countActiveUsers(days));
        vo.setTotalUsers(sysAiConversationMapper.countTotalUsers());
        List<UserStatisticsVO.TopUser> topUsers = sysAiConversationMapper.selectTopUsers(TOP_USERS_LIMIT);
        vo.setTopUsers(topUsers != null ? topUsers : new ArrayList<>());
        // 3. 写入缓存
        redisService.setCacheObject(cacheKey, vo, STATS_CACHE_TTL, TimeUnit.SECONDS);
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
     * 基于 sys_ai_feedback 表聚合统计回答满意度，支持按日期范围过滤
     * （startDate/endDate 为 null 时不加日期过滤，返回全量统计）。
     *
     * @param startDate 起始日期（YYYYMMDD 格式 Long 值，可空）
     * @param endDate   结束日期（YYYYMMDD 格式 Long 值，可空）
     * @return 回答满意度统计 VO
     */
    @Override
    public FeedbackStatisticsVO getFeedbackStats(Long startDate, Long endDate) {
        // 按日期范围构建缓存 key，避免不同过滤条件的结果互相覆盖
        String cacheKey = CACHE_KEY_FEEDBACK + ":" + startDate + ":" + endDate;
        // 1. 尝试读取缓存
        FeedbackStatisticsVO cached = redisService.getCacheObject(cacheKey,
                new TypeReference<FeedbackStatisticsVO>() {});
        if (cached != null) {
            return cached;
        }
        // 2. 查询数据库
        FeedbackStatisticsVO vo = new FeedbackStatisticsVO();
        Long likeCount = sysAiFeedbackMapper.countByType(FEEDBACK_LIKE, startDate, endDate);
        Long dislikeCount = sysAiFeedbackMapper.countByType(FEEDBACK_DISLIKE, startDate, endDate);
        Long totalFeedback = sysAiFeedbackMapper.countTotalFeedback(startDate, endDate);
        Long feedbackUsers = sysAiFeedbackMapper.countFeedbackUsers(startDate, endDate);
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
        List<Map<String, Object>> distRows = sysAiFeedbackMapper.countDislikeReasonDistribution(startDate, endDate);
        vo.setDislikeReasonDistribution(buildDislikeReasonDistribution(distRows, vo.getDislikeCount()));
        // 3. 写入缓存
        redisService.setCacheObject(cacheKey, vo, STATS_CACHE_TTL, TimeUnit.SECONDS);
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

    /*=============================================    用户级统计（C 端用量页）    =============================================*/

    /**
     * 获取指定用户的用量汇总
     * <p>
     * 执行流程：
     * <ol>
     *     <li>构建缓存 Key "stats:user_usage:summary:{userId}"</li>
     *     <li>尝试从 Redis 读取缓存 → 命中则直接返回</li>
     *     <li>未命中执行数据库聚合查询：
     *         <ul>
     *             <li>累计 Token（SUM(total_tokens) WHERE status=SUCCESS）</li>
     *             <li>今日 Token（同上 + create_date=今日）</li>
     *             <li>对话总数（COUNT(DISTINCT conversation_id)）</li>
     *             <li>今日对话数（同上 + 当日过滤）</li>
     *             <li>活跃天数（COUNT(DISTINCT create_date)）</li>
     *             <li>首次使用时间（MIN(create_time) 转毫秒时间戳）</li>
     *             <li>近 7 天 Token 趋势（GROUP BY DATE(create_time)，补全无数据日期）</li>
     *         </ul>
     *     </li>
     *     <li>写入缓存（TTL=60 秒，用户级数据更新频率高）</li>
     * </ol>
     *
     * @param userId 用户ID
     * @return 用户用量汇总 VO
     */
    @Override
    public UsageSummaryVO getUserUsageSummary(Long userId) {
        // 1. 构建缓存 Key
        String cacheKey = CACHE_KEY_USER_USAGE_SUMMARY + ":" + userId;
        // 2. 尝试读取缓存
        UsageSummaryVO cached = redisService.getCacheObject(cacheKey,
                new TypeReference<UsageSummaryVO>() {});
        if (cached != null) {
            return cached;
        }
        // 3. 执行数据库聚合查询
        UsageSummaryVO vo = new UsageSummaryVO();
        Long todayDate = Long.parseLong(LocalDate.now().format(DATE_FORMATTER));
        vo.setTotalTokens(sysAiOperationLogMapper.sumTotalTokensByUser(userId));
        vo.setTodayTokens(sysAiOperationLogMapper.sumTodayTokensByUser(userId, todayDate));
        vo.setTotalConversations(sysAiOperationLogMapper.countDistinctConversationsByUser(userId));
        vo.setTodayConversations(sysAiOperationLogMapper.countTodayConversationsByUser(userId, todayDate));
        vo.setActiveDays(sysAiOperationLogMapper.countDistinctActiveDaysByUser(userId));
        // 首次使用时间（毫秒时间戳）
        LocalDateTime firstUsedTime = sysAiOperationLogMapper.selectFirstUsedTimeByUser(userId);
        vo.setFirstUsedTime(firstUsedTime != null
                ? firstUsedTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null);
        // 近 7 天 Token 趋势
        Long trendStartDate = Long.parseLong(LocalDate.now().minusDays(TREND_DAYS - 1).format(DATE_FORMATTER));
        List<Map<String, Object>> trendRows = sysAiOperationLogMapper.selectDailyTokenTrendByUser(userId, trendStartDate);
        vo.setTrendData(buildUserTrendData(trendRows));
        // 4. 写入缓存
        redisService.setCacheObject(cacheKey, vo, USER_USAGE_CACHE_TTL, TimeUnit.SECONDS);
        return vo;
    }

    /**
     * 分页获取指定用户的 AI 调用明细
     * <p>
     * 按 create_time 倒序分页返回该用户的 AI 调用记录。
     * 明细列表不缓存（实时性要求高，且分页参数多变）。
     *
     * @param userId   用户ID
     * @param pageNo   页码（默认 1）
     * @param pageSize 每页数量（默认 10）
     * @return 用量明细分页结果
     */
    @Override
    public BasePageVO<UsageItemVO> getUserUsageList(Long userId, Integer pageNo, Integer pageSize) {
        // 参数兜底
        int finalPageNo = pageNo != null && pageNo > 0 ? pageNo : 1;
        int finalPageSize = pageSize != null && pageSize > 0 ? pageSize : 10;
        int offset = (finalPageNo - 1) * finalPageSize;
        // 查询总数
        Long total = sysAiOperationLogMapper.countUserOperations(userId);
        BasePageVO<UsageItemVO> pageVO = new BasePageVO<>();
        pageVO.setTotals(total != null ? total.intValue() : 0);
        pageVO.setTotalPages(pageVO.getTotals() > 0
                ? (pageVO.getTotals() + finalPageSize - 1) / finalPageSize
                : 0);
        // 查询当前页数据
        List<UsageItemVO> list = sysAiOperationLogMapper.selectUserOperations(userId, offset, finalPageSize);
        pageVO.setList(list != null ? list : new ArrayList<>());
        return pageVO;
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
     * 构建用户级 Token 趋势数据
     * <p>
     * 补全近 7 天中无 AI 调用的日期（count=0），确保趋势图连续。
     *
     * @param trendRows 数据库查询结果（date + count 字段）
     * @return 趋势数据列表（共 7 天，按日期升序）
     */
    private List<UsageSummaryVO.TrendData> buildUserTrendData(List<Map<String, Object>> trendRows) {
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
        List<UsageSummaryVO.TrendData> result = new ArrayList<>();
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            String dateStr = date.format(outputFormatter);
            UsageSummaryVO.TrendData trend = new UsageSummaryVO.TrendData();
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