package com.zmbdp.chat.api.feedback.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 回答反馈 VO
 * <p>
 * 提交反馈后的返回对象，或查询用户已提交反馈的返回对象。
 * 用户未反馈时返回 {@code data: null}（非错误）。
 *
 * @author 稚名不带撇
 */
@Data
public class FeedbackVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 反馈ID（雪花算法）
     */
    private Long id;

    /**
     * 对话记录ID
     */
    private Long conversationId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户来源（sys/app）
     */
    private String userFrom;

    /**
     * 反馈类型（LIKE/DISLIKE，对应 FeedbackType 枚举）
     */
    private String feedbackType;

    /**
     * 点踩原因（仅 feedbackType=DISLIKE 时有值，对应 DislikeReason 枚举）
     */
    private String dislikeReason;

    /**
     * 文字评论
     */
    private String comment;

    /**
     * 创建日期（格式：20260712）
     */
    private Long createDate;

    /**
     * 创建时间（精确到秒）
     */
    private LocalDateTime createTime;
}
