package com.zmbdp.chat.service.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 调用链路日志表 sys_ai_operation_log
 * <p>
 * 专用于 AI 调用链路追踪（记录每次 AI 调用的完整 Prompt、LLM 响应、
 * 工具调用链路、Token 消耗），用于 AI 调试、效果评估和调用统计。
 * <p>
 * 与脚手架 operation_log 的职责切分：B 端管理操作审计走 @LogAction + operation_log，
 * AI 模型调用链路走本表（AI 编排层手动埋点）。
 *
 * @author 稚名不带撇
 */
@Data
@TableName("sys_ai_operation_log")
public class SysAiOperationLog {

    /**
     * 日志ID（雪花算法）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 调用用户ID（关联 sys_user 或 app_user 表）
     */
    private Long userId;

    /**
     * 用户来源（sys/app）
     */
    private String userFrom;

    /**
     * 关联对话ID（sys_ai_conversation.id，便于追溯）
     */
    private Long conversationId;

    /**
     * AI 操作类型（CHAT/RETRIEVE/EMBEDDING/RERANK）
     */
    private String operationType;

    /**
     * 调用的模型名称（如 deepseek-v4-flash、text-embedding-v1、gte-rerank）
     */
    private String model;

    /**
     * 完整 Prompt（含 RAG 检索到的文档上下文）
     */
    private String prompt;

    /**
     * LLM 完整响应内容
     */
    private String response;

    /**
     * 工具调用链路（JSON 数组，如 [{"name":"ReadFileTool","success":true,"duration":45,"summary":"..."}]）
     */
    private String toolCalls;

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
}
