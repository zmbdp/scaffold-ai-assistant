# 📄 RAG流程设计文档

> **文档说明**：本文档详细描述 Scaffold AI Assistant 的RAG（检索增强生成）流程设计，采用双库架构：MySQL存完整文档，Milvus存分段向量。

---

## 6.1 文档分块策略

| 文档类型 | 分块方式 | 块大小 | 重叠比例 |
|---------|---------|--------|---------|
| **Markdown文档** | 按章节分块（基于`#`、`##`） | 500-1000字符 | 10-15% |
| **JavaDoc HTML** | 按类/方法分块 | 300-500字符 | 5% |
| **配置文件** | 按配置节分块（基于YAML层级） | 200-300字符 | 5% |

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
- **文档类**：chunkSize=800, chunkOverlap=80（保留完整段落上下文）
- **代码类**：chunkSize=500, chunkOverlap=50（按方法粒度切分）
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
                                         截取 topK 个最相关分块
                                                    ↓
                                         根据document_id去重，得到文档ID列表
                                                    ↓
                                         （可选）去MySQL获取完整文档内容
                                                    ↓
                                         拼接上下文 → LLM生成回答
                                                    ↓
                                         返回回答（包含引用来源，指向原始文档）

可选：基于元数据过滤（如指定模块、类型）   可选：按相似度排序
```

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

```
手动触发/定时任务
    ↓
遍历知识源目录，收集当前所有文件路径
    ↓
从MySQL查询已有文档列表（按知识源分组）
    ↓
对比文件路径，识别三种状态：
    ├─ 新增文件：当前目录有，MySQL中没有
    ├─ 更新文件：当前目录有，MySQL中有，哈希不同
    └─ 删除文件：当前目录没有，MySQL中有
    ↓
处理新增文件：
    ├─ 读取文件内容
    ├─ 计算文件哈希
    ├─ 插入MySQL文档表，获取文档ID
    ├─ 文档分块处理
    ├─ 对每个分块Embedding生成向量
    └─ 写入Milvus（包含document_id和chunk_index）
    ↓
处理更新文件：
    ├─ 读取文件内容
    ├─ 计算文件哈希
    ├─ 更新MySQL文档内容，版本号+1
    ├─ 删除Milvus中该document_id的所有旧分块
    ├─ 文档分块处理
    ├─ 对每个分块Embedding生成向量
    └─ 写入Milvus（包含document_id和chunk_index）
    ↓
处理删除文件：
    ├─ 获取document_id
    ├─ 删除Milvus中该document_id的所有分块
    └─ 更新MySQL文档状态为DELETED（软删除）
    ↓
更新MySQL中document表的chunk_count字段
```

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

**拼接位置**：在 `portal-service` 的 `PortalChatService` 中完成（不在 chat-service 中）

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

[对话历史]（最近5轮，从 Redis 读取）
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
4. 从 ChatMemoryService 获取最近5轮对话历史
5. 拼接完整Prompt：System Prompt + 文档上下文 + 对话历史 + 用户提问
6. 将完整Prompt传给 chat-service 的 SSE 端点
```

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

**热更新场景**：知识文件变更后，无需重启服务即可更新向量库

**实现方式**：
1. **手动触发**：通过 B端 `/admin/knowledge/sync` 接口手动触发同步
2. **定时任务**：`KnowledgeSyncScheduler` 定时检查文件变更（默认每小时一次）
3. **文件监听**（可选）：通过 Java NIO WatchService 监听知识源目录变更

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

**文档版本**：v1.2  
**创建日期**：2026-07-12  
**最后更新**：2026-07-13  
**适用版本**：Scaffold AI Assistant v1.0