package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * 反馈类型枚举
 * <p>
 * C 端用户对 AI 回答的反馈类型，用于回答满意度统计和 RAG 质量优化。
 *
 * @author 稚名不带撇
 */
@Getter
public enum FeedbackType {

    /**
     * 点赞（回答有帮助）
     */
    LIKE("LIKE", "点赞（回答有帮助）"),

    /**
     * 点踩（回答有问题）
     */
    DISLIKE("DISLIKE", "点踩（回答有问题）");

    /**
     * 反馈类型编码（存入数据库 sys_ai_feedback.feedback_type 字段）
     */
    private final String code;

    /**
     * 反馈类型描述
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code        反馈类型编码
     * @param description 反馈类型描述
     */
    FeedbackType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 反馈类型编码
     * @return 反馈类型枚举，未匹配返回 null
     */
    public static FeedbackType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (FeedbackType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}