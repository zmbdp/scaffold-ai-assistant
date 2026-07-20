package com.zmbdp.chat.api.chat.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * C 端文本对话请求 DTO
 * <p>
 * 由 C 端用户通过 portal-service 发起对话时使用，portal-service 接收后执行业务编排：
 * ① Feign 调用 {@code ChatApi.retrieveContext()} 获取 RAG 上下文 →
 * ② 拼接完整 Prompt →
 * ③ 通过 WebClient 调用 chat-service 的 SSE 端点（构建 {@link ChatStreamReqDTO}）。
 * <p>
 * <b>与 {@link ChatStreamReqDTO} 的区别</b>：
 * <ul>
 *     <li>本 DTO 不含 {@code prompt} 字段（由 portal-service 负责拼接）</li>
 *     <li>本 DTO 不含 {@code userId}/{@code userFrom}（由 portal-service 从 JWT 解析后填入 {@link ChatStreamReqDTO}）</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@Data
public class ChatReqDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户提问内容（必填）
     */
    @NotBlank(message = "消息不能为空")
    private String message;

    /**
     * 会话ID（可选，不传则新建会话）
     */
    private String sessionId;

    /**
     * 指定模型（可选，不传则使用 Nacos 配置的默认文本模型）
     */
    private String model;

    /**
     * 温度参数（可选，0-2，默认 0.7）
     */
    private Double temperature;

    /**
     * RAG 检索数量（可选，1-20，默认 5）
     */
    private Integer topK;
}
