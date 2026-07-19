package com.zmbdp.chat.api.statistics.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 对话统计 VO
 * <p>
 * 聚合 sys_ai_conversation 表的统计数据，含总量、今日量、平均响应时间、活跃用户数、趋势。
 * 数据来源：IStatisticsService.getConversationStats()（含 Redis 缓存）。
 *
 * @author 稚名不带撇
 */
@Data
public class ConversationStatisticsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 对话总数
     */
    private Long totalConversations;

    /**
     * 今日对话数
     */
    private Long todayConversations;

    /**
     * 平均响应时间（毫秒）
     */
    private Long avgResponseTime;

    /**
     * 活跃用户数（近 N 天有对话记录）
     */
    private Long activeUsers;

    /**
     * 趋势数据（按日期聚合的对话量）
     */
    private List<TrendData> trendData;

    /**
     * 趋势数据项
     */
    @Data
    public static class TrendData implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 日期（格式：2026-07-10）
         */
        private String date;

        /**
         * 当日对话量
         */
        private Long count;
    }
}