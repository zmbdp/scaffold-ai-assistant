package com.zmbdp.chat.service.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zmbdp.admin.api.config.domain.dto.ArgumentDTO;
import com.zmbdp.admin.api.config.feign.ArgumentServiceApi;
import com.zmbdp.chat.api.chat.domain.dto.ChatStreamReqDTO;
import com.zmbdp.chat.api.chat.domain.dto.ChatWithImageStreamReqDTO;
import com.zmbdp.chat.service.config.DashScopeVisionConfig;
import com.zmbdp.chat.service.config.ModelConfig;
import com.zmbdp.chat.service.domain.entity.SysAiConversation;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import com.zmbdp.chat.service.mapper.SysAiOperationLogMapper;
import com.zmbdp.chat.service.service.IChatMemoryService;
import com.zmbdp.chat.service.service.IChatService;
import com.zmbdp.chat.service.service.IHistoryService;
import com.zmbdp.chat.service.service.IModelService;
import com.zmbdp.chat.service.service.ToolRegistryService;
import com.zmbdp.chat.service.tool.ToolCallRecorder;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.snowflake.service.SnowflakeIdService;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 对话核心服务实现类
 * <p>
 * 负责流式调用大模型、工具调用编排、对话记忆管理和对话记录持久化。
 * <p>
 * <b>仅负责流式对话</b>（返回 {@code Flux<String>}），不提供同步对话方法；
 * 同步 RAG 检索由 {@code ChatController.retrieveContext()} 直接委托给 {@code IVectorStoreService}。
 * <p>
 * <b>Prompt 拼接</b>由 portal-service 的 {@code IChatPortalService} 完成，
 * chat-service 接收已拼接好的 {@code prompt}。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class ChatServiceImpl implements IChatService {

    /**
     * 对话状态：成功
     */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /**
     * 对话状态：失败
     */
    private static final String STATUS_FAILED = "FAILED";

    /**
     * 默认温度参数
     */
    private static final double DEFAULT_TEMPERATURE = 0.7;

    /**
     * 日期格式化器（YYYYMMDD，与脚手架统一）
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * sys_argument 表中是否启用工具调用的 configKey
     */
    private static final String CONFIG_KEY_ENABLE_TOOLS = "ai.enable_tools";

    /**
     * 启用状态值（sys_argument 表 value 字段，与 db.sql 预置数据一致）
     */
    private static final String ENABLED_VALUE = "true";

    /**
     * 模型管理服务
     */
    @Autowired
    private IModelService modelService;

    /**
     * 对话记忆服务
     */
    @Autowired
    private IChatMemoryService chatMemoryService;

    /**
     * 对话历史服务
     */
    @Autowired
    private IHistoryService historyService;

    /**
     * AI 调用链路日志 mapper（写入 sys_ai_operation_log 表）
     * <p>
     * 在流式对话完成后异步写入，用于 AI 调用统计、用户使用量查询、调用链路追溯。
     * <p>
     * <b>已支持字段</b>：promptTokens / completionTokens / totalTokens（来自 Spring AI Usage）、
     * toolCalls（由 {@link ToolCallRecorder} 装饰器收集工具调用链路）。
     */
    @Autowired
    private SysAiOperationLogMapper sysAiOperationLogMapper;

    /**
     * Agent 工具注册服务
     */
    @Autowired
    private ToolRegistryService toolRegistryService;

    /**
     * 参数服务远程调用 Api（Feign，查 sys_argument 表的 ai.enable_tools 等运行时配置）
     */
    @Autowired
    private ArgumentServiceApi argumentServiceApi;

    /**
     * 雪花 ID 生成服务
     */
    @Autowired
    private SnowflakeIdService snowflakeIdService;

    /**
     * DashScope 视觉模型专用 WebClient（OpenAI 兼容模式）
     * <p>
     * 用于图文对话，绕过 Spring AI Alibaba 1.0.0.2 的多模态路由 bug。
     * 配置详见 {@link DashScopeVisionConfig}。
     */
    @Autowired
    @Qualifier("dashscopeVisionWebClient")
    private WebClient dashscopeVisionWebClient;

    /**
     * JSON 序列化器（用于构建 DashScope OpenAI 兼容模式请求体和解析 SSE 响应）
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /*=============================================    内部调用    =============================================*/

    /**
     * 流式文本对话
     * <p>
     * 执行流程（8 步）：
     * <ol>
     *     <li>解析参数，确定使用的模型（ModelService.getChatClient）</li>
     *     <li>获取对话历史（ChatMemoryService.getHistory）</li>
     *     <li>构建消息列表（历史消息 + 当前用户消息）</li>
     *     <li>判断是否需要工具调用（ToolRegistryService 获取已启用工具）</li>
     *     <li>调用 ChatClient.stream() 流式生成</li>
     *     <li>流式输出完成后，异步保存对话历史到 Redis</li>
     *     <li>记录对话到 MySQL（sys_ai_conversation 表）</li>
     *     <li>如果使用了工具，记录 tool_usage 信息</li>
     * </ol>
     * <p>
     * <b>SSE 帧类型</b>：内容帧（chunk）、工具调用开始帧（toolCall）、工具调用结果帧（toolResult）、
     * 结束帧（done=true）、错误帧（error）。
     *
     * @param request 流式对话请求（含完整 Prompt、sessionId、模型参数）
     * @return SSE 流（每帧为 JSON 字符串）
     */
    @Override
    public Flux<String> streamChat(ChatStreamReqDTO request) {
        long startTime = System.currentTimeMillis();
        // Step 1: 确定使用的模型（用户指定模型名则使用指定模型，否则使用默认文本模型）
        ModelConfig modelConfig = modelService.selectModel(request);
        String modelName = modelConfig.getName();
        ChatClient chatClient = modelService.getChatClient(modelName);
        // 生成或复用 sessionId
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : String.valueOf(snowflakeIdService.nextId());
        log.info("开始流式文本对话：sessionId = {}, model = {}, userId = {}", sessionId, modelName, request.getUserId());

        // Step 2: 获取对话历史
        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(request.getSessionId())) {
            try {
                List<Message> history = chatMemoryService.getHistory(sessionId);
                if (history != null && !history.isEmpty()) {
                    messages.addAll(history);
                }
            } catch (Exception e) {
                log.warn("获取对话历史失败，继续无历史对话：sessionId = {}, error = {}", sessionId, e.getMessage());
            }
        }

        // Step 3: 构建 SystemMessage（身份设定）+ UserMessage（RAG 上下文 + 历史 + 提问）
        // portal-service 已将 systemPrompt 单独拆出，这里用 SystemMessage 包装让大模型识别为身份设定，
        // 否则 systemPrompt 会被当成"用户的奇怪输入"导致 AI 不知道自身身份（如脚手架名称）
        if (StringUtils.hasText(request.getSystemPrompt())) {
            messages.add(new SystemMessage(request.getSystemPrompt()));
        }

        // 当前用户消息（含 RAG 上下文 + 历史摘要 + 用户提问，由 portal-service 拼接）
        messages.add(new UserMessage(request.getPrompt()));

        // Step 4: 判断是否需要工具调用
        // 通过 Feign 查询 sys_argument 表的 ai.enable_tools 配置项（运行时可调，B 端可动态调整）
        // 若 Feign 调用失败或配置项不存在，默认启用工具调用（与 db.sql 预置数据 ai.enable_tools=true 一致）
        List<Object> enabledToolBeans = resolveEnabledToolBeans(sessionId);

        // Step 5: 调用 ChatClient.stream() 流式生成
        StringBuilder fullResponse = new StringBuilder();
        final String finalModelName = modelName;
        // Usage 持有器：DashScope 在流式响应最后一帧返回 usage（累计值），每个 ChatResponse 都检查并覆盖
        // 使用 AtomicReference 是因为 lambda 闭包要求 effectively final，且 reactor 链路可能跨线程切换
        final AtomicReference<Usage> usageRef = new AtomicReference<>();
        // 工具调用记录持有器：ToolCallRecorder 装饰器在工具被调用时记录 name/args/result/duration/success，
        // doOnComplete 时 drainRecords 取出 JSON 数组写入 sys_ai_operation_log.tool_calls 字段
        final List<ToolCallRecorder> toolRecorders = new ArrayList<>();
        // 显式指定模型名，覆盖 DashScopeAutoConfiguration 中 spring.ai.dashscope.chat.model 的默认值
        // 这样修改 spring.ai.models[*].name 才能真正切换模型，而不是被默认 model 固定
        DashScopeChatOptions chatOptions = DashScopeChatOptions.builder()
                .withModel(modelName)
                .build();
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                .options(chatOptions)
                .messages(messages);
        if (!CollectionUtils.isEmpty(enabledToolBeans)) {
            // 将工具 Bean 转为 ToolCallback 数组，用 ToolCallRecorder 装饰器包装以记录调用链路
            // 用 toolCallbacks(ToolCallback...) 替代 tools(Object...)：前者显式传入 callback 实例，
            // Spring AI 不再自动扫描 @Tool 注解，而是直接调用传入的 callback（含装饰器逻辑）
            ToolCallback[] originalCallbacks = ToolCallbacks.from(enabledToolBeans.toArray());
            List<ToolCallback> wrappedCallbacks = new ArrayList<>(originalCallbacks.length);
            for (ToolCallback original : originalCallbacks) {
                ToolCallRecorder recorder = ToolCallRecorder.wrap(original);
                toolRecorders.add(recorder);
                wrappedCallbacks.add(recorder);
            }
            requestSpec = requestSpec.toolCallbacks(wrappedCallbacks.toArray(new ToolCallback[0]));
        }
        // 用 .chatResponse() 替代 .content()：前者返回 Flux<ChatResponse> 含 metadata.usage，后者只返回字符串丢弃 Usage
        return requestSpec.stream()
                .chatResponse()
                .doOnNext(chatResponse -> {
                    // 累积响应内容
                    String content = chatResponse.getResult().getOutput().getText();
                    if (content != null) {
                        fullResponse.append(content);
                    }
                    // 捕获 Usage（最后一帧才真正含 usage，前面的帧 usage 可能为 null，覆盖即可）
                    Usage usage = chatResponse.getMetadata().getUsage();
                    if (usage != null) {
                        usageRef.set(usage);
                    }
                })
                .map(chatResponse -> buildContentFrame(chatResponse.getResult().getOutput().getText()))
                .doOnComplete(() -> {
                    // Step 6-8: 异步保存对话历史到 Redis + 记录到 MySQL
                    long responseTime = System.currentTimeMillis() - startTime;
                    Usage usage = usageRef.get();
                    // 收集本次对话的工具调用记录（JSON 数组字符串，无调用时为 null）
                    String toolCallsJson = drainToolCallRecords(toolRecorders);
                    CompletableFuture.runAsync(() -> saveConversation(
                            request, sessionId, fullResponse.toString(),
                            finalModelName, responseTime, STATUS_SUCCESS, null, usage, toolCallsJson));
                })
                .onErrorResume(e -> {
                    // 错误处理：返回错误帧
                    long responseTime = System.currentTimeMillis() - startTime;
                    String errorMsg = e.getMessage();
                    log.error("流式对话失败：sessionId = {}, error = {}", sessionId, errorMsg, e);
                    // 异步保存失败记录（失败时无 Usage）
                    String toolCallsJson = drainToolCallRecords(toolRecorders);
                    CompletableFuture.runAsync(() -> saveConversation(
                            request, sessionId, fullResponse.toString(),
                            finalModelName, responseTime, STATUS_FAILED, errorMsg, null, toolCallsJson));
                    return Flux.just(buildErrorFrame(ResultCode.AI_SERVICE_CONNECT_FAILED));
                })
                .concatWith(Flux.just(buildEndFrame(sessionId, finalModelName)));
    }

    /**
     * 流式图文对话
     * <p>
     * 使用视觉模型（默认取 Nacos 配置的默认视觉模型），支持多图输入。
     * <p>
     * <b>实现方式</b>：通过 WebClient 直接调用 <b>DashScope OpenAI 兼容模式</b>端点
     * {@code /compatible-mode/v1/chat/completions}，请求体采用标准 OpenAI 多模态格式。
     * <p>
     * <b>为什么不复用 Spring AI Alibaba 的 ChatClient？</b>
     * Spring AI Alibaba 1.0.0.2 的 {@code DashScopeChatModel} 存在多模态路由 bug：
     * 即使 {@link UserMessage} 中携带了 {@code Media}，它仍把请求发到纯文本端点
     * {@code /api/v1/services/aigc/text-generation/generation}，该端点不识别图片，
     * DashScope 返回 200 OK 但响应体为空。
     * <p>
     * <b>模型切换</b>：在 Nacos 配置 {@code spring.ai.models} 中修改默认视觉模型即可，
     * 无需改代码。支持 qwen-vl-plus、qwen-vl-plus、qwen3.5-omni-plus 等所有 DashScope 视觉/全模态模型。
     *
     * @param request 图文流式对话请求（含用户提问、图片 URL 列表、完整 Prompt）
     * @return SSE 流（每帧为 JSON 字符串）
     */
    @Override
    public Flux<String> streamChatWithImage(ChatWithImageStreamReqDTO request) {
        long startTime = System.currentTimeMillis();
        // Step 1: 确定使用的视觉模型（用户未指定时从 Nacos 配置读取默认视觉模型）
        String defaultVisionModel = modelService.getDefaultVisionModelName();
        String modelName = StringUtils.hasText(request.getModel())
                ? request.getModel()
                : (defaultVisionModel != null ? defaultVisionModel : "");
        if (modelName.isEmpty()) {
            log.error("未配置默认视觉模型，且请求未指定 model，无法进行图文对话");
            return Flux.just(buildErrorFrame(ResultCode.AI_SERVICE_CONNECT_FAILED));
        }
        // 生成或复用 sessionId
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : String.valueOf(snowflakeIdService.nextId());
        log.info("开始流式图文对话：sessionId = {}, model = {}, userId = {}, imageCount = {}",
                sessionId, modelName, request.getUserId(),
                request.getImages() != null ? request.getImages().size() : 0);

        // Step 2: 获取对话历史（过滤掉空 content 的 AssistantMessage，避免污染上下文）
        // 历史消息仅保留文本部分，图片不回传给模型（多模态历史会显著增加 token 消耗）
        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(request.getSessionId())) {
            try {
                List<Message> history = chatMemoryService.getHistory(sessionId);
                if (history != null && !history.isEmpty()) {
                    for (Message msg : history) {
                        if (msg instanceof org.springframework.ai.chat.messages.AssistantMessage
                                && !StringUtils.hasText(msg.getText())) {
                            log.warn("过滤掉空 content 的 AssistantMessage：sessionId = {}", sessionId);
                            continue;
                        }
                        messages.add(msg);
                    }
                }
            } catch (Exception e) {
                log.warn("获取对话历史失败，继续无历史对话：sessionId = {}, error = {}", sessionId, e.getMessage());
            }
        }

        // Step 3: 构建 SystemMessage（身份设定）+ UserMessage（RAG 上下文 + 提问 + 图片）
        if (StringUtils.hasText(request.getSystemPrompt())) {
            messages.add(new SystemMessage(request.getSystemPrompt()));
        }

        // 清洗图片 URL 列表（去除反引号、引号、逗号等 Markdown 残留字符）
        List<String> imageUrls = new ArrayList<>();
        if (request.getImages() != null) {
            for (String rawUrl : request.getImages()) {
                String url = sanitizeImageUrl(rawUrl);
                if (url == null) {
                    log.warn("图片 URL 清洗后无效，跳过：rawUrl = {}", rawUrl);
                    continue;
                }
                imageUrls.add(url);
                log.info("图片加入请求：url = {}", url);
            }
        }

        // 构建 OpenAI 兼容格式的请求体
        ObjectNode requestBody = buildOpenAiCompatibleRequest(modelName, request, messages, imageUrls);

        // 诊断日志：打印请求概要
        log.info("[图文对话诊断] 准备调用 DashScope OpenAI 兼容模式：sessionId = {}, model = {}, 消息数 = {}, 含图片数 = {}, prompt长度 = {}",
                sessionId, modelName, messages.size() + 1, imageUrls.size(),
                request.getPrompt() != null ? request.getPrompt().length() : 0);

        // Step 4: 调用 DashScope OpenAI 兼容模式端点（流式）
        StringBuilder fullResponse = new StringBuilder();
        final String finalModelName = modelName;
        // Usage 持有器：长度 3 的 long 数组，分别对应 promptTokens / completionTokens / totalTokens
        // DashScope OpenAI 兼容模式在流式响应最后一帧返回 usage（累计值），每帧都尝试解析并覆盖
        final AtomicReference<long[]> usageRef = new AtomicReference<>();

        return dashscopeVisionWebClient.post()
                .uri(DashScopeVisionConfig.CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .doOnEach(signal -> {
                    if (signal.isOnNext()) {
                        String event = signal.get();
                        log.debug("[图文对话诊断] 收到 SSE 事件：sessionId = {}, data = {}",
                                sessionId, event);
                        // 尝试解析 usage（仅最后一帧含 usage，前面的帧解析不到会返回 null，自然跳过覆盖）
                        long[] usage = extractUsage(event, sessionId);
                        if (usage != null) {
                            usageRef.set(usage);
                        }
                    } else if (signal.isOnError()) {
                        Throwable t = signal.getThrowable();
                        log.error("[图文对话诊断] 流异常：sessionId = {}, errorType = {}, errorMsg = {}",
                                sessionId,
                                t != null ? t.getClass().getName() : "null",
                                t != null ? t.getMessage() : "null",
                                t);
                    } else if (signal.isOnComplete()) {
                        log.info("[图文对话诊断] 流完成：sessionId = {}, 累计响应长度 = {}",
                                sessionId, fullResponse.length());
                    }
                })
                .filter(event -> event != null && !event.isEmpty() && !"[DONE]".equals(event.trim()))
                .map(event -> extractDeltaContent(event, sessionId))
                .filter(chunk -> chunk != null && !chunk.isEmpty())
                .doOnNext(fullResponse::append)
                .map(this::buildContentFrame)
                .doOnComplete(() -> {
                    long responseTime = System.currentTimeMillis() - startTime;
                    String status = fullResponse.isEmpty() ? STATUS_FAILED : STATUS_SUCCESS;
                    String errMsg = fullResponse.isEmpty() ? "AI 返回空响应" : null;
                    if (fullResponse.isEmpty()) {
                        log.warn("检测到空响应，状态从 SUCCESS 改为 FAILED：sessionId = {}, model = {}, responseTime = {}ms",
                                sessionId, finalModelName, responseTime);
                    }
                    long[] usage = usageRef.get();
                    CompletableFuture.runAsync(() -> saveImageConversation(
                            request, sessionId, fullResponse.toString(),
                            finalModelName, responseTime, status, errMsg, usage));
                })
                .onErrorResume(e -> {
                    long responseTime = System.currentTimeMillis() - startTime;
                    String errorMsg = extractErrorMessage(e);
                    log.error("流式图文对话失败：sessionId = {}, model = {}, errorType = {}, error = {}",
                            sessionId, finalModelName, e.getClass().getName(), errorMsg, e);
                    // 失败时无 Usage，传 null
                    CompletableFuture.runAsync(() -> saveImageConversation(
                            request, sessionId, fullResponse.toString(),
                            finalModelName, responseTime, STATUS_FAILED, errorMsg, null));
                    return Flux.just(buildErrorFrame(ResultCode.AI_SERVICE_CONNECT_FAILED));
                })
                .concatWith(Flux.just(buildEndFrame(sessionId, finalModelName)));
    }

    /**
     * 构建 DashScope OpenAI 兼容模式的请求体
     * <p>
     * 格式参考 OpenAI Chat Completions API：
     * <pre>{@code
     * {
     *   "model": "qwen-vl-plus",
     *   "messages": [
     *     {"role": "system", "content": "系统提示词"},
     *     {"role": "user", "content": "历史用户提问"},
     *     {"role": "assistant", "content": "历史 AI 回答"},
     *     {
     *       "role": "user",
     *       "content": [
     *         {"type": "text", "text": "当前用户提问"},
     *         {"type": "image_url", "image_url": {"url": "https://..."}}
     *       ]
     *     }
     *   ],
     *   "stream": true,
     *   "temperature": 0.7
     * }
     * }</pre>
     * <p>
     * <b>多模态消息规则</b>：只有最后一条 user 消息（当前提问）使用数组形式的 content（含 text 和 image_url），
     * 历史消息一律用纯字符串 content，避免历史图片重复发送导致 token 暴涨。
     *
     * @param modelName  视觉模型名（如 qwen-vl-plus）
     * @param request    图文对话请求（取 prompt 和 temperature）
     * @param historyMsg 历史消息列表（已过滤空 AssistantMessage）
     * @param imageUrls  清洗后的图片 URL 列表
     * @return OpenAI 兼容格式的请求体 JSON
     */
    private ObjectNode buildOpenAiCompatibleRequest(String modelName, ChatWithImageStreamReqDTO request,
                                                    List<Message> historyMsg, List<String> imageUrls) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelName);
        body.put("stream", true);
        // stream_options.include_usage=true：要求 DashScope 在流式响应最后一帧返回 usage（Token 消耗）
        // OpenAI 兼容协议默认不返回 usage，不加此参数 extractUsage 永远解析不到 Token
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);
        // 图文对话 DTO 无 temperature 字段，使用默认温度（与纯文本对话保持一致）
        body.put("temperature", DEFAULT_TEMPERATURE);

        ArrayNode messagesArray = body.putArray("messages");

        // 1. 添加历史消息（纯字符串 content）
        for (Message msg : historyMsg) {
            ObjectNode msgNode = messagesArray.addObject();
            String role;
            String text = msg.getText() != null ? msg.getText() : "";
            switch (msg) {
                case SystemMessage systemMessage -> role = "system";
                case UserMessage userMessage -> role = "user";
                case org.springframework.ai.chat.messages.AssistantMessage assistantMessage -> role = "assistant";
                default -> {
                    // 跳过未知类型的消息
                    continue;
                }
            }
            msgNode.put("role", role);
            msgNode.put("content", text);
        }

        // 2. 添加当前用户消息（多模态数组 content：text + image_url）
        ObjectNode currentUserMsg = messagesArray.addObject();
        currentUserMsg.put("role", "user");
        ArrayNode contentArray = currentUserMsg.putArray("content");

        // 文本部分（prompt 已由 portal-service 拼接 RAG 上下文 + 用户提问）
        ObjectNode textPart = contentArray.addObject();
        textPart.put("type", "text");
        textPart.put("text", request.getPrompt() != null ? request.getPrompt() : "");

        // 图片部分（URL 形式，DashScope 服务端直接拉取，无需 base64）
        for (String url : imageUrls) {
            ObjectNode imagePart = contentArray.addObject();
            imagePart.put("type", "image_url");
            ObjectNode imageUrlObj = imagePart.putObject("image_url");
            imageUrlObj.put("url", url);
        }

        return body;
    }

    /**
     * 从 DashScope OpenAI 兼容模式的 SSE 事件中提取增量内容
     * <p>
     * SSE 事件格式（每个事件是一个 JSON 字符串）：
     * <pre>{@code
     * {"id":"...","choices":[{"index":0,"delta":{"content":"回答片段"},"finish_reason":null}]}
     * }</pre>
     * <p>
     * 当 {@code choices[0].delta.content} 不存在或为 null 时返回空字符串（如首个事件只含 role，或最后一帧只含 usage），
     * 由调用方的 {@code .filter(chunk -> !chunk.isEmpty())} 过滤掉。
     * <p>
     * 当 {@code choices[0].finish_reason} 非空（如 "stop"）时，表示流即将结束，也返回空字符串。
     * <p>
     * <b>注意</b>：本方法不返回 null，因为 Reactor {@code .map()} 操作符不允许 mapper 返回 null，
     * 否则会抛出 {@code NullPointerException: The mapper returned a null value}。
     *
     * @param event    SSE 事件原始字符串
     * @param sessionId 会话ID（仅用于日志）
     * @return 增量内容片段；无内容时返回空字符串（非 null）
     */
    private String extractDeltaContent(String event, String sessionId) {
        try {
            JsonNode root = objectMapper.readTree(event);
            // 检查是否为错误事件（DashScope 在流中也可能返回错误）
            JsonNode errorNode = root.path("error");
            if (errorNode.isObject()) {
                String errorMsg = errorNode.path("message").asText("未知错误");
                log.error("[图文对话] DashScope 流内返回错误：sessionId = {}, error = {}", sessionId, errorMsg);
                throw new ServiceException("DashScope 流内错误：" + errorMsg);
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                // 返回空字符串而非 null：Reactor .map() 不允许返回 null，否则会抛 NullPointerException
                // 后续 .filter(chunk -> !chunk.isEmpty()) 会过滤掉空字符串
                return "";
            }
            JsonNode delta = choices.get(0).path("delta");
            JsonNode content = delta.path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                return content.asText();
            }
            return "";
        } catch (RuntimeException e) {
            // 流内错误向上抛，触发 onErrorResume
            throw e;
        } catch (Exception e) {
            log.warn("[图文对话] 解析 SSE 事件失败，跳过：sessionId = {}, event = {}, error = {}",
                    sessionId, event, e.getMessage());
            return "";
        }
    }

    /**
     * 从 DashScope OpenAI 兼容模式的 SSE 事件中解析 usage 字段
     * <p>
     * DashScope 流式响应中，<b>只有最后一个 chunk 含 usage 字段</b>（累计值，非增量），
     * 前面的 chunk 不含 usage（JSON 解析时 path("usage") 会返回 MissingNode）。
     * <p>
     * usage 字段格式：
     * <pre>{@code
     * "usage": {
     *   "prompt_tokens": 50,
     *   "completion_tokens": 100,
     *   "total_tokens": 150
     * }
     * }</pre>
     * <p>
     * 调用方在每帧都尝试调用本方法，解析失败或无 usage 字段时返回 null，自然跳过覆盖；
     * 只有最后一帧能真正解析到非 null 值，覆盖到 AtomicReference 中。
     *
     * @param event    SSE 事件原始 JSON 字符串
     * @param sessionId 会话ID（仅用于日志）
     * @return 长度 3 的 long 数组（promptTokens / completionTokens / totalTokens）；无 usage 时返回 null
     */
    private long[] extractUsage(String event, String sessionId) {
        try {
            JsonNode root = objectMapper.readTree(event);
            JsonNode usageNode = root.path("usage");
            if (usageNode.isObject()) {
                long promptTokens = usageNode.path("prompt_tokens").asLong(0);
                long completionTokens = usageNode.path("completion_tokens").asLong(0);
                long totalTokens = usageNode.path("total_tokens").asLong(0);
                // totalTokens 为 0 视为无效 usage（DashScope 异常情况）
                if (totalTokens > 0) {
                    return new long[]{promptTokens, completionTokens, totalTokens};
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("[图文对话] 解析 usage 失败（可能是非 JSON 帧或无 usage 字段）：sessionId = {}, event = {}",
                    sessionId, event);
            return null;
        }
    }

    /**
     * 从异常中提取可读的错误信息
     * <p>
     * 针对 {@link WebClientResponseException}（DashScope 返回 4xx/5xx），
     * 提取响应体中的 error.message 字段，便于定位问题（如 API Key 无效、模型不存在等）。
     *
     * @param e 异常
     * @return 可读的错误信息
     */
    private String extractErrorMessage(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode msg = root.path("error").path("message");
                if (msg.isTextual()) {
                    return msg.asText() + " (HTTP " + wcre.getStatusCode().value() + ")";
                }
            } catch (Exception ignored) {
                // 响应体非 JSON，返回原始内容
            }
            return body != null && !body.isEmpty() ? body : wcre.getMessage();
        }
        return e.getMessage();
    }

    /**
     * 清洗图片 URL
     * <p>
     * 使用正则 {@code https?://[^\s`'"<>]+} 从原始字符串中提取第一个合法的 http(s) URL，
     * 自动丢弃前后的反引号、引号、逗号、空格等 Markdown 残留字符（Apifox 等客户端可能误加）。
     * <p>
     * <b>为什么用正则</b>：循环去除首尾字符的方式在某些场景下会失效（如 URL 中间嵌套了引号），
     * 正则提取最鲁棒。
     *
     * @param rawUrl 原始 URL 字符串
     * @return 清洗后的合法 URL；非法时返回 null
     */
    private String sanitizeImageUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        // 用正则提取 http(s) URL，自动跳过前后的反引号、引号、逗号等残留字符
        java.util.regex.Matcher matcher = URL_PATTERN.matcher(rawUrl);
        if (matcher.find()) {
            String url = matcher.group();
            // 去除末尾可能残留的引号/反引号（正则的 [^...] 集合已排除，但防御性处理）
            while (!url.isEmpty() && (url.endsWith("`") || url.endsWith("'")
                    || url.endsWith("\"") || url.endsWith(",") || url.endsWith(")"))) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }
        log.warn("图片 URL 中未提取到合法 http(s) URL：rawUrl = {}", rawUrl);
        return null;
    }

    /** URL 提取正则：匹配 http:// 或 https:// 开头，到第一个空白或引号类字符结束 */
    private static final java.util.regex.Pattern URL_PATTERN =
            java.util.regex.Pattern.compile("https?://[^\\s`'\"<>\\)]+");

    /*=============================================    私有方法    =============================================*/

    /**
     * 解析已启用的工具 Bean 列表（Step 4 实现）
     * <p>
     * 通过 Feign 查询 sys_argument 表的 {@code ai.enable_tools} 配置项：
     * <ul>
     *     <li>配置值为 "true" 时，从 {@link ToolRegistryService#getEnabledToolBeans()} 获取已启用的工具 Bean 列表</li>
     *     <li>配置值为 "false" 时，返回空列表（禁用工具调用）</li>
     *     <li>Feign 调用失败或配置项不存在时，默认启用工具调用（与 db.sql 预置数据 {@code ai.enable_tools=true} 一致）</li>
     * </ul>
     * <p>
     *
     * @param sessionId 会话ID（仅用于日志标识）
     * @return 已启用的工具 Bean 实例列表；未启用工具调用时返回空列表
     */
    private List<Object> resolveEnabledToolBeans(String sessionId) {
        try {
            ArgumentDTO enableToolsArg = argumentServiceApi.getByConfigKey(CONFIG_KEY_ENABLE_TOOLS);
            boolean enableTools = enableToolsArg != null
                    && ENABLED_VALUE.equalsIgnoreCase(enableToolsArg.getValue());
            if (!enableTools) {
                log.info("Agent 工具调用已禁用：sessionId = {}", sessionId);
                return Collections.emptyList();
            }
            List<Object> toolBeans = toolRegistryService.getEnabledToolBeans();
            if (!CollectionUtils.isEmpty(toolBeans)) {
                log.info("Agent 工具调用已启用：sessionId = {}, toolCount = {}", sessionId, toolBeans.size());
            }
            return toolBeans;
        } catch (Exception e) {
            log.warn("查询 ai.enable_tools 配置失败，按默认策略启用工具调用：sessionId = {}, error = {}",
                    sessionId, e.getMessage());
            return toolRegistryService.getEnabledToolBeans();
        }
    }

    /**
     * 构建 SSE 内容帧
     * <p>
     * 格式：{@code {"chunk": "内容片段", "done": false}}
     *
     * @param chunk 内容片段
     * @return JSON 字符串
     */
    private String buildContentFrame(String chunk) {
        Map<String, Object> frame = new HashMap<>(4);
        frame.put("chunk", chunk != null ? chunk : "");
        frame.put("done", false);
        return JsonUtil.classToJson(frame);
    }

    /**
     * 构建 SSE 结束帧
     * <p>
     * 格式：{@code {"chunk": "", "done": true, "sessionId": "...", "model": "..."}}
     *
     * @param sessionId 会话ID
     * @param model     模型名称
     * @return JSON 字符串
     */
    private String buildEndFrame(String sessionId, String model) {
        Map<String, Object> frame = new HashMap<>(8);
        frame.put("chunk", "");
        frame.put("done", true);
        frame.put("sessionId", sessionId);
        frame.put("model", model);
        return JsonUtil.classToJson(frame);
    }

    /**
     * 构建 SSE 错误帧
     * <p>
     * 格式：{@code {"error": "错误信息", "code": 500023, "done": true}}
     *
     * @param resultCode 错误码
     * @return JSON 字符串
     */
    private String buildErrorFrame(ResultCode resultCode) {
        Map<String, Object> frame = new HashMap<>(8);
        frame.put("error", resultCode.getErrMsg());
        frame.put("code", resultCode.getCode());
        frame.put("done", true);
        return JsonUtil.classToJson(frame);
    }

    /**
     * 异步保存文本对话记录
     * <p>
     * Step 6: 保存对话历史到 Redis（ChatMemoryService.addMessage）
     * Step 7: 记录对话到 MySQL（sys_ai_conversation 表）
     * Step 8: 记录 AI 调用链路日志到 sys_ai_operation_log 表（含 Token 消耗）
     *
     * @param request      流式对话请求
     * @param sessionId    会话ID
     * @param answer       AI 完整回答
     * @param modelName    模型名称
     * @param responseTime 响应时间（毫秒）
     * @param status       对话状态（SUCCESS/FAILED）
     * @param errorMsg     失败原因（status=FAILED 时记录）
     * @param usage        Spring AI Usage 对象（含 promptTokens/completionTokens/totalTokens，FAILED 时为 null）
     * @param toolCallsJson 工具调用记录 JSON 数组字符串（无调用时为 null）
     */
    private void saveConversation(ChatStreamReqDTO request, String sessionId, String answer,
                                  String modelName, long responseTime, String status, String errorMsg,
                                  Usage usage, String toolCallsJson) {
        try {
            // Step 6: 保存对话历史到 Redis（仅 SUCCESS 时保存，避免部分响应污染下一轮上下文）
            if (STATUS_SUCCESS.equals(status) && StringUtils.hasText(answer)) {
                chatMemoryService.addMessage(sessionId, new UserMessage(request.getMessage()));
                chatMemoryService.addMessage(sessionId, new AssistantMessage(answer));
            }
            // Step 7: 记录对话到 MySQL（SUCCESS/FAILED 都记录，便于排查失败原因）
            SysAiConversation conversation = buildConversationEntity(
                    sessionId, request.getUserId(), request.getUserFrom(),
                    request.getMessage(), answer, modelName,
                    request.getTemperature(), responseTime, status, errorMsg, request.getSources());
            historyService.saveConversation(conversation);
            log.info("保存对话记录成功：sessionId = {}, status = {}, responseTime = {}ms",
                    sessionId, status, responseTime);
            // Step 8: 记录 AI 调用链路日志（含 Token 消耗 + 工具调用链路）
            Integer promptTokens = usage != null ? Math.toIntExact(usage.getPromptTokens()) : null;
            Integer completionTokens = usage != null ? Math.toIntExact(usage.getCompletionTokens()) : null;
            Integer totalTokens = usage != null ? Math.toIntExact(usage.getTotalTokens()) : null;
            recordOperationLog(conversation, request.getPrompt(), answer, modelName,
                    responseTime, status, errorMsg, "CHAT",
                    promptTokens, completionTokens, totalTokens, toolCallsJson);
        } catch (Exception e) {
            log.error("保存对话记录失败：sessionId = {}", sessionId, e);
        }
    }

    /**
     * 异步保存图文对话记录
     *
     * @param request      图文流式对话请求
     * @param sessionId    会话ID
     * @param answer       AI 完整回答
     * @param modelName    模型名称
     * @param responseTime 响应时间（毫秒）
     * @param status       对话状态（SUCCESS/FAILED）
     * @param errorMsg     失败原因（status=FAILED 时记录）
     * @param usage        Token 数组（长度 3：promptTokens / completionTokens / totalTokens），FAILED 时为 null
     */
    private void saveImageConversation(ChatWithImageStreamReqDTO request, String sessionId, String answer,
                                       String modelName, long responseTime, String status, String errorMsg,
                                       long[] usage) {
        try {
            // answer 为空时强制改为 FAILED（避免空 answer 被标记为 SUCCESS 污染下一轮上下文）
            String finalStatus = status;
            String finalErrorMsg = errorMsg;
            if (STATUS_SUCCESS.equals(status) && !StringUtils.hasText(answer)) {
                finalStatus = STATUS_FAILED;
                finalErrorMsg = "AI 返回空响应";
                log.warn("检测到空响应，状态从 SUCCESS 改为 FAILED：sessionId = {}", sessionId);
            }
            // 保存对话历史到 Redis（仅 SUCCESS 且 answer 非空时保存，避免污染下一轮上下文）
            if (STATUS_SUCCESS.equals(finalStatus) && StringUtils.hasText(answer)) {
                chatMemoryService.addMessage(sessionId, new UserMessage(request.getMessage()));
                chatMemoryService.addMessage(sessionId,
                        new org.springframework.ai.chat.messages.AssistantMessage(answer));
            }
            // 记录对话到 MySQL（SUCCESS/FAILED 都记录，便于排查失败原因）
            SysAiConversation conversation = buildConversationEntity(
                    sessionId, request.getUserId(), request.getUserFrom(),
                    request.getMessage(), answer, modelName,
                    null, responseTime, finalStatus, finalErrorMsg, request.getSources());
            // 保存图片 URL 列表（JSON 数组格式）
            if (request.getImages() != null && !request.getImages().isEmpty()) {
                conversation.setImages(JsonUtil.classToJson(request.getImages()));
            }
            historyService.saveConversation(conversation);
            log.info("保存图文对话记录成功：sessionId = {}, status = {}, responseTime = {}ms",
                    sessionId, finalStatus, responseTime);
            // 记录 AI 调用链路日志（含 Token 消耗；图文对话不走工具调用，toolCalls 传 null）
            Integer promptTokens = usage != null ? Math.toIntExact(usage[0]) : null;
            Integer completionTokens = usage != null ? Math.toIntExact(usage[1]) : null;
            Integer totalTokens = usage != null ? Math.toIntExact(usage[2]) : null;
            recordOperationLog(conversation, request.getPrompt(), answer, modelName,
                    responseTime, finalStatus, finalErrorMsg, "CHAT",
                    promptTokens, completionTokens, totalTokens, null);
        } catch (Exception e) {
            log.error("保存图文对话记录失败：sessionId = {}", sessionId, e);
        }
    }

    /**
     * 异步记录 AI 调用链路日志到 sys_ai_operation_log 表
     * <p>
     * <b>第二阶段已支持 Token 字段</b>，填充字段：
     * <ul>
     *     <li>userId / userFrom / conversationId：用户与对话关联</li>
     *     <li>operationType：CHAT（文本对话）/ 其他类型由调用方指定</li>
     *     <li>model / prompt / response：完整 Prompt 与 LLM 响应</li>
     *     <li>promptTokens / completionTokens / totalTokens：Token 消耗（来自 Spring AI Usage 或 SSE 解析）</li>
     *     <li>responseTime / status / errorMsg：耗时与状态</li>
     *     <li>createDate / createTime：操作时间</li>
     * </ul>
     * <p>
     * <b>工具调用链路</b>：由 {@link ToolCallRecorder} 装饰器在工具调用时记录，
     * 通过 {@link #drainToolCallRecords} 收集后传入，无工具调用时为 null。
     * <p>
     * <b>异常隔离</b>：写入失败不影响主流程（对话已保存到 sys_ai_conversation 表），仅记录 warn 日志。
     *
     * @param conversation      对话记录实体（已保存，取其 id 作为 conversationId 关联）
     * @param prompt            完整 Prompt（含 RAG 上下文）
     * @param response          LLM 完整响应内容
     * @param modelName         模型名称
     * @param responseTime      响应耗时（毫秒）
     * @param status            调用状态（SUCCESS/FAILED）
     * @param errorMsg          失败原因（status=FAILED 时记录，可为 null）
     * @param operationType     AI 操作类型（CHAT/RETRIEVE/EMBEDDING/RERANK）
     * @param promptTokens      Prompt Token 数（可为 null）
     * @param completionTokens  响应 Token 数（可为 null）
     * @param totalTokens       总 Token 数（可为 null）
     * @param toolCallsJson     工具调用记录 JSON 数组字符串（无调用时为 null）
     */
    private void recordOperationLog(SysAiConversation conversation, String prompt, String response,
                                     String modelName, long responseTime, String status,
                                     String errorMsg, String operationType,
                                     Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                     String toolCallsJson) {
        try {
            SysAiOperationLog opLog = new SysAiOperationLog();
            opLog.setUserId(conversation.getUserId());
            opLog.setUserFrom(conversation.getUserFrom());
            opLog.setConversationId(conversation.getId());
            opLog.setOperationType(operationType);
            opLog.setModel(modelName);
            opLog.setPrompt(prompt);
            opLog.setResponse(response);
            opLog.setPromptTokens(promptTokens);
            opLog.setCompletionTokens(completionTokens);
            opLog.setTotalTokens(totalTokens);
            // 工具调用链路（JSON 数组，由 ToolCallRecorder 装饰器收集，无调用时为 null）
            opLog.setToolCalls(toolCallsJson);
            opLog.setResponseTime((int) responseTime);
            opLog.setStatus(status);
            if (errorMsg != null) {
                opLog.setErrorMsg(errorMsg);
            }
            opLog.setCreateDate(Long.parseLong(LocalDate.now().format(DATE_FORMATTER)));
            opLog.setCreateTime(LocalDateTime.now());
            sysAiOperationLogMapper.insert(opLog);
        } catch (Exception ex) {
            log.warn("写入 AI 调用链路日志失败：sessionId = {}, conversationId = {}",
                    conversation.getSessionId(), conversation.getId(), ex);
        }
    }

    /**
     * 收集本次对话所有工具调用记录
     * <p>
     * 遍历所有 {@link ToolCallRecorder} 装饰器，调用 {@link ToolCallRecorder#drainRecords()}
     * 取出每个工具的调用记录（JSON 数组），合并为一个 JSON 数组字符串。
     * <p>
     * <b>调用时机</b>：在流式对话的 doOnComplete / onErrorResume 回调中调用，
     * 此时所有工具调用已完成，可以安全地 drain。
     *
     * @param toolRecorders 本次对话的工具调用记录装饰器列表（可为空列表）
     * @return 合并后的工具调用记录 JSON 数组字符串；无调用记录时返回 null
     */
    private String drainToolCallRecords(List<ToolCallRecorder> toolRecorders) {
        if (CollectionUtils.isEmpty(toolRecorders)) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ToolCallRecorder recorder : toolRecorders) {
            String json = recorder.drainRecords();
            if (json != null) {
                // recorder.drainRecords() 返回 "[{...},{...}]" 格式，去掉外层 [] 后拼接
                String inner = json.substring(1, json.length() - 1);
                if (!first) {
                    sb.append(",");
                }
                sb.append(inner);
                first = false;
            }
        }
        sb.append("]");
        return first ? null : sb.toString();
    }

    /**
     * 构建对话记录实体
     *
     * @param sessionId    会话ID
     * @param userId       用户ID
     * @param userFrom     用户来源（sys/app）
     * @param question     用户提问
     * @param answer       AI 回答
     * @param modelName    模型名称
     * @param temperature  温度参数
     * @param responseTime 响应时间（毫秒）
     * @param status       对话状态
     * @param errorMsg     失败原因
     * @param sources      RAG 引用来源（文档标题列表，可为 null）
     * @return 对话记录实体
     */
    private SysAiConversation buildConversationEntity(String sessionId, Long userId, String userFrom,
                                                       String question, String answer, String modelName,
                                                       Double temperature, long responseTime,
                                                       String status, String errorMsg, List<String> sources) {
        SysAiConversation conversation = new SysAiConversation();
        conversation.setId(snowflakeIdService.nextId());
        conversation.setSessionId(sessionId);
        conversation.setUserId(userId);
        conversation.setUserFrom(userFrom);
        conversation.setQuestion(question);
        conversation.setAnswer(answer);
        conversation.setModel(modelName);
        if (temperature != null) {
            conversation.setTemperature(BigDecimal.valueOf(temperature));
        } else {
            conversation.setTemperature(BigDecimal.valueOf(DEFAULT_TEMPERATURE));
        }
        conversation.setResponseTime((int) responseTime);
        conversation.setStatus(status);
        conversation.setIsDeleted(0);
        if (errorMsg != null) {
            conversation.setErrorMsg(errorMsg);
        }
        // RAG 引用来源序列化为 JSON 数组存储（仅 assistant 消息有，user 消息此字段为 null）
        if (sources != null && !sources.isEmpty()) {
            conversation.setSources(JsonUtil.classToJson(sources));
        }
        long today = Long.parseLong(LocalDate.now().format(DATE_FORMATTER));
        conversation.setCreateDate(today);
        conversation.setCreateTime(LocalDateTime.now());
        return conversation;
    }
}