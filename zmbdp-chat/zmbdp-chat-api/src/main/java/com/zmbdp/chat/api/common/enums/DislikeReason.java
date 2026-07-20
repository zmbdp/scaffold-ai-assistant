package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * 点踩原因枚举
 * <p>
 * 用户点踩时选择的具体原因，用于指导 RAG 质量优化和 Prompt 调优。
 *
 * @author 稚名不带撇
 */
@Getter
public enum DislikeReason {

    /**
     * 信息过时（优化方向：重新同步知识库，更新文档）
     */
    OUTDATED("OUTDATED", "信息过时", "重新同步知识库，更新文档"),

    /**
     * 答非所问（优化方向：优化 RAG 检索，调整 topK、Reranking）
     */
    IRRELEVANT("IRRELEVANT", "答非所问", "优化 RAG 检索（调整 topK、Reranking）"),

    /**
     * 代码错误（优化方向：检查知识库中的代码分块是否准确）
     */
    CODE_ERROR("CODE_ERROR", "代码错误", "检查知识库中的代码分块是否准确"),

    /**
     * 其他（优化方向：结合 comment 评论内容分析）
     */
    OTHER("OTHER", "其他", "结合 comment 评论内容分析");

    /**
     * 原因编码（存入数据库 sys_ai_feedback.dislike_reason 字段，仅 feedback_type=DISLIKE 时有值）
     */
    private final String code;

    /**
     * 原因描述
     */
    private final String description;

    /**
     * 优化方向（指导 RAG 质量优化）
     */
    private final String optimization;

    /**
     * 构造函数
     *
     * @param code         原因编码
     * @param description  原因描述
     * @param optimization 优化方向
     */
    DislikeReason(String code, String description, String optimization) {
        this.code = code;
        this.description = description;
        this.optimization = optimization;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 原因编码
     * @return 点踩原因枚举，未匹配返回 null
     */
    public static DislikeReason fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (DislikeReason reason : values()) {
            if (reason.getCode().equals(code)) {
                return reason;
            }
        }
        return null;
    }
}