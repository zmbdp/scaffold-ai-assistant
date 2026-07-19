package com.zmbdp.portal.service.config;

import com.zmbdp.common.core.utils.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient 配置类（C端流式对话专用）
 * <p>
 * 为 portal-service 提供调用 chat-service SSE 端点所需的 WebClient.Builder。
 * <p>
 * <b>核心能力</b>：
 * <ul>
 *     <li>{@code @LoadBalanced}：通过 Nacos 服务发现解析 {@code lb://zmbdp-chat-service} 协议，
 *     不硬编码 IP/端口</li>
 *     <li>{@code traceIdExchangeFilter}：从 MDC 获取 traceId 写入 {@code X-Trace-Id} 请求头，
 *     与 {@code FeignTraceInterceptor} 模式保持一致，实现跨服务链路追踪</li>
 *     <li>16MB 内存缓冲上限：避免大响应（如多图回答）触发 DataBufferLimitException</li>
 *     <li>默认 Content-Type: application/json：与 chat-service SSE 端点契约一致</li>
 * </ul>
 * <p>
 * <b>使用方式</b>：
 * <pre>{@code
 * @Autowired
 * private WebClient.Builder chatWebClientBuilder;
 *
 * public Flux<String> callChatSse(ChatStreamReqDTO req) {
 *     return chatWebClientBuilder.build()
 *         .post()
 *         .uri("lb://zmbdp-chat-service/chat/completions/stream")
 *         .accept(MediaType.TEXT_EVENT_STREAM)
 *         .bodyValue(req)
 *         .retrieve()
 *         .bodyToFlux(String.class);
 * }
 * }</pre>
 *
 * @author 稚名不带撇
 * @see com.zmbdp.common.log.interceptor.FeignTraceInterceptor
 */
@Slf4j
@Configuration
public class WebClientConfig {

    /**
     * TraceId 请求头名称
     * <p>
     * 与 {@code FeignTraceInterceptor#TRACE_ID_HEADER} 保持一致，
     * chat-service 侧的 TraceIdFilter 从该 Header 提取 traceId 写入 MDC。
     */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * MDC 中 traceId 的键名
     * <p>
     * 与 {@code FeignTraceInterceptor#TRACE_ID_MDC_KEY} 保持一致，
     * 由网关层或 TraceIdFilter 在请求进入时设置到 MDC 中。
     */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    /**
     * WebClient 内存缓冲上限（16MB）
     * <p>
     * 默认 256KB 不足以承载 AI 流式回答中的单帧数据（图文回答含 Base64），
     * 提升到 16MB 兼顾大响应和内存占用。
     */
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    /**
     * C端流式对话专用的 WebClient.Builder
     * <p>
     * <b>关键点</b>：
     * <ul>
     *     <li>{@code @LoadBalanced} 启用服务发现，支持 {@code lb://} 协议</li>
     *     <li>默认 Content-Type: application/json</li>
     *     <li>16MB 内存缓冲上限</li>
     *     <li>挂载 traceIdExchangeFilter，自动传递链路追踪标识</li>
     * </ul>
     * <p>
     * <b>使用注意</b>：不要在 Builder 上直接设置 baseUrl，
     * 因为不同 SSE 端点路径不同（{@code /chat/completions/stream} 和 {@code /chat/image/completions/stream}），
     * 应在每次调用时通过 {@code .uri("lb://zmbdp-chat-service/...")} 指定完整 URL。
     *
     * @return 负载均衡的 WebClient.Builder
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder chatWebClientBuilder() {
        return WebClient.builder()
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
            .filter(traceIdExchangeFilter());
    }

    /**
     * TraceId 传递过滤器
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *     <li>从 MDC 获取当前线程的 traceId</li>
     *     <li>traceId 非空时，将其写入 {@code X-Trace-Id} 请求头</li>
     *     <li>traceId 为空时（理论上不应发生，网关层已设置），不添加 Header，仅记录警告</li>
     *     <li>放行请求到下一处理器</li>
     * </ol>
     * <p>
     * <b>注意事项</b>：
     * <ul>
     *     <li>WebClient 在 Reactor 线程池执行，MDC 是 ThreadLocal 的，需注意线程切换问题；
     *     但本系统在调用 WebClient 时尚未切换线程（Controller 仍是 Servlet 线程），MDC 可正常读取</li>
     *     <li>如果未来调整为纯 Reactive 栈，需改用 Reactor Context 传递 traceId</li>
     *     <li>异常不影响请求放行，只记录错误日志</li>
     * </ul>
     *
     * @return ExchangeFilterFunction 实例
     */
    @Bean
    public ExchangeFilterFunction traceIdExchangeFilter() {
        return (request, next) -> {
            try {
                String traceId = MDC.get(TRACE_ID_MDC_KEY);
                if (StringUtil.isNotBlank(traceId)) {
                    // Spring 6.x 中 ClientRequest 使用 from(request) 而非 mutate() 修改请求
                    ClientRequest enhancedRequest = ClientRequest.from(request)
                        .header(TRACE_ID_HEADER, traceId)
                        .build();
                    return next.exchange(enhancedRequest);
                }
                log.warn("MDC 中没有 traceId，无法传递到 chat-service，目标端点: {}", request.url());
            } catch (Exception e) {
                // MDC 读取异常不影响请求放行，仅记录错误日志
                log.error("WebClient 拦截器添加 traceId 失败: {}", e.getMessage(), e);
            }
            return next.exchange(request);
        };
    }
}