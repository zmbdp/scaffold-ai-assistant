package com.zmbdp.chat.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UsageItemVO;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 调用链路日志表 sys_ai_operation_log 的 mapper
 * <p>
 * 提供 AI 调用链路日志的基础 CRUD 操作，自定义统计/追溯查询方法
 *
 * @author 稚名不带撇
 */
@Mapper
public interface SysAiOperationLogMapper extends BaseMapper<SysAiOperationLog> {

    /**
     * 统计 AI 总调用次数
     *
     * @return 总调用次数
     */
    Long countTotalAiCalls();

    /**
     * 统计成功调用次数（status='SUCCESS'）
     *
     * @return 成功调用次数
     */
    Long countSuccessAiCalls();

    /**
     * 统计平均 Token 消耗
     *
     * @return 平均 Token 消耗
     */
    Long avgTokenUsage();

    /**
     * 统计总 Token 消耗
     *
     * @return 总 Token 消耗
     */
    Long sumTotalTokens();

    /**
     * 统计平均延迟（毫秒）
     *
     * @return 平均延迟
     */
    Long avgLatency();

    /**
     * 按模型分组统计 Top 模型调用次数
     *
     * @param limit 返回数量
     * @return Top 模型列表
     */
    List<AiMetricsVO.TopModel> selectTopModels(@Param("limit") int limit);

    /**
     * 查询所有工具调用记录（用于解析 tool_calls JSON 字段统计工具使用情况）
     * <p>
     * 仅返回 tool_calls 非空的记录，避免全表扫描。
     *
     * @return 操作日志列表（仅含 id 和 tool_calls 字段）
     */
    List<SysAiOperationLog> selectAllToolCallRecords();

    /**
     * 按操作类型分组统计
     *
     * @return 操作类型-数量列表
     */
    List<Map<String, Object>> countByOperationType();

    /*=============================================    用户级统计（按 userId 过滤）    =============================================*/

    /**
     * 统计指定用户的累计 Token 消耗（仅 status=SUCCESS）
     *
     * @param userId 用户ID
     * @return 累计 Token 消耗（无数据返回 0）
     */
    Long sumTotalTokensByUser(@Param("userId") Long userId);

    /**
     * 统计指定用户今日的 Token 消耗（仅 status=SUCCESS）
     *
     * @param userId    用户ID
     * @param todayDate 今日日期（格式：20260712）
     * @return 今日 Token 消耗（无数据返回 0）
     */
    Long sumTodayTokensByUser(@Param("userId") Long userId, @Param("todayDate") Long todayDate);

    /**
     * 统计指定用户的对话总数（COUNT(DISTINCT conversation_id)，conversation_id 为 NULL 不计入）
     *
     * @param userId 用户ID
     * @return 对话总数（无数据返回 0）
     */
    Long countDistinctConversationsByUser(@Param("userId") Long userId);

    /**
     * 统计指定用户今日的对话数
     *
     * @param userId    用户ID
     * @param todayDate 今日日期（格式：20260712）
     * @return 今日对话数（无数据返回 0）
     */
    Long countTodayConversationsByUser(@Param("userId") Long userId, @Param("todayDate") Long todayDate);

    /**
     * 统计指定用户的活跃天数（COUNT(DISTINCT create_date)）
     *
     * @param userId 用户ID
     * @return 活跃天数（无数据返回 0）
     */
    Integer countDistinctActiveDaysByUser(@Param("userId") Long userId);

    /**
     * 查询指定用户的首次 AI 调用时间
     *
     * @param userId 用户ID
     * @return 首次调用时间（无数据返回 null）
     */
    LocalDateTime selectFirstUsedTimeByUser(@Param("userId") Long userId);

    /**
     * 查询指定用户近 N 天的每日 Token 消耗趋势
     * <p>
     * 仅返回有数据的日期，调用方需自行补全无数据日期为 0。
     *
     * @param userId    用户ID
     * @param startDate 起始日期（格式：20260712，含当天）
     * @return 日期-Token 数列表
     */
    List<Map<String, Object>> selectDailyTokenTrendByUser(@Param("userId") Long userId, @Param("startDate") Long startDate);

    /**
     * 统计指定用户的 AI 调用记录总数（用于分页）
     *
     * @param userId 用户ID
     * @return 记录总数
     */
    Long countUserOperations(@Param("userId") Long userId);

    /**
     * 分页查询指定用户的 AI 调用明细
     * <p>
     * 按 create_time 倒序，仅返回 VO 所需字段（不返回完整 prompt/response）。
     * prompt 字段截前 50 字符作为提问摘要，create_time 转毫秒时间戳。
     *
     * @param userId 用户ID
     * @param offset 偏移量（pageNo * pageSize）
     * @param limit  每页数量
     * @return 用量明细 VO 列表
     */
    List<UsageItemVO> selectUserOperations(@Param("userId") Long userId,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);
}
