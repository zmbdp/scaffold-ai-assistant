package com.zmbdp.chat.api.statistics.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 调用指标 VO（聚合）
 * <p>
 * 聚合 sys_ai_operation_log 表的 AI 调用统计数据，含总调用数、成功率、Token 消耗、延迟、Top 模型。
 *
 * @author 稚名不带撇
 */
@Data
public class AiMetricsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * AI 总调用次数
     */
    private Long totalAiCalls;

    /**
     * 成功率（%）
     */
    private Double successRate;

    /**
     * 平均 Token 消耗
     */
    private Long avgTokenUsage;

    /**
     * 总 Token 消耗
     */
    private Long totalTokenUsage;

    /**
     * 平均延迟（毫秒）
     */
    private Long avgLatency;

    /**
     * Top 模型调用统计（按调用次数降序）
     */
    private List<TopModel> topModels;

    /**
     * Top 模型项
     */
    @Data
    public static class TopModel implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 模型名称
         */
        private String model;

        /**
         * 调用次数
         */
        private Long count;
    }
}