package com.zmbdp.chat.service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * DashScope 视觉模型调用配置
 * <p>
 * 专门用于图文对话，通过 <b>DashScope OpenAI 兼容模式</b>端点调用视觉模型（如 qwen-vl-plus）。
 * <p>
 * <b>为什么不复用 Spring AI Alibaba 的 ChatClient？</b>
 * Spring AI Alibaba 1.0.0.2 的 {@code DashScopeChatModel} 存在多模态路由 bug：
 * 即使 {@link org.springframework.ai.chat.messages.UserMessage} 中携带了 {@link org.springframework.ai.content.Media}，
 * 它仍然把请求发到纯文本端点 {@code /api/v1/services/aigc/text-generation/generation}，
 * 该端点不识别图片，DashScope 返回 200 OK 但响应体为空。
 * <p>
 * <b>OpenAI 兼容模式</b>端点 {@code /compatible-mode/v1/chat/completions} 原生支持多模态消息
 * （{@code image_url} 类型），格式与 OpenAI 官方一致，多模态处理稳定可靠。
 *
 * @author 稚名不带撇
 */
@Configuration
public class DashScopeVisionConfig {

    /**
     * DashScope OpenAI 兼容模式基础 URL
     */
    public static final String OPENAI_COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * OpenAI 兼容模式下的聊天补全端点（相对路径）
     */
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    /**
     * DashScope API Key（从 Nacos 配置 {@code spring.ai.dashscope.api-key} 注入，与 Spring AI 共用同一个 Key）
     */
    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    /**
     * 构建专门用于视觉模型调用的 WebClient
     * <p>
     * 配置要点：
     * <ul>
     *     <li>Base URL 指向 DashScope OpenAI 兼容模式端点</li>
     *     <li>默认 Header 设置 {@code Authorization: Bearer <api-key>} 和 JSON Content-Type</li>
     *     <li>连接超时 30s，响应超时不限（SSE 流式响应需要长连接）</li>
     * </ul>
     *
     * @return 配置好的 WebClient 实例
     */
    @Bean("dashscopeVisionWebClient")
    public WebClient dashscopeVisionWebClient() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("spring.ai.dashscope.api-key 未配置，无法初始化 DashScope 视觉模型 WebClient");
        }

        // 连接池配置：图文对话并发量较低，使用默认连接池即可
        ConnectionProvider provider = ConnectionProvider.builder("dashscope-vision")
                .maxConnections(20)
                .maxIdleTime(Duration.ofSeconds(30))
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .responseTimeout(Duration.ofSeconds(120)) // 单次响应超时 120s（视觉模型处理图片较慢）
                .compress(true);

        return WebClient.builder()
                .baseUrl(OPENAI_COMPATIBLE_BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB 防止大响应溢出
                .build();
    }
}
