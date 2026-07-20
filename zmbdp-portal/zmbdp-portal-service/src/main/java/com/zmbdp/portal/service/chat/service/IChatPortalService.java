package com.zmbdp.portal.service.chat.service;

import com.zmbdp.chat.api.chat.domain.dto.ChatReqDTO;
import com.zmbdp.chat.api.chat.domain.dto.ChatWithImageReqDTO;
import reactor.core.publisher.Flux;

/**
 * C端对话业务编排服务
 * <p>
 * portal-service 的核心服务，负责 C端 AI 对话的业务编排：
 * <ol>
 *     <li>解析 JWT Token，提取 userId、username、userFrom</li>
 *     <li>生成或复用 sessionId</li>
 *     <li>RAG 检索（Feign 调用 {@code ChatApi.retrieveContext()}）</li>
 *     <li>拼接完整 Prompt（System Prompt + 文档上下文 + 对话历史 + 用户提问）</li>
 *     <li>构建 {@link com.zmbdp.chat.api.chat.domain.dto.ChatStreamReqDTO}</li>
 *     <li>WebClient 调用 chat-service SSE 端点</li>
 *     <li>透传 {@code Flux<String>} 给前端</li>
 *     <li>流结束后异步保存对话历史（由 chat-service 内部处理，portal-service 不重复保存）</li>
 * </ol>
 * <p>
 *
 * @author 稚名不带撇
 */
public interface IChatPortalService {

    /**
     * 流式文本对话
     * <p>
     * 执行 8 步业务编排，
     * 返回 SSE 流（每帧为 JSON 字符串）。
     * <p>
     * <b>SSE 帧类型</b>：内容帧（chunk）、结束帧（done=true）、错误帧（error）。
     *
     * @param request C端文本对话请求（message、sessionId、model、temperature、topK）
     * @return SSE 流（每帧为 JSON 字符串）
     */
    Flux<String> streamChat(ChatReqDTO request);

    /**
     * 流式图文对话
     * <p>
     * 使用视觉模型（默认取 Nacos 配置的默认视觉模型），支持多图输入。
     * 业务编排流程与 {@link #streamChat} 一致，仅请求体和 SSE 端点不同。
     *
     * @param request C端图文对话请求（message、images、sessionId、model、temperature、topK）
     * @return SSE 流（每帧为 JSON 字符串）
     */
    Flux<String> streamChatWithImage(ChatWithImageReqDTO request);
}
