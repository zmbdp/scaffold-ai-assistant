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
>   - 示例：前端请求 `http://ip:10030/portal/chat/stream` → gateway 转发到 portal-service `/chat/stream`
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
| POST /portal/chat/stream | PortalChatController | PortalChatService | Feign ChatApi.retrieveContext() + WebClient /chat/completions/stream | 流式文本对话 |
| POST /portal/chat/stream/image | PortalChatController | PortalChatService | Feign ChatApi.retrieveContext() + WebClient /chat/image/completions/stream | 流式图文对话 |
| GET /portal/history | HistoryController | PortalHistoryService | Feign HistoryApi.getHistoryList() | 历史列表（从 JWT 提取 userId） |
| GET /portal/history/{sessionId} | HistoryController | PortalHistoryService | Feign HistoryApi.getSessionHistory() | 会话详情 |
| DELETE /portal/history/{sessionId} | HistoryController | PortalHistoryService | Feign HistoryApi.deleteSession() | 删除会话 |

### B端接口调用链路（zmbdp-admin-service → Feign → zmbdp-chat-service）

| 接口 | Controller | Feign 接口 | chat-service Service | 说明 |
|-----|-----------|-----------|---------------------|------|
| GET /admin/knowledge/sources | KnowledgeController | KnowledgeApi.getSources() | IKnowledgeService.listSources() | 知识源列表 |
| POST /admin/knowledge/sources | KnowledgeController | KnowledgeApi.addSource() | IKnowledgeService.createSource() | 新增知识源 |
| PUT /admin/knowledge/sources/{id} | KnowledgeController | KnowledgeApi.updateSource() | IKnowledgeService.updateSource() | 更新知识源 |
| DELETE /admin/knowledge/sources/{id} | KnowledgeController | KnowledgeApi.deleteSource() | IKnowledgeService.deleteSource() → IVectorStoreService.deleteByDocumentId() | 删除知识源（级联删除） |
| POST /admin/knowledge/sync | KnowledgeController | KnowledgeApi.sync() | IKnowledgeService.sync() → IKnowledgeLoaderService.syncKnowledge() | 知识同步 |
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
| POST /admin/data/backup | DataController | DataApi.backup() | IAdminService.backupData() | 数据备份 |
| POST /admin/data/restore | DataController | DataApi.restore() | IAdminService.restoreData() | 数据恢复 |

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
| POST   | `/portal/chat/stream` | `/chat/stream` | `PortalChatController.streamChat()` | 流式对话（文本） | WebClient |
| POST   | `/portal/chat/stream/image` | `/chat/stream/image` | `PortalChatController.streamChatWithImage()` | 流式对话（图文） | WebClient |
| GET | `/portal/history` | `/history` | `HistoryController.getHistory()` | 获取对话历史列表 | Feign |
| GET | `/portal/history/{sessionId}` | `/history/{sessionId}` | `HistoryController.getSessionHistory()` | 获取会话详情 | Feign |
| DELETE | `/portal/history/{sessionId}` | `/history/{sessionId}` | `HistoryController.deleteSession()` | 删除会话 | Feign |

### 8.2.1 流式对话流程说明

**portal-service业务编排步骤**：
1. 解析前端请求，提取用户提问、sessionId、模型配置等参数
2. **RAG检索**：通过Feign调用 `ChatApi.retrieveContext()` 获取相关文档上下文
3. **Prompt拼接**：将system prompt + 检索到的文档上下文 + 用户提问拼接为完整Prompt
4. **流式调用**：通过WebClient调用 `zmbdp-chat-service` 的SSE端点 `/chat/completions/stream`
5. **流透传**：接收Flux<String>流数据，直接透传给前端

**WebClient调用示例**（portal-service 中的 `PortalChatService`，注意与 chat-service 中的 `ChatService` 区分）：
```java
@Service
public class PortalChatService {
    private final WebClient chatWebClient;
    private final ChatApi chatApi;

    public Flux<String> streamChat(ChatReqDTO request) {
        // 步骤1: RAG检索获取上下文
        Result<List<DocumentVO>> contextResult = chatApi.retrieveContext(
            RetrieveReqDTO.builder()
                .question(request.getMessage())
                .topK(request.getTopK() != null ? request.getTopK() : 5)
                .build()
        );
        
        // 步骤2: 拼接Prompt
        String fullPrompt = buildPrompt(contextResult.getData(), request.getMessage());
        
        // 步骤3: WebClient调用流式端点
        return chatWebClient.post()
            .uri("/chat/completions/stream")
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
| GET | `/admin/statistics/ai-metrics` | `/statistics/ai-metrics` | 获取AI调用指标 |
| **工具管理** | | | |
| GET | `/admin/tools` | `/tools` | 获取工具列表 |
| PUT | `/admin/tools/{name}` | `/tools/{name}` | 更新工具配置 |
| POST | `/admin/tools/{name}/test` | `/tools/{name}/test` | 测试工具 |
| **数据管理** | | | |
| POST | `/admin/data/backup` | `/data/backup` | 执行数据备份 |
| POST | `/admin/data/restore` | `/data/restore` | 执行数据恢复 |

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

> **说明**：`userId` 由 portal-service 从 JWT Token 中解析后传递给 Feign 接口，chat-service 根据该 userId 过滤对话历史。

### 8.4.8 OperationLogApi（操作日志）

```java
@FeignClient(contextId = "operationLogApi", name = "zmbdp-chat-service", path = "/operation-log")
public interface OperationLogApi {

    @GetMapping("/list")
    Result<BasePageVO<OperationLogVO>> getLogs(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                               @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
                                               @RequestParam(value = "module", required = false) String module,
                                               @RequestParam(value = "operation", required = false) String operation,
                                               @RequestParam(value = "startDate", required = false) Long startDate,
                                               @RequestParam(value = "endDate", required = false) Long endDate);
}
```

### 8.4.9 DataApi（数据备份恢复）

```java
@FeignClient(contextId = "dataApi", name = "zmbdp-chat-service", path = "/data")
public interface DataApi {

    @PostMapping("/backup")
    Result<BackupResultVO> backup(@RequestBody BackupReqDTO dto);

    @PostMapping("/restore")
    Result<RestoreResultVO> restore(@RequestBody RestoreReqDTO dto);
}
```

---

## 8.5 HTTP SSE端点（zmbdp-chat-service）

> **说明**：流式对话接口不通过Feign暴露，直接提供HTTP SSE端点，供portal-service通过WebClient调用。
> **重要**：以下端点是 `zmbdp-chat-service` 内部接口（不经过网关，不对外暴露），由 portal-service 通过 WebClient 直接调用 `http://{chat-service-ip}:18084{path}`。

### 8.5.1 ChatStreamController（文本流式对话）

```java
@RestController
@RequestMapping("/chat")
public class ChatStreamController {

    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> streamChat(@RequestBody ChatStreamReqDTO request);
}
```

**内部调用URL**：`http://{chat-service-ip}:18084/chat/completions/stream`

**请求参数**：`ChatStreamReqDTO`
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | String | 是 | 用户提问 |
| sessionId | String | 否 | 会话ID（不传则新建） |
| prompt | String | 是 | 完整Prompt（含RAG上下文+历史+提问） |
| model | String | 否 | 模型名称（默认 qwen-max） |
| temperature | Double | 否 | 温度参数（默认 0.7） |

**响应格式**（SSE）：
```
data: {"chunk": "回答内容片段", "done": false}

data: {"chunk": "", "done": true, "sessionId": "xxx", "sources": [...]}
```

### 8.5.2 ChatWithImageStreamController（图文流式对话）

```java
@RestController
@RequestMapping("/chat/image")
public class ChatWithImageStreamController {

    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> streamChatWithImage(@RequestBody ChatWithImageStreamReqDTO request);
}
```

**内部调用URL**：`http://{chat-service-ip}:18084/chat/image/completions/stream`

**请求参数**：`ChatWithImageStreamReqDTO`
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | String | 是 | 用户提问 |
| images | List<String> | 是 | 图片Base64编码列表 |
| sessionId | String | 否 | 会话ID |
| prompt | String | 是 | 完整Prompt（含上下文） |
| model | String | 否 | 模型名称（默认 qwen-vl-max） |

---

## 8.6 接口详细设计

### 8.6.1 POST /portal/chat/stream (流式文本对话)

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "message": "三级缓存怎么用？",
  "sessionId": "abc123",
  "model": "qwen-max",
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

data: {"chunk": "", "done": true, "sessionId": "abc123", "sources": [...], "model": "qwen-max"}
```

**调用链路**：portal-service `PortalChatController.streamChat()` → `PortalChatService.streamChat()` → Feign `ChatApi.retrieveContext()`（RAG检索） + WebClient `/chat/completions/stream`（SSE流式调用 chat-service） → chat-service `ChatStreamController.streamChat()` → `IChatService.streamChat()` → Spring AI ChatClient

---

### 8.6.2 POST /portal/chat/stream/image (流式图文对话)

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "message": "这张图片里的代码是什么意思？",
  "images": ["base64_image_data"],
  "sessionId": "abc123",
  "model": "qwen-vl-max",
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
        "model": "qwen-max",
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
        "hasImage": false
      },
      {
        "role": "assistant",
        "content": "三级缓存架构包含布隆过滤器...",
        "timestamp": 1700000000001,
        "hasImage": false,
        "sources": [...],
        "model": "qwen-max"
      }
    ]
  }
}
```

**调用链路**：portal-service `HistoryController.getSessionHistory()` → `PortalHistoryService.getSessionHistory()` → Feign `HistoryApi.getSessionHistory(sessionId)` → chat-service `IHistoryService.getSessionHistory()`

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

### 8.6.6 GET /admin/knowledge/logs

**请求头**：`Authorization: Bearer {token}`

**请求参数**：

| 参数名         | 类型      | 必填  | 说明                                |
| ----------- | ------- | --- | --------------------------------- |
| `pageNo`    | Integer | 否   | 页码，默认1                            |
| `pageSize`  | Integer | 否   | 每页数量，默认20                         |
| `module`    | String  | 否   | 模块过滤（knowledge/ai_config/tools/statistics/data） |
| `operation` | String  | 否   | 操作类型过滤（CREATE/UPDATE/DELETE/SYNC等） |
| `startDate` | Long    | 否   | 开始日期（格式：20260311）                 |
| `endDate`   | Long    | 否   | 结束日期（格式：20260712）                 |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "list": [
      {
        "id": 1,
        "userId": 1,
        "username": "admin",
        "userFrom": "sys",
        "operation": "SYNC",
        "module": "knowledge",
        "targetId": 1,
        "targetType": "KnowledgeSource",
        "content": "知识库同步完成，新增文档10条",
        "status": "SUCCESS",
        "errorMsg": null,
        "ipAddress": "192.168.1.100",
        "createDate": 20260712,
        "createTime": "2026-07-12 10:00:00"
      }
    ],
    "totals": 100,
    "totalPages": 5
  }
}
```

**调用链路**：admin-service `LogController.getLogs()` → Feign `OperationLogApi.getLogs()` → chat-service `IAdminService.listLogs()`

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
| `sourceType` | String  | 否   | 同步类型（all/doc/code），默认all |
| `force`     | Boolean | 否   | 是否强制重新同步，默认false     |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "totalDocuments": 100,
    "updatedDocuments": 10,
    "deletedDocuments": 5,
    "skippedDocuments": 85,
    "failedDocuments": 0,
    "duration": 15000
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalDocuments` | Integer | 处理的文档总数 |
| `updatedDocuments` | Integer | 新增+更新的文档数 |
| `deletedDocuments` | Integer | 删除的文档数 |
| `skippedDocuments` | Integer | 跳过的文档数（哈希未变） |
| `failedDocuments` | Integer | 处理失败的文档数 |
| `duration` | Long | 同步耗时（毫秒） |

**调用链路**：admin-service `KnowledgeController` → Feign `KnowledgeApi.sync()` → chat-service `IKnowledgeService.sync()` → `IKnowledgeLoaderService.syncKnowledge()`

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
    "defaultModel": "qwen-max",
    "defaultVisionModel": "qwen-vl-max",
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

> **说明**：`apiKey` 字段为脱敏显示（仅展示前后4位），更新时传入完整 Key。

**调用链路**：admin-service `AiConfigController.getConfig()` → Feign `AiConfigApi.getConfig()` → chat-service `IAdminService.getAiConfig()` → `SysAiConfigMapper`（读取 MySQL）

---

### 8.6.16 PUT /admin/ai/config

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "defaultModel": "qwen-max",
  "defaultVisionModel": "qwen-vl-max",
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

**调用链路**：admin-service `AiConfigController.updateConfig()` → Feign `AiConfigApi.updateConfig()` → chat-service `IAdminService.updateAiConfig()` → `SysAiConfigMapper`（更新 MySQL）+ AES 加密 API Key

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
      "name": "qwen-max",
      "provider": "alibaba",
      "type": "text",
      "capabilities": ["text"],
      "enabled": true,
      "description": "通义千问大模型"
    },
    {
      "name": "qwen-vl-max",
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
  "model": "qwen-max",
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
      {"model": "qwen-max", "count": 1800},
      {"model": "qwen-vl-max", "count": 200}
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

**说明**：从 `sys_ai_conversation` 表按 user_message 字段分组统计，按 count 降序排序。

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

**说明**：从 `sys_ai_operation_log` 表按 `module='tools'` 分组统计。

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

**说明**：更新后立即生效；若 `enabled` 从 `false` 改为 `true`，工具会被重新注册到 `ToolExecutor`；反之则被移除。`config` 字段内容因工具类型而异（如 ReadFileTool 的 `maxFileSize`、`pathWhitelist`，SearchCodeTool 的 `limit` 等）。

**调用链路**：admin-service `ToolController.updateToolConfig()` → Feign `ToolsApi.updateToolConfig()` → chat-service `IAdminService.updateToolConfig()` → 刷新 `ToolExecutor`

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

### 8.6.28 POST /admin/data/backup

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "scope": "all",
  "includeDocuments": true,
  "includeVectors": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scope` | String | 否 | 备份范围（all/knowledge/ai_config），默认 all |
| `includeDocuments` | Boolean | 否 | 是否包含文档内容（MySQL），默认 true |
| `includeVectors` | Boolean | 否 | 是否包含向量数据（Milvus），默认 true |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "backupId": "backup_20260713_001",
    "backupFile": "/data/backups/backup_20260713_001.tar.gz",
    "fileSize": 10485760,
    "documentCount": 100,
    "vectorCount": 500,
    "createTime": 1700000000000
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `backupId` | String | 备份ID |
| `backupFile` | String | 备份文件路径 |
| `fileSize` | Long | 备份文件大小（字节） |
| `documentCount` | Integer | 备份的文档数量 |
| `vectorCount` | Integer | 备份的向量数量 |
| `createTime` | Long | 备份时间戳 |

**调用链路**：admin-service `DataController` → Feign `DataApi.backup()` → chat-service `IAdminService.backupData()` → `IVectorStoreService.exportVectors()` + `IKnowledgeService.exportDocuments()`

**说明**：备份操作可能耗时较长（视数据量而定），建议在非高峰期执行。备份文件存储在 `/data/backups/` 目录下。

---

### 8.6.29 POST /admin/data/restore

**请求头**：`Authorization: Bearer {token}`

**请求体**：

```json
{
  "backupId": "backup_20260713_001",
  "confirm": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `backupId` | String | 是 | 备份ID |
| `confirm` | Boolean | 是 | 确认恢复（必须为 true，防止误操作） |

**响应体**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "restoredDocuments": 100,
    "restoredVectors": 500,
    "duration": 30000
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `restoredDocuments` | Integer | 恢复的文档数量 |
| `restoredVectors` | Integer | 恢复的向量数量 |
| `duration` | Long | 恢复耗时（毫秒） |

**调用链路**：admin-service `DataController` → Feign `DataApi.restore()` → chat-service `IAdminService.restoreData()` → `IKnowledgeService.importDocuments()` + `IVectorStoreService.importVectors()`

**说明**：恢复操作会覆盖现有数据，`confirm` 参数必须为 `true` 才能执行。恢复前建议先备份当前数据。

---

**文档版本**：v1.5  
**创建日期**：2026-07-12  
**最后更新**：2026-07-13  
**适用版本**：Scaffold AI Assistant v1.0
