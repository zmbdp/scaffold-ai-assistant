package com.zmbdp.chat.api.statistics.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户用量明细项 VO
 * <p>
 * 表示当前用户的单次 AI 调用记录，供 C 端用量管理页表格展示。
 * <p>
 * <b>数据来源</b>：sys_ai_operation_log 表，{@code WHERE user_id = ?} 按 create_time 倒序分页。
 *
 * @author 稚名不带撇
 */
@Data
public class UsageItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * AI 操作日志ID（sys_ai_operation_log.id）
     */
    private Long operationId;

    /**
     * 会话ID（sys_ai_operation_log.conversation_id）
     */
    private Long conversationId;

    /**
     * 用户提问摘要（prompt 字段前 50 字符）
     */
    private String question;

    /**
     * 模型名（sys_ai_operation_log.model）
     */
    private String model;

    /**
     * 本次 Token 消耗（sys_ai_operation_log.total_tokens）
     */
    private Integer tokenUsage;

    /**
     * 耗时（毫秒，sys_ai_operation_log.response_time）
     */
    private Integer duration;

    /**
     * 调用时间（毫秒时间戳，由 sys_ai_operation_log.create_time 转换）
     */
    private Long createTime;
}
