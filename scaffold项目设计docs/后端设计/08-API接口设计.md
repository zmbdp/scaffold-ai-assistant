# 📄 API接口设计文档

> **文档说明**：本文档详细描述 Scaffold AI Assistant 的所有API接口设计。
> **服务划分**：
> 
> - **zmbdp-chat-service**：仅提供AI核心能力（通过Feign接口和HTTP SSE端点暴露），不对外直接暴露给前端
> - **zmbdp-portal-service**：提供C端用户接口，通过Feign（同步）和WebClient（流式）调用zmbdp-chat-service
> - **zmbdp-admin-service**：提供B端管理接口（知识库管理、AI配置管理、统计等），通过Feign调用zmbdp-chat-service
>
> **网关路由配置**（复用现有脚手架网关配置，保持不变）：
> ```yaml
> - id: zmbdp-portal-service
>   uri: lb://zmbdp-portal-service
>   predicates:
>     - Path=/portal/**
>   filters:
>     - StripPrefix=1
>
> - id: zmbdp-admin-service
>   uri: lb://zmbdp-admin-service
>   predicates:
>     - Path=/admin/**
>   filters:
>     - StripPrefix=1
> ```
>
> **请求路径规则**：
> - C端前端请求：`http://{gateway-ip}:10030/portal/{Controller内部路径}` → gateway匹配 `/portal/**` → StripPrefix=1后转发到portal-service `/{Controller内部路径}`
>   - 示例：前端请求 `http://ip:10030/portal/chat/completions/stream` → gateway 转发到 portal-service `/chat/stream`
> - B端前端请求：`http://{gateway-ip}:10030/admin/{Controller内部路径}` → gateway匹配 `/admin/**` → StripPrefix=1后转发到admin-service `/{Controller内部路径}`
>   - 示例：前端请求 `http://ip:10030/admin/knowledge/sources` → gateway 转发到 admin-service `/knowledge/sources`

---

## 8.1 统一响应格式

所有接口统一使用 `Result<T>` 格式响应：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {}
}
```

| 字段       | 类型      | 说明                   |
| -------- | ------- | -------------------- |
| `code`   | Integer | 响应码，6位数字，前3位为HTTP状态码 |
| `errMsg` | String  | 响应消息                 |
| `data`   | Object  | 响应数据                 |

**错误响应示例**：

```json
{
  "code": 400000,
  "errMsg": "无效的参数",
  "data": null
}
```

---

## 8.1.1 脚手架复用组件

> **说明**：以下组件均来自现有脚手架，直接复用，无需重复开发。

### 分页组件

分页请求使用 `BasePageReqDTO`（继承）：
```java
public class BasePageReqDTO {
    Integer pageNo = 1;   // 页码，默认1
    Integer pageSize = 10; // 每页数量，默认10
}
```

分页响应使用 `BasePageVO<T>`：
```java
public class BasePageVO<T> {
    Integer totals;      // 查询结果总数
    Integer totalPages;  // 总页数
    List<T> list;        // 数据列表
}
```

### 幂等性控制

写入操作接口使用 `@Idempotent` 注解保证幂等性：
```java
@PostMapping("/knowledge/sources")
@Idempotent(message = "知识源已创建，请勿重复提交")
public Result<KnowledgeSourceVO> createSource(@RequestBody KnowledgeSourceReqDTO dto) {
    // 实现逻辑
}
```

### 限流控制

高频接口使用 `@RateLimit` 注解进行限流：
```java
@PostMapping("/chat")
@RateLimit(limit = 100, windowSec = 60)  // 每分钟最多100次请求
public Result<ChatVO> chat(@RequestBody ChatReqDTO request) {
    // 实现逻辑
}
```

### 操作日志

重要操作接口使用 `@LogAction` 注解记录操作日志：
```java
@PutMapping("/ai/config")
@LogAction(module = "AI配置", operation = "更新AI配置")
public Result<Void> updateConfig(@RequestBody AiConfigDTO dto) {
    // 实现逻辑
}
```

---

## 8.1.2 接口调用链路总览

> **说明**：以下表格列出每个接口的完整调用链路，包括 Controller → Service → Feign/外部服务。

### C端接口调用链路（zmbdp-portal-service）

| 接口 | Controller | Service | 调用的 Feign/WebClient | 说明 |
|-----|-----------|---------|----------------------|------|
| POST /portal/chat/completions/stream | PortalChatController | PortalChatService | Feign ChatApi.retrieveContext() + WebClient /chat/completions/stream | 流式文本对话 |
| POST /portal/chat/image/completions/stream | PortalChatController | PortalChatService | Feign ChatApi.retrieveContext() + WebClient /chat/image/completions/stream | 流式图文对话 |
| GET /portal/history | HistoryController | PortalHistoryService | Feign HistoryApi.getHistoryList() | 历史列表（从 JWT 提取 userId） |
| GET /portal/history/{sessionId} | HistoryController | PortalHistoryService | Feign HistoryApi.getSessionHistory() | 会话详情 |
| DELETE /portal/history/{sessionId} | HistoryController | PortalHistoryService | Feign HistoryApi.deleteSession() | 删除会话 |
| POST /portal/feedback | FeedbackController | - | Feign FeedbackApi.submitFeedback() → IFeedbackService.submitFeedback() | 提交回答反馈 |
| GET /portal/feedback/{conversationId} | FeedbackController | - | Feign FeedbackApi.getFeedback() → IFeedbackService.getFeedback() | 查询用户已提交的反馈 |
| DELETE /portal/feedback/{conversationId} | FeedbackController | - | Feign FeedbackApi.deleteFeedback() → IFeedbackService.deleteFeedback() | 撤销反馈 |

### B端接口调用链路（zmbdp-admin-service → Feign → zmbdp-chat-service）

| 接口 | Controller | Feign 接口 | chat-service Service | 说明 |
|-----|-----------|-----------|---------------------|------|
| GET /admin/knowledge/sources | KnowledgeController | KnowledgeApi.getSources() | IKnowledgeService.listSources() | 知识源列表 |
| POST /admin/knowledge/sources | KnowledgeController | KnowledgeApi.addSource() | IKnowledgeService.createSource() | 新增知识源 |
| PUT /admin/knowledge/sources/{id} | KnowledgeController | KnowledgeApi.updateSource() | IKnowledgeService.updateSource() | 更新知识源 |
| DELETE /admin/knowledge/sources/{id} | KnowledgeController | KnowledgeApi.deleteSource() | IKnowledgeService.deleteSource() → IVectorStoreService.deleteByDocumentId() | 删除知识源（级联删除） |
| POST /admin/knowledge/sync | KnowledgeController | MQ 异步（knowledge.sync.exchange） | KnowledgeSyncConsumer → IKnowledgeLoaderService.syncKnowledge(sourceType, force) | 知识同步（异步） |
| GET /admin/knowledge/documents | KnowledgeController | KnowledgeApi.getDocuments() | IKnowledgeService.listDocuments() | 文档列表 |
| POST /admin/knowledge/documents/upload | KnowledgeController | 直接处理（不走 Feign） | IKnowledgeService.uploadDocument() → IKnowledgeLoaderService | 上传文档 |
| GET /admin/knowledge/documents/{id} | KnowledgeController | KnowledgeApi.getDocument() | IKnowledgeService.getDocument() | 文档详情 |
| DELETE /admin/knowledge/documents/{id} | KnowledgeController | KnowledgeApi.deleteDocument() | IKnowledgeService.deleteDocument() → IVectorStoreService.deleteByDocumentId() | 删除文档 |
| GET /admin/knowledge/logs | LogController | OperationLogApi.getLogs() | IAdminService.listLogs() | 操作日志 |
| GET /admin/ai/config | AiConfigController | AiConfigApi.getConfig() | IAdminService.getAiConfig() | AI配置 |
| PUT /admin/ai/config | AiConfigController | AiConfigApi.updateConfig() | IAdminService.updateAiConfig() | 更新AI配置 |
| GET /admin/ai/models | AiConfigController | AiConfigApi.getModels() | IModelService.listModels() | 模型列表 |
| POST /admin/ai/test | AiConfigController | AiConfigApi.testConnection() | IModelService.testConnection() | 测试连接 |
| GET /admin/statistics/conversations | StatisticsController | StatisticsApi.getConversationStatistics() | IStatisticsService.getConversationStats() | 对话统计 |
| GET /admin/statistics/questions | StatisticsController | StatisticsApi.getHotQuestions() | IStatisticsService.getTopQuestions() | 热门问题 |
| GET /admin/statistics/users | StatisticsController | StatisticsApi.getUserStatistics() | IStatisticsService.getUserStats() | 用户统计 |
| GET /admin/statistics/tools | StatisticsController | StatisticsApi.getToolStatistics() | IStatisticsService.getToolUsageStats() | 工具统计 |
| GET /admin/statistics/ai-metrics | StatisticsController | StatisticsApi.getAiMetrics() | IStatisticsService.getAiCallMetrics() | AI指标 |
| GET /admin/tools | ToolController | ToolsApi.getTools() | IAdminService.listTools() | 工具列表 |
| PUT /admin/tools/{name} | ToolController | ToolsApi.updateToolConfig() | IAdminService.updateToolConfig() | 更新工具配置 |
| POST /admin/tools/{name}/test | ToolController | ToolsApi.testTool() | IAdminService.testTool() | 测试工具 |
| POST /admin/knowledge/retrieve-test | KnowledgeController | KnowledgeApi.retrieveTest() | IKnowledgeService.retrieveTest() | 知识源召回测试 |
| GET /admin/statistics/ai-metrics/{operationId} | StatisticsController | StatisticsApi.getOperationDetail() | IStatisticsService.getOperationDetail() | 单次AI调用详情 |
| GET /admin/statistics/feedback | StatisticsController | StatisticsApi.getFeedbackStats() | IStatisticsService.getFeedbackStats() | 回答满意度统计 |
| GET /admin/system/health | SystemController | 直接处理（不走 Feign） | IAdminService 或直接返回 | 系统健康状态 |

### chat-service 内部 SSE 端点调用链路

| 端点 | Controller | Service | 调用的外部服务 |
|-----|-----------|---------|-------------|
| POST /chat/completions/stream | ChatStreamController | IChatService.streamChat() | Spring AI ChatClient + IModelService + IChatMemoryService + Agent工具 |
| POST /chat/image/completions/stream | ChatWithImageStreamController | IChatService.streamChatWithImage() | Spring AI ChatClient + IModelService + IChatMemoryService + Agent工具 |
| POST /chat/retrieve | ChatController | IChatService.retrieveContext() | IVectorStoreService.search() + IModelService.getEmbeddingModel() |

---

## 8.2 C端接口（zmbdp-portal-service，需要认证）

> **说明**：以下C端接口由 `zmbdp-portal-service` 提供，需要携带JWT Token认证。
> - **AI对话接口**：全部采用流式输出（WebClient + SSE），**不提供同步对话接口**
> - **非对话接口**：通过Feign调用 `zmbdp-chat-service`（如RAG检索、历史记录）
> - **前端请求路径**：`http://{gateway-ip}:10030/portal/{Controller内部路径}`（网关 StripPrefix=1 转发至 portal-service）

| HTTP方法 | 前端请求路径（经网关） | Controller内部路径 | Controller | 说明 | 调用方式 |
| ------ | ---------------------- | --------------- | ---------- | ---- | ------ |
| POST   | `/portal/chat/completions/stream` | `/chat/completions/stream` | `PortalChatController.streamChat()` | 流式对话（文本） | WebClient |
| POST   | `/portal/chat/image/completions/stream` | `/chat/image/completions/stream` | `PortalChatController.streamChatWithImage()` | 流式对话（图文） | WebClient |
| GET | `/portal/history` | `/history` | `HistoryController.getHistory()` | 获取对话历史列表 | Feign |
| GET | `/portal/history/{sessionId}` | `/history/{sessionId}` | `HistoryController.getSessionHistory()` | 获取会话详情 | Feign |
| DELETE | `/portal/history/{sessionId}` | `/history/{sessionId}` | `HistoryController.deleteSession()` | 删除会话 | Feign |
| POST | `/portal/feedback` | `/feedback` | `FeedbackPortalController.submitFeedback()` | 提交回答反馈（点赞/点踩） | Feign |
| GET | `/portal/feedback/{conversationId}` | `/feedback/{conversationId}` | `FeedbackPortalController.getFeedback()` | 查询用户已提交的反馈 | Feign |
| DELETE | `/portal/feedback/{conversationId}` | `/feedback/{conversationId}` | `FeedbackPortalController.deleteFeedback()` | 撤销反馈 | Feign |

### 8.2.1 流式对话流程说明

**portal-service业务编排步骤**：
1. 解析前端请求，提取用户提问、sessionId、模型配置等参数
2. **RAG检索**：通过Feign调用 `ChatApi.retrieveContext()` 获取相关文档上下文
3. **Prompt拼接**：将system prompt + 检索到的文档上下文 + 用户提问拼接为完整Prompt
4. **流式调用**：通过WebClient调用 `zmbdp-chat-service` 的SSE端点 `/chat/completions/stream`
5. **流透传**：接收Flux<String>流数据，直接透传给前端

**WebClient调用示例**（portal-service 中的 `PortalChatService`，注意与 chat-service 中的 `ChatService` 区分）：

> **WebClient 配置**：`chatWebClientBuilder` 通过 `@LoadBalanced WebClient.Builder` 注入（详见 03-C端功能设计.md 3.0.1 节 WebClientConfig），使用 `lb://zmbdp-chat-service` 协议通过 Nacos 服务发现解析实际地址，**不硬编码 IP/端口**。

```java
@Service
public class PortalChatService {
    private final WebClient.Builder chatWebClientBuilder;  // @LoadBalanced 注入，build() 后使用
    private final ChatApi chatApi;

    public Flux<String> streamChat(ChatReqDTO request) {
        // 步骤1: RAG检索获取上下文（Feign调用chat-service）
        Result<List<DocumentVO>> contextResult = chatApi.retrieveContext(
            RetrieveReqDTO.builder()
                .question(request.getMessage())
                .topK(request.getTopK() != null ? request.getTopK() : 5)
                .build()
        );
        
        // 步骤2: 拼接Prompt
        String fullPrompt = buildPrompt(contextResult.getData(), request.getMessage());
        
        // 步骤3: WebClient调用流式端点（通过@LoadBalanced服务发现，不硬编码IP/端口）
        return chatWebClientBuilder.build().post()
            .uri("lb://zmbdp-chat-service/chat/completions/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ChatStreamReqDTO.builder()
                .message(request.getMessage())
                .sessionId(request.getSessionId())
                .prompt(fullPrompt)
                .model(request.getModel())
                .temperature(request.getTemperature())
                .build())
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .bodyToFlux(String.class);
    }
}
```

---

## 8.3 B端接口（zmbdp-admin-service，需要JWT认证）

> **说明**：系统管理接口（用户登录、登出、用户管理等）已在 `zmbdp-admin-service` 中实现。脚手架未实现角色管理（无 sys_role 表）和细粒度权限控制。以下AI业务相关接口由 `zmbdp-admin-service` 通过Feign代理调用 `zmbdp-chat-service`。
> - **前端请求路径**：`http://{gateway-ip}:10030/admin/{Controller内部路径}`（网关 StripPrefix=1 转发至 admin-service）

| HTTP方法 | 前端请求路径（经网关） | Controller内部路径 | 说明 |
| ------ | ---------------------- | --------------- | ---- |
| **知识库管理** | | | |
| GET | `/admin/knowledge/sources` | `/knowledge/sources` | 获取知识源列表 |
| POST | `/admin/knowledge/sources` | `/knowledge/sources` | 新增知识源 |
| PUT | `/admin/knowledge/sources/{id}` | `/knowledge/sources/{id}` | 更新知识源 |
| DELETE | `/admin/knowledge/sources/{id}` | `/knowledge/sources/{id}` | 删除知识源 |
| POST | `/admin/knowledge/sync` | `/knowledge/sync` | 触发知识同步 |
| GET | `/admin/knowledge/documents` | `/knowledge/documents` | 获取文档列表 |
| POST | `/admin/knowledge/documents/upload` | `/knowledge/documents/upload` | 上传文档 |
| GET | `/admin/knowledge/documents/{id}` | `/knowledge/documents/{id}` | 获取文档详情 |
| DELETE | `/admin/knowledge/documents/{id}` | `/knowledge/documents/{id}` | 删除文档 |
| GET | `/admin/knowledge/logs` | `/knowledge/logs` | 获取操作日志（知识库同步、配置修改等） |
| POST | `/admin/knowledge/retrieve-test` | `/knowledge/retrieve-test` | 知识源召回测试（输入测试问题，返回检索结果及相似度） |
| **AI配置管理** | | | |
| GET | `/admin/ai/config` | `/ai/config` | 获取AI配置 |
| PUT | `/admin/ai/config` | `/ai/config` | 更新AI配置 |
| GET | `/admin/ai/models` | `/ai/models` | 获取可用模型列表 |
| POST | `/admin/ai/test` | `/ai/test` | 测试AI连接 |
| **使用统计** | | | |
| GET | `/admin/statistics/conversations` | `/statistics/conversations` | 获取对话统计 |
| GET | `/admin/statistics/questions` | `/statistics/questions` | 获取热门问题 |
| GET | `/admin/statistics/users` | `/statistics/users` | 获取用户统计 |
| GET | `/admin/statistics/tools` | `/statistics/tools` | 获取工具使用统计 |
| GET | `/admin/statistics/ai-metrics` | `/statistics/ai-metrics` | 获取AI调用指标（聚合） |
| GET | `/admin/statistics/ai-metrics/{operationId}` | `/statistics/ai-metrics/{operationId}` | 查看单次AI调用详情（Prompt、响应、工具调用链路） |
| GET | `/admin/statistics/feedback` | `/statistics/feedback` | 获取回答满意度统计（点赞率、点踩率、点踩原因分布） |
| **工具管理** | | | |
| GET | `/admin/tools` | `/tools` | 获取工具列表 |
| PUT | `/admin/tools/{name}` | `/tools/{name}` | 更新工具配置 |
| POST | `/admin/tools/{name}/test` | `/tools/{name}/test` | 测试工具 |
| **系统管理** | | | |
| GET | `/admin/system/health` | `/system/health` | 获取系统健康状态（MySQL/Redis/Nacos/Milvus/LLM 组件状态） |

---

## 8.4 Feign接口定义（zmbdp-chat-api）

> **说明**：`zmbdp-chat-service` 通过Feign接口暴露**同步非对话能力**（RAG检索、历史记录、知识库管理、AI配置等）。
> **AI对话不通过Feign**，必须使用流式SSE端点（见8.5节），由portal-service通过WebClient调用。

### 8.4.1 ChatApi（RAG检索）

```java
@FeignClient(contextId = "chatApi", name = "zmbdp-chat-service", path = "/chat")
public interface ChatApi {

    @PostMapping("/retrieve")
    Result<List<DocumentVO>> retrieveContext(@RequestBody RetrieveReqDTO request);
}
```

> **注意**：ChatApi **仅提供RAG检索接口**，不提供对话接口。AI对话必须使用流式SSE端点（见 8.5节），不使用Feign。

### 8.4.2 ~~ChatWithImageApi~~（已删除）

> **已删除**：AI对话不使用Feign，图文对话通过流式SSE端点实现（见 8.5.2节）。

### 8.4.3 KnowledgeApi（知识库管理）

```java
@FeignClient(contextId = "knowledgeApi", name = "zmbdp-chat-service", path = "/knowledge")
public interface KnowledgeApi {

    @GetMapping("/sources")
    Result<BasePageVO<KnowledgeSourceVO>> getSources(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                    @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
                                                    @RequestParam(value = "type", required = false) String type);

    @PostMapping("/sources")
    Result<KnowledgeSourceVO> addSource(@RequestBody KnowledgeSourceReqDTO dto);

    @PutMapping("/sources/{id}")
    Result<Void> updateSource(@PathVariable Long id, @RequestBody KnowledgeSourceReqDTO dto);

    @DeleteMapping("/sources/{id}")
    Result<Void> deleteSource(@PathVariable Long id);

    @PostMapping("/sync")
    Result<SyncResultVO> sync(@RequestBody SyncReqDTO dto);

    @GetMapping("/documents")
    Result<BasePageVO<KnowledgeDocumentVO>> getDocuments(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                        @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
                                                        @RequestParam(value = "sourceId", required = false) Long sourceId,
                                                        @RequestParam(value = "status", required = false) String status);

    @GetMapping("/documents/{id}")
    Result<KnowledgeDocumentVO> getDocument(@PathVariable Long id);

    @DeleteMapping("/documents/{id}")
    Result<Void> deleteDocument(@PathVariable Long id);

    /**
     * 知识源召回测试（输入测试问题，返回检索到的文档分块及相似度分数）
     * 复用 /chat/retrieve 的 RAG 检索能力，用于验证知识库质量
     */
    @PostMapping("/retrieve-test")
    Result<List<DocumentVO>> retrieveTest(@RequestBody RetrieveReqDTO request);
}
```

> **说明**：分页参数统一使用 `pageNo`/`pageSize`（与脚手架 `BasePageReqDTO` 字段名一致），分页响应统一使用 `BasePageVO<T>`（含 `totals`、`totalPages`、`list` 字段）。上传文档接口 `POST /documents/upload` 因涉及 `MultipartFile`，由 admin-service 直接代理而非通过 Feign 调用（Feign 对文件上传支持有限）。

### 8.4.4 AiConfigApi（AI配置管理）

```java
@FeignClient(contextId = "aiConfigApi", name = "zmbdp-chat-service", path = "/ai")
public interface AiConfigApi {

    @GetMapping("/config")
    Result<AiConfigVO> getConfig();

    @PutMapping("/config")
    Result<Void> updateConfig(@RequestBody AiConfigDTO dto);

    @GetMapping("/models")
    Result<List<ModelVO>> getModels();

    @PostMapping("/test")
    Result<Void> testConnection();
}
```

### 8.4.5 StatisticsApi（统计接口）

```java
@FeignClient(contextId = "statisticsApi", name = "zmbdp-chat-service", path = "/statistics")
public interface StatisticsApi {

    @GetMapping("/conversations")
    Result<ConversationStatisticsVO> getConversationStatistics();

    @GetMapping("/questions")
    Result<List<HotQuestionVO>> getHotQuestions();

    @GetMapping("/users")
    Result<UserStatisticsVO> getUserStatistics();

    @GetMapping("/tools")
    Result<ToolStatisticsVO> getToolStatistics();

    @GetMapping("/ai-metrics")
    Result<AiMetricsVO> getAiMetrics();

    /**
     * 查看单次AI调用详情（Prompt、LLM响应、工具调用链路）
     * 数据来源：sys_ai_operation_log 表的显式字段（prompt/response/tool_calls/prompt_tokens等）
     */
    @GetMapping("/ai-metrics/{operationId}")
    Result<OperationLogVO> getOperationDetail(@PathVariable("operationId") Long operationId);

    /**
     * 获取回答满意度统计（点赞率、点踩率、反馈率、点踩原因分布）
     * 数据来源：sys_ai_feedback 表
     */
    @GetMapping("/feedback")
    Result<FeedbackStatisticsVO> getFeedbackStatistics(@RequestParam(value = "startDate", required = false) Long startDate,
                                                        @RequestParam(value = "endDate", required = false) Long endDate);
}
```

### 8.4.6 ToolsApi（工具管理）

```java
@FeignClient(contextId = "toolsApi", name = "zmbdp-chat-service", path = "/tools")
public interface ToolsApi {

    @GetMapping
    Result<List<ToolVO>> getTools();

    @PutMapping("/{name}")
    Result<Void> updateToolConfig(@PathVariable String name, @RequestBody ToolConfigDTO dto);

    @PostMapping("/{name}/test")
    Result<ToolTestResultVO> testTool(@PathVariable String name, @RequestBody ToolTestReqDTO dto);
}
```

### 8.4.7 HistoryApi（对话历史管理）

```java
@FeignClient(contextId = "historyApi", name = "zmbdp-chat-service", path = "/history")
public interface HistoryApi {

    @GetMapping
    Result<BasePageVO<HistoryVO>> getHistoryList(@RequestParam("userId") Long userId,
                                                 @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                 @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize);

    @GetMapping("/{sessionId}")
    Result<HistoryDetailVO> getSessionHistory(@PathVariable("sessionId") String sessionId);

    @DeleteMapping("/{sessionId}")
    Result<Void> deleteSession(@PathVariable("sessionId") String sessionId);
}
```

> **说明**：`userId` 由 portal-service 通过 `tokenService.getLoginUser(token, secret)` 从 JWT Token 中解析后传递给 Feign 接口（注：TokenService 无 parseToken 方法，实际方法为 getLoginUser，需传入 jwt.token.secret 密钥），chat-service 根据该 userId 过滤对话历史。

### 8.4.8 OperationLogApi（AI调用链路日志）

```java
@FeignClient(contextId = "operationLogApi", name = "zmbdp-chat-service", path = "/operation-log")
public interface OperationLogApi {

    @GetMapping("/list")
    Result<BasePageVO<OperationLogVO>> getLogs(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                               @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
                                               @RequestParam(value = "operationType", required = false) String operationType,
                                               @RequestParam(value = "model", required = false) String model,
                                               @RequestParam(value = "status", required = false) String status,
                                               @RequestParam(value = "startDate", required = false) Long startDate,
                                               @RequestParam(value = "endDate", required = false) Long endDate);
}
```

> **数据来源**：AI调用链路日志存储在 `sys_ai_operation_log` 表（详见 07-项目架构设计.md 7.4.3.1 节操作日志职责边界对照表），由 chat-service 的 `IAdminService.listLogs()` 通过 `SysAiOperationLogMapper` 查询。注：B端管理操作审计（知识源CRUD、配置修改等）由脚手架 `@LogAction` 注解自动记录到 `operation_log` 表，不在本表查询。

### 8.4.9 ~~DataApi~~（已移除）

> **已移除**：数据备份恢复不在应用层实现，由 DevOps/DBA 工具负责（详见 04-B端功能设计.md 4.1 节说明）。
> - **MySQL**：使用 `mysqldump` 或云数据库备份服务
> - **Milvus**：使用 milvus-backup 工具
> - **知识文件**：使用 `rsync`、`tar` 等标准文件系统备份工具

### 8.4.10 FeedbackApi（回答反馈）

```java
@FeignClient(contextId = "feedbackApi", name = "zmbdp-chat-service", path = "/feedback")
public interface FeedbackApi {

    /**
     * 提交回答反馈（点赞/点踩）
     * 业务规则：同一用户对同一对话只能反馈一次（uk_conversation_user 唯一索引 + @Idempotent 双重防重）
     */
    @PostMapping
    @Idempotent(message = "请勿重复提交反馈")  // 应用层幂等防重，配合唯一索引双重保障
    Result<FeedbackVO> submitFeedback(@RequestBody FeedbackReqDTO request,
                                       @RequestHeader("USER_ID") Long userId,
                                       @RequestHeader("USER_FROM") String userFrom);

    /**
     * 查询用户对某对话已提交的反馈
     */
    @GetMapping("/{conversationId}")
    Result<FeedbackVO> getFeedback(@PathVariable("conversationId") Long conversationId,
                                    @RequestHeader("USER_ID") Long userId);

    /**
     * 撤销反馈（物理删除记录）
     */
    @DeleteMapping("/{conversationId}")
    Result<Void> deleteFeedback(@PathVariable("conversationId") Long conversationId,
                                 @RequestHeader("USER_ID") Long userId);
}
```

> **说明**：FeedbackApi 由 portal-service 通过 Feign 调用，用户信息（userId、userFrom）从 Gateway AuthFilter 设置的 Header 中获取。反馈数据存储在 `sys_ai_feedback` 表（详见 07-项目架构设计.md 7.4.8 节）。
>
> **@Idempotent 注解位置重要说明**：`IdempotentAspect` 的切点定义为 `@annotation(com.zmbdp.common.idempotent.annotation.Idempotent)`，**只匹配目标类自己声明的方法上的注解，不会从接口继承**。因此：
> - Feign 接口 `FeedbackApi` 上的 `@Idempotent` **仅作为接口契约声明**，不会触发 AOP 切面
> - **实际生效位置**：必须在 chat-service 的 `FeedbackController.submitFeedback()` 方法上**显式添加** `@Idempotent(message = "请勿重复提交反馈")` 注解
> - Controller 实现类继承 Feign 接口时，方法签名一致，但注解需在实现类方法上重新声明

---

## 8.5 HTTP SSE端点（zmbdp-chat-service）

> **说明**：流式对话接口不通过Feign暴露，直接提供HTTP SSE端点，供portal-service通过WebClient调用。
> **重要**：以下端点是 `zmbdp-chat-service` 内部接口（不经过网关，不对外暴露），由 portal-service 通过 `@LoadBalanced` WebClient 调用 `lb://zmbdp-chat-service{path}`（通过 Nacos 服务发现解析实际地址，**不硬编码 IP/端口**）。

### 8.5.1 ChatStreamController（文本流式对话）

```java
@RestController
@RequestMapping("/chat")
public class ChatStreamController {

    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> streamChat(@RequestBody ChatStreamReqDTO request);
}
```

**内部调用URL**：`lb://zmbdp-chat-service/chat/completions/stream`（通过 `@LoadBalanced` WebClient 调用，Nacos 服务发现自动解析实际地址）

**请求参数**：`ChatStreamReqDTO`
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | String | 是 | 用户提问 |
| sessionId | String | 否 | 会话ID（不传则新建） |
| prompt | String | 是 | 完整Prompt（含RAG上下文+历史+提问） |
| model | String | 否 | 模型名称（默认 deepseek-v4-flash） |
| temperature | Double | 否 | 温度参数（默认 0.7） |

**响应格式**（SSE，与03-C端功能设计.md 3.2.1节、05-Agent工具设计.md 5.3.3节、07-项目架构设计.md 7.2.13节一致）：
```
data: {"chunk": "回答内容片段", "done": false}

data: {"toolCall": {"name": "ReadFileTool", "args": {"filePath": "/path/to/file"}}, "done": false}

data: {"toolResult": {"name": "ReadFileTool", "success": true, "summary": "读取文件成功，共123行", "duration": 45}, "done": false}

data: {"chunk": "", "done": true, "sessionId": "xxx", "sources": [...]}
```

> **SSE 帧类型**：
> - **内容帧**（`chunk`）：AI 生成的回答内容片段
> - **工具调用开始帧**（`toolCall`）：AI 决定调用工具时发送，告知前端正在调用哪个工具
> - **工具调用结果帧**（`toolResult`）：工具执行完成后发送，告知前端工具执行结果摘要
> - **结束帧**（`done: true`）：流结束，携带 sessionId、sources、model 等元数据
> - **错误帧**（`error`）：异常时发送，如 `{"error": "AI服务连接失败", "code": 500023, "done": true}`

### 8.5.2 ChatWithImageStreamController（图文流式对话）

```java
@RestController
@RequestMapping("/chat/image")
public class ChatWithImageStreamController {

    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> streamChatWithImage(@RequestBody ChatWithImageStreamReqDTO request);
}
```

**内部调用URL**：`lb://zmbdp-chat-service/chat/image/completions/stream`（通过 `@LoadBalanced` WebClient 调用，Nacos 服务发现自动解析实际地址）

**请求参数**：`ChatWithImageStreamReqDTO`
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | String | 是 | 用户提问 |
| images | List<String> | 是 | 图片Base64编码列表 |
| sessionId | String | 否 | 会话ID |
| prompt | String | 是 | 完整Prompt（含上下文） |
| model | String | 否 | 模型名称（默认 qwen-vl-plus） |

---

## 8.6 接口详细设计

### 8.6.1 POST /portal/chat/completions/stream (流式文本对话)

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "message": "三级缓存怎么用？",
  "sessionId": "abc123",
  "model": "deepseek-v4-flash",
  "temperature": 0.7,
  "topK": 5
}
```

| 字段         | 类型      | 必填  | 说明                 |
| ---------- | ------- | --- | ------------------ |
| `message`  | String  | 是   | 用户消息               |
| `sessionId` | String  | 否   | 会话ID，不传则新建会话        |
| `model`    | String  | 否   | 模型名称，默认使用配置的主模型    |
| `temperature` | Double | 否   | 温度系数，0-1，默认0.7     |
| `topK`     | Integer | 否   | RAG检索数量，默认5        |

**响应体**：Server-Sent Events (SSE)

```
data: {"chunk": "三级缓存架构包含", "done": false}

data: {"chunk": "布隆过滤器、Caffeine本地缓存", "done": false}

data: {"chunk": "和Redis分布式缓存...", "done": false}

data: {"chunk": "", "done": true, "sessionId": "abc123", "sources": [...], "model": "deepseek-v4-flash"}
```

**调用链路**：portal-service `PortalChatController.streamChat()` → `PortalChatService.streamChat()` → Feign `ChatApi.retrieveContext()`（RAG检索） + WebClient `/chat/completions/stream`（SSE流式调用 chat-service） → chat-service `ChatStreamController.streamChat()` → `IChatService.streamChat()` → Spring AI ChatClient

---

### 8.6.2 POST /portal/chat/image/completions/stream (流式图文对话)

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "message": "这张图片里的代码是什么意思？",
  "images": ["base64_image_data"],
  "sessionId": "abc123",
  "model": "qwen-vl-plus",
  "temperature": 0.7
}
```

| 字段         | 类型       | 必填  | 说明                 |
| ---------- | -------- | --- | ------------------ |
| `message`  | String   | 是   | 用户消息               |
| `images`   | String[] | 是   | 图片Base64编码数组        |
| `sessionId` | String   | 否   | 会话ID，不传则新建会话        |
| `model`    | String   | 否   | 模型名称，默认使用配置的图文模型   |
| `temperature` | Double  | 否   | 温度系数，0-1，默认0.7     |

**响应体**：同 8.6.1（SSE格式）

**调用链路**：portal-service `PortalChatController.streamChatWithImage()` → `PortalChatService.streamChatWithImage()` → Feign `ChatApi.retrieveContext()`（RAG检索） + WebClient `/chat/image/completions/stream`（SSE流式调用 chat-service） → chat-service `ChatWithImageStreamController.streamChatWithImage()` → `IChatService.streamChatWithImage()` → Spring AI ChatClient（视觉模型）

---

### 8.6.3 GET /portal/history

**请求头**：`Authorization: Bearer {token}`

**请求参数**：

| 参数名         | 类型      | 必填  | 说明                   |
| ----------- | ------- | --- | -------------------- |
| `pageNo`    | Integer | 否   | 页码，默认1               |
| `pageSize`  | Integer | 否   | 每页数量，默认20            |

**说明**：`userId` 从 JWT Token 中自动提取，前端无需传递。

**调用链路**：portal-service `HistoryController` → `PortalHistoryService` → Feign `HistoryApi.getHistoryList(userId, pageNo, pageSize)` → chat-service `IHistoryService.getHistoryList()`

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "list": [
      {
        "sessionId": "abc123",
        "lastMessage": "三级缓存怎么用？",
        "timestamp": 1700000000000,
        "model": "deepseek-v4-flash",
        "messageCount": 4
      }
    ],
    "totals": 100,
    "totalPages": 5
  }
}
```

---

### 8.6.4 GET /portal/history/{sessionId}

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数名         | 类型     | 必填  | 说明   |
| ----------- | ------ | --- | ---- |
| `sessionId` | String | 是   | 会话ID |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "sessionId": "abc123",
    "messages": [
      {
        "role": "user",
        "content": "三级缓存怎么用？",
        "timestamp": 1700000000000,
        "images": null
      },
      {
        "role": "assistant",
        "content": "三级缓存由布隆过滤器 + Caffeine + Redis 组成...",
        "timestamp": 1700000000000,
        "images": null,
        "sources": ["脚手架缓存设计"],
        "model": "deepseek-v4-flash"
      },
      {
        "role": "user",
        "content": "这张图片里是什么？",
        "timestamp": 1700000001000,
        "images": [
          "https://your-oss-bucket.oss-cn-beijing.aliyuncs.com/2026/07/19/test-image-001.png"
        ]
      }
    ]
  }
}
```

**字段说明**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `messages[].role` | String | 消息角色：user（用户提问）/ assistant（AI 回答） |
| `messages[].content` | String | 消息内容（user 取 question，assistant 取 answer） |
| `messages[].timestamp` | Long | 消息时间戳（毫秒，取自 sys_ai_conversation.create_time） |
| `messages[].images` | List&lt;String&gt; | 图片 URL 列表（仅 user 消息有效，图文对话时返回；纯文本对话或 assistant 消息为 null） |
| `messages[].sources` | List&lt;String&gt; | RAG 引用来源（文档标题列表，仅 assistant 消息有效，未命中 RAG 时为 null） |
| `messages[].model` | String | 模型名称（仅 assistant 消息有效） |

**数据源**：`sys_ai_conversation` 表（按 session_id 查询，按 create_time 正序）。每条记录拆分为 user + assistant 两条 Message；answer 为空时跳过 assistant 消息（FAILED 且无响应的情况）。

**调用链路**：portal-service `HistoryController.getSessionHistory()` → `PortalHistoryService.getSessionHistory()` → Feign `HistoryApi.getSessionHistory(sessionId)` → chat-service `IHistoryService.getSessionHistory()` → `SysAiConversationMapper.selectListBySessionId()`

---

### 8.6.5 DELETE /portal/history/{sessionId}

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数名         | 类型     | 必填  | 说明   |
| ----------- | ------ | --- | ---- |
| `sessionId` | String | 是   | 会话ID |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

**调用链路**：portal-service `HistoryController.deleteSession()` → `PortalHistoryService.deleteSession()` → Feign `HistoryApi.deleteSession(sessionId)` → chat-service `IHistoryService.deleteSession()` → 删除 MySQL 记录 + Redis 缓存

---

### 8.6.6 GET /admin/knowledge/logs（AI调用链路日志）

**请求头**：`Authorization: Bearer {token}`

**请求参数**：

| 参数名         | 类型      | 必填  | 说明                                |
| ----------- | ------- | --- | --------------------------------- |
| `pageNo`    | Integer | 否   | 页码，默认1                            |
| `pageSize`  | Integer | 否   | 每页数量，默认20                         |
| `operationType` | String  | 否   | AI操作类型过滤（CHAT/RETRIEVE/EMBEDDING/RERANK） |
| `model`     | String  | 否   | 模型名称过滤（如 deepseek-v4-flash） |
| `status`    | String  | 否   | 调用状态过滤（SUCCESS/FAILED/TIMEOUT） |
| `startDate` | Long    | 否   | 开始日期（格式：20260712）                 |
| `endDate`   | Long    | 否   | 结束日期（格式：20260712）                 |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "list": [
      {
        "id": 100001,
        "userId": 1001,
        "userFrom": "app",
        "conversationId": 1234567890,
        "operationType": "CHAT",
        "model": "deepseek-v4-flash",
        "promptTokens": 1200,
        "completionTokens": 350,
        "totalTokens": 1550,
        "responseTime": 2300,
        "status": "SUCCESS",
        "errorMsg": null,
        "createDate": 20260714,
        "createTime": "2026-07-14 15:30:00"
      }
    ],
    "totals": 100,
    "totalPages": 5
  }
}
```

> **说明**：本接口查询 AI 调用链路日志（`sys_ai_operation_log` 表），列表页仅返回摘要信息（不含完整 prompt/response，避免响应过大）。完整链路详情通过 8.6.35 接口（`GET /admin/statistics/ai-metrics/{operationId}`）查看。B端管理操作审计（知识源CRUD等）由脚手架 `@LogAction` 自动记录到 `operation_log` 表。

**调用链路**：admin-service `LogController.getLogs()` → Feign `OperationLogApi.getLogs()` → chat-service `IAdminService.listLogs()` → `SysAiOperationLogMapper`（查询 `sys_ai_operation_log` 表，详见 07-项目架构设计.md 7.4.3 节）

---

### 8.6.7 GET /admin/knowledge/sources

**请求头**：`Authorization: Bearer {token}`

**请求参数**：

| 参数名       | 类型      | 必填  | 说明         |
| --------- | ------- | --- | ---------- |
| `pageNo`  | Integer | 否   | 页码，默认1     |
| `pageSize`| Integer | 否   | 每页数量，默认10  |
| `type`    | String  | 否   | 类型过滤（doc/javadoc/config/code）        |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "list": [
      {
        "id": 1,
        "name": "文档知识库",
        "path": "/path/to/docs",
        "type": "doc",
        "enabled": true,
        "chunkSize": 500,
        "chunkOverlap": 50,
        "lastSyncDate": 20260712,
        "createDate": 20260712,
        "updateDate": 20260712
      }
    ],
    "totals": 10,
    "totalPages": 1
  }
}
```

**调用链路**：admin-service `KnowledgeController.getSources()` → Feign `KnowledgeApi.getSources()` → chat-service `IKnowledgeService.listSources()`

---

### 8.6.8 POST /admin/knowledge/sources

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "name": "文档知识库",
  "path": "/path/to/docs",
  "type": "doc",
  "enabled": true,
  "chunkSize": 500,
  "chunkOverlap": 50
}
```

| 字段          | 类型      | 必填  | 说明                 |
| ----------- | ------- | --- | ------------------ |
| `name`      | String  | 是   | 知识源名称              |
| `path`      | String  | 是   | 知识源路径              |
| `type`      | String  | 是   | 类型（doc/javadoc/config/code）   |
| `enabled`   | Boolean | 否   | 是否启用，默认true       |
| `chunkSize` | Integer | 否   | 分块大小，默认500         |
| `chunkOverlap` | Integer | 否   | 分块重叠大小，默认50      |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "id": 1,
    "name": "文档知识库",
    "path": "/path/to/docs",
    "type": "doc",
    "enabled": true,
    "chunkSize": 500,
    "chunkOverlap": 50,
    "createDate": 20260712,
    "updateDate": 20260712
  }
}
```

**调用链路**：admin-service `KnowledgeController.createSource()` → Feign `KnowledgeApi.addSource()` → chat-service `IKnowledgeService.createSource()`

---

### 8.6.9 PUT /admin/knowledge/sources/{id}

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `id` | Long | 是 | 知识源ID |

**请求体**：

```json
{
  "name": "文档知识库（更新）",
  "path": "/path/to/docs",
  "type": "doc",
  "enabled": true,
  "chunkSize": 800,
  "chunkOverlap": 80
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | String | 否 | 知识源名称 |
| `path` | String | 否 | 知识源路径 |
| `type` | String | 否 | 类型（doc/javadoc/config/code） |
| `enabled` | Boolean | 否 | 是否启用 |
| `chunkSize` | Integer | 否 | 分块大小（100-2000） |
| `chunkOverlap` | Integer | 否 | 分块重叠大小（0-200，需小于chunkSize） |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

**说明**：仅更新传入的非空字段；若修改 `path` 或 `chunkSize`/`chunkOverlap`，需手动触发知识同步以重建向量。

**调用链路**：admin-service `KnowledgeController.updateSource()` → Feign `KnowledgeApi.updateSource()` → chat-service `IKnowledgeService.updateSource()`

---

### 8.6.10 DELETE /admin/knowledge/sources/{id}

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `id` | Long | 是 | 知识源ID |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

**说明**：删除知识源会级联删除该知识源下所有文档（MySQL 软删除）和向量分块（Milvus 物理删除），操作不可逆。

**调用链路**：admin-service `KnowledgeController.deleteSource()` → Feign `KnowledgeApi.deleteSource()` → chat-service `IKnowledgeService.deleteSource()` → `IVectorStoreService.deleteByDocumentId()`（级联删除 Milvus 向量）

---

### 8.6.11 POST /admin/knowledge/sync

**说明**：本接口为**异步**接口。由于知识同步涉及扫描文件、Embedding 调用、Milvus 写入，耗时可能数分钟，同步 HTTP 调用会导致前端超时。改为 MQ 异步后，接口立即返回"已提交"提示，实际同步流程由 chat-service 消费端 `KnowledgeSyncConsumer` 异步执行，前端通过文档列表（GET /admin/knowledge/documents）查看同步结果。

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "sourceType": "all",
  "force": false
}
```

| 字段          | 类型      | 必填  | 说明                  |
| ----------- | ------- | --- | ------------------- |
| `sourceType` | String  | 否   | 同步类型（all/doc/javadoc/config/code），传 null 或 "all" 表示全部，默认 all |
| `force`     | Boolean | 否   | 是否强制重新同步，默认false     |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": "知识同步任务已提交，请稍后通过文档列表查看同步结果"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `data` | String | 异步任务提交提示信息（非同步结果，实际同步结果需查看文档列表） |

**调用链路**：admin-service `KnowledgeController.syncKnowledge()` → `IAiAdminService.syncKnowledge()` → 发送 MQ 消息到 `knowledge.sync.exchange`（Direct 交换机）→ chat-service `KnowledgeSyncConsumer.handleKnowledgeSync()` 消费 → `IKnowledgeLoaderService.syncKnowledge(sourceType, force)` 执行 12 步同步流程

**MQ 配置**：

| 配置项 | 值 | 说明 |
|------|------|------|
| 交换机 | `knowledge.sync.exchange` | Direct 类型，admin-service 声明（`KnowledgeSyncMqConfig`） |
| 队列 | `knowledge.sync.queue` | 持久化命名队列，chat-service 消费端通过 `@QueueBinding` 自动声明 |
| 路由键 | `knowledge.sync` | Direct 路由键 |
| 消息体 | `KnowledgeSyncMessage` | 含 sourceType、force 字段 |

**sourceType 筛选说明**：消费端调用 `syncKnowledge(sourceType, force)` 时，sourceType 非空且非 "all" 会作为查询条件过滤 `sys_ai_knowledge_source.type` 字段，仅同步匹配类型的知识源。

---

### 8.6.12 POST /admin/knowledge/documents/upload

**请求头**：`Authorization: Bearer {token}`

**请求参数**：

| 参数名                 | 类型            | 必填  | 说明    |
| ------------------- | ------------- | --- | ----- |
| `knowledgeSourceId` | Long          | 是   | 知识源ID |
| `file`              | MultipartFile | 是   | 文件    |

**文件限制**：

- 最大大小：50MB
- 支持类型：.md, .txt, .html, .java, .py, .xml, .json

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "id": 1,
    "title": "三级缓存架构.md",
    "path": "/path/to/docs/CACHE_ARCHITECTURE.md",
    "type": "doc",
    "status": "ACTIVE",
    "createDate": 20260712
  }
}
```

**调用链路**：admin-service `KnowledgeController.uploadDocument()`（直接处理，不走 Feign，因涉及 `MultipartFile`） → chat-service `IKnowledgeService.uploadDocument()` → `IKnowledgeLoaderService.syncKnowledge()`

---

### 8.6.13 GET /admin/knowledge/documents/{id}

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数名  | 类型   | 必填  | 说明   |
| ---- | ---- | --- | ---- |
| `id` | Long | 是   | 文档ID |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "id": 1,
    "knowledgeSourceId": 1,
    "title": "三级缓存架构.md",
    "path": "/path/to/docs/CACHE_ARCHITECTURE.md",
    "content": "完整文档内容...",
    "type": "doc",
    "module": "common-cache",
    "category": "cache",
    "status": "ACTIVE",
    "version": 1,
    "chunkCount": 5,
    "createDate": 20260712,
    "updateDate": 20260712
  }
}
```

**调用链路**：admin-service `KnowledgeController.getDocument()` → Feign `KnowledgeApi.getDocument()` → chat-service `IKnowledgeService.getDocument()`

---

### 8.6.14 DELETE /admin/knowledge/documents/{id}

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数名  | 类型   | 必填  | 说明   |
| ---- | ---- | --- | ---- |
| `id` | Long | 是   | 文档ID |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

**调用链路**：admin-service `KnowledgeController.deleteDocument()` → Feign `KnowledgeApi.deleteDocument()` → chat-service `IKnowledgeService.deleteDocument()` → `IVectorStoreService.deleteByDocumentId()`（级联删除 Milvus 向量）

---

### 8.6.15 GET /admin/ai/config

**请求头**：`Authorization: Bearer {token}`

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "defaultModel": "deepseek-v4-flash",
    "defaultVisionModel": "qwen-vl-plus",
    "temperature": 0.7,
    "maxTokens": 4096,
    "embeddingModel": "text-embedding-v1",
    "topK": 5,
    "enableRag": true,
    "enableTools": true,
    "apiKey": "sk-1***wxyz"
  }
}
```

> **说明**：`apiKey` 字段为脱敏显示（仅展示前后4位），api-key 属基础设施配置，统一在 Nacos 管理，不可通过此接口修改。

**调用链路**：admin-service `AiConfigController.getConfig()` → Feign `AiConfigApi.getConfig()` → chat-service `IAdminService.getAiConfig()` → Feign `ArgumentServiceApi`（读取 `sys_argument` 表的 `ai.*` 配置项）+ Nacos 配置（读取基础设施配置）

---

### 8.6.16 PUT /admin/ai/config

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "defaultModel": "deepseek-v4-flash",
  "defaultVisionModel": "qwen-vl-plus",
  "temperature": 0.5,
  "maxTokens": 4096,
  "topK": 5,
  "enableRag": true,
  "enableTools": true
}
```

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

**调用链路**：admin-service `AiConfigController.updateConfig()` → Feign `AiConfigApi.updateConfig()` → chat-service `IAdminService.updateAiConfig()` → Feign `ArgumentServiceApi`（更新 `sys_argument` 表的 `ai.*` 配置项）。注：api-key 等基础设施配置在 Nacos 管理，不通过此接口修改

---

### 8.6.17 GET /admin/ai/models

**请求头**：`Authorization: Bearer {token}`

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": [
    {
      "name": "deepseek-v4-flash",
      "provider": "alibaba",
      "type": "text",
      "capabilities": ["text"],
      "enabled": true,
      "description": "通义千问大模型"
    },
    {
      "name": "qwen-vl-plus",
      "provider": "alibaba",
      "type": "vision",
      "capabilities": ["text", "image"],
      "enabled": true,
      "description": "通义千问视觉大模型"
    }
  ]
}
```

**调用链路**：admin-service `AiConfigController.getModels()` → Feign `AiConfigApi.getModels()` → chat-service `IModelService.listModels()`

---

### 8.6.18 POST /admin/ai/test

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "model": "deepseek-v4-flash",
  "message": "hello"
}
```

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "success": true,
    "response": "你好！有什么我可以帮助你的吗？",
    "latency": 1500
  }
}
```

**调用链路**：admin-service `AiConfigController.testConnection()` → Feign `AiConfigApi.testConnection()` → chat-service `IModelService.testConnection()` → Spring AI ChatClient（测试调用）

---

### 8.6.19 GET /admin/statistics/conversations

**请求头**：`Authorization: Bearer {token}`

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "totalConversations": 1000,
    "todayConversations": 50,
    "avgResponseTime": 2500,
    "activeUsers": 20,
    "trendData": [
      {"date": "2026-07-10", "count": 45},
      {"date": "2026-07-11", "count": 55},
      {"date": "2026-07-12", "count": 50}
    ]
  }
}
```

**调用链路**：admin-service `StatisticsController.getConversationStatistics()` → Feign `StatisticsApi.getConversationStatistics()` → chat-service `IStatisticsService.getConversationStats()`（含 Redis 缓存）

---

### 8.6.20 GET /admin/statistics/ai-metrics

**请求头**：`Authorization: Bearer {token}`

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "totalAiCalls": 2000,
    "successRate": 95.5,
    "avgTokenUsage": 1500,
    "totalTokenUsage": 3000000,
    "avgLatency": 3200,
    "topModels": [
      {"model": "deepseek-v4-flash", "count": 1800},
      {"model": "qwen-vl-plus", "count": 200}
    ]
  }
}
```

**调用链路**：admin-service `StatisticsController.getAiMetrics()` → Feign `StatisticsApi.getAiMetrics()` → chat-service `IStatisticsService.getAiCallMetrics()`

---

### 8.6.21 GET /admin/tools

**请求头**：`Authorization: Bearer {token}`

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": [
    {
      "name": "ReadFileTool",
      "description": "读取指定文件内容",
      "enabled": true,
      "config": {
        "maxFileSize": 10485760,
        "pathWhitelist": ["/project/src", "/project/docs"]
      },
      "lastUsedTime": 1700000000000,
      "usedCount": 150
    },
    {
      "name": "SearchCodeTool",
      "description": "搜索代码中的类/方法",
      "enabled": true,
      "config": {
        "limit": 10
      },
      "lastUsedTime": 1700000000000,
      "usedCount": 80
    }
  ]
}
```

> **说明**：工具列表不分页，返回所有已注册的工具。每个工具的 `config` 字段内容因工具类型而异。

**调用链路**：admin-service `ToolController.getTools()` → Feign `ToolsApi.getTools()` → chat-service `IAdminService.listTools()`

---

### 8.6.22 GET /admin/knowledge/documents

**请求头**：`Authorization: Bearer {token}`

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `pageNo` | Integer | 否 | 页码，默认1 |
| `pageSize` | Integer | 否 | 每页数量，默认10 |
| `sourceId` | Long | 否 | 知识源ID过滤 |
| `status` | String | 否 | 状态过滤（ACTIVE/DELETED/SYNCING） |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "list": [
      {
        "id": 1,
        "knowledgeSourceId": 1,
        "title": "三级缓存架构.md",
        "path": "/path/to/docs/CACHE_ARCHITECTURE.md",
        "type": "doc",
        "module": "common-cache",
        "category": "cache",
        "status": "ACTIVE",
        "version": 1,
        "chunkCount": 5,
        "fileSize": 10240,
        "createDate": 20260712,
        "updateDate": 20260712
      }
    ],
    "totals": 100,
    "totalPages": 10
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 文档ID |
| `knowledgeSourceId` | Long | 知识源ID |
| `title` | String | 文档标题 |
| `path` | String | 文件路径 |
| `type` | String | 类型（doc/javadoc/config/code） |
| `module` | String | 所属模块 |
| `category` | String | 功能类别 |
| `status` | String | 状态（ACTIVE/DELETED/SYNCING） |
| `version` | Integer | 版本号 |
| `chunkCount` | Integer | 分块数量 |
| `fileSize` | Long | 文件大小（字节） |
| `createDate` | Long | 创建日期（YYYYMMDD） |
| `updateDate` | Long | 更新日期（YYYYMMDD） |

**调用链路**：admin-service `KnowledgeController.getDocuments()` → Feign `KnowledgeApi.getDocuments()` → chat-service `IKnowledgeService.listDocuments()`

---

### 8.6.23 GET /admin/statistics/questions

**请求头**：`Authorization: Bearer {token}`

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `limit` | Integer | 否 | 返回数量，默认10 |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": [
    {
      "question": "三级缓存怎么用？",
      "count": 56,
      "lastAskedTime": 1700000000000
    },
    {
      "question": "@Idempotent注解有哪些参数？",
      "count": 43,
      "lastAskedTime": 1699999999000
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `question` | String | 问题内容 |
| `count` | Integer | 被问次数 |
| `lastAskedTime` | Long | 最后一次提问时间戳 |

**说明**：从 `sys_ai_conversation` 表按 `question` 字段分组统计（过滤 `is_deleted=0`），按 count 降序排序。

**调用链路**：admin-service `StatisticsController.getHotQuestions()` → Feign `StatisticsApi.getHotQuestions()` → chat-service `IStatisticsService.getTopQuestions()`

---

### 8.6.24 GET /admin/statistics/users

**请求头**：`Authorization: Bearer {token}`

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `days` | Integer | 否 | 统计天数，默认7 |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "activeUsers": 20,
    "totalUsers": 50,
    "topUsers": [
      {
        "userId": 1,
        "username": "zhangsan",
        "conversationCount": 50,
        "lastActiveTime": 1700000000000
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `activeUsers` | Integer | 活跃用户数（近N天有对话记录） |
| `totalUsers` | Integer | 总用户数 |
| `topUsers` | List | 活跃用户列表（按对话次数排序） |
| `topUsers[].userId` | Long | 用户ID |
| `topUsers[].username` | String | 用户名 |
| `topUsers[].conversationCount` | Integer | 对话次数 |
| `topUsers[].lastActiveTime` | Long | 最后活跃时间戳 |

**调用链路**：admin-service `StatisticsController.getUserStatistics()` → Feign `StatisticsApi.getUserStatistics()` → chat-service `IStatisticsService.getUserStats()`

---

### 8.6.25 GET /admin/statistics/tools

**请求头**：`Authorization: Bearer {token}`

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "totalCalls": 300,
    "toolUsage": [
      {
        "toolName": "ReadFileTool",
        "callCount": 150,
        "successCount": 145,
        "failCount": 5,
        "lastUsedTime": 1700000000000
      },
      {
        "toolName": "SearchCodeTool",
        "callCount": 80,
        "successCount": 78,
        "failCount": 2,
        "lastUsedTime": 1700000000000
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalCalls` | Integer | 工具总调用次数 |
| `toolUsage` | List | 工具使用详情列表 |
| `toolUsage[].toolName` | String | 工具名称 |
| `toolUsage[].callCount` | Integer | 调用次数 |
| `toolUsage[].successCount` | Integer | 成功次数 |
| `toolUsage[].failCount` | Integer | 失败次数 |
| `toolUsage[].lastUsedTime` | Long | 最后使用时间戳 |

**说明**：从 `sys_ai_operation_log` 表的 `tool_calls` 字段（JSON数组）解析工具调用记录，按工具名称分组统计。

**调用链路**：admin-service `StatisticsController.getToolStatistics()` → Feign `StatisticsApi.getToolStatistics()` → chat-service `IStatisticsService.getToolUsageStats()`

---

### 8.6.26 PUT /admin/tools/{name}

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `name` | String | 是 | 工具名称（如 ReadFileTool） |

**请求体**：

```json
{
  "enabled": true,
  "config": {
    "maxFileSize": 10485760,
    "pathWhitelist": ["/project/src", "/project/docs"]
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `enabled` | Boolean | 否 | 是否启用工具 |
| `config` | Map<String, Object> | 否 | 工具配置参数（JSON格式，不同工具配置不同） |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

**说明**：更新后立即生效；若 `enabled` 从 `false` 改为 `true`，工具会被 `ToolRegistryService` 重新注册；反之则被移除。`config` 字段内容因工具类型而异（如 ReadFileTool 的 `maxFileSize`、`pathWhitelist`，SearchCodeTool 的 `limit` 等）。

**调用链路**：admin-service `ToolController.updateToolConfig()` → Feign `ToolsApi.updateToolConfig()` → chat-service `IAdminService.updateToolConfig()` → `ArgumentServiceApi`（更新 sys_argument 表的 ai.tool.{toolName}.enabled 配置项）+ `ToolRegistryService` 刷新工具注册（详见 07-项目架构设计.md 7.0.4 节）

---

### 8.6.27 POST /admin/tools/{name}/test

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `name` | String | 是 | 工具名称 |

**请求体**：

```json
{
  "params": {
    "filePath": "/path/to/test/file.java"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `params` | Map<String, Object> | 是 | 测试参数（不同工具参数不同） |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "success": true,
    "result": "文件内容...",
    "duration": 150,
    "errorMsg": null
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | Boolean | 是否执行成功 |
| `result` | Object | 工具执行结果（String 或 JSON） |
| `duration` | Long | 执行耗时（毫秒） |
| `errorMsg` | String | 失败时的错误信息 |

**说明**：测试工具不会影响线上对话流；若工具执行超时（30秒），返回 `success=false`，`errorMsg="工具执行超时"`。

**调用链路**：admin-service `ToolController.testTool()` → Feign `ToolsApi.testTool()` → chat-service `IAdminService.testTool()` → 调用对应工具的 `@Tool` 方法

---

### 8.6.28 ~~POST /admin/data/backup~~（已移除）

> **已移除**：数据备份不在应用层实现，由 DevOps/DBA 工具负责（详见 04-B端功能设计.md 4.1 节说明）。MySQL 使用 `mysqldump`，Milvus 使用 milvus-backup 工具。

---

### 8.6.29 ~~POST /admin/data/restore~~（已移除）

> **已移除**：数据恢复不在应用层实现，由 DevOps/DBA 工具负责（详见 04-B端功能设计.md 4.1 节说明）。MySQL 使用 `mysqldump` 恢复，Milvus 使用 milvus-backup 工具。

### 8.6.30 POST /portal/feedback（提交回答反馈）

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "conversationId": 1234567890,
  "feedbackType": "DISLIKE",
  "dislikeReason": "CODE_ERROR",
  "comment": "代码示例中缺少import语句"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `conversationId` | Long | 是 | 对话记录ID（关联sys_ai_conversation.id） |
| `feedbackType` | String | 是 | 反馈类型（LIKE/DISLIKE，见 FeedbackType 枚举） |
| `dislikeReason` | String | 否 | 点踩原因（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，feedbackType=DISLIKE时必填） |
| `comment` | String | 否 | 文字评论（最多500字符） |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "id": 9876543210,
    "conversationId": 1234567890,
    "userId": 1001,
    "feedbackType": "DISLIKE",
    "dislikeReason": "CODE_ERROR",
    "comment": "代码示例中缺少import语句",
    "createDate": 20260714,
    "createTime": "2026-07-14 15:30:00"
  }
}
```

**业务规则**：
1. 同一用户对同一对话只能反馈一次（唯一索引 `uk_conversation_user` 保证）
2. 接口加 `@Idempotent(message = "请勿重复提交反馈")` 注解（复用 `zmbdp-common-idempotent`），网络重试/双击场景在应用层直接拦截，避免重复提交报错
3. 重复反馈返回错误码 `500030`（反馈已存在，请先撤销再重新提交）——唯一索引作为最终防线
4. feedbackType=DISLIKE 时，dislikeReason 必填（Service 层校验，缺失返回 `500031`）

**调用链路**：portal-service `FeedbackPortalController.submitFeedback()` → Feign `FeedbackApi.submitFeedback()` → chat-service `FeedbackController.submitFeedback()` → `IFeedbackService.submitFeedback()`

---

### 8.6.31 GET /portal/feedback/{conversationId}（查询用户已提交的反馈）

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `conversationId` | Long | 对话记录ID |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "id": 9876543210,
    "conversationId": 1234567890,
    "userId": 1001,
    "feedbackType": "DISLIKE",
    "dislikeReason": "CODE_ERROR",
    "comment": "代码示例中缺少import语句",
    "createDate": 20260714,
    "createTime": "2026-07-14 15:30:00"
  }
}
```

> **说明**：用户未反馈时返回 `data: null`（非错误）。前端根据返回结果控制点赞/点踩按钮的选中状态。

**调用链路**：portal-service `FeedbackPortalController.getFeedback()` → Feign `FeedbackApi.getFeedback()` → chat-service `FeedbackController.getFeedback()` → `IFeedbackService.getFeedback()`

---

### 8.6.32 DELETE /portal/feedback/{conversationId}（撤销反馈）

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `conversationId` | Long | 对话记录ID |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

> **说明**：物理删除反馈记录（不软删除）。用户撤销后可重新提交反馈。

**调用链路**：portal-service `FeedbackPortalController.deleteFeedback()` → Feign `FeedbackApi.deleteFeedback()` → chat-service `FeedbackController.deleteFeedback()` → `IFeedbackService.deleteFeedback()`

---

### 8.6.33 POST /admin/knowledge/retrieve-test（知识源召回测试）

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "question": "三级缓存怎么用？",
  "topK": 5,
  "sourceType": "doc",
  "module": "common-cache"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `question` | String | 是 | 测试问题 |
| `topK` | Integer | 否 | 检索数量，默认5 |
| `sourceType` | String | 否 | 文档类型过滤（doc/javadoc/config/code） |
| `module` | String | 否 | 模块过滤 |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": [
    {
      "content": "三级缓存架构包含布隆过滤器、Caffeine本地缓存和Redis分布式缓存...",
      "title": "三级缓存架构.md",
      "module": "common-cache",
      "sourcePath": "docs/cache/三级缓存架构.md",
      "score": 0.95,
      "chunkIndex": 0
    }
  ]
}
```

> **说明**：复用 `/chat/retrieve` 的 RAG 检索能力（Embedding生成 → Milvus检索 → Reranking重排序），用于管理员验证知识库质量。不调用LLM，仅返回检索结果。

**调用链路**：admin-service → Feign `KnowledgeApi.retrieveTest()` → chat-service `KnowledgeController.retrieveTest()` → `IChatService.retrieve()`（复用RAG检索逻辑）

---

### 8.6.34 GET /admin/system/health（系统健康状态）

**请求头**：`Authorization: Bearer {token}`

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "overallStatus": "UP",
    "components": [
      {
        "name": "MySQL",
        "status": "UP",
        "latency": 5,
        "details": "连接正常"
      },
      {
        "name": "Redis",
        "status": "UP",
        "latency": 2,
        "details": "连接正常"
      },
      {
        "name": "Nacos",
        "status": "UP",
        "latency": 8,
        "details": "连接正常"
      },
      {
        "name": "Milvus",
        "status": "UP",
        "latency": 15,
        "details": "集合scaffold_knowledge正常"
      },
      {
        "name": "LLM",
        "status": "UP",
        "latency": 120,
        "details": "DashScope API可达"
      }
    ],
    "checkTime": "2026-07-14 15:30:00"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `overallStatus` | String | 整体状态（UP/DOWN/PARTIAL） |
| `components` | List | 各组件状态列表 |
| `components[].name` | String | 组件名称（MySQL/Redis/Nacos/Milvus/LLM） |
| `components[].status` | String | 组件状态（UP/DOWN） |
| `components[].latency` | Long | 检测延迟（毫秒） |
| `components[].details` | String | 详细信息（异常时含错误原因） |
| `checkTime` | String | 检测时间 |

> **检测方式**：
> - **MySQL**：执行 `SELECT 1` 测试连接
> - **Redis**：执行 `PING` 命令
> - **Nacos**：调用 Nacos OpenAPI `/ns/operator/metrics`
> - **Milvus**：调用 `MilvusServiceClient.getCollectionStats()`
> - **LLM**：调用 DashScope API 健康检查端点（轻量请求）

> **降级策略**：单个组件检测失败不影响其他组件检测结果，`overallStatus` 为 PARTIAL（部分组件异常），前端根据各组件 status 独立展示。

---

### 8.6.35 GET /admin/statistics/ai-metrics/{operationId}（单次AI调用详情）

**请求头**：`Authorization: Bearer {token}`

**路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `operationId` | Long | AI操作日志ID（sys_ai_operation_log.id） |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "id": 100001,
    "userId": 1001,
    "userFrom": "app",
    "conversationId": 1234567890,
    "operationType": "CHAT",
    "model": "deepseek-v4-flash",
    "prompt": "你是脚手架专家AI助手...[检索到的文档上下文]...[用户提问: 三级缓存怎么用？]",
    "response": "三级缓存架构包含布隆过滤器、Caffeine本地缓存和Redis分布式缓存...",
    "toolCalls": [
      {"name": "ReadFileTool", "success": true, "duration": 45, "summary": "读取CacheConfig.java成功"}
    ],
    "promptTokens": 1200,
    "completionTokens": 350,
    "totalTokens": 1550,
    "responseTime": 2300,
    "status": "SUCCESS",
    "errorMsg": null,
    "createDate": 20260714,
    "createTime": "2026-07-14 15:30:00"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 日志ID |
| `userId` | Long | 用户ID |
| `userFrom` | String | 用户来源（sys/app） |
| `conversationId` | Long | 关联对话ID |
| `operationType` | String | AI操作类型（CHAT/RETRIEVE/EMBEDDING/RERANK） |
| `model` | String | 模型名称 |
| `prompt` | String | 完整Prompt（含RAG上下文） |
| `response` | String | LLM完整响应 |
| `toolCalls` | List | 工具调用链路 |
| `toolCalls[].name` | String | 工具名称 |
| `toolCalls[].success` | Boolean | 是否成功 |
| `toolCalls[].duration` | Long | 耗时（毫秒） |
| `toolCalls[].summary` | String | 结果摘要 |
| `promptTokens` | Integer | Prompt Token数 |
| `completionTokens` | Integer | 响应 Token数 |
| `totalTokens` | Integer | 总 Token数 |
| `responseTime` | Long | 响应耗时（毫秒） |
| `status` | String | 调用状态（SUCCESS/FAILED/TIMEOUT） |
| `errorMsg` | String | 失败原因 |
| `createDate` | Long | 操作日期 |
| `createTime` | String | 操作时间 |

> **说明**：管理员通过此接口查看单次AI调用的完整链路，包含Prompt、LLM响应、工具调用详情和Token消耗，用于问题排查和效果评估。数据来源：`sys_ai_operation_log` 表的显式字段（`prompt`/`response`/`tool_calls`/`prompt_tokens` 等）。
>
> **埋点前置依赖**：`sys_ai_operation_log` 表的数据由 `ChatServiceImpl.recordOperationLog()` 在流式对话完成后异步写入。当前已实施到第二阶段：`prompt`/`response`/`status`/`response_time`/`prompt_tokens`/`completion_tokens`/`total_tokens` 字段已填充，仅 `tool_calls` 字段暂留 null（第三阶段通过 ToolCallback 装饰器补全），详见 [11-用户级AI调用统计设计.md](./11-用户级AI调用统计设计.md) 11.12 节。

**调用链路**：admin-service → Feign `StatisticsApi.getOperationDetail()` → chat-service `StatisticsController.getOperationDetail()` → `IStatisticsService.getOperationDetail()` → 查询 `sys_ai_operation_log` 表

---

### 8.6.36 GET /admin/statistics/feedback（回答满意度统计）

**请求头**：`Authorization: Bearer {token}`

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `startDate` | Long | 否 | 起始日期（格式：20260712） |
| `endDate` | Long | 否 | 结束日期（格式：20260712） |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "likeCount": 120,
    "dislikeCount": 30,
    "totalFeedback": 150,
    "feedbackCount": 150,
    "totalConversations": 1200,
    "likeRate": 80.0,
    "dislikeRate": 20.0,
    "feedbackRate": 12.5,
    "dislikeReasonDistribution": [
      {"reason": "OUTDATED", "count": 5, "percentage": 16.7},
      {"reason": "IRRELEVANT", "count": 10, "percentage": 33.3},
      {"reason": "CODE_ERROR", "count": 12, "percentage": 40.0},
      {"reason": "OTHER", "count": 3, "percentage": 10.0}
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `likeCount` | Long | 点赞数 |
| `dislikeCount` | Long | 点踩数 |
| `totalFeedback` | Long | 反馈总数（likeCount + dislikeCount） |
| `feedbackCount` | Long | 反馈用户数（去重） |
| `totalConversations` | Long | 对话总数（用于计算反馈率） |
| `likeRate` | Double | 点赞率（%）（likeCount / totalFeedback × 100） |
| `dislikeRate` | Double | 点踩率（%）（dislikeCount / totalFeedback × 100） |
| `feedbackRate` | Double | 反馈率（%）（totalFeedback / totalConversations × 100） |
| `dislikeReasonDistribution` | List | 点踩原因分布 |
| `dislikeReasonDistribution[].reason` | String | 点踩原因（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER） |
| `dislikeReasonDistribution[].count` | Long | 该原因的点踩数 |
| `dislikeReasonDistribution[].percentage` | Double | 该原因占比（%） |

> **说明**：未传 startDate/endDate 时默认统计最近7天数据。`dislikeReasonDistribution` 帮助管理员定位回答质量问题（如 CODE_ERROR 占比高说明知识库代码分块不准确，需优化分块策略）。

**调用链路**：admin-service → Feign `StatisticsApi.getFeedbackStatistics()` → chat-service `StatisticsController.getFeedbackStatistics()` → `IStatisticsService.getFeedbackStatistics()` → 查询 `sys_ai_feedback` 表聚合

---

### 8.6.37 GET /admin/feedback/list（B端反馈明细分页查询）

**请求头**：`Authorization: Bearer {token}`

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `pageNo` | Integer | 否 | 页码，默认1 |
| `pageSize` | Integer | 否 | 每页数量，默认20 |
| `feedbackType` | String | 否 | 反馈类型过滤（LIKE/DISLIKE） |
| `dislikeReason` | String | 否 | 点踩原因过滤（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，仅 feedbackType=DISLIKE 时有意义） |
| `userId` | Long | 否 | 用户ID过滤 |
| `startDate` | Long | 否 | 起始日期（格式：20260712） |
| `endDate` | Long | 否 | 结束日期（格式：20260712） |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "list": [
      {
        "id": 200001,
        "conversationId": 803176621614825472,
        "userId": 10000020,
        "userFrom": "app",
        "feedbackType": "DISLIKE",
        "dislikeReason": "CODE_ERROR",
        "comment": "示例代码有语法错误，缺少分号",
        "createTime": "2026-07-21 23:10:20",
        "question": "如何配置 Nacos 配置中心？",
        "answerSummary": "Nacos 配置中心的使用步骤如下：1. 引入依赖...（截断200字）",
        "model": "deepseek-v4-flash",
        "sources": ["脚手架总览README", "common-nacos 配置说明"]
      }
    ],
    "totals": 50,
    "totalPages": 3
  }
}
```

**响应字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `list[].id` | Long | 反馈ID |
| `list[].conversationId` | Long | 对话记录ID（关联 sys_ai_conversation.id） |
| `list[].userId` | Long | 反馈用户ID |
| `list[].userFrom` | String | 用户来源（sys/app） |
| `list[].feedbackType` | String | 反馈类型（LIKE/DISLIKE） |
| `list[].dislikeReason` | String | 点踩原因（仅 feedbackType=DISLIKE 时有值，OUTDATED/IRRELEVANT/CODE_ERROR/OTHER） |
| `list[].comment` | String | 文字评论 |
| `list[].createTime` | LocalDateTime | 反馈时间 |
| `list[].question` | String | 用户原始提问（SQL LEFT 截断 100 字，避免响应过大） |
| `list[].answerSummary` | String | AI 回答摘要（SQL LEFT 截断 200 字，完整回答可通过 8.6.4 接口查看） |
| `list[].model` | String | 使用的模型名称 |
| `list[].sources` | List&lt;String&gt; | RAG 引用来源列表（JSON 数组反序列化） |
| `totals` | Integer | 总记录数 |
| `totalPages` | Integer | 总页数 |

> **说明**：本接口为 B 端管理员查看用户反馈明细，单条记录同时返回反馈信息 + 对话问答摘要，管理员可一眼看到"用户问了什么、AI 答了什么、为什么点踩"。
>
> **answer 截断原因**：`sys_ai_conversation.answer` 字段为 LONGTEXT，AI 一次回答可能数千字。列表页若全量返回 20 条 × LONGTEXT 会导致响应体上 MB，前端渲染卡顿。故 SQL 层用 `LEFT(answer, 200)` 截断 200 字；如需查看完整 AI 回答，可通过 8.6.4 `GET /portal/history/{sessionId}` 会话详情接口查看（该接口返回完整 answer）。
>
> **数据来源**：`sys_ai_feedback` 表 LEFT JOIN `sys_ai_conversation` 表（ON `f.conversation_id = c.id`），一对一关系。LEFT JOIN 而非 INNER JOIN 是为了兼容对话记录被软删除但反馈记录仍存在的场景。

**SQL 设计**：

```sql
SELECT 
    f.id, f.conversation_id, f.user_id, f.user_from, 
    f.feedback_type, f.dislike_reason, f.comment, f.create_time,
    LEFT(c.question, 100) AS question,
    LEFT(c.answer, 200) AS answer_summary,
    c.model, c.sources
FROM sys_ai_feedback f
LEFT JOIN sys_ai_conversation c ON f.conversation_id = c.id
WHERE 1=1
  [AND f.feedback_type = #{feedbackType}]      -- 可选过滤
  [AND f.dislike_reason = #{dislikeReason}]    -- 可选过滤
  [AND f.user_id = #{userId}]                  -- 可选过滤
  [AND f.create_date >= #{startDate}]          -- 可选过滤
  [AND f.create_date <= #{endDate}]            -- 可选过滤
ORDER BY f.create_time DESC
```

> **分页实现**：使用 MyBatis-Plus `Page<FeedbackAdminVO>` 对象，分页插件自动注入 `LIMIT` 子句。`selectFeedbackListPage` 是自定义 Mapper 方法（XML 实现），因联表查询无法用 `LambdaQueryWrapper`。

**调用链路**：admin-service `FeedbackController.listFeedbacks()` → Feign `FeedbackApi.listFeedbacks()` → chat-service `FeedbackController.listFeedbacks()` → `IFeedbackService.listFeedbacks()` → `SysAiFeedbackMapper.selectFeedbackListPage()`（JOIN sys_ai_conversation）

---

**文档版本**：v1.7  
**创建日期**：2026-07-12  
**最后更新**：2026-07-21  
**适用版本**：Scaffold AI Assistant v1.0
