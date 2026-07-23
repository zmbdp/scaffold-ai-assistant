# AI 调用指标与详情

## 功能介绍

本模块提供 AI 调用的聚合统计指标和单次调用的完整链路详情，用于 B 端管理员评估 AI 服务整体表现（调用次数、成功率、Token 消耗、延迟、Top 模型）以及排查单次 AI 调用问题（查看完整 Prompt、LLM 响应、工具调用链路）。

数据来源：`sys_ai_operation_log` 表（AI 调用链路日志，AI 编排层手动埋点）。

包含两个接口：
1. **获取 AI 调用聚合指标**（`GET /admin/statistics/ai-metrics`）
2. **查看单次 AI 调用详情**（`GET /admin/statistics/ai-metrics/{operationId}`）

聚合指标接口走 Redis 缓存（TTL=300 秒），缓存 key 为 `stats:ai_metrics`。详情接口不走缓存（实时查询）。

---

## 接口一：获取 AI 调用聚合指标

| 项 | 值 |
| --- | --- |
| 路径 | `GET /admin/statistics/ai-metrics` |
| 认证 | 必须携带 B 端 JWT（`userFrom=sys`），见总说明第三章 |
| Content-Type | `application/json` |
| 请求参数 | 无 |

#### 调用链路

前端请求 `/admin/statistics/ai-metrics` → 网关 StripPrefix=1 → admin-service `StatisticsController#getAiMetrics()` → Feign 调用 chat-service `StatisticsApi#getAiMetrics()` → `StatisticsServiceImpl#getAiCallMetrics()`。

---

### 响应结构

返回 `Result<AiMetricsVO>`，`data` 为 AI 调用指标聚合 VO。

**AiMetricsVO 字段**：

| 字段 | 类型 | 含义 | 数据来源 / 计算口径 |
| --- | --- | --- | --- |
| `totalAiCalls` | Long | AI 总调用次数 | `COUNT(*)` FROM `sys_ai_operation_log`（含成功/失败/超时，不限 status） |
| `successRate` | Double | 成功率（%） | `成功调用数 * 100.0 / 总调用数`，保留两位小数；总调用数为 0 时返回 `0.0` |
| `avgTokenUsage` | Long | 平均 Token 消耗 | `IFNULL(AVG(total_tokens), 0)` WHERE `status='SUCCESS'` |
| `totalTokenUsage` | Long | 总 Token 消耗 | `IFNULL(SUM(total_tokens), 0)` WHERE `status='SUCCESS'` |
| `avgLatency` | Long | 平均延迟（毫秒） | `IFNULL(AVG(response_time), 0)` WHERE `status='SUCCESS'` |
| `topModels` | List&lt;TopModel&gt; | Top 模型调用统计（按调用次数降序，固定取前 10） | 按模型分组 `COUNT(*)` |

**TopModel 字段**：

| 字段 | 类型 | 含义 | 数据来源 |
| --- | --- | --- | --- |
| `model` | String | 模型名称 | `sys_ai_operation_log.model` |
| `count` | Long | 调用次数 | `COUNT(*)` GROUP BY `model` |

> **统计口径说明**：
> - `totalAiCalls` 统计全量调用记录（含 FAILED/TIMEOUT），反映总调用规模。
> - `avgTokenUsage`、`totalTokenUsage`、`avgLatency` 仅统计 `status='SUCCESS'` 的记录（失败/超时调用无有效 Token 数和响应时间）。
> - `successRate` = 成功调用数 / 总调用数 × 100，保留两位小数（`Math.round(rate * 100.0) / 100.0`）。
> - `topModels` 统计全量记录（不限 status），过滤 `model` 为 NULL 或空字符串的记录。

---

### 响应示例

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "totalAiCalls": 1280,
    "successRate": 96.25,
    "avgTokenUsage": 1850,
    "totalTokenUsage": 2368000,
    "avgLatency": 3200,
    "topModels": [
      { "model": "deepseek-v4-flash", "count": 980 },
      { "model": "qwen-vl-max", "count": 210 },
      { "model": "qwen3.7-text-embedding", "count": 90 }
    ]
  }
}
```

> **空数据场景**：无任何 AI 调用记录时，`totalAiCalls=0`、`successRate=0.0`、`avgTokenUsage=0`、`totalTokenUsage=0`、`avgLatency=0`、`topModels` 返回空数组 `[]`。

---

## 接口二：查看单次 AI 调用详情

| 项 | 值 |
| --- | --- |
| 路径 | `GET /admin/statistics/ai-metrics/{operationId}` |
| 认证 | 必须携带 B 端 JWT（`userFrom=sys`），见总说明第三章 |
| Content-Type | `application/json` |

#### 调用链路

前端请求 `/admin/statistics/ai-metrics/{operationId}` → 网关 StripPrefix=1 → admin-service `StatisticsController#getOperationDetail(operationId)` → Feign 调用 chat-service `StatisticsApi#getOperationDetail(operationId)` → `StatisticsServiceImpl#getOperationDetail(operationId)` → `SysAiOperationLogMapper#selectById(operationId)`。

---

### 请求参数

**Path 参数**：

| 参数名 | 类型 | 必传 | 含义 |
| --- | --- | --- | --- |
| `operationId` | Long | 是 | AI 操作日志ID（`sys_ai_operation_log.id`，雪花算法 Long） |

> **operationId 获取方式**：先调用「操作日志」接口（`GET /admin/knowledge/logs/list`）获取日志列表，从列表项的 `id` 字段取得真实值，再调用本接口查看完整链路详情。

#### 请求示例

```
GET /admin/statistics/ai-metrics/1894736281934594
Authorization: Bearer {accessToken}
```

---

### 响应结构

返回 `Result<OperationLogVO>`，`data` 为操作日志 VO（含完整链路字段）。

> **operationId 不存在时**：返回 `code=200000`、`data=null`（非错误）。chat-service 端 `selectById` 返回 `null` 时，Controller 返回 `Result.success(null)`，admin-service 透传该结果，不抛出 `404004` 错误。

**OperationLogVO 字段**：

| 字段 | 类型 | 含义 | 说明 |
| --- | --- | --- | --- |
| `id` | Long | 日志ID | 雪花算法 |
| `userId` | Long | 调用用户ID | 关联 `sys_user` 或 `app_user` |
| `userFrom` | String | 用户来源 | `sys` / `app` |
| `conversationId` | Long | 关联对话ID | 关联 `sys_ai_conversation.id`，便于追溯 |
| `operationType` | String | AI 操作类型 | `CHAT` / `RETRIEVE` / `EMBEDDING` / `RERANK`，见下方枚举 |
| `model` | String | 模型名称 | 如 `deepseek-v4-flash`、`text-embedding-v1` |
| `prompt` | String | 完整 Prompt（含 RAG 上下文） | 仅详情接口返回，列表接口为 `null` |
| `response` | String | LLM 完整响应内容 | 仅详情接口返回，列表接口为 `null` |
| `toolCalls` | List&lt;ToolCallDetail&gt; | 工具调用链路（JSON 解析为列表） | 仅详情接口返回，列表接口为 `null` |
| `promptTokens` | Integer | Prompt Token 数 | — |
| `completionTokens` | Integer | 响应 Token 数 | — |
| `totalTokens` | Integer | 总 Token 数 | — |
| `responseTime` | Integer | 响应耗时（毫秒） | — |
| `status` | String | 调用状态 | `SUCCESS` / `FAILED` / `TIMEOUT`，见下方枚举 |
| `errorMsg` | String | 失败原因 | `status=FAILED`/`TIMEOUT` 时记录，成功时为 `null` |
| `createDate` | Long | 创建日期 | 格式 `YYYYMMDD`（如 `20260723`） |
| `createTime` | LocalDateTime | 创建时间（精确到秒） | 序列化为 `yyyy-MM-dd HH:mm:ss` 字符串 |

**ToolCallDetail 字段**（`toolCalls` 列表项）：

| 字段 | 类型 | 含义 | 数据来源 |
| --- | --- | --- | --- |
| `name` | String | 工具名称 | `tool_calls` JSON 的 `name` 字段 |
| `success` | Boolean | 是否成功 | `tool_calls` JSON 的 `success` 字段 |
| `duration` | Long | 耗时（毫秒） | `tool_calls` JSON 的 `duration` 字段 |
| `summary` | String | 结果摘要 | `tool_calls` JSON 的 `summary` 字段 |

> **toolCalls 解析逻辑**：`sys_ai_operation_log.tool_calls` 为 JSON 字符串（如 `[{"name":"readFile","success":true,"duration":45,"summary":"..."}]`），详情接口通过 `JsonUtil` 反序列化为 `List<ToolCallDetail>`。解析失败时返回空数组 `[]`（不影响其他字段展示）。无工具调用时 `tool_calls` 为空，返回空数组 `[]`。

---

### 响应示例

**成功调用（含工具调用链路）**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "id": 1894736281934594,
    "userId": 10000020,
    "userFrom": "app",
    "conversationId": 1894736281934592,
    "operationType": "CHAT",
    "model": "deepseek-v4-flash",
    "prompt": "你是一个 AI 助手，请根据以下上下文回答用户问题...\n用户问题：如何实现二级缓存？",
    "response": "Caffeine + Redis 二级缓存的实现步骤如下：1. 引入依赖...",
    "toolCalls": [
      {
        "name": "searchCode",
        "success": true,
        "duration": 85,
        "summary": "找到 3 处 Caffeine 相关代码"
      },
      {
        "name": "readFile",
        "success": true,
        "duration": 45,
        "summary": "读取 CacheConfig.java 成功"
      }
    ],
    "promptTokens": 1200,
    "completionTokens": 850,
    "totalTokens": 2050,
    "responseTime": 3200,
    "status": "SUCCESS",
    "errorMsg": null,
    "createDate": 20260723,
    "createTime": "2026-07-23 15:30:00"
  }
}
```

**operationId 不存在**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

**失败调用（无工具调用）**：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "id": 1894736281934595,
    "userId": 10000020,
    "userFrom": "app",
    "conversationId": 1894736281934596,
    "operationType": "CHAT",
    "model": "deepseek-v4-flash",
    "prompt": "...",
    "response": null,
    "toolCalls": [],
    "promptTokens": null,
    "completionTokens": null,
    "totalTokens": null,
    "responseTime": 60000,
    "status": "TIMEOUT",
    "errorMsg": "DashScope API 响应超时（60秒）",
    "createDate": 20260723,
    "createTime": "2026-07-23 15:31:00"
  }
}
```

---

### 枚举值

#### operationType（AI 操作类型）

| 值 | 含义 | 数据状态 |
| --- | --- | --- |
| `CHAT` | AI 对话 | **当前版本唯一有实际日志数据的类型**，由对话流程记录 |
| `RETRIEVE` | RAG 检索 | 枚举已声明，当前代码尚未写入日志数据 |
| `EMBEDDING` | 向量嵌入 | 枚举已声明，当前代码尚未写入日志数据 |
| `RERANK` | 重排序 | 枚举已声明，当前代码尚未写入日志数据 |

> **重要**：当前版本只有 `CHAT` 类型有实际日志数据写入。查询时传 `CHAT` 可查到数据；其他类型在统计中暂时不会出现。

#### status（调用状态）

| 值 | 含义 |
| --- | --- |
| `SUCCESS` | 成功 |
| `FAILED` | 失败 |
| `TIMEOUT` | 超时 |

---

### 错误码说明

| code | errMsg | 含义 | 场景 |
| --- | --- | --- | --- |
| `200000` | 操作成功 | 成功 | operationId 存在或不存在均返回此 code |
| `401000` | 令牌不能为空 | 缺 token（HTTP 401） | 未携带 Authorization |
| `401003` | 登录状态已过期！ | Redis 登录态过期（HTTP 401） | token 过期 |
| `401004` | 令牌验证失败！ | 越权访问（HTTP 401） | C 端 token 访问 `/admin/**` |
| `404000` | 服务未找到 | chat-service 不可用（HTTP 404） | chat-service 未注册到 Nacos |
| `500000` | 服务繁忙请稍后重试 | 服务异常（HTTP 500） | Feign 调用异常或数据库异常 |

> **operationId 不存在不返回 404004**：虽然 `ResultCode.AI_OPERATION_LOG_NOT_FOUND(404004, "AI 操作日志不存在")` 在 `ResultCode` 中已定义，且 chat-service 的 `AdminServiceImpl#getOperationLogDetail` 会抛出该错误，但本详情接口走的是 `StatisticsApi` → `StatisticsServiceImpl#getOperationDetail` 路径，该方法在 `selectById` 返回 `null` 时直接返回 `null`，不抛异常。因此本接口在 operationId 不存在时返回 `data=null`，而非 `404004` 错误。
>
> 当 chat-service Feign 调用返回非 `200000` 时，admin-service 抛出 `ServiceException("获取 AI 调用详情失败")`，HTTP 200 + `code=500001`。

---

## 前端注意事项

### 聚合指标接口（`/ai-metrics`）

1. **缓存说明**：聚合指标走 Redis 缓存，TTL=300 秒（5 分钟），缓存 key 为 `stats:ai_metrics`，无参数。

2. **统计口径差异**：
   - `totalAiCalls`：全量记录（含失败/超时），反映总调用规模。
   - `avgTokenUsage` / `totalTokenUsage` / `avgLatency`：仅 `status='SUCCESS'` 的记录（失败调用无有效 Token 数和响应时间）。
   - `topModels`：全量记录按模型分组，过滤 `model` 为 NULL/空字符串的记录。

3. **successRate 计算**：`successRate = 成功调用数 * 100.0 / 总调用数`，保留两位小数。总调用数为 0 时返回 `0.0`。

4. **topModels 固定 10 条**：固定取前 10 个模型（Service 常量 `TOP_MODELS_LIMIT=10`），按调用次数降序。

### 详情接口（`/ai-metrics/{operationId}`）

5. **operationId 不存在返回 null**：当传入不存在的 `operationId` 时，返回 `code=200000`、`data=null`，前端应判断 `data` 是否为 `null` 并展示「日志不存在」提示，而非当作错误处理。

6. **详情接口返回完整字段**：与「操作日志」列表接口不同，详情接口返回 `prompt`、`response`、`toolCalls` 完整字段。列表接口这三个字段被清空为 `null`（避免响应过大）。

7. **toolCalls 解析**：`toolCalls` 由 `tool_calls` JSON 字符串解析为 `List<ToolCallDetail>`。解析失败时返回空数组 `[]`；无工具调用时也返回空数组 `[]`。

8. **createTime 序列化格式**：`createTime` 为 `LocalDateTime` 类型，序列化为 `yyyy-MM-dd HH:mm:ss` 字符串。`createDate` 为 `Long` 类型，格式 `YYYYMMDD`。

9. **prompt/response 可能很大**：`prompt` 含 RAG 检索到的文档上下文，`response` 为 LLM 完整输出，单条记录可能较大，前端展示时建议做折叠/滚动处理。

10. **无审计/限流注解**：两个接口均为只读查询，未标注 `@LogAction` 和 `@RateLimit`。
