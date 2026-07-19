package com.zmbdp.portal.service.chat.controller;

import com.zmbdp.chat.api.chat.domain.dto.ChatReqDTO;
import com.zmbdp.chat.api.chat.domain.dto.ChatWithImageReqDTO;
import com.zmbdp.common.ratelimit.annotation.RateLimit;
import com.zmbdp.portal.service.chat.service.IChatPortalService;
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
 * C端对话入口 Controller
 * <p>
 * 作为 C端 AI 对话的统一入口，接收前端请求后委托给 {@link IChatPortalService}
 * 执行 8 步业务编排（RAG 检索 → Prompt 拼接 → WebClient 调用 chat-service SSE 端点 → 透传 Flux）。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code POST /chat/completions/stream}：流式文本对话</li>
 *     <li>{@code POST /chat/image/completions/stream}：流式图文对话</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /portal/chat/completions/stream} → gateway StripPrefix=1 → 本 Controller {@code /chat/completions/stream}
 * <p>
 * <b>认证</b>：需要 JWT Token（Gateway AuthFilter 校验），Controller 无需手动获取 token，
 * Service 内部通过 {@code tokenService.getLoginUser(secret)} 从当前请求自动提取。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatPortalController {

    /**
     * C端对话业务编排服务
     */
    @Autowired
    private IChatPortalService chatPortalService;

    /**
     * 流式文本对话（SSE）
     * <p>
     * 接收 C端用户文本对话请求，返回 SSE 流（每帧为 JSON 字符串）。
     * <p>
     * <b>SSE 帧类型</b>：内容帧（chunk）、结束帧（done=true）、错误帧（error）。
     * <p>
     * <b>限流</b>：通过 {@code @RateLimit} 注解限制单用户调用频率，防止恶意调用。
     *
     * @param request 文本对话请求（message、sessionId、model、temperature、topK）
     * @return SSE 流
     */
    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit
    public Flux<String> streamChat(@Validated @RequestBody ChatReqDTO request) {
        return chatPortalService.streamChat(request);
    }

    /**
     * 流式图文对话（SSE）
     * <p>
     * 接收 C端用户图文对话请求，使用视觉模型（默认取 Nacos 配置的默认视觉模型）返回 SSE 流。
     *
     * @param request 图文对话请求（message、images、sessionId、model、temperature、topK）
     * @return SSE 流
     */
    @PostMapping(value = "/image/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit
    public Flux<String> streamChatWithImage(@Validated @RequestBody ChatWithImageReqDTO request) {
        return chatPortalService.streamChatWithImage(request);
    }
}