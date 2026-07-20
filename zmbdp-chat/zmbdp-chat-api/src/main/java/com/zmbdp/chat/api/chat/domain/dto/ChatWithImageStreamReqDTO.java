package com.zmbdp.chat.api.chat.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 图文流式对话请求 DTO
 * <p>
 * portal-service 通过 WebClient 调用 chat-service 的 SSE 端点
 * {@code POST /chat/image/completions/stream} 时使用的请求体。
 * <p>
 * 注意：本 DTO 用于 chat-service 内部 SSE 端点，不通过 Feign 暴露
 * （Feign 不支持 Flux 流式返回）。
 *
 * @author 稚名不带撇
 */
@Data
public class ChatWithImageStreamReqDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户提问（必填）
     */
    @NotBlank(message = "消息不能为空")
    private String message;

    /**
     * 图片 URL 列表（必填，支持多图）
     * <p>
     * 前端通过 file 服务获取签名后直传 OSS，再将 URL 传给后端；
     * 后端不接收也不存储图片二进制内容，数据库仅存 URL 列表（JSON 数组）。
     */
    @NotEmpty(message = "图片不能为空")
    private List<String> images;

    /**
     * 会话ID（可选，不传则新建会话）
     */
    private String sessionId;

    /**
     * 系统提示词（可选，由 portal-service 从 system-prompt.txt 加载后传入）
     * <p>
     * chat-service 会用 {@code SystemMessage} 包装后传给大模型，作为模型行为设定。
     */
    private String systemPrompt;

    /**
     * 完整 Prompt（必填，含上下文，不含 systemPrompt）
     */
    @NotBlank(message = "Prompt不能为空")
    private String prompt;

    /**
     * 模型名称（可选，不传则使用 Nacos 配置的默认视觉模型）
     */
    private String model;

    /**
     * 用户ID（由 portal-service 从 JWT 中提取后传入，用于持久化对话记录）
     */
    private Long userId;

    /**
     * 用户来源（sys/app，由 portal-service 从 JWT 中提取后传入）
     */
    private String userFrom;
}
