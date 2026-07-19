package com.zmbdp.chat.service.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 回答反馈表 sys_ai_feedback
 * <p>
 * 存储 C 端用户对 AI 回答的反馈（点赞/点踩），
 * 用于回答满意度统计和 RAG 质量优化。
 *
 * @author 稚名不带撇
 */
@Data
@TableName("sys_ai_feedback")
public class SysAiFeedback {

    /**
     * 反馈ID（雪花算法）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 对话记录ID（关联 sys_ai_conversation.id）
     */
    private Long conversationId;

    /**
     * 用户ID（关联 sys_user 或 app_user 表）
     */
    private Long userId;

    /**
     * 用户来源（sys/app），用于区分 B 端/C 端
     */
    private String userFrom;

    /**
     * 反馈类型（LIKE/DISLIKE）
     */
    private String feedbackType;

    /**
     * 点踩原因（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，仅 feedback_type=DISLIKE 时有值）
     */
    private String dislikeReason;

    /**
     * 文字评论（用户可选填）
     */
    private String comment;

    /**
     * 创建日期（格式：20260712）
     */
    private Long createDate;

    /**
     * 创建时间（精确到秒，用于时间范围统计）
     */
    private LocalDateTime createTime;
}
