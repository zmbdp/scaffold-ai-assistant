package com.zmbdp.chat.api.feedback.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * B 端反馈明细 VO
 * <p>
 * 用于 B 端管理员的反馈明细分页查询，单条记录同时返回反馈信息 + 对话问答摘要，
 * 管理员可一眼看到"用户问了什么、AI 答了什么、为什么点踩"。
 * <p>
 * <b>数据来源</b>：sys_ai_feedback 表 LEFT JOIN sys_ai_conversation 表
 * （ON f.conversation_id = c.id），一对一关系。
 * <p>
 * <b>answer 截断说明</b>：sys_ai_conversation.answer 字段为 LONGTEXT，列表页若全量返回
 * 会导致响应体过大，故 SQL 层用 LEFT(answer, 200) 截断 200 字，完整回答通过
 * 会话详情接口（GET /portal/history/{sessionId}）查看。
 *
 * @author 稚名不带撇
 */
@Data
public class FeedbackAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 反馈ID（雪花算法）
     */
    private Long id;

    /**
     * 对话记录ID（关联 sys_ai_conversation.id）
     */
    private Long conversationId;

    /**
     * 反馈用户ID
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
     * 点踩原因（仅 feedbackType=DISLIKE 时有值，OUTDATED/IRRELEVANT/CODE_ERROR/OTHER）
     */
    private String dislikeReason;

    /**
     * 文字评论
     */
    private String comment;

    /**
     * 反馈时间（精确到秒）
     */
    private LocalDateTime createTime;

    /**
     * 用户原始提问（SQL LEFT 截断 100 字，避免响应过大）
     */
    private String question;

    /**
     * AI 回答摘要（SQL LEFT 截断 200 字，完整回答通过会话详情接口查看）
     */
    private String answerSummary;

    /**
     * 使用的模型名称（如 deepseek-v4-flash、qwen-vl-max）
     */
    private String model;

    /**
     * RAG 引用来源列表（由 sourcesJson 反序列化得到）
     */
    private List<String> sources;

    /**
     * sources 字段的 JSON 字符串（数据库查询中间字段，不返回前端）
     * <p>
     * MyBatis 映射用，Service 层反序列化为 {@link #sources} 后清空此字段。
     * 加 {@code @JsonIgnore} 避免 Jackson 序列化到前端响应。
     */
    @JsonIgnore
    private String sourcesJson;
}
