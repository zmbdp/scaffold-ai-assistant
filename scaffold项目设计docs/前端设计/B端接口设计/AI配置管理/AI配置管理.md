# AI 配置管理

> 模块：AI 配置获取 / 更新
>
> 公共约定（网关地址、JWT 认证、统一响应结构 `Result<T>`、错误码、操作审计）见 `总说明.md`，本文档不再重复。

## 功能介绍

本模块用于管理 AI 助手的**运行时业务参数**，包含两个接口：

- **获取 AI 配置**：返回当前生效的运行时参数（temperature、maxTokens、topK、enableRag、enableTools）、由模型列表推导出的默认文本/视觉模型名、Embedding 模型名，以及**脱敏后的 API Key**。
- **更新 AI 配置**：局部更新运行时参数（仅更新请求中非空的字段），更新后失效 `AI_CONFIG` 缓存空间，立即生效。

**职责边界（重要）**：

- 本模块仅管理 `sys_argument` 表中 `ai.*` 运行时可调参数（`ai.temperature` / `ai.max_tokens` / `ai.top_k` / `ai.enable_rag` / `ai.enable_tools`）。
- **API Key、模型名称、Embedding 模型**等基础设施配置统一在 **Nacos** 管理，**不通过本模块接口修改**。`apiKey`、`embeddingModel`、`defaultModel`、`defaultVisionModel` 在获取接口中仅做回显展示。

**调用链路**：前端 `GET/PUT /admin/ai/config` → 网关 StripPrefix=1 → admin-service `AiConfigController` → Feign `AiConfigApi` → chat-service `AiConfigController` → `AdminServiceImpl`。

---

## 一、获取 AI 配置

### 1.1 接口信息

| 项目 | 内容 |
| --- | --- |
| 路径 | `GET /admin/ai/config` |
| 认证 | 需要 B 端 token（`userFrom=sys`） |
| 操作审计 | 无（查询接口不记录 `@LogAction`） |
| Content-Type | 无需请求体 |
| 权限 | B 端登录用户均可访问 |

### 1.2 请求参数

无。

### 1.3 请求示例

```http
GET /admin/ai/config HTTP/1.1
Authorization: Bearer {accessToken}
```

### 1.4 响应结构

`Result<AiConfigVO>`，`data` 字段为 `AiConfigVO`：

| 字段 | 类型 | 含义 | 说明 |
| --- | --- | --- | --- |
| `defaultModel` | String | 默认文本模型名称 | 由模型列表中第一个启用的非视觉模型推导，如 `deepseek-v4-flash`；无可启用模型时为 `null` |
| `defaultVisionModel` | String | 默认视觉模型名称 | 由模型列表中第一个启用的 `TEXT_AND_IMAGE` 类型模型推导，如 `qwen-vl-plus`；无则为 `null` |
| `temperature` | Double | 温度参数 | 来自 `ai.temperature`；未配置时为 `null` |
| `maxTokens` | Integer | 最大生成 Token 数 | 来自 `ai.max_tokens`；未配置时为 `null` |
| `embeddingModel` | String | Embedding 模型名称 | **仅展示不可修改**。`scaffold.embedding.provider=local` 时回显本地模型名，否则回显 DashScope 远程模型名；未配置时为 `null` |
| `topK` | Integer | RAG 检索数量 | 来自 `ai.top_k`；未配置时为 `null` |
| `enableRag` | Boolean | 是否启用 RAG 检索 | 来自 `ai.enable_rag`；未配置时为 `null` |
| `enableTools` | Boolean | 是否启用 Agent 工具调用 | 来自 `ai.enable_tools`；未配置时为 `null` |
| `apiKey` | String | API Key（脱敏） | **敏感字段，脱敏显示**：仅展示前后各 4 位，中间用 `*` 替换，如 `sk-1***wxyz`；Nacos 未配置 `spring.ai.dashscope.api-key` 时为 `null` |

> **脱敏规则**：保留前 4 位 + 后 4 位，中间字符替换为 `*`（由 `DesensitizeUtil.desensitize(apiKey, 4, 4)` 实现）。

### 1.5 响应示例

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "defaultModel": "deepseek-v4-flash",
    "defaultVisionModel": "qwen-vl-plus",
    "temperature": 0.7,
    "maxTokens": 2048,
    "embeddingModel": "text-embedding-v1",
    "topK": 5,
    "enableRag": true,
    "enableTools": true,
    "apiKey": "sk-1***wxyz"
  }
}
```

### 1.6 错误码说明

| code | errMsg | 触发场景 |
| --- | --- | --- |
| `200000` | 操作成功 | 正常返回 |
| `500000` | 服务繁忙请稍后重试 / 获取 AI 配置失败 | chat-service 异常或 Feign 调用失败时，由 `ServiceException` 透传 |

> 本接口不触发 AI 模块专属错误码（`500022`~`500028`）。`500022 AI 配置未设置` 等码定义于 `ResultCode`，但本获取接口不会主动抛出（API Key 缺失时 `apiKey` 字段返回 `null`，而非报错）。

### 1.7 前端注意事项

- `apiKey` 为脱敏值，**仅用于展示**，不要回填到更新表单提交。
- `defaultModel` / `defaultVisionModel` / `embeddingModel` 为只读回显字段，更新接口**不持久化**这些字段（见下文更新接口说明）。
- 各数值字段在 `sys_argument` 表未配置时会返回 `null`，前端表单需做空值兜底（如显示占位符或默认值）。

---

## 二、更新 AI 配置

### 2.1 接口信息

| 项目 | 内容 |
| --- | --- |
| 路径 | `PUT /admin/ai/config` |
| 认证 | 需要 B 端 token（`userFrom=sys`） |
| 操作审计 | **有**：`@LogAction(value = "更新AI配置", module = "ai_config", recordParams = true)`，记录到 `operation_log` 表 |
| Content-Type | `application/json` |
| 权限 | B 端登录用户均可访问 |

> `recordParams = true` 表示审计日志会记录请求参数（注意：本 DTO 不含 API Key 等敏感字段，可安全记录）。

### 2.2 请求参数

请求体为 `AiConfigDTO`（JSON）。**局部更新语义**：仅更新请求体中非 `null` 的字段；未传的字段保持原值不变。

| 字段名 | 类型 | 必传 | 校验规则（注解原文） | 默认值 | 含义 |
| --- | --- | --- | --- | --- | --- |
| `temperature` | Double | 否 | `@Min(value = 0, message = "温度参数最小为0")`<br>`@Max(value = 2, message = "温度参数最大为2")` | 无 | 温度参数（0.0-2.0，控制生成随机性），持久化到 `ai.temperature` |
| `maxTokens` | Integer | 否 | `@Min(value = 1, message = "maxTokens最小为1")` | 无 | 最大生成 Token 数（≥1），持久化到 `ai.max_tokens` |
| `topK` | Integer | 否 | `@Min(value = 1, message = "topK最小为1")`<br>`@Max(value = 20, message = "topK最大为20")` | 无 | RAG 检索数量（1-20），持久化到 `ai.top_k` |
| `enableRag` | Boolean | 否 | 无 | 无 | 是否启用 RAG 检索，持久化到 `ai.enable_rag` |
| `enableTools` | Boolean | 否 | 无 | 无 | 是否启用 Agent 工具调用，持久化到 `ai.enable_tools` |
| `defaultModel` | String | 否 | 无 | 无 | 默认文本模型名称。**注意：当前服务实现未持久化此字段**（默认模型由 Nacos `spring.ai.models` 的 `defaultModel` 配置决定），传入不生效 |
| `defaultVisionModel` | String | 否 | 无 | 无 | 默认视觉模型名称。**注意：当前服务实现未持久化此字段**，传入不生效 |

> **所有字段均为可选**。服务端仅处理 `temperature` / `maxTokens` / `topK` / `enableRag` / `enableTools` 五个字段的非空值，逐一更新到 `sys_argument` 表对应 `configKey`，更新完成后失效整个 `AI_CONFIG` 缓存空间。`defaultModel` / `defaultVisionModel` 虽在 DTO 中定义，但服务端**不读取、不持久化**。

### 2.3 请求示例

仅更新部分字段：

```http
PUT /admin/ai/config HTTP/1.1
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "temperature": 0.5,
  "topK": 8,
  "enableTools": false
}
```

全量更新示例：

```json
{
  "temperature": 0.7,
  "maxTokens": 2048,
  "topK": 5,
  "enableRag": true,
  "enableTools": true
}
```

### 2.4 响应结构

`Result<Void>`，无 `data`。

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `code` | int | 响应码，成功为 `200000` |
| `errMsg` | String | 消息描述 |
| `data` | null | 无业务数据 |

### 2.5 响应示例

成功：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

### 2.6 错误码说明

| code | errMsg | 触发场景 |
| --- | --- | --- |
| `200000` | 操作成功 | 更新成功 |
| `400000` | 校验失败信息（分号分隔） | 参数校验失败，如 `temperature` 超出 0-2、`topK` 超出 1-20、`maxTokens` 小于 1。例：`温度参数最大为2` |
| `500000` | 更新 AI 配置失败 / 服务繁忙请稍后重试 | Feign 调用 chat-service 失败，或 chat-service 内部异常 |

> 校验失败时，HTTP 状态码为 400，响应体 `code=400000`，`errMsg` 为具体校验信息（多个错误用 `;` 分隔）。校验信息来源于注解 `message` 原文。

### 2.7 枚举值

本接口无枚举字段。`enableRag` / `enableTools` 为布尔值（`true` / `false`）。

### 2.8 前端注意事项

- **API Key 不可通过本接口修改**。如需修改 API Key，请在 Nacos 的 `spring.ai.dashscope.api-key` 配置项中调整（属基础设施配置）。
- 采用**局部更新**语义：表单中未修改的字段不要传或传 `null`，避免误覆盖。建议前端只提交实际发生变更的字段。
- `defaultModel` / `defaultVisionModel` 字段虽存在于 DTO，但服务端不持久化，前端更新表单**不应**将这两个字段作为可编辑项提交（如需切换默认模型，请在 Nacos `spring.ai.models` 中调整 `defaultModel` 标志）。
- 校验边界：`temperature` ∈ [0, 2]、`topK` ∈ [1, 20]、`maxTokens` ≥ 1。前端应做前置校验以减少 400 错误。
- 更新成功后缓存立即失效，再次调用获取接口可拿到最新值，无需等待。
