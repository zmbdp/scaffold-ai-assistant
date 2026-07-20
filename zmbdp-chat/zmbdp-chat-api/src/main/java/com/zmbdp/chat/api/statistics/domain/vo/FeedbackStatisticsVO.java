package com.zmbdp.chat.api.statistics.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 回答满意度统计 VO
 * <p>
 * 聚合 sys_ai_feedback 表的反馈数据，含点赞率、点踩率、反馈率、点踩原因分布。
 * 未传 startDate/endDate 时默认统计最近 7 天数据。
 * <p>
 * <b>用途</b>：dislikeReasonDistribution 帮助管理员定位回答质量问题
 * （如 CODE_ERROR 占比高说明知识库代码分块不准确，需优化分块策略）。
 *
 * @author 稚名不带撇
 */
@Data
public class FeedbackStatisticsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 点赞数
     */
    private Long likeCount;

    /**
     * 点踩数
     */
    private Long dislikeCount;

    /**
     * 反馈总数（likeCount + dislikeCount）
     */
    private Long totalFeedback;

    /**
     * 反馈用户数（去重）
     */
    private Long feedbackCount;

    /**
     * 对话总数（用于计算反馈率）
     */
    private Long totalConversations;

    /**
     * 点赞率（%，likeCount / totalFeedback × 100）
     */
    private Double likeRate;

    /**
     * 点踩率（%，dislikeCount / totalFeedback × 100）
     */
    private Double dislikeRate;

    /**
     * 反馈率（%，totalFeedback / totalConversations × 100）
     */
    private Double feedbackRate;

    /**
     * 点踩原因分布
     */
    private List<DislikeReasonDistribution> dislikeReasonDistribution;

    /**
     * 点踩原因分布项
     */
    @Data
    public static class DislikeReasonDistribution implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 点踩原因（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，对应 DislikeReason 枚举）
         */
        private String reason;

        /**
         * 该原因的点踩数
         */
        private Long count;

        /**
         * 该原因占比（%）
         */
        private Double percentage;
    }
}