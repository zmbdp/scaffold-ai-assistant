## 通用说明

- **网关地址**：`http://localhost:10030`
- **认证 Header**（所有接口必填）：
  
  ```
  Authorization: Bearer {token}
  Content-Type: application/json
  ```
- **日期格式**：BIGINT，如 `20260712`（YYYYMMDD）
- **网关权限**：`/admin/**` 路径仅允许 B 端系统用户（JWT 中 `userFrom=sys`）访问，C 端用户 token（`userFrom=app`）会被网关 AuthFilter 拒绝并返回 `TOKEN_CHECK_FAILED`。测试 B 端接口时务必使用 B 端登录获取的 token，勿复用 C 端 `/portal/login/**` 产出的 token。

---

## 一、C端接口（前缀 `/portal`）

### 1.1 对话模块（SSE 流式）

#### 1. POST `/portal/chat/completions/stream` — 流式文本对话

**Body (JSON)**：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `message` | String | ✅ 是 | - | 用户提问内容 |
| `sessionId` | String | ❌ 否 | 新建 | 会话ID，不传则新建会话 |
| `model` | String | ❌ 否 | Nacos 默认文本模型 | 指定模型 |
| `temperature` | Double | ❌ 否 | 0.7 | 温度参数（0-2） |
| `topK` | Integer | ❌ 否 | 5 | RAG 检索数量（1-20） |

```json
{
  "message": "如何实现二级缓存？",
  "sessionId": "sess_20260719_001",
  "model": "deepseek-v4-flash",
  "temperature": 0.7,
  "topK": 5
}
```

**响应**：`text/event-stream`，每帧 `data: {chunk}\n\n`

---

#### 2. POST `/portal/chat/image/completions/stream` — 流式图文对话

**Body (JSON)**：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `message` | String | ✅ 是 | - | 用户提问内容 |
| `images` | List&lt;String&gt; | ✅ 是 | - | 图片 URL 列表（前端通过 file 服务签名直传 OSS 后返回的地址） |
| `sessionId` | String | ❌ 否 | 新建 | 会话ID，不传则新建会话 |
| `model` | String | ❌ 否 | Nacos 默认视觉模型 | 指定视觉模型 |
| `temperature` | Double | ❌ 否 | 0.7 | 温度参数（0-2） |
| `topK` | Integer | ❌ 否 | 5 | RAG 检索数量（1-20） |

```json
{
  "message": "这张图片里是什么？请描述一下",
  "images": [
    "https://your-oss-bucket.oss-cn-beijing.aliyuncs.com/2026/07/19/test-image-001.png",
    "https://your-oss-bucket.oss-cn-beijing.aliyuncs.com/2026/07/19/test-image-002.jpg"
  ],
  "sessionId": "sess_20260719_002",
  "model": "qwen-vl-max",
  "temperature": 0.7,
  "topK": 5
}
```

---

### 1.2 历史模块

#### 3. GET `/portal/history` — 获取对话历史列表（分页）

**Query 参数**：

| 参数名 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `pageNo` | Integer | ❌ 否 | 1 | 页码 |
| `pageSize` | Integer | ❌ 否 | 20 | 每页大小 |

---

#### 4. GET `/portal/history/{sessionId}` — 获取会话详情

**Path 参数**（必传）：`sessionId=sess_20260719_001`

**响应字段说明**：

| 字段                     | 类型                 | 含义                                                |
| ---------------------- | ------------------ | ------------------------------------------------- |
| `messages[].role`      | String             | 消息角色：user（用户提问）/ assistant（AI 回答）                 |
| `messages[].content`   | String             | 消息内容（user 取 question，assistant 取 answer）          |
| `messages[].timestamp` | Long               | 消息时间戳（毫秒，取自 sys_ai_conversation.create_time）      |
| `messages[].images`    | List&lt;String&gt; | 图片 URL 列表（仅 user 消息有效，图文对话时返回；纯文本对话或 assistant 消息为 null） |
| `messages[].sources`   | List&lt;String&gt; | RAG 引用来源（文档标题列表，仅 assistant 消息有效，未命中 RAG 时为 null） |
| `messages[].model`     | String             | 模型名称（仅 assistant 消息有效）                            |

> **数据源**：sys_ai_conversation 表（按 session_id 查询，按 create_time 正序）。每条记录拆分为 user + assistant 两条 Message；answer 为空时跳过 assistant 消息（FAILED 且无响应的情况）。
> **注意**：2026-07-21 之前的旧对话记录 `sources` 字段为 null（之前 buildConversationEntity 未填此字段），新对话才会有 sources。

---

#### 5. DELETE `/portal/history/{sessionId}` — 删除会话

**Path 参数**（必传）：`sessionId=sess_20260719_001`

---

### 1.3 反馈模块（覆盖语义）

#### 6. POST `/portal/feedback` — 提交反馈（点赞）

**Header 参数**：

| 参数名 | 类型 | 必传 | 说明 |
| --- | --- | --- | --- |
| `Idempotent-Token` | String | ✅ 是 | 幂等性 Token（任意唯一字符串，如 UUID 或时间戳），同一 Token 在过期时间内只允许一次请求。切换反馈类型时需换新 Token |

**Body (JSON)**：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `conversationId` | Long | ✅ 是 | - | 对话记录ID（雪花算法 Long），需先调用接口 1 获取真实值 |
| `feedbackType` | String | ✅ 是 | - | 反馈类型：`LIKE` / `DISLIKE` |
| `dislikeReason` | String | ⚠️ 条件必传 | - | 点踩原因（feedbackType=DISLIKE 时必填）：`OUTDATED` / `IRRELEVANT` / `CODE_ERROR` / `OTHER` |
| `comment` | String | ❌ 否 | - | 文字评论（最多 500 字符） |

**请求示例**（点赞）：

```
POST /portal/feedback
Authorization: Bearer {token}
Content-Type: application/json
Idempotent-Token: fb-20260721-001

{
  "conversationId": 1894736281934592,
  "feedbackType": "LIKE",
  "comment": "回答很详细，帮助很大"
}
```

**请求示例**（点踩，需用新的 Idempotent-Token）：

```
POST /portal/feedback
Authorization: Bearer {token}
Content-Type: application/json
Idempotent-Token: fb-20260721-002

{
  "conversationId": 1894736281934592,
  "feedbackType": "DISLIKE",
  "dislikeReason": "CODE_ERROR",
  "comment": "示例代码有语法错误"
}
```

> **幂等校验**：portal-service 入口层拦截，同一 Idempotent-Token 在过期时间内只允许一次请求；缺 Token 返回 400000「幂等性 Token 不能为空」。
> **覆盖语义**：重复提交会覆盖上一次反馈（先删后插），切换反馈类型时务必传新的 Idempotent-Token，否则会被拦截返回「请勿重复提交反馈」。

---

#### 7. GET `/portal/feedback/{conversationId}` — 查询反馈状态

**Path 参数**（必传）：`conversationId=1894736281934592`

---

#### 8. DELETE `/portal/feedback/{conversationId}` — 撤销反馈

**Path 参数**（必传）：`conversationId=1894736281934592`

---

## 二、B端接口（前缀 `/admin`）

### 2.1 知识库管理

#### 9. GET `/admin/knowledge/sources` — 获取知识源列表（分页）

**Query 参数**：

| 参数名 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `pageNo` | Integer | ❌ 否 | 1 | 页码 |
| `pageSize` | Integer | ❌ 否 | 10 | 每页大小 |
| `type` | String | ❌ 否 | 查全部 | 类型枚举：`doc` / `javadoc` / `config` / `code` |

---

#### 10. POST `/admin/knowledge/sources` — 新增知识源

**Body (JSON) 字段说明**：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `name` | String | ✅ 是 | - | 知识源名称 |
| `path` | String | ✅ 是 | - | 知识源路径（文件系统绝对路径） |
| `type` | String | ✅ 是 | - | 类型枚举：`doc` / `javadoc` / `config` / `code` |
| `enabled` | Boolean | ❌ 否 | true | 是否启用 |
| `chunkSize` | Integer | ❌ 否 | 500 | 分块大小（100-2000） |
| `chunkOverlap` | Integer | ❌ 否 | 50 | 分块重叠大小（0-200，需小于 chunkSize） |

**Body (JSON)** — 文档类型：

```json
{
  "name": "脚手架后端设计文档",
  "path": "d:/GitHub/scaffold-ai-assistant/scaffold项目设计docs/后端设计",
  "type": "doc",
  "enabled": true,
  "chunkSize": 500,
  "chunkOverlap": 50
}
```

> **path 路径需与服务部署环境一致**：
> 
> - 本地 IDEA 运行（dev）：`d:/GitHub/scaffold-ai-assistant/...`（宿主机绝对路径）
> - Docker 部署（test/prd）：`/knowledge/...`（容器内路径，由 docker-compose 的 `./scaffold-ai-assistant:/knowledge:ro` 挂载）

**Body (JSON)** — Java 源码类型：

```json
{
  "name": "脚手架核心源码",
  "path": "d:/GitHub/scaffold-ai-assistant/zmbdp-common",
  "type": "code",
  "enabled": true,
  "chunkSize": 800,
  "chunkOverlap": 100
}
```

**Body (JSON)** — JavaDoc 类型：

```json
{
  "name": "脚手架 JavaDoc",
  "path": "d:/GitHub/scaffold-ai-assistant/zmbdp-common/zmbdp-common-cache/src/main/java",
  "type": "javadoc",
  "enabled": true,
  "chunkSize": 500,
  "chunkOverlap": 50
}
```

**Body (JSON)** — 配置文件类型：

```json
{
  "name": "脚手架配置文件",
  "path": "d:/GitHub/scaffold-ai-assistant/zmbdp-common/zmbdp-common-cache/src/main/resources",
  "type": "config",
  "enabled": true,
  "chunkSize": 300,
  "chunkOverlap": 30
}
```

---

#### 11. PUT `/admin/knowledge/sources/{id}` — 更新知识源

**Path 参数**（必传）：`id=1`

**Body (JSON) 字段说明**（校验规则与新增一致，全量覆盖更新）：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `name` | String | ✅ 是 | - | 知识源名称 |
| `path` | String | ✅ 是 | - | 知识源路径（文件系统绝对路径） |
| `type` | String | ✅ 是 | - | 类型枚举：`doc` / `javadoc` / `config` / `code` |
| `enabled` | Boolean | ❌ 否 | true | 是否启用 |
| `chunkSize` | Integer | ❌ 否 | 500 | 分块大小（100-2000） |
| `chunkOverlap` | Integer | ❌ 否 | 50 | 分块重叠大小（0-200，需小于 chunkSize） |

**Body (JSON)**：

```json
{
  "name": "脚手架后端设计文档（更新版）",
  "path": "d:/GitHub/scaffold-ai-assistant/scaffold项目设计docs/后端设计",
  "type": "doc",
  "enabled": true,
  "chunkSize": 600,
  "chunkOverlap": 60
}
```

---

#### 12. DELETE `/admin/knowledge/sources/{id}` — 删除知识源（级联）

**Path 参数**（必传）：`id=1`

---

#### 13. POST `/admin/knowledge/sync` — 触发知识同步（异步）

> **异步说明**：本接口通过 MQ 异步执行知识同步，接口立即返回"已提交"提示，不等待同步完成。实际同步结果需通过 GET `/admin/knowledge/documents` 查看文档列表确认。同步流程：admin-service 发送 MQ 消息 → chat-service `KnowledgeSyncConsumer` 消费 → 执行 12 步同步流程（扫描文件→哈希比对→分块→向量化→写 Milvus）。

**Body (JSON) 字段说明**：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `sourceType` | String | ❌ 否 | `all` | 同步类型：`all`（全部）/ `doc`（仅 Markdown 文档）/ `javadoc`（仅 JavaDoc）/ `config`（仅配置文件）/ `code`（仅 Java 源码）。传 null 也表示全部 |
| `force` | Boolean | ❌ 否 | false | 是否强制全量重新同步（false 仅同步哈希变更的文件，true 全量重建） |

**Body (JSON)** — 全量增量同步：

```json
{
  "sourceType": "all",
  "force": false
}
```

**Body (JSON)** — 强制全量重新同步：

```json
{
  "sourceType": "all",
  "force": true
}
```

**Body (JSON)** — 仅同步代码类：

```json
{
  "sourceType": "code",
  "force": false
}
```

**响应示例**（立即返回，非同步结果）：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": "知识同步任务已提交，请稍后通过文档列表查看同步结果"
}
```

> **注意**：`data` 为提示信息字符串，不是 `SyncResultVO` 统计结果。同步流程在 chat-service 后台异步执行，统计结果仅记录在 chat-service 日志中。

---

#### 14. GET `/admin/knowledge/documents` — 获取文档列表（分页）

**Query 参数**：

| 参数名 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `pageNo` | Integer | ❌ 否 | 1 | 页码 |
| `pageSize` | Integer | ❌ 否 | 10 | 每页大小 |
| `sourceId` | Long | ❌ 否 | 查全部 | 知识源ID（先通过接口 9 获取真实值） |
| `status` | String | ❌ 否 | 查全部 | 文档状态枚举：`ACTIVE` / `DELETED` |

---

#### 15. GET `/admin/knowledge/documents/{id}` — 获取文档详情

**Path 参数**（必传）：`id=1894736281934593`

---

#### 16. DELETE `/admin/knowledge/documents/{id}` — 删除文档（级联删除向量）

**Path 参数**（必传）：`id=1894736281934593`

---

#### 17. POST `/admin/knowledge/retrieve-test` — 召回测试

**Body (JSON) 字段说明**：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `question` | String | ✅ 是 | - | 用户问题（用于生成 Embedding 向量） |
| `topK` | Integer | ❌ 否 | 5 | RAG 检索数量（1-20） |
| `sourceType` | String | ❌ 否 | 查全部 | 文档类型过滤：`doc` / `javadoc` / `config` / `code` |
| `module` | String | ❌ 否 | 查全部 | 模块过滤（如 `common-cache`） |

**Body (JSON)**：

```json
{
  "question": "如何实现 Caffeine + Redis 二级缓存？",
  "topK": 5,
  "sourceType": "doc",
  "module": "common-cache"
}
```

---

#### 18. POST `/admin/knowledge/documents/upload` — 上传文档到指定知识源

**Content-Type**：`multipart/form-data`

**Query 参数**：

| 参数名 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `knowledgeSourceId` | Long | ✅ 是 | - | 知识源ID（先通过接口 9 获取真实值） |

**Body (form-data)**：

| 字段 | 类型 | 必传 | 说明 |
| --- | --- | --- | --- |
| `file` | File | ✅ 是 | 文件二进制，大小 ≤ 50MB，扩展名 ∈ {`.md`, `.txt`, `.html`, `.java`, `.py`, `.xml`, `.json`} |

```
file=@/path/to/your-doc.md
```

> 上传后立即对该文件执行分块、向量化、写入 Milvus，不等定时同步任务

---

### 2.2 AI 配置管理

#### 19. GET `/admin/ai/config` — 获取 AI 配置

无参数

---

#### 20. PUT `/admin/ai/config` — 更新 AI 配置

**Body (JSON) 字段说明**（所有字段可选，只传需要更新的字段）：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `defaultModel` | String | ❌ 否 | Nacos 配置 | 默认文本模型名称（如 `deepseek-v4-flash`） |
| `defaultVisionModel` | String | ❌ 否 | Nacos 配置 | 默认视觉模型名称（如 `qwen-vl-max`） |
| `temperature` | Double | ❌ 否 | 0.7 | 温度参数（0.0-2.0，控制生成随机性） |
| `maxTokens` | Integer | ❌ 否 | 2048 | 最大生成 Token 数（≥1） |
| `topK` | Integer | ❌ 否 | 5 | RAG 检索数量（1-20） |
| `enableRag` | Boolean | ❌ 否 | true | 是否启用 RAG 检索 |
| `enableTools` | Boolean | ❌ 否 | true | 是否启用 Agent 工具调用 |

**Body (JSON)**：

```json
{
  "defaultModel": "deepseek-v4-flash",
  "defaultVisionModel": "qwen-vl-max",
  "temperature": 0.7,
  "maxTokens": 2048,
  "topK": 5,
  "enableRag": true,
  "enableTools": true
}
```

---

#### 21. GET `/admin/ai/models` — 获取可用模型列表

无参数

---

#### 22. POST `/admin/ai/test` — 测试 AI 连接

无参数、无 Body

---

### 2.3 工具管理

#### 23. GET `/admin/tools` — 获取工具列表

无参数

---

#### 24. PUT `/admin/tools/{name}` — 更新工具配置

**Path 参数**（必传）：`name=readFileTool`

**Body (JSON) 字段说明**：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `enabled` | Boolean | ❌ 否 | - | 是否启用工具 |
| `config` | Object | ❌ 否 | - | 工具配置参数（JSON 对象，不同工具配置不同） |

**Body (JSON)** — ReadFileTool：

```json
{
  "enabled": true,
  "config": {
    "maxFileSize": 10485760,
    "pathWhitelist": ["/project/src", "/project/docs"]
  }
}
```

> `config` 字段因工具而异，`pathWhitelist` 是 ReadFileTool 的业务配置（存 `sys_ai_tool_config` 表）
> 注意：此处 `pathWhitelist` 与 Nacos 的 `knowledge.allowed-paths` 职责不同：
> 
> - Nacos `knowledge.allowed-paths`：Agent 工具的安全白名单（基础设施层，全局生效）
> - ToolConfigDTO.config.pathWhitelist：ReadFileTool 的业务配置（工具层，可做更细粒度控制）

**Body (JSON)** — SearchCodeTool：

```json
{
  "enabled": true,
  "config": {
    "limit": 10
  }
}
```

---

#### 25. POST `/admin/tools/{name}/test` — 测试工具调用

**Path 参数**（必传）：`name=readFileTool`

**Body (JSON) 字段说明**：

| 字段 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `params` | Object | ✅ 是 | - | 工具调用参数（JSON 对象，不同工具参数不同） |

**Body (JSON)** — ReadFileTool：

```json
{
  "params": {
    "filePath": "d:/GitHub/scaffold-ai-assistant/README.md"
  }
}
```

**Body (JSON)** — SearchCodeTool：

```json
{
  "params": {
    "keyword": "Caffeine",
    "limit": 5
  }
}
```

---

### 2.4 统计分析

#### 26. GET `/admin/statistics/conversations` — 获取对话统计

无参数

---

#### 27. GET `/admin/statistics/questions` — 获取热门问题

**Query 参数**：

| 参数名 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `limit` | Integer | ❌ 否 | 10 | 返回热门问题数量 |

---

#### 28. GET `/admin/statistics/users` — 获取用户统计

**Query 参数**：

| 参数名 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `days` | Integer | ❌ 否 | 7 | 统计天数窗口 |

> **参数说明**：`days` 参数已生效，按指定天数窗口统计活跃用户（如传 `30` 表示统计近 30 天活跃用户）；不传时默认统计近 7 天

---

#### 29. GET `/admin/statistics/tools` — 获取工具使用统计

无参数

---

#### 30. GET `/admin/statistics/ai-metrics` — 获取 AI 调用聚合指标

无参数

---

#### 31. GET `/admin/statistics/ai-metrics/{operationId}` — 查看单次 AI 调用详情

**Path 参数**（必传）：`operationId=1894736281934594`

> `operationId` 是 AI 操作日志主键（雪花算法 Long），示例值为占位符，需先有 AI 调用记录后从接口 33 日志列表查询获取真实值

---

#### 32. GET `/admin/statistics/feedback` — 获取回答满意度统计

**Query 参数**：

| 参数名 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `startDate` | Long | ❌ 否 | 查全部 | 起始日期（BIGINT YYYYMMDD，如 `20260701`） |
| `endDate` | Long | ❌ 否 | 查全部 | 结束日期（BIGINT YYYYMMDD，如 `20260719`） |

> **参数说明**：`startDate` / `endDate` 参数已生效，按日期范围过滤反馈统计；均不传时返回全量反馈统计

---

### 2.5 操作日志

#### 33. GET `/admin/knowledge/logs/list` — 获取 AI 调用链路日志列表

**Query 参数**：

| 参数名 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `pageNo` | Integer | ❌ 否 | 1 | 页码 |
| `pageSize` | Integer | ❌ 否 | 20 | 每页大小 |
| `operationType` | String | ❌ 否 | 查全部 | 操作类型枚举：`CHAT` / `RETRIEVE` / `EMBEDDING` / `RERANK` |
| `model` | String | ❌ 否 | 查全部 | 模型名称过滤 |
| `status` | String | ❌ 否 | 查全部 | 状态枚举：`SUCCESS` / `FAILED` |
| `startDate` | Long | ❌ 否 | 查全部 | 起始日期（BIGINT YYYYMMDD） |
| `endDate` | Long | ❌ 否 | 查全部 | 结束日期（BIGINT YYYYMMDD） |

> **参数说明**：`operationType` / `model` / `status` / `startDate` / `endDate` 参数均已生效，支持多条件组合过滤（可同时传多个参数做交集查询）；均不传时返回全部日志

---

### 2.6 系统管理

#### 34. GET `/admin/system/health` — 获取系统健康状态（本次新增）

无参数

**响应示例**：

```json
{
  "code": 200,
  "data": {
    "overallStatus": "UP",
    "components": [
      {"name": "MySQL",  "status": "UP", "latency": 12,  "details": "SELECT 1 OK"},
      {"name": "Redis",  "status": "UP", "latency": 3,   "details": "PONG"},
      {"name": "Nacos",  "status": "UP", "latency": 25,  "details": "services=8"},
      {"name": "Milvus", "status": "UP", "latency": 45,  "details": "collection exists"},
      {"name": "LLM",    "status": "UP", "latency": 850, "details": "qwen response ok"}
    ],
    "checkTime": "2026-07-19 15:30:00"
  }
}
```

---

### 2.7 反馈管理

#### 35. GET `/admin/feedback/list` — B端反馈明细分页查询（本次新增）

**Query 参数**：

| 参数名 | 类型 | 必传 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `pageNo` | Integer | ❌ 否 | 1 | 页码 |
| `pageSize` | Integer | ❌ 否 | 20 | 每页大小 |
| `feedbackType` | String | ❌ 否 | 查全部 | 反馈类型枚举：`LIKE` / `DISLIKE` |
| `dislikeReason` | String | ❌ 否 | 查全部 | 点踩原因枚举：`OUTDATED` / `IRRELEVANT` / `CODE_ERROR` / `OTHER`（仅 `feedbackType=DISLIKE` 时有意义） |
| `userId` | Long | ❌ 否 | 查全部 | 用户ID过滤 |
| `startDate` | Long | ❌ 否 | 查全部 | 起始日期（BIGINT YYYYMMDD，如 `20260701`） |
| `endDate` | Long | ❌ 否 | 查全部 | 结束日期（BIGINT YYYYMMDD，如 `20260721`） |

**请求示例**：

```
GET /admin/feedback/list?feedbackType=DISLIKE&dislikeReason=CODE_ERROR&pageNo=1&pageSize=20
Authorization: Bearer {token}
```

**响应示例**：

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
| --- | --- | --- |
| `list[].id` | Long | 反馈ID |
| `list[].conversationId` | Long | 对话记录ID（关联 sys_ai_conversation.id） |
| `list[].userId` | Long | 反馈用户ID |
| `list[].userFrom` | String | 用户来源（sys/app） |
| `list[].feedbackType` | String | 反馈类型（LIKE/DISLIKE） |
| `list[].dislikeReason` | String | 点踩原因（仅 DISLIKE 时有值） |
| `list[].comment` | String | 文字评论 |
| `list[].createTime` | String | 反馈时间 |
| `list[].question` | String | 用户原始提问（SQL LEFT 截断 100 字） |
| `list[].answerSummary` | String | AI 回答摘要（SQL LEFT 截断 200 字） |
| `list[].model` | String | 使用的模型名称 |
| `list[].sources` | List&lt;String&gt; | RAG 引用来源列表 |
| `totals` | Integer | 总记录数 |
| `totalPages` | Integer | 总页数 |

> **说明**：本接口为 B 端管理员查看用户反馈明细，单条记录同时返回反馈信息 + 对话问答摘要（question + answerSummary + model + sources）。`answer` 字段为 LONGTEXT，列表页用 `LEFT(answer, 200)` 截断避免响应过大，完整 AI 回答通过接口 4 `GET /portal/history/{sessionId}` 查看。
>
> **数据来源**：`sys_ai_feedback` LEFT JOIN `sys_ai_conversation`（一对一关系）

---

## 推荐测试顺序

1. **34** `GET /admin/system/health` — 先确认 5 个组件全 UP
2. **10** 新增知识源（先 doc 类型）→ **13** 触发同步 → **14** 查看文档列表 → **17** 召回测试（→ **18** 上传单个文档验证即时入库）
3. **20** 更新 AI 配置 → **22** 测试 AI 连接
4. **23** 查看工具列表 → **25** 测试工具调用
5. **1** 流式文本对话 → **3** 查历史 → **4** 查会话详情
6. **2** 流式图文对话（需先通过 file 服务上传图片拿 URL）
7. **6** 提交点赞 → **6** 再提交点踩（验证覆盖语义）→ **7** 查反馈状态 → **8** 撤销反馈
8. **26-31** 统计分析（有数据后再查）
9. **33** 操作日志（有 AI 调用后再查）
10. **35** 反馈明细查询（有反馈数据后查看用户点踩明细，配合 **32** 满意度统计一起看）

所有入参假数据已按 DTO 字段定义和校验注解填写，可直接复制到 Apifox 使用。