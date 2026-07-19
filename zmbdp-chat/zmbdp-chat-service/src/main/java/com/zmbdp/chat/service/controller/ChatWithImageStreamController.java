package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.chat.domain.dto.ChatWithImageStreamReqDTO;
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
 * 图文流式对话 SSE 端点 Controller（chat-service 内部端点）
 * <p>
 * 不实现 Feign 接口（Feign 不支持 Flux 返回），由 portal-service 通过
 * {@code @LoadBalanced} WebClient 调用 {@code lb://zmbdp-chat-service/chat/image/completions/stream}。
 * <p>
 * 使用视觉模型（默认取 Nacos 配置的默认视觉模型），支持多图输入，SSE 帧格式与 {@link ChatStreamController} 一致。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/chat/image")
public class ChatWithImageStreamController {

    /**
     * AI 对话核心服务
     */
    @Autowired
    private IChatService chatService;

    /**
     * 图文流式对话（SSE）
     * <p>
     * 调用链路：portal-service WebClient → 本端点 → IChatService.streamChatWithImage() → Spring AI ChatClient（视觉模型）
     *
     * @param request 图文流式对话请求（含用户提问、图片 Base64 列表、完整 Prompt）
     * @return SSE 流（每帧为 JSON 字符串，格式同文本流式）
     */
    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChatWithImage(@Validated @RequestBody ChatWithImageStreamReqDTO request) {
        log.info("接收到流式图文对话请求：sessionId = {}, userId = {}, imageCount = {}",
                request.getSessionId(), request.getUserId(),
                request.getImages() != null ? request.getImages().size() : 0);
        return chatService.streamChatWithImage(request);
    }
}