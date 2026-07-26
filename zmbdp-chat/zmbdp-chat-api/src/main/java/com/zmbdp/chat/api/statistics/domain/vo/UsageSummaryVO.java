package com.zmbdp.chat.api.statistics.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 用户用量汇总 VO
 * <p>
 * 聚合 sys_ai_operation_log 表按 userId 过滤后的统计数据，供 C 端仪表盘 + 用量管理页顶部卡片使用。
 * <p>
 * <b>数据来源</b>：sys_ai_operation_log 表，{@code WHERE user_id = ?} 聚合查询。
 *
 * @author 稚名不带撇
 */
@Data
public class UsageSummaryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 累计 Token 消耗（SUM(total_tokens)，仅统计 status=SUCCESS）
     */
    private Long totalTokens;

    /**
     * 今日 Token 消耗（同上 + 当日过滤）
     */
    private Long todayTokens;

    /**
     * 对话总数（COUNT(DISTINCT conversation_id)，conversation_id 为 NULL 的记录不计入）
     */
    private Long totalConversations;

    /**
     * 今日对话数（同上 + 当日过滤）
     */
    private Long todayConversations;

    /**
     * 活跃天数（COUNT(DISTINCT create_date)，该用户有过 AI 调用的日期数）
     */
    private Integer activeDays;

    /**
     * 首次使用时间（MIN(create_time) 转毫秒时间戳）
     * <p>
     * 用于"使用第 N 天"等展示场景（app_user 表无注册时间字段，用首次 AI 调用时间近似）
     */
    private Long firstUsedTime;

    /**
     * 近 7 天每日 Token 趋势
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
         * 当日 Token 消耗
         */
        private Long count;
    }
}
