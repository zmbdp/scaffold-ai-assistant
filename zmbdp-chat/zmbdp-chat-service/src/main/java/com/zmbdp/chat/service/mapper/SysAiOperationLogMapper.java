package com.zmbdp.chat.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
