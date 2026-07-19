## 通用说明

- **网关地址**：`http://localhost:10030`
- **认证 Header**（所有接口必填）：
  ```
  Authorization: Bearer {token}
  Content-Type: application/json
  ```
- **日期格式**：BIGINT，如 `20260712`（YYYYMMDD）

---

## 一、C端接口（前缀 `/portal`）

### 1.1 对话模块（SSE 流式）

#### 1. POST `/portal/chat/completions/stream` — 流式文本对话

**Body (JSON)**：
```json
{
  "message": "如何实现二级缓存？",
  "sessionId": "sess_20260719_001",
  "model": "deepseek-v4-flash",
  "temperature": 0.7,
  "topK": 5
}
```
> 新会话可不传 `sessionId`；`model`/`temperature`/`topK` 不传则用默认值
> `model` 字段可不传，不传时使用 Nacos 配置的默认文本模型（spring.ai.models 中 default-model=true 的文本模型）

**响应**：`text/event-stream`，每帧 `data: {chunk}\n\n`

---

#### 2. POST `/portal/chat/image/completions/stream` — 流式图文对话

**Body (JSON)**：
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
> `images` 必填，URL 是前端通过 file 服务签名直传 OSS 后返回的地址
> `model` 字段可不传，不传时使用 Nacos 配置的默认视觉模型（spring.ai.models 中 default-model=true 的视觉模型）

---

### 1.2 历史模块

#### 3. GET `/portal/history` — 获取对话历史列表（分页）

**Query 参数**：
```
pageNo=1
pageSize=20
```

---

#### 4. GET `/portal/history/{sessionId}` — 获取会话详情

**Path 参数**：`sessionId=sess_20260719_001`

---

#### 5. DELETE `/portal/history/{sessionId}` — 删除会话

**Path 参数**：`sessionId=sess_20260719_001`

---

### 1.3 反馈模块（覆盖语义）

#### 6. POST `/portal/feedback` — 提交反馈（点赞）

**Body (JSON)**：
```json
{
  "conversationId": 1894736281934592,
  "feedbackType": "LIKE",
  "comment": "回答很详细，帮助很大"
}
```

**Body (JSON)** — 点踩：
```json
{
  "conversationId": 1894736281934592,
  "feedbackType": "DISLIKE",
  "dislikeReason": "CODE_ERROR",
  "comment": "示例代码有语法错误"
}
```
> `dislikeReason` 枚举：`OUTDATED` / `IRRELEVANT` / `CODE_ERROR` / `OTHER`
> 重复提交会覆盖上一次反馈
> `conversationId` 是对话记录主键（雪花算法 Long），示例值为占位符，需先调用接口 1 进行对话获取真实值（响应中返回或从接口 3 历史列表查询）

---

#### 7. GET `/portal/feedback/{conversationId}` — 查询反馈状态

**Path 参数**：`conversationId=1894736281934592`

---

#### 8. DELETE `/portal/feedback/{conversationId}` — 撤销反馈

**Path 参数**：`conversationId=1894736281934592`

---

## 二、B端接口（前缀 `/admin`）

### 2.1 知识库管理

#### 9. GET `/admin/knowledge/sources` — 获取知识源列表（分页）

**Query 参数**：
```
pageNo=1
pageSize=10
type=doc
```
> `type` 可选，枚举：`doc` / `javadoc` / `config` / `code`，不传查全部

---

#### 10. POST `/admin/knowledge/sources` — 新增知识源

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

**Path 参数**：`id=1`

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

**Path 参数**：`id=1`

---

#### 13. POST `/admin/knowledge/sync` — 触发知识同步

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

---

#### 14. GET `/admin/knowledge/documents` — 获取文档列表（分页）

**Query 参数**：
```
pageNo=1
pageSize=10
sourceId=1
status=ACTIVE
```
> `sourceId` 和 `status` 都可选；`status` 枚举：`ACTIVE` / `DELETED`

---

#### 15. GET `/admin/knowledge/documents/{id}` — 获取文档详情

**Path 参数**：`id=1894736281934593`

---

#### 16. DELETE `/admin/knowledge/documents/{id}` — 删除文档（级联删除向量）

**Path 参数**：`id=1894736281934593`

---

#### 17. POST `/admin/knowledge/retrieve-test` — 召回测试

**Body (JSON)**：
```json
{
  "question": "如何实现 Caffeine + Redis 二级缓存？",
  "topK": 5,
  "sourceType": "doc",
  "module": "common-cache"
}
```
> `sourceType` 和 `module` 都可选

---

### 2.2 AI 配置管理

#### 18. GET `/admin/ai/config` — 获取 AI 配置

无参数

---

#### 19. PUT `/admin/ai/config` — 更新 AI 配置

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
> 所有字段都可选，只传需要更新的字段即可

---

#### 20. GET `/admin/ai/models` — 获取可用模型列表

无参数

---

#### 21. POST `/admin/ai/test` — 测试 AI 连接

无参数、无 Body

---

### 2.3 工具管理

#### 22. GET `/admin/tools` — 获取工具列表

无参数

---

#### 23. PUT `/admin/tools/{name}` — 更新工具配置

**Path 参数**：`name=readFileTool`

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

#### 24. POST `/admin/tools/{name}/test` — 测试工具调用

**Path 参数**：`name=readFileTool`

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

#### 25. GET `/admin/statistics/conversations` — 获取对话统计

无参数

---

#### 26. GET `/admin/statistics/questions` — 获取热门问题

**Query 参数**：
```
limit=10
```

---

#### 27. GET `/admin/statistics/users` — 获取用户统计

**Query 参数**：
```
days=7
```

---

#### 28. GET `/admin/statistics/tools` — 获取工具使用统计

无参数

---

#### 29. GET `/admin/statistics/ai-metrics` — 获取 AI 调用聚合指标

无参数

---

#### 30. GET `/admin/statistics/ai-metrics/{operationId}` — 查看单次 AI 调用详情

**Path 参数**：`operationId=1894736281934594`

> `operationId` 是 AI 操作日志主键（雪花算法 Long），示例值为占位符，需先有 AI 调用记录后从接口 32 日志列表查询获取真实值

---

#### 31. GET `/admin/statistics/feedback` — 获取回答满意度统计

**Query 参数**：
```
startDate=20260701
endDate=20260719
```
> 两个参数都可选，不传查全部

---

### 2.5 操作日志

#### 32. GET `/admin/knowledge/logs/list` — 获取 AI 调用链路日志列表

**Query 参数**：
```
pageNo=1
pageSize=20
operationType=CHAT
model=deepseek-v4-flash
status=SUCCESS
startDate=20260701
endDate=20260719
```
> 所有过滤参数都可选；`operationType` 枚举：`CHAT` / `RAG_RETRIEVE` / `TOOL_CALL` / `EMBEDDING`；`status` 枚举：`SUCCESS` / `FAILED` / `TIMEOUT`

---

### 2.6 系统管理

#### 33. GET `/admin/system/health` — 获取系统健康状态（本次新增）

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

## 推荐测试顺序

1. **33** `GET /admin/system/health` — 先确认 5 个组件全 UP
2. **10** 新增知识源（先 doc 类型）→ **13** 触发同步 → **14** 查看文档列表 → **17** 召回测试
3. **19** 更新 AI 配置 → **21** 测试 AI 连接
4. **22** 查看工具列表 → **24** 测试工具调用
5. **1** 流式文本对话 → **3** 查历史 → **4** 查会话详情
6. **2** 流式图文对话（需先通过 file 服务上传图片拿 URL）
7. **6** 提交点赞 → **6** 再提交点踩（验证覆盖语义）→ **7** 查反馈状态 → **8** 撤销反馈
8. **25-31** 统计分析（有数据后再查）
9. **32** 操作日志（有 AI 调用后再查）

所有入参假数据已按 DTO 字段定义和校验注解填写，可直接复制到 Apifox 使用。