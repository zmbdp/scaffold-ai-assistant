package com.zmbdp.chat.api.statistics.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 工具使用统计 VO
 * <p>
 * 从 sys_ai_operation_log 表的 tool_calls 字段（JSON 数组）解析工具调用记录，按工具名称分组统计。
 *
 * @author 稚名不带撇
 */
@Data
public class ToolStatisticsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工具总调用次数
     */
    private Long totalCalls;

    /**
     * 工具使用详情列表
     */
    private List<ToolUsage> toolUsage;

    /**
     * 工具使用详情项
     */
    @Data
    public static class ToolUsage implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 工具名称
         */
        private String toolName;

        /**
         * 调用次数
         */
        private Integer callCount;

        /**
         * 成功次数
         */
        private Integer successCount;

        /**
         * 失败次数
         */
        private Integer failCount;

        /**
         * 最后使用时间（时间戳，毫秒）
         */
        private Long lastUsedTime;
    }
}