package com.zmbdp.portal.service.chat.service.impl;

import com.zmbdp.chat.api.chat.domain.dto.*;
import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import com.zmbdp.chat.api.chat.feign.ChatApi;
import com.zmbdp.chat.api.history.domain.vo.HistoryDetailVO;
import com.zmbdp.chat.api.history.feign.HistoryApi;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.core.utils.StringUtil;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.security.domain.dto.LoginUserDTO;
import com.zmbdp.common.security.service.TokenService;
import com.zmbdp.portal.service.chat.service.IChatPortalService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * C端对话业务编排服务实现
 * <p>
 * portal-service 的核心服务实现，负责 C端 AI 对话的业务编排。
 * <p>
 * <b>执行流程（8 步）</b>：
 * <ol>
 *     <li>解析 JWT Token，提取 userId、username、userFrom（通过 TokenService）</li>
 *     <li>生成或复用 sessionId（request.sessionId 为空时生成 UUID）</li>
 *     <li>RAG 检索（Feign 调用 ChatApi.retrieveContext），失败时使用空上下文继续</li>
 *     <li>拼接完整 Prompt（System Prompt + 文档上下文 + 对话历史 + 用户提问）</li>
 *     <li>构建 ChatStreamReqDTO / ChatWithImageStreamReqDTO</li>
 *     <li>WebClient 调用 chat-service SSE 端点（lb://zmbdp-chat-service/...）</li>
 *     <li>透传 Flux&lt;String&gt; 给前端</li>
 *     <li>流结束后异步保存对话历史（由 chat-service 内部处理，portal-service 不重复保存）</li>
 * </ol>
 * <p>
 * <b>Prompt 拼接策略</b>：
 * <ul>
 *     <li>System Prompt：从 classpath:prompt/system-prompt.txt 加载</li>
 *     <li>文档上下文：按 score 降序排序，限制总长度不超过 scaffold.portal.context-max-length（默认 8000 字符）</li>
 *     <li>对话历史：通过 Feign 调用 HistoryApi.getSessionHistory(sessionId) 获取，取最近 N 轮（由 chat-service 内部根据 scaffold.rag.memory-rounds 截取）</li>
 *     <li>用户提问：直接拼接在末尾</li>
 * </ul>
 * <p>
 * <b>错误处理</b>：
 * <ul>
 *     <li>RAG 检索失败：记录 warning 日志，使用空上下文继续对话（不中断流程）</li>
 *     <li>WebClient 调用失败：返回错误帧 {@code {"error": "AI服务连接失败", "code": 500023, "done": true}}</li>
 *     <li>WebClient 超时（60秒）：返回超时帧 {@code {"error": "AI服务响应超时", "code": 500024, "done": true}}</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
@RefreshScope
public class ChatPortalServiceImpl implements IChatPortalService {

    /**
     * chat-service 文本 SSE 端点（通过 @LoadBalanced 服务发现，不硬编码 IP/端口）
     */
    private static final String TEXT_STREAM_URL = "lb://zmbdp-chat-service/chat/completions/stream";

    /**
     * chat-service 图文 SSE 端点
     */
    private static final String IMAGE_STREAM_URL = "lb://zmbdp-chat-service/chat/image/completions/stream";

    /**
     * 默认 RAG 检索数量（用户未指定 topK 时使用）
     */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 默认上下文最大长度（用户未指定时使用，可通过 Nacos 配置覆盖）
     */
    private static final int DEFAULT_CONTEXT_MAX_LENGTH = 8000;

    /**
     * WebClient 超时时间（毫秒，默认 60 秒）
     */
    private static final long DEFAULT_WEBCLIENT_TIMEOUT_MS = 60_000L;

    /**
     * 对话历史消息角色：用户（其他角色统一显示为 Assistant）
     */
    private static final String ROLE_USER = "user";

    /**
     * SSE 错误帧的 done 字段值
     */
    private static final boolean FRAME_DONE = true;

    /**
     * Token 服务（用于解析 JWT 提取用户信息）
     */
    @Autowired
    private TokenService tokenService;

    /**
     * RAG 检索 Feign 接口
     */
    @Autowired
    private ChatApi chatApi;

    /**
     * 对话历史 Feign 接口（用于获取对话历史用于 Prompt 拼接）
     */
    @Autowired
    private HistoryApi historyApi;

    /**
     * C端流式对话专用的 WebClient.Builder（由 WebClientConfig 注入，已启用 @LoadBalanced）
     */
    @Autowired
    private Builder chatWebClientBuilder;

    /**
     * 资源加载器（用于加载 system-prompt.txt）
     */
    @Autowired
    private ResourceLoader resourceLoader;

    /**
     * JWT 密钥（从 share-token-${env}.yaml 共享配置读取）
     */
    @Value("${jwt.token.secret}")
    private String secret;

    /**
     * 系统提示词文件路径（默认 classpath:prompt/system-prompt.txt，可通过 Nacos 配置覆盖）
     */
    @Value("${scaffold.portal.system-prompt-path:classpath:prompt/system-prompt.txt}")
    private String systemPromptPath;

    /**
     * RAG 上下文最大长度（字符数，默认 8000，超出则按 score 降序截断低分文档）
     */
    @Value("${scaffold.portal.context-max-length:" + DEFAULT_CONTEXT_MAX_LENGTH + "}")
    private int contextMaxLength;

    /**
     * WebClient 调用超时时间（毫秒，默认 60 秒）
     */
    @Value("${scaffold.portal.webclient-timeout:" + DEFAULT_WEBCLIENT_TIMEOUT_MS + "}")
    private long webClientTimeout;

    /**
     * 系统提示词内容（启动时加载并缓存，避免每次请求都读文件）
     */
    private String systemPrompt;

    /**
     * 初始化：加载系统提示词文件
     * <p>
     * 在 Bean 初始化后执行，将 system-prompt.txt 内容加载到内存中缓存。
     * <p>
     * <b>异常处理</b>：加载失败时使用空字符串作为系统提示词，仅记录错误日志，不中断启动。
     *
     * @throws IOException 文件读取异常（捕获后降级为空字符串）
     */
    @PostConstruct
    public void init() throws IOException {
        try {
            Resource resource = resourceLoader.getResource(systemPromptPath);
            systemPrompt = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            log.info("系统提示词加载成功：path = {}, length = {}字符", systemPromptPath, systemPrompt.length());
        } catch (IOException e) {
            log.error("系统提示词加载失败，使用空字符串降级：path = {}, error = {}", systemPromptPath, e.getMessage());
            systemPrompt = "";
        }
    }

    /**
     * 流式文本对话
     * <p>
     * 执行 8 步业务编排，返回 SSE 流。
     *
     * @param request C端文本对话请求
     * @return SSE 流（每帧为 JSON 字符串）
     */
    @Override
    public Flux<String> streamChat(ChatReqDTO request) {
        // Step 1: 解析 JWT Token，提取用户信息
        LoginUserDTO loginUser = getLoginUser();
        Long userId = loginUser.getUserId();
        String userFrom = loginUser.getUserFrom();

        // Step 2: 生成或复用 sessionId
        String sessionId = resolveSessionId(request.getSessionId(), userId);

        // Step 3: RAG 检索（失败时使用空上下文继续，不中断流程）
        List<DocumentVO> documents = retrieveContext(request.getMessage(), request.getTopK());

        // Step 4: 拼接完整 Prompt
        String prompt = buildPrompt(request.getMessage(), sessionId, documents, userId);

        // Step 5: 构建 ChatStreamReqDTO
        ChatStreamReqDTO streamReq = new ChatStreamReqDTO();
        streamReq.setMessage(request.getMessage());
        streamReq.setSessionId(sessionId);
        streamReq.setPrompt(prompt);
        streamReq.setSystemPrompt(systemPrompt);  // 单独传递 System Prompt，chat-service 用 SystemMessage 包装
        streamReq.setModel(request.getModel());
        streamReq.setTemperature(request.getTemperature());
        streamReq.setUserId(userId);
        streamReq.setUserFrom(userFrom);
        // 透传 RAG 引用来源（文档标题列表），用于历史详情展示
        streamReq.setSources(extractSourceTitles(documents));

        log.info("发起流式文本对话：sessionId = {}, userId = {}, userFrom = {}, docCount = {}, promptLength = {}",
                sessionId, userId, userFrom, documents.size(), prompt.length());

        // Step 6-7: WebClient 调用 chat-service SSE 端点，透传 Flux
        return chatWebClientBuilder.build()
                .post()
                .uri(TEXT_STREAM_URL)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(streamReq)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(java.time.Duration.ofMillis(webClientTimeout),
                        Mono.just(buildErrorFrame(ResultCode.AI_SERVICE_TIMEOUT)))
                .onErrorResume(e -> {
                    log.error("WebClient 调用 chat-service 失败：sessionId = {}, url = {}, error = {}",
                            sessionId, TEXT_STREAM_URL, e.getMessage(), e);
                    return Flux.just(buildErrorFrame(ResultCode.AI_SERVICE_CONNECT_FAILED));
                });
    }

    /**
     * 流式图文对话
     * <p>
     * 业务编排流程与 {@link #streamChat} 一致，仅请求体和 SSE 端点不同。
     *
     * @param request C端图文对话请求
     * @return SSE 流（每帧为 JSON 字符串）
     */
    @Override
    public Flux<String> streamChatWithImage(ChatWithImageReqDTO request) {
        // Step 1: 解析 JWT Token，提取用户信息
        LoginUserDTO loginUser = getLoginUser();
        Long userId = loginUser.getUserId();
        String userFrom = loginUser.getUserFrom();

        // Step 2: 生成或复用 sessionId
        String sessionId = resolveSessionId(request.getSessionId(), userId);

        // Step 3: RAG 检索（图文对话也走 RAG，提供项目上下文）
        List<DocumentVO> documents = retrieveContext(request.getMessage(), request.getTopK());

        // Step 4: 拼接完整 Prompt
        String prompt = buildPrompt(request.getMessage(), sessionId, documents, userId);

        // Step 4.5: 清洗图片 URL（去除前端/ApiFox 误加的反引号、引号等 Markdown 残留字符）
        List<String> sanitizedImages = sanitizeImageUrls(request.getImages());

        // Step 5: 构建 ChatWithImageStreamReqDTO
        ChatWithImageStreamReqDTO streamReq = new ChatWithImageStreamReqDTO();
        streamReq.setMessage(request.getMessage());
        streamReq.setImages(sanitizedImages);
        streamReq.setSessionId(sessionId);
        streamReq.setPrompt(prompt);
        streamReq.setSystemPrompt(systemPrompt);  // 单独传递 System Prompt，chat-service 用 SystemMessage 包装
        streamReq.setModel(request.getModel());
        streamReq.setUserId(userId);
        streamReq.setUserFrom(userFrom);
        // 透传 RAG 引用来源（文档标题列表），用于历史详情展示
        streamReq.setSources(extractSourceTitles(documents));

        log.info("发起流式图文对话：sessionId = {}, userId = {}, userFrom = {}, docCount = {}, imageCount = {}, promptLength = {}",
                sessionId, userId, userFrom, documents.size(),
                sanitizedImages.size(), prompt.length());

        // Step 6-7: WebClient 调用 chat-service 图文 SSE 端点，透传 Flux
        return chatWebClientBuilder.build()
                .post()
                .uri(IMAGE_STREAM_URL)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(streamReq)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(java.time.Duration.ofMillis(webClientTimeout),
                        Mono.just(buildErrorFrame(ResultCode.AI_SERVICE_TIMEOUT)))
                .onErrorResume(e -> {
                    log.error("WebClient 调用 chat-service 图文端点失败：sessionId = {}, url = {}, error = {}",
                            sessionId, IMAGE_STREAM_URL, e.getMessage(), e);
                    return Flux.just(buildErrorFrame(ResultCode.AI_SERVICE_CONNECT_FAILED));
                });
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 解析 JWT Token 获取登录用户信息
     * <p>
     * 调用 {@code tokenService.getLoginUser(secret)} 从当前 HTTP 请求中提取 JWT 并解析。
     * <p>
     * <b>调用时机</b>：必须在 Service 方法体的同步部分调用（即 Controller 返回 Flux 之前），
     * 因为 {@code ServletUtil.getRequest()} 依赖 ThreadLocal，进入 Reactor 线程后将无法获取。
     *
     * @return 登录用户信息
     * @throws com.zmbdp.common.domain.exception.ServiceException 如果 Token 无效或已过期
     */
    private LoginUserDTO getLoginUser() {
        LoginUserDTO loginUser = tokenService.getLoginUser(secret);
        if (loginUser == null) {
            log.warn("用户令牌有误，无法获取登录用户信息");
            throw new com.zmbdp.common.domain.exception.ServiceException(
                    "用户令牌有误", ResultCode.INVALID_PARA.getCode());
        }
        return loginUser;
    }

    /**
     * 生成或复用 sessionId
     * <p>
     * <b>规则</b>：
     * <ul>
     *     <li>request.sessionId 非空：通过 Feign 调用 {@code HistoryApi.checkSessionOwnership}
     *     校验 sessionId 归属当前用户（新会话视为允许使用）。校验失败抛
     *     {@link com.zmbdp.common.domain.exception.ServiceException}（错误码 500032）</li>
     *     <li>request.sessionId 为空：生成新 sessionId（UUID）</li>
     * </ul>
     *
     * @param requestSessionId 请求中的 sessionId（可为空）
     * @param userId           用户ID（用于归属校验）
     * @return 最终使用的 sessionId
     * @throws com.zmbdp.common.domain.exception.ServiceException sessionId 归属校验失败
     */
    private String resolveSessionId(String requestSessionId, Long userId) {
        if (StringUtil.isNotBlank(requestSessionId)) {
            // 通过 Feign 调用 chat-service 校验 sessionId 归属
            if (!checkSessionOwnership(userId, requestSessionId)) {
                log.warn("sessionId 归属校验失败：userId = {}, sessionId = {}", userId, requestSessionId);
                throw new com.zmbdp.common.domain.exception.ServiceException(
                        "会话不属于当前用户", ResultCode.SESSION_NOT_BELONG_TO_USER.getCode());
            }
            return requestSessionId;
        }
        String newSessionId = UUID.randomUUID().toString().replace("-", "");
        log.info("生成新 sessionId：userId = {}, sessionId = {}", userId, newSessionId);
        return newSessionId;
    }

    /**
     * 调用 chat-service 校验 sessionId 归属
     * <p>
     * <b>异常处理</b>：Feign 调用失败时降级为校验通过（false 会导致正常用户无法对话），
     * 仅记录错误日志。降级策略与 RAG 检索失败一致：不中断主流程。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return true 表示属于当前用户或为新会话；Feign 调用失败时降级返回 true
     */
    private boolean checkSessionOwnership(Long userId, String sessionId) {
        try {
            Result<Boolean> result = historyApi.checkSessionOwnership(userId, sessionId);
            if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
                log.warn("sessionId 归属校验 Feign 调用返回异常，降级为通过：userId = {}, sessionId = {}, code = {}, msg = {}",
                        userId, sessionId, result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
                return true;
            }
            return Boolean.TRUE.equals(result.getData());
        } catch (Exception e) {
            log.warn("sessionId 归属校验 Feign 调用失败，降级为通过：userId = {}, sessionId = {}, error = {}",
                    userId, sessionId, e.getMessage());
            return true;
        }
    }

    /**
     * RAG 检索
     * <p>
     * 通过 Feign 调用 {@code ChatApi.retrieveContext()} 获取相关文档上下文。
     * <p>
     * <b>异常处理</b>：
     * 检索失败时记录 warning 日志，使用空上下文继续对话（不中断流程），
     * 因为 RAG 检索是增强项，不应影响对话本身。
     *
     * @param question 用户问题
     * @param topK     检索数量（为空时使用默认值 5）
     * @return 检索到的文档分块列表（失败时返回空列表）
     */
    private List<DocumentVO> retrieveContext(String question, Integer topK) {
        try {
            RetrieveReqDTO retrieveReq = new RetrieveReqDTO();
            retrieveReq.setQuestion(question);
            retrieveReq.setTopK(topK != null ? topK : DEFAULT_TOP_K);
            Result<List<DocumentVO>> result = chatApi.retrieveContext(retrieveReq);
            if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
                log.warn("RAG 检索返回异常：question = {}, code = {}, msg = {}",
                        question, result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
                return List.of();
            }
            return result.getData();
        } catch (Exception e) {
            log.warn("RAG 检索失败，使用空上下文继续对话：question = {}, error = {}", question, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 RAG 检索结果中提取文档标题列表，作为引用来源透传给 chat-service
     * <p>
     * 用于历史详情展示 assistant 消息时显示引用了哪些文档。
     * 标题缺失时用源文件路径兜底；去重后返回（同一文档可能被分块命中多次）。
     *
     * @param documents RAG 检索到的文档分块列表
     * @return 文档标题列表（无文档时返回空列表）
     */
    private List<String> extractSourceTitles(List<DocumentVO> documents) {
        if (documents == null || documents.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return documents.stream()
                .map(doc -> {
                    String title = doc.getTitle();
                    return StringUtil.isNotBlank(title) ? title : doc.getSourcePath();
                })
                .filter(StringUtil::isNotBlank)
                .distinct()
                .toList();
    }

    /**
     * 拼接完整 Prompt
     * <p>
     * <b>Prompt 结构</b>：
     * <pre>
     * [系统提示词 System Prompt]
     *
     * [检索到的文档上下文]（按 score 降序，限制总长度）
     *
     * [对话历史]（最近 N 轮，通过 Feign 调用 HistoryApi.getSessionHistory 获取）
     *
     * [用户提问]
     * </pre>
     *
     * @param question  用户提问
     * @param sessionId 会话ID（用于获取对话历史）
     * @param documents RAG 检索到的文档分块列表
     * @param userId    用户ID（用于历史会话归属校验）
     * @return 拼接后的完整 Prompt（不含 System Prompt，System Prompt 通过 systemPrompt 字段单独传递）
     */
    private String buildPrompt(String question, String sessionId, List<DocumentVO> documents, Long userId) {
        StringBuilder prompt = new StringBuilder();

        // 注意：System Prompt 不再拼入 prompt，由调用方通过 streamReq.setSystemPrompt(systemPrompt) 单独传递
        // chat-service 会用 SystemMessage 包装后传给大模型，让大模型正确识别为身份设定而非用户输入

        // 1. 文档上下文（按 score 降序排序，限制总长度）
        String contextStr = buildDocumentContext(documents);
        if (StringUtil.isNotBlank(contextStr)) {
            prompt.append(contextStr).append("\n\n");
        }

        // 2. 对话历史（最近 N 轮，由 chat-service 内部根据 scaffold.rag.memory-rounds 截取）
        String historyStr = buildHistoryContext(sessionId, userId);
        if (StringUtil.isNotBlank(historyStr)) {
            prompt.append(historyStr).append("\n\n");
        }

        // 3. 用户提问
        prompt.append("[用户提问]\n").append(question);

        return prompt.toString();
    }

    /**
     * 构建文档上下文部分
     * <p>
     * <b>拼接逻辑</b>：
     * <ol>
     *     <li>按 score 降序排序文档分块</li>
     *     <li>遍历每个文档分块，格式：{@code ---文档{N}: {title}（模块: {module}）---\n{content}}</li>
     *     <li>限制总长度不超过 {@code scaffold.portal.context-max-length}（默认 8000 字符），超出则截断低分文档</li>
     * </ol>
     *
     * @param documents 文档分块列表
     * @return 文档上下文字符串（无文档时返回空字符串）
     */
    private String buildDocumentContext(List<DocumentVO> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }
        // 按 score 降序排序（null score 视为 0，排到最后）
        List<DocumentVO> sorted = documents.stream()
                .sorted(Comparator.comparing(
                        (DocumentVO d) -> d.getScore() == null ? 0.0 : d.getScore(),
                        Comparator.reverseOrder()))
                .toList();

        StringBuilder context = new StringBuilder();
        context.append("[检索到的文档上下文]\n");
        int currentLength = context.length();
        int docIndex = 0;
        for (DocumentVO doc : sorted) {
            String docBlock = buildSingleDocBlock(++docIndex, doc);
            // 检查长度限制（+1 是为了换行符）
            if (currentLength + docBlock.length() + 1 > contextMaxLength) {
                log.info("文档上下文达到长度限制，截断低分文档：currentLength = {}, max = {}",
                        currentLength, contextMaxLength);
                break;
            }
            context.append(docBlock).append("\n");
            currentLength += docBlock.length() + 1;
        }
        return context.toString();
    }

    /**
     * 构建单个文档分块的上下文块
     *
     * @param index 文档序号（从 1 开始）
     * @param doc   文档分块
     * @return 格式化后的文档块字符串
     */
    private String buildSingleDocBlock(int index, DocumentVO doc) {
        StringBuilder block = new StringBuilder();
        block.append("---文档").append(index).append(": ");
        // 标题（缺失时使用源文件路径作为兜底）
        String title = StringUtil.isNotBlank(doc.getTitle()) ? doc.getTitle() : doc.getSourcePath();
        block.append(title != null ? title : "未命名文档");
        // 模块（缺失时跳过）
        if (StringUtil.isNotBlank(doc.getModule())) {
            block.append("（模块: ").append(doc.getModule()).append("）");
        }
        block.append("---\n");
        // 分块内容（缺失时跳过）
        if (StringUtil.isNotBlank(doc.getContent())) {
            block.append(doc.getContent());
        }
        return block.toString();
    }

    /**
     * 构建对话历史上下文部分
     * <p>
     * 通过 Feign 调用 {@code HistoryApi.getSessionHistory(sessionId, userId)} 获取对话历史。
     * <p>
     * <b>跨服务说明</b>：portal-service 无法直接调用 chat-service 的 IChatMemoryService，
     * 必须通过 Feign 跨服务获取。chat-service 内部会按 {@code scaffold.rag.memory-rounds}
     * 截取最近 N 轮历史返回，并校验 sessionId 归属 userId。
     * <p>
     * <b>异常处理</b>：获取失败时返回空字符串，不中断对话流程。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（用于归属校验）
     * @return 对话历史上下文字符串（无历史时返回空字符串）
     */
    private String buildHistoryContext(String sessionId, Long userId) {
        try {
            Result<HistoryDetailVO> result = historyApi.getSessionHistory(sessionId, userId);
            if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()
                    || result.getData() == null
                    || result.getData().getMessages() == null
                    || result.getData().getMessages().isEmpty()) {
                return "";
            }
            StringBuilder history = new StringBuilder();
            history.append("[对话历史]\n");
            for (HistoryDetailVO.Message msg : result.getData().getMessages()) {
                String roleLabel = ROLE_USER.equalsIgnoreCase(msg.getRole()) ? "User" : "Assistant";
                history.append(roleLabel).append(": ").append(msg.getContent()).append("\n");
            }
            return history.toString();
        } catch (Exception e) {
            log.warn("获取对话历史失败，使用空历史继续对话：sessionId = {}, userId = {}, error = {}",
                    sessionId, userId, e.getMessage());
            return "";
        }
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
        frame.put("done", FRAME_DONE);
        return JsonUtil.classToJson(frame);
    }

    /**
     * 清洗图片 URL 列表
     * <p>
     * 去除每个 URL 首尾的空白、反引号、单引号、双引号等 Markdown 残留字符，
     * 并过滤掉非 http/https 协议的非法 URL。
     * <p>
     * <b>使用场景</b>：前端或 ApiFox 测试时可能误用 `` `URL` `` 等 Markdown 格式传入，
     * 在 portal-service 入口处统一清洗，确保传给 chat-service 的 URL 是干净的。
     *
     * @param rawUrls 原始 URL 列表
     * @return 清洗后的合法 URL 列表；输入为空时返回空列表
     */
    private List<String> sanitizeImageUrls(List<String> rawUrls) {
        if (rawUrls == null || rawUrls.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>(rawUrls.size());
        for (int i = 0; i < rawUrls.size(); i++) {
            String raw = rawUrls.get(i);
            if (!StringUtil.isNotBlank(raw)) {
                continue;
            }
            String url = raw.trim();
            // 循环去除首尾的反引号、单引号、双引号（防止 "`url`" 这种嵌套）
            while (url.length() > 1) {
                char first = url.charAt(0);
                char last = url.charAt(url.length() - 1);
                boolean firstIsQuote = first == '`' || first == '\'' || first == '"';
                boolean lastIsQuote = last == '`' || last == '\'' || last == '"';
                if (!firstIsQuote && !lastIsQuote) {
                    break;
                }
                if (firstIsQuote) {
                    url = url.substring(1);
                }
                if (lastIsQuote && url.length() > 0) {
                    url = url.substring(0, url.length() - 1);
                }
                url = url.trim();
            }
            if (url.isEmpty()) {
                log.warn("图片 URL 清洗后为空，跳过：rawUrl = {}", raw);
                continue;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                log.warn("图片 URL 非 http/https 协议，跳过：rawUrl = {}, url = {}", raw, url);
                continue;
            }
            if (!url.equals(raw.trim())) {
                log.info("图片 URL 已清洗：第 {} 张, rawUrl = {}, url = {}", i + 1, raw, url);
            }
            sanitized.add(url);
        }
        return sanitized;
    }
}