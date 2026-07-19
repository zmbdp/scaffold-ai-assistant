package com.zmbdp.chat.api.operationlog.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 调用链路日志 VO
 * <p>
 * 对应 sys_ai_operation_log 表，记录每次 AI 调用的完整链路。
 * <p>
 * <b>列表页</b>：仅返回摘要字段（不含 prompt/response/toolCalls，避免响应过大）；
 * <b>详情页</b>：返回完整字段（含 prompt/response/toolCalls，通过 statistics 接口查看单次调用详情）。
 *
 * @author 稚名不带撇
 */
@Data
public class OperationLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 日志ID
     */
    private Long id;

    /**
     * 调用用户ID
     */
    private Long userId;

    /**
     * 用户来源（sys/app）
     */
    private String userFrom;

    /**
     * 关联对话ID
     */
    private Long conversationId;

    /**
     * AI 操作类型（CHAT/RETRIEVE/EMBEDDING/RERANK，对应 OperationType 枚举）
     */
    private String operationType;

    /**
     * 调用的模型名称（如 deepseek-v4-flash、text-embedding-v1、gte-rerank）
     */
    private String model;

    /**
     * 完整 Prompt（仅详情接口返回，含 RAG 上下文）
     */
    private String prompt;

    /**
     * LLM 完整响应内容（仅详情接口返回）
     */
    private String response;

    /**
     * 工具调用链路（仅详情接口返回，JSON 数组解析为列表）
     */
    private List<ToolCallDetail> toolCalls;

    /**
     * Prompt Token 数
     */
    private Integer promptTokens;

    /**
     * 响应 Token 数
     */
    private Integer completionTokens;

    /**
     * 总 Token 数
     */
    private Integer totalTokens;

    /**
     * 响应耗时（毫秒）
     */
    private Integer responseTime;

    /**
     * 调用状态（SUCCESS/FAILED/TIMEOUT）
     */
    private String status;

    /**
     * 失败原因（status=FAILED/TIMEOUT 时记录）
     */
    private String errorMsg;

    /**
     * 创建日期（格式：20260712）
     */
    private Long createDate;

    /**
     * 创建时间（精确到秒）
     */
    private LocalDateTime createTime;

    /**
     * 工具调用详情（toolCalls 字段的解析对象）
     */
    @Data
    public static class ToolCallDetail implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 工具名称
         */
        private String name;

        /**
         * 是否成功
         */
        private Boolean success;

        /**
         * 耗时（毫秒）
         */
        private Long duration;

        /**
         * 结果摘要
         */
        private String summary;
    }
}