# 📄 RAG流程设计文档

> **文档说明**：本文档详细描述 Scaffold AI Assistant 的RAG（检索增强生成）流程设计，采用双库架构：MySQL存完整文档，Milvus存分段向量。

---

## 6.1 文档分块策略

| 文档类型 | 分块方式 | 块大小 | 重叠比例 |
|---------|---------|--------|---------|
| **Markdown文档** | 按章节分块（基于`#`、`##`） | 500-1000字符 | 10-15% |
| **Java源码** | 按类/方法分块（基于AST解析） | 500-800字符 | 5% |
| **JavaDoc HTML** | 按类/方法分块 | 300-500字符 | 5% |
| **配置文件** | 按配置节分块（基于YAML层级） | 200-300字符 | 5% |

> **Java源码分块说明**：脚手架项目主体是 Java 源码（.java 文件），必须作为独立的分块类型。Java 源码分块后存入 Milvus，用户提问"某个功能在哪里实现"时，RAG 可检索到对应的类/方法分块，配合 05-Agent工具设计.md 的 SearchCodeTool/ReadFileTool 提供完整的源码问答能力。

### 6.1.1 分块算法详细说明

**Markdown 分块算法**：
```
1. 读取文档内容，按行分割
2. 识别章节标题（以 #、##、### 开头的行）
3. 按章节切分文档，每个章节作为一个初始分块
4. 若章节长度 > chunkSize（默认500）：
   ├─ 按段落（空行分隔）二次切分
   ├─ 段落累计长度达到 chunkSize 时，形成一个新的分块
   └─ 保留 chunkOverlap（默认50）字符的重叠，确保上下文连续性
5. 若章节长度 < chunkSize：
   └─ 与下一个章节合并（避免过短分块）
6. 为每个分块分配 chunkIndex（从0开始递增）
7. 记录元数据：source_type、source_path、module、category、title
```

**Java 源码分块算法**：
```
1. 读取 .java 文件内容
2. 使用 JavaParser（或正则）解析类结构
3. 识别类级注释（JavaDoc）、类声明、字段、方法
4. 分块策略：
   ├─ 类级注释 + 类声明 + import 列表 → 作为一个分块（类概述）
   ├─ 每个方法（含方法注释、方法签名、方法体）→ 作为一个独立分块
   └─ 若方法体过长（> chunkSize），按方法内的逻辑块二次切分（基于空行或注释分隔）
5. 为每个分块分配 chunkIndex（从0开始递增）
6. 记录元数据：
   ├─ source_type: code
   ├─ source_path: 文件相对路径（如 zmbdp-common/zmbdp-common-core/src/.../JsonUtil.java）
   ├─ module: 所属模块（如 common-core）
   ├─ className: 类名（如 JsonUtil）
   ├─ methodName: 方法名（类概述分块为 null）
   └─ title: "JsonUtil.toJson" 或 "JsonUtil（类概述）"
```

> **为什么用 JavaParser 而非正则**：JavaParser 能准确解析 Java 语法结构（类、方法、注释），避免正则匹配嵌套大括号的歧义。若需简化实现，可降级为正则匹配 `public/private/protected` 方法签名。

**JavaDoc HTML 分块算法**：
```
1. 解析 HTML 结构
2. 识别 <class> 和 <method> 标签
3. 每个类/方法作为一个独立分块
4. 提取方法签名、参数说明、返回值说明
5. 若方法说明过长（> chunkSize），按段落二次切分
6. 记录元数据：className、methodName、filePath
```

**配置文件分块算法**：
```
1. 解析 YAML/Properties 结构
2. 按顶层配置节切分（如 spring、server、mybatis 等）
3. 每个配置节作为一个分块
4. 保留父级配置节名称作为上下文（避免脱离上下文）
5. 记录元数据：source_type=config、source_path、env（dev/prd）
```

### 6.1.2 分块参数配置

分块参数存储在 `sys_ai_knowledge_source` 表，支持每个知识源独立配置：

| 参数 | 字段名 | 默认值 | 范围 | 说明 |
|-----|--------|--------|------|------|
| 分块大小 | `chunk_size` | 500 | 100-2000 | 每个分块的最大字符数 |
| 分块重叠 | `chunk_overlap` | 50 | 0-200 | 相邻分块的重叠字符数（需小于chunkSize） |

**参数选择建议**：
- **Markdown文档类**：chunkSize=800, chunkOverlap=80（保留完整段落上下文）
- **Java源码类**：chunkSize=600, chunkOverlap=30（按方法粒度切分，保留方法注释）
- **JavaDoc类**：chunkSize=400, chunkOverlap=20（按类/方法粒度切分）
- **配置类**：chunkSize=300, chunkOverlap=30（按配置节切分）

---

## 6.2 双库架构设计

### 6.2.1 MySQL文档表（document）

存储完整文档内容和元数据，详情请查看 [07-项目架构设计.md](07-项目架构设计.md) 中的文档表设计。

### 6.2.2 Milvus向量集合

```
collection_name: scaffold_knowledge
├── id: BIGINT (主键, 雪花算法)
├── content: String (文档分块内容)
├── document_id: BIGINT (关联MySQL文档表的id)
├── chunk_index: INT (分块序号，从0开始)
├── metadata: Map<String, String> (元数据)
│   ├── source_type: String (doc/javadoc/config/code)
│   ├── source_path: String (文件路径)
│   ├── module: String (所属模块)
│   ├── category: String (功能类别)
│   ├── title: String (文档标题，便于展示引用来源)
│   └── create_time: Long (分块创建时间戳)
├── embedding_model: String (生成该向量使用的Embedding模型名称，如text-embedding-v1)
└── vector: FloatVector (向量, 维度由Embedding模型决定，text-embedding-v1为1536维)
```

**索引设计**：
- **向量索引**：HNSW索引，metric_type=IP，efConstruction=200，M=16
- **标量索引**：对 document_id、source_type、module、category 建立标量索引

> **说明**：`embedding_model` 字段用于记录向量生成时使用的模型，当切换 Embedding 模型时可用于识别需要重新向量化的分块。

### 6.2.3 双库关联关系

```
MySQL document表                    Milvus scaffold_knowledge集合
┌─────────────────┐                ┌─────────────────────────────┐
│ id (BIGINT)     │◄───────────────│ document_id (BIGINT)        │
│ title           │                │ chunk_index (INT)           │
│ content (完整)   │                │ content (分块内容)          │
│ hash            │                │ vector                      │
│ chunk_count     │                │ metadata                    │
│ ...             │                └─────────────────────────────┘
└─────────────────┘

一个document对应多个向量分块，通过document_id关联
```

---

## 6.3 检索逻辑

```
用户提问 → Embedding生成向量 → Milvus相似性检索（topK×2）→ 获取相关分块列表
                                                    ↓
                                         Reranking 重排序（按相关性二次排序）
                                                    ↓
                                         截取 topK 个最相关分块（保留所有分块，不去重）
                                                    ↓
                                         按document_id分组（用于展示引用来源，同文档的多个分块都保留）
                                                    ↓
                                         返回 List<DocumentVO>（含 content、title、module、score、source_path）

可选：基于元数据过滤（如指定模块、类型）   可选：按相似度排序
```

> **向量检索结果不缓存**：每次都实时查询 Milvus + Reranking，保证检索结果基于最新的向量库数据。业界主流 RAG 框架（LangChain `CacheBackedEmbeddings`、GPTCache 等）缓存的是 embedding 结果或 LLM 响应，而非向量检索结果（详见 07-项目架构设计.md 7.12.1 节"缓存范围决策"）。

> **去重说明**：检索结果**不做去重**。如果同一个文档的多个分块都相关，保留所有分块以提供更完整的上下文。按 document_id 分组仅用于前端展示引用来源（同文档的分块合并为一个引用条目），不影响传给 LLM 的上下文内容。
>
> **"去MySQL获取完整文档内容"已移除**：C端对话场景不需要获取完整文档，分块内容已足够。B端召回测试（/knowledge/retrieve-test）如需查看完整文档，单独调用文档详情接口。

> **职责划分说明**（与 03-C端功能设计.md 3.0 节一致）：
> - **RAG 检索**（Embedding 生成 → Milvus 相似性检索 → Reranking 重排序 → 截取 topK）在 **`chat-service`** 完成
> - `portal-service` 通过 Feign 调用 `ChatApi.retrieve()` 获取检索结果（`List<DocumentVO>`），**不直接访问 Milvus**
> - **Prompt 拼接**在 `portal-service` 完成（详见 6.8 节）
> - **LLM 生成回答**在 `chat-service` 完成（`portal-service` 通过 WebClient 调用 SSE 端点，透传流数据给前端）

### 6.3.1 Reranking 重排序策略

**为什么需要 Reranking**：
- Milvus 基于向量相似度检索，速度快但精度有限
- Reranking 使用更精细的模型对候选结果进行二次排序，提升相关性

**Reranking 流程**：
```
1. Milvus 检索时获取 topK×2 个候选分块（如 topK=5，则获取10个候选）
2. 对每个候选分块，使用 Reranking 模型计算 query 与 chunk 的相关性分数
3. 按相关性分数降序排序
4. 截取前 topK 个分块作为最终结果
```

**Reranking 模型**：
- 默认使用 `gte-rerank` 模型（阿里云 DashScope 提供）
- 配置项：`spring.ai.rerank.model=gte-rerank`（位于 Nacos 配置）
- 若未配置 Reranking 模型，跳过重排序步骤，直接使用 Milvus 原始排序

**Reranking 降级策略**：
- Reranking 服务调用失败 → 降级为 Milvus 原始排序（记录 warning 日志）
- Reranking 服务超时（>5秒）→ 降级为 Milvus 原始排序
- 通过降级策略确保检索流程的可用性

### 6.3.2 元数据过滤

支持基于元数据的过滤检索，提升检索精度：

| 过滤字段 | 说明 | 示例 |
|---------|------|------|
| `source_type` | 文档类型 | doc/javadoc/config/code |
| `module` | 所属模块 | common-cache/common-security |
| `category` | 功能类别 | cache/auth/ratelimit |

**过滤逻辑**：在 Milvus 检索时通过 `expr` 参数传入过滤条件，如：
```
source_type == "doc" and module == "common-cache"
```

---

## 6.4 检索参数

| 参数 | 默认值 | 说明 |
|-----|--------|------|
| **topK** | 5 | 返回最相关的分块数量，支持运行时动态调整（1-20） |
| **metric_type** | IP | 内积相似度 |
| **index_type** | HNSW | 高性能向量索引 |
| **efConstruction** | 200 | 索引构建参数 |
| **efRuntime** | 100 | 查询时参数 |
| **M** | 16 | HNSW图的最大连接数 |

**topK动态调整机制**：
- 在AI配置中设置默认topK值（Nacos配置）
- API请求时可通过`topK`参数覆盖默认值
- 范围限制：1-20，超出范围使用默认值

---

## 6.5 知识同步流程

> **什么是知识同步**：把脚手架的文档/源码/配置文件，经过分块和 Embedding 向量化后存入 Milvus，让 AI 对话时能检索到（RAG 的"灌数据"环节）。
>
> **什么时候触发**（v1.0 两种方式，均为**管理员维护行为**，非用户查询触发）：
> 1. **手动触发**：管理员在 B端点"知识同步"按钮（`POST /admin/knowledge/sync`），适用于文档/源码变更后立即更新
> 2. **定时任务**：`KnowledgeSyncScheduler` 默认每小时自动执行一次，适用于无人值守的增量同步
>
> **典型场景**：
> - 脚手架升级，文档内容变了 → 触发同步 → 更新 Milvus 向量 → 用户下次提问检索到最新内容
> - 新增了某个模块的文档 → 触发同步 → 新文档被分块+向量化+写入 Milvus → 用户能问到新模块
> - 删除了过时文档 → 触发同步 → 删除 Milvus 对应向量 → 用户不会再检索到过时内容
>
> **与用户查询的区别**：同步是"写"（往向量库灌数据），低频，需要分布式锁；用户查询是"读"（从向量库检索），高频，不需要锁。

```
手动触发/定时任务
    ↓
从 sys_ai_knowledge_source 表查询所有 enabled=1 的知识源
    ↓
遍历每个知识源（knowledgeSourceId 为表主键 id，雪花算法生成）：
    ├─ 获取分布式锁（Redis分布式锁，key: knowledge:sync:{knowledgeSourceId}）
    │   ├─ 获取成功 → 继续执行
    │   └─ 获取失败 → 跳过该知识源（已有同步在执行），继续处理下一个
    ├─ 遍历该知识源目录，收集当前所有文件路径
    ├─ 从MySQL查询该知识源的已有文档列表
    ├─ 对比文件路径，识别三种状态：
    │   ├─ 新增文件：当前目录有，MySQL中没有
    │   ├─ 更新文件：当前目录有，MySQL中有，哈希不同
    │   └─ 删除文件：当前目录没有，MySQL中有
    ├─ 处理新增文件：
    │   ├─ 读取文件内容
    │   ├─ 计算文件哈希
    │   ├─ 插入MySQL文档表，获取文档ID
    │   ├─ 文档分块处理
    │   ├─ 对每个分块Embedding生成向量
    │   ├─ 写入Milvus（包含document_id和chunk_index）
    │   └─ 将document_id加入布隆过滤器（bloomFilterService.put）
    ├─ 处理更新文件：
    │   ├─ 读取文件内容
    │   ├─ 计算文件哈希
    │   ├─ 更新MySQL文档内容，版本号+1
    │   ├─ 删除Milvus中该document_id的所有旧分块
    │   ├─ 文档分块处理
    │   ├─ 对每个分块Embedding生成向量
    │   └─ 写入Milvus（包含document_id和chunk_index）
    ├─ 处理删除文件：
    │   ├─ 获取document_id
    │   ├─ 删除Milvus中该document_id的所有分块
    │   └─ 更新MySQL文档状态为DELETED（软删除）
    ├─ 更新MySQL中document表的chunk_count字段
    └─ 释放分布式锁
```

> **向量检索不涉及缓存失效**：向量检索结果不缓存（详见 6.3 节），知识同步完成后向量库已更新为最新数据，下次检索自动获取最新结果。

> **分布式锁说明**：使用 Redis 分布式锁（key: `knowledge:sync:{knowledgeSourceId}`），确保同一知识源的同步任务不会并发执行。锁设置合理过期时间（默认30分钟），防止死锁。多容器部署时，定时任务在所有容器都会触发，但只有获取到锁的容器执行同步。

**增量更新优化**：
- 使用文件哈希（SHA-256）对比，避免重复处理未修改的文件
- 软删除机制：MySQL中标记为DELETED状态，保留历史记录
- Milvus中物理删除对应分块，释放向量存储空间
- 支持`force`参数强制重新同步（跳过哈希检查）

---

## 6.6 双库架构优势

| 优势 | MySQL文档表 | Milvus向量集合 |
|-----|------------|---------------|
| **数据完整性** | 存储完整文档，可随时获取全文 | 只存分块，不存储完整文档 |
| **检索精度** | 不支持向量检索 | 专用向量检索，精度高 |
| **CRUD操作** | 方便，支持事务 | 不适合复杂CRUD |
| **元数据管理** | 天然支持，查询方便 | 元数据作为标量字段 |
| **版本控制** | 支持版本号、哈希对比 | 不支持版本控制 |
| **增量更新** | 通过哈希对比实现 | 通过document_id删除旧分块 |

---

## 6.7 Embedding 生成流程

**Embedding 模型**：`text-embedding-v1`（阿里云 DashScope 提供，1536维向量）

**生成时机**：
1. **知识同步时**：对每个文档分块生成向量，写入 Milvus
2. **检索时**：对用户提问生成查询向量，用于 Milvus 相似性检索

**同步时生成流程**：
```
1. KnowledgeLoaderService.chunkDocument() 生成文档分块
2. 遍历每个分块：
   ├─ 调用 modelService.getEmbeddingModel().embed(chunkContent)
   ├─ 返回 float[]（维度1536）
   └─ 将向量与分块内容、元数据一起写入 Milvus
3. 记录 embedding_model="text-embedding-v1"（便于后续模型切换时识别）
```

**检索时生成流程**：
```
1. 接收用户提问 query
2. 调用 modelService.getEmbeddingModel().embed(query)
3. 返回 float[] 查询向量（维度1536）
4. 使用查询向量在 Milvus 中执行相似性检索
```

**模型切换处理**：
- 当切换 Embedding 模型时（如从 text-embedding-v1 切换到 text-embedding-v2）
- 通过 `embedding_model` 字段识别使用旧模型的分块
- 需要对这些分块重新生成向量（通过 `force=true` 强制同步）

---

## 6.8 Prompt 拼接策略

**拼接位置**：在 `portal-service` 的 `PortalChatService` 中完成

> **职责划分说明**（与 03-C端功能设计.md 3.0 节一致）：
> - **RAG 检索**（Embedding 生成 → Milvus 检索 → Reranking）在 `chat-service` 完成
> - `portal-service` 通过 Feign 调用 `ChatApi.retrieve()` 获取检索结果（`List<DocumentVO>`）
> - `portal-service` 拿到检索结果后，完成 Prompt 拼接（System Prompt + 文档上下文 + 对话历史 + 用户提问）
> - 拼接后的完整 Prompt 通过 WebClient 传给 `chat-service` 的 SSE 端点，由 `chat-service` 调用 LLM 生成回答

**Prompt 结构**：
```
[系统提示词 System Prompt]
你是脚手架专家AI助手，擅长回答关于脚手架项目的问题。
请基于以下检索到的文档上下文回答用户问题。
如果上下文中没有相关信息，请明确告知用户并建议查阅官方文档。
回答时请标注引用来源（文档名称和章节）。

[检索到的文档上下文]
---文档1: 三级缓存架构.md（模块: common-cache）---
[分块内容1]
[分块内容2]
---文档2: 分布式幂等性.md（模块: common-idempotent）---
[分块内容3]

[对话历史]（最近5轮，通过 Feign 调用 HistoryApi.getSessionHistory 获取，优先 Redis，降级 MySQL）
User: 上一个问题
Assistant: 上一个回答

[用户提问]
三级缓存怎么用？入参出参是什么？
```

**拼接逻辑**：
```
1. 从 RAG 检索结果获取 List<DocumentVO>（含 content、title、module、score）
2. 按文档去重，按 score 降序排序
3. 构建文档上下文部分：
   ├─ 遍历每个文档分块
   ├─ 格式：---文档{N}: {title}（模块: {module}）---\n{content}
   └─ 限制总长度（默认8000字符，超出则截断低分文档）
4. 通过 Feign 调用 HistoryApi.getSessionHistory(sessionId, 5) 获取最近5轮对话历史
   ├─ 优先从 Redis（IChatMemoryService）读取，延迟低
   └─ Redis 不可用时降级查 MySQL（sys_ai_conversation 表），保证可用性
5. 拼接完整Prompt：System Prompt + 文档上下文 + 对话历史 + 用户提问
6. 将完整Prompt传给 chat-service 的 SSE 端点
```

> **跨服务说明**：`portal-service` 和 `chat-service` 是独立服务，`IChatMemoryService` 属于 `chat-service`。`portal-service` 无法直接调用 `IChatMemoryService`，必须通过 Feign 调用 `HistoryApi.getSessionHistory()` 跨服务获取对话历史（与 03-C端功能设计.md 3.0.1 节 streamChat 第4步一致）。

**上下文长度限制**：
- 最大上下文长度：8000字符（约2000 token）
- 超出限制时，按 score 降序截断低分文档分块
- 确保留给 LLM 生成的 token 数充足（maxTokens - 上下文长度 > 1000）

**引用来源处理**：
- 在 DocumentVO 中返回 `title`、`source_path`、`module` 字段
- LLM 生成的回答中包含引用标注（如 `[引用: 三级缓存架构.md]`）
- 前端解析引用标注，渲染为可点击的链接

---

## 6.9 知识热更新机制

**热更新场景**：知识文件变更后，无需重启服务即可更新向量库，确保用户查询时能检索到最新内容。

> **再次明确**：知识同步是**管理员维护行为**（手动触发或定时任务），**不是用户查询时触发的**。用户查询（RAG 检索）只从 Milvus 读取向量，不触发同步。同步是"写"操作（往向量库灌数据），查询是"读"操作（从向量库检索）。

**实现方式**（v1.0）：
1. **手动触发**：通过 B端 `/admin/knowledge/sync` 接口手动触发同步
2. **定时任务**：`KnowledgeSyncScheduler` 定时检查文件变更（默认每小时一次）

**v2.0 规划**（v1.0 不实现，避免过度设计）：
3. **文件监听**：通过 Java NIO WatchService 监听知识源目录变更

> **v1.0 不实现文件监听的原因**：
> - 手动触发 + 定时任务（每小时）已能满足知识更新需求，延迟可接受
> - 文件监听增加系统复杂度，且多容器部署时所有容器都会触发监听事件，需额外处理去重（否则重复同步）
> - 文件频繁变更时（如开发环境 git pull），文件监听会触发大量同步任务，影响性能
> - 作为 v2.0 功能，结合分布式锁 + 延迟批量同步（防抖）再实现更合理

**定时任务配置**：
```yaml
# 位于 zmbdp-chat-service-${env}.yaml
knowledge:
  sync:
    enabled: true                  # 是否启用定时同步
    cron: "0 0 * * * ?"           # 每小时执行一次
    force: false                   # 是否强制同步（默认增量）
```

**同步流程**（详见 6.5 节）：
- 使用 SHA-256 哈希对比，仅处理变更的文件
- 通过分布式锁防止并发同步冲突
- 软删除机制：MySQL 标记 DELETED，Milvus 物理删除

---

**文档版本**：v1.3  
**创建日期**：2026-07-12  
**最后更新**：2026-07-14  
**适用版本**：Scaffold AI Assistant v1.0