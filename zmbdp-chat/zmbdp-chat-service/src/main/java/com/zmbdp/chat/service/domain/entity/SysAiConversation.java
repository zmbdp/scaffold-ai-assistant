package com.zmbdp.chat.service.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 对话记录表 sys_ai_conversation
 * <p>
 * 存储 C 端用户与 AI 的一问一答对话记录（用户视角的聚合），
 * 含使用的模型参数、Token 消耗、工具调用、引用来源等信息。
 *
 * @author 稚名不带撇
 */
@Data
@TableName("sys_ai_conversation")
public class SysAiConversation {

    /**
     * 对话ID（雪花算法）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话ID（同一会话的多轮对话共享此ID）
     */
    private String sessionId;

    /**
     * 用户ID（关联 sys_user 或 app_user 表）
     */
    private Long userId;

    /**
     * 用户来源（sys/app），用于区分 B 端/C 端
     */
    private String userFrom;

    /**
     * 用户提问内容
     */
    private String question;

    /**
     * AI 回答内容（流式输出完成后持久化）
     */
    private String answer;

    /**
     * 图片 URL 列表（JSON 数组格式，如 ["url1","url2"]，图文对话时使用）
     */
    private String images;

    /**
     * 使用的模型名称（如 deepseek-v4-flash、qwen-vl-max）
     */
    private String model;

    /**
     * 温度参数（实际使用的值，0.00-2.00）
     */
    private BigDecimal temperature;

    /**
     * RAG 检索数量（实际使用的值）
     */
    private Integer topK;

    /**
     * 响应时间（毫秒，从接收请求到完成回答）
     */
    private Integer responseTime;

    /**
     * 本次对话消耗的 Token 数量（输入+输出）
     */
    private Integer tokenUsage;

    /**
     * 工具调用信息（JSON 格式，记录调用了哪些工具及结果）
     */
    private String toolUsage;

    /**
     * 引用来源（JSON 格式，记录 RAG 检索命中的文档列表）
     */
    private String sources;

    /**
     * 对话状态（SUCCESS/FAILED/TIMEOUT，仅表示对话执行状态，不用于软删除）
     */
    private String status;

    /**
     * 是否软删除（0=未删除，1=已删除），与 status 字段职责分离
     */
    private Integer isDeleted;

    /**
     * 失败原因（status=FAILED 时记录）
     */
    private String errorMsg;

    /**
     * 创建日期（格式：20260712）
     */
    private Long createDate;

    /**
     * 创建时间（精确到秒，用于趋势统计）
     */
    private LocalDateTime createTime;
}
