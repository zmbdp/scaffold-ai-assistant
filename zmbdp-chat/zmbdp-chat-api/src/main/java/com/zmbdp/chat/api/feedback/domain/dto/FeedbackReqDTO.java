package com.zmbdp.chat.api.feedback.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回答反馈请求 DTO
 * <p>
 * C 端用户提交对 AI 回答的反馈（点赞 / 点踩）。
 * <p>
 * <b>业务规则</b>：
 * <ul>
 *     <li>同一用户对同一对话重复提交反馈时覆盖上一次的反馈（先删后插）</li>
 *     <li>feedbackType=DISLIKE 时，dislikeReason 必填（Service 层校验，缺失返回 500031）</li>
 *     <li>comment 最多 500 字符</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@Data
public class FeedbackReqDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 对话记录ID（必填，关联 sys_ai_conversation.id）
     */
    @NotNull(message = "对话记录ID不能为空")
    private Long conversationId;

    /**
     * 反馈类型（必填，LIKE/DISLIKE，对应 FeedbackType 枚举）
     */
    @NotNull(message = "反馈类型不能为空")
    private String feedbackType;

    /**
     * 点踩原因（feedbackType=DISLIKE 时必填，OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，对应 DislikeReason 枚举）
     */
    private String dislikeReason;

    /**
     * 文字评论（可选，最多 500 字符）
     */
    @Size(max = 500, message = "评论最多500字符")
    private String comment;
}
