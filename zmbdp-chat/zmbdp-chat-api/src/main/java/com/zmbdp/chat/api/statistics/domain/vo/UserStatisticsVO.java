package com.zmbdp.chat.api.statistics.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 用户统计 VO
 * <p>
 * 聚合 sys_ai_conversation 表的用户维度统计数据，含活跃用户数、总用户数、Top 活跃用户列表。
 *
 * @author 稚名不带撇
 */
@Data
public class UserStatisticsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 活跃用户数（近 N 天有对话记录）
     */
    private Long activeUsers;

    /**
     * 总用户数
     */
    private Long totalUsers;

    /**
     * 活跃用户列表（按对话次数降序）
     */
    private List<TopUser> topUsers;

    /**
     * 活跃用户项
     */
    @Data
    public static class TopUser implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 用户ID
         */
        private Long userId;

        /**
         * 用户名
         */
        private String username;

        /**
         * 对话次数
         */
        private Integer conversationCount;

        /**
         * 最后活跃时间（时间戳，毫秒）
         */
        private Long lastActiveTime;
    }
}