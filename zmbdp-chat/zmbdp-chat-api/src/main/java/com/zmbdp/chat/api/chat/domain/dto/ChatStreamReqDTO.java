package com.zmbdp.chat.api.chat.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文本流式对话请求 DTO
 * <p>
 * portal-service 通过 WebClient 调用 chat-service 的 SSE 端点
 * {@code POST /chat/completions/stream} 时使用的请求体。
 * <p>
 * 注意：本 DTO 用于 chat-service 内部 SSE 端点，不通过 Feign 暴露
 * （Feign 不支持 Flux 流式返回）。
 *
 * @author 稚名不带撇
 */
@Data
public class ChatStreamReqDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户提问（必填）
     */
    @NotBlank(message = "消息不能为空")
    private String message;

    /**
     * 会话ID（可选，不传则新建会话）
     */
    private String sessionId;

    /**
     * 系统提示词（可选，由 portal-service 从 system-prompt.txt 加载后传入）
     * <p>
     * chat-service 会用 {@code SystemMessage} 包装后传给大模型，作为模型行为设定；
     * 若不传则不设置 SystemMessage（不推荐，会导致 AI 不知道自身身份和能力边界）。
     */
    private String systemPrompt;

    /**
     * 完整 Prompt（必填，含 RAG 上下文 + 历史 + 提问，由 portal-service 拼接）
     * <p>
     * 注意：不含 systemPrompt，systemPrompt 已拆出单独传递；
     * chat-service 会用 {@code UserMessage} 包装本字段后传给大模型。
     */
    @NotBlank(message = "Prompt不能为空")
    private String prompt;

    /**
     * 模型名称（可选，不传则使用 Nacos 配置的默认文本模型）
     */
    private String model;

    /**
     * 温度参数（可选，默认 0.7）
     */
    private Double temperature;

    /**
     * 用户ID（由 portal-service 从 JWT 中提取后传入，用于持久化对话记录）
     */
    private Long userId;

    /**
     * 用户来源（sys/app，由 portal-service 从 JWT 中提取后传入）
     */
    private String userFrom;
}
