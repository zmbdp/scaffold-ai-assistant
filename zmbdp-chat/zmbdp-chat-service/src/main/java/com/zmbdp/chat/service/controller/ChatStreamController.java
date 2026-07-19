package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.chat.domain.dto.ChatStreamReqDTO;
import com.zmbdp.chat.service.service.IChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 文本流式对话 SSE 端点 Controller（chat-service 内部端点）
 * <p>
 * 不实现 Feign 接口（Feign 不支持 Flux 返回），由 portal-service 通过
 * {@code @LoadBalanced} WebClient 调用 {@code lb://zmbdp-chat-service/chat/completions/stream}。
 * <p>
 * <b>SSE 帧类型</b>：
 * <ul>
 *     <li>内容帧（chunk）：AI 生成的回答内容片段</li>
 *     <li>工具调用开始帧（toolCall）：AI 决定调用工具时发送</li>
 *     <li>工具调用结果帧（toolResult）：工具执行完成后发送</li>
 *     <li>结束帧（done=true）：流结束，携带 sessionId、sources、model 等元数据</li>
 *     <li>错误帧（error）：异常时发送</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatStreamController {

    /**
     * AI 对话核心服务
     */
    @Autowired
    private IChatService chatService;

    /**
     * 文本流式对话（SSE）
     * <p>
     * 调用链路：portal-service WebClient → 本端点 → IChatService.streamChat() → Spring AI ChatClient
     *
     * @param request 流式对话请求（含完整 Prompt、sessionId、模型参数）
     * @return SSE 流（每帧为 JSON 字符串，如 {@code {"chunk":"回答片段","done":false}}）
     */
    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@Validated @RequestBody ChatStreamReqDTO request) {
        log.info("接收到流式文本对话请求：sessionId = {}, userId = {}", request.getSessionId(), request.getUserId());
        return chatService.streamChat(request);
    }
}