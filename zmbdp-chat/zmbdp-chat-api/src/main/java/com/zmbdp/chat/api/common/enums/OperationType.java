package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * AI 操作类型枚举
 * <p>
 * 定义 AI 调用链路日志（sys_ai_operation_log.operation_type）的操作类型，用于 AI 调用追踪和统计。
 *
 * @author 稚名不带撇
 */
@Getter
public enum OperationType {

    /**
     * 创建
     */
    CREATE("CREATE", "创建"),

    /**
     * 更新
     */
    UPDATE("UPDATE", "更新"),

    /**
     * 删除
     */
    DELETE("DELETE", "删除"),

    /**
     * 查询
     */
    QUERY("QUERY", "查询"),

    /**
     * 同步
     */
    SYNC("SYNC", "同步"),

    /**
     * 登录
     */
    LOGIN("LOGIN", "登录"),

    /**
     * AI 对话（C端/B端发起 AI 对话，流式或非流式）
     */
    CHAT("CHAT", "AI对话"),

    /**
     * RAG 检索（向量检索 + 重排序）
     */
    RETRIEVE("RETRIEVE", "RAG检索"),

    /**
     * 向量嵌入（知识同步时批量生成向量）
     */
    EMBEDDING("EMBEDDING", "向量嵌入"),

    /**
     * 重排序（RAG 检索的重排序阶段）
     */
    RERANK("RERANK", "重排序");

    /**
     * 操作类型编码（存入数据库 sys_ai_operation_log.operation_type 字段）
     */
    private final String code;

    /**
     * 操作类型描述
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code        操作类型编码
     * @param description 操作类型描述
     */
    OperationType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 操作类型编码
     * @return 操作类型枚举，未匹配返回 null
     */
    public static OperationType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (OperationType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}