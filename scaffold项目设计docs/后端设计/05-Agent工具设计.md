# 📄 Agent工具设计文档

> **文档说明**：本文档详细描述 Scaffold AI Assistant 中所有Agent工具的设计。

---

## 5.1 工具清单

| 工具名称                   | 功能描述       | 参数                                           | 返回值          | 使用场景          |
| ---------------------- | ---------- | -------------------------------------------- | ------------ | ------------- |
| **ReadFileTool**       | 读取指定文件内容   | `filePath`: 文件绝对路径                           | 文件内容字符串      | 用户询问某个类的实现    |
| **SearchCodeTool**     | 搜索代码中的类/方法 | `keyword`: 搜索关键词                             | 匹配的类/方法列表及位置 | 用户询问某个功能在哪里实现 |
| **ListDirTool**        | 列出目录结构     | `dirPath`: 目录路径                              | 目录树结构        | 用户询问项目结构      |
| **SearchInFileTool**   | 在文件中搜索关键字  | `filePath`: 文件路径<br>`keyword`: 关键字           | 匹配行及上下文      | 用户询问某个方法的具体实现 |
| **NacosConfigTool**    | 查询Nacos配置项 | `dataId`: 配置ID<br>`group`: 分组<br>`env`: 环境   | 配置内容         | 用户询问配置项含义     |
| **CompareConfigTool**  | 对比不同环境配置   | `dataId`: 配置ID<br>`env1`: 环境1<br>`env2`: 环境2 | 配置差异对比       | 用户询问环境差异      |
| **PreDeployCheckTool** | 部署前检查清单    | `env`: 目标环境                                  | 检查结果清单       | 用户部署前检查       |

---

## 5.2 工具详细设计

### 5.2.1 ReadFileTool

**类名**：`ReadFileTool`

**包路径**：`com.zmbdp.chat.tool.tool`

**职责**：读取指定文件的内容

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class ReadFileTool {

    @Tool(description = "读取指定文件的内容，参数filePath为文件绝对路径。用于查看某个类的完整源码实现。")
    public String readFile(String filePath) {
        // 实现逻辑见下文
    }
}
```

> **重要**：`description` 属性会被发送给大模型，必须清晰描述工具用途和参数含义，以便大模型正确决策是否调用。

**依赖注入**：

| 依赖 | 类型 | 说明 |
|------|------|------|
| `allowedPaths` | `List<String>`（`@Value`注入） | 从 `knowledge.allowed-paths` 配置读取路径白名单 |
| `pathBlacklist` | `List<String>`（`@Value`注入） | 从 `pathBlacklist` 配置读取黑名单目录 |
| `maxFileSize` | `Long`（`@Value`注入） | 从 `maxFileSize` 配置读取最大文件大小 |

> **说明**：ReadFileTool 不依赖任何 Service，仅通过 `@Value` 注入 Nacos 配置项（支持 `@RefreshScope` 动态刷新）。

**参数**：

| 参数名        | 类型     | 必填  | 说明      |
| ---------- | ------ | --- | ------- |
| `filePath` | String | 是   | 文件的绝对路径 |

**返回值**：

| 返回类型   | 说明      |
| ------ | ------- |
| String | 文件内容字符串 |

**安全性**：

- 必须校验路径在允许的范围内（动态白名单机制）
- 禁止访问系统敏感目录（如 `/etc`, `~/` 等）
- 禁止访问 `.git` 目录
- 禁止访问隐藏文件

**实现逻辑**：

```
1. 从Nacos配置读取路径白名单（配置项 `knowledge.allowed-paths`，支持动态刷新，详见 09-部署方案.md 9.4.4节）
2. 校验filePath是否在白名单范围内
   - 检查路径是否以白名单前缀开头
   - 检查路径是否包含黑名单目录（.git, /etc等）
3. 如果不在白名单，抛出SecurityException
4. 使用Java NIO读取文件内容
5. 如果文件过大（>配置的maxFileSize，默认10MB），返回前maxFileSize内容并提示文件过大
6. 返回文件内容
```

**异常处理**：

| 异常场景 | 处理方式 | 返回给大模型的内容 |
|---------|---------|----------------|
| 文件不存在 | `NoSuchFileException` | `{"error": "文件不存在: {filePath}"}` |
| 路径不在白名单 | `SecurityException` | `{"error": "路径不在允许范围内: {filePath}"}` |
| 文件过大 | 截断返回 | 文件前 `maxFileSize` 内容 + `...[文件过大，已截断]` |
| 读取IO异常 | `IOException` | `{"error": "文件读取失败: {具体错误信息}"}` |

**配置项**（位于 `zmbdp-chat-service-${env}.yaml`，支持 `refresh: true` 动态刷新）：

| 配置项 | 说明 | 默认值 |
|-------|------|--------|
| `knowledge.allowed-paths` | 允许访问的路径前缀列表（范围包含整个项目） | `["/path/to/scaffold-ai-assistant"]` |
| `knowledge.base-path` | 项目根路径 | `/path/to/scaffold-ai-assistant` |
| `pathBlacklist` | 禁止访问的路径列表 | `[".git", "/etc", "/tmp"]` |
| `maxFileSize` | 最大文件大小（字节） | `10485760`（10MB） |

---

### 5.2.2 SearchCodeTool

**类名**：`SearchCodeTool`

**包路径**：`com.zmbdp.chat.tool.tool`

**职责**：在代码库中搜索类或方法，基于Lucene倒排索引实现，提升搜索效率

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class SearchCodeTool {

    private final ILuceneSearchService luceneSearchService;

    public SearchCodeTool(ILuceneSearchService luceneSearchService) {
        this.luceneSearchService = luceneSearchService;
    }

    @Tool(description = "在代码库中搜索类或方法，参数keyword为搜索关键词（类名、方法名等），limit为返回结果数量。用于查找某个功能在哪里实现。")
    public String searchCode(String keyword, Integer limit) {
        // 实现逻辑见下文
    }
}
```

**依赖注入**：

| 依赖 | 类型 | 说明 |
|------|------|------|
| `luceneSearchService` | `ILuceneSearchService` | Lucene 倒排索引搜索服务，负责分词和索引检索 |

> **说明**：SearchCodeTool 通过构造器注入 `ILuceneSearchService`，该 Service 在 `ToolConfig` 中注册时传入（见 5.4 节）。`ILuceneSearchService` 的实现类 `LuceneSearchServiceImpl` 负责索引的构建、更新和检索。

**参数**：

| 参数名       | 类型     | 必填  | 说明             |
| --------- | ------ | --- | -------------- |
| `keyword` | String | 是   | 搜索关键词（类名、方法名等） |
| `limit`   | Integer | 否   | 返回结果数量，默认10 |

**返回值**：

```json
{
  "results": [
    {
      "filePath": "/path/to/File.java",
      "className": "ClassName",
      "methodName": "methodName",
      "lineNumber": 100,
      "context": "方法签名和简短上下文",
      "score": 0.95
    }
  ]
}
```

**安全性**：
- 搜索范围仅限于 Lucene 索引中已索引的文件（即知识源配置的代码目录）
- 不直接访问文件系统，通过 `ILuceneSearchService` 间接访问，无需额外白名单校验
- 返回结果中的 `filePath` 已被限制在知识源路径范围内

**实现逻辑**：

```
1. 调用 ILuceneSearchService.search() 进行倒排索引搜索
2. ILuceneSearchService 使用 Analyzer 分词关键词
3. 在倒排索引中查找匹配的文档（类名、方法名、文件内容）
4. 返回按相关性排序的结果列表（最多返回limit个结果）
5. 索引构建：系统启动时或知识同步时自动构建Lucene索引
6. 增量更新：文件变更时自动更新对应索引
```

**异常处理**：

| 异常场景 | 处理方式 | 返回给大模型的内容 |
|---------|---------|----------------|
| 关键词为空 | 参数校验 | `{"error": "搜索关键词不能为空"}` |
| 索引未初始化 | `IllegalStateException` | `{"error": "代码索引未初始化，请稍后重试"}` |
| 搜索无结果 | 正常返回 | `{"results": [], "message": "未找到匹配的代码"}` |
| 搜索异常 | `Exception` | `{"error": "代码搜索失败: {具体错误信息}"}` |

**索引结构**：
- **Field**: `filePath` - 文件路径
- **Field**: `className` - 类名（分词索引）
- **Field**: `methodName` - 方法名（分词索引）
- **Field**: `content` - 文件内容（分词索引，用于全文搜索）
- **Field**: `lineNumber` - 行号（存储字段）

> **索引构建时机**：`LuceneSearchServiceImpl` 在 `@PostConstruct` 阶段检查索引是否存在，不存在则触发全量构建；知识同步时（`IKnowledgeLoaderService.syncKnowledge()`）会调用 `ILuceneSearchService.updateIndex()` 增量更新索引。

---

### 5.2.3 ListDirTool

**类名**：`ListDirTool`

**包路径**：`com.zmbdp.chat.tool.tool`

**职责**：列出指定目录的结构

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class ListDirTool {

    @Tool(description = "列出指定目录的结构，参数dirPath为目录绝对路径。用于查看项目或模块的目录组织。")
    public String listDir(String dirPath) {
        // 实现逻辑见下文
    }
}
```

**依赖注入**：

| 依赖 | 类型 | 说明 |
|------|------|------|
| `allowedPaths` | `List<String>`（`@Value`注入） | 从 `knowledge.allowed-paths` 配置读取路径白名单 |
| `pathBlacklist` | `List<String>`（`@Value`注入） | 从 `pathBlacklist` 配置读取黑名单目录 |

> **说明**：ListDirTool 不依赖任何 Service，仅通过 `@Value` 注入 Nacos 配置项（与 ReadFileTool 共用白名单配置）。

**参数**：

| 参数名       | 类型     | 必填  | 说明      |
| --------- | ------ | --- | ------- |
| `dirPath` | String | 是   | 目录的绝对路径 |

**返回值**：

| 返回类型   | 说明       |
| ------ | -------- |
| String | 目录树结构字符串 |

**安全性**：

- 必须校验路径在允许的范围内
- 复用 ReadFileTool 的白名单校验逻辑（`knowledge.allowed-paths` + `pathBlacklist`）

**实现逻辑**：

```
1. 校验dirPath是否在允许的知识库路径内
2. 如果不在白名单，抛出SecurityException
3. 递归遍历目录结构
4. 生成ASCII目录树（最多显示3层深度）
5. 返回目录树字符串
```

**异常处理**：

| 异常场景 | 处理方式 | 返回给大模型的内容 |
|---------|---------|----------------|
| 目录不存在 | `NoSuchFileException` | `{"error": "目录不存在: {dirPath}"}` |
| 路径不在白名单 | `SecurityException` | `{"error": "路径不在允许范围内: {dirPath}"}` |
| 目录层级过深 | 限制3层 | 正常返回，超出3层部分显示 `...` |
| 遍历IO异常 | `IOException` | `{"error": "目录遍历失败: {具体错误信息}"}` |

---

### 5.2.4 SearchInFileTool

**类名**：`SearchInFileTool`

**包路径**：`com.zmbdp.chat.tool.tool`

**职责**：在指定文件中搜索关键字

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class SearchInFileTool {

    @Tool(description = "在指定文件中搜索关键字，参数filePath为文件绝对路径，keyword为搜索关键字。用于查找某个方法或变量在文件中的具体位置。")
    public String searchInFile(String filePath, String keyword) {
        // 实现逻辑见下文
    }
}
```

**依赖注入**：

| 依赖 | 类型 | 说明 |
|------|------|------|
| `allowedPaths` | `List<String>`（`@Value`注入） | 从 `knowledge.allowed-paths` 配置读取路径白名单 |
| `pathBlacklist` | `List<String>`（`@Value`注入） | 从 `pathBlacklist` 配置读取黑名单目录 |

> **说明**：SearchInFileTool 不依赖任何 Service，仅通过 `@Value` 注入 Nacos 配置项（与 ReadFileTool 共用白名单配置）。

**参数**：

| 参数名        | 类型     | 必填  | 说明      |
| ---------- | ------ | --- | ------- |
| `filePath` | String | 是   | 文件的绝对路径 |
| `keyword`  | String | 是   | 搜索关键字   |

**返回值**：

```json
{
  "matches": [
    {
      "lineNumber": 100,
      "lineContent": "包含关键字的完整行",
      "contextBefore": "前2行内容",
      "contextAfter": "后2行内容"
    }
  ]
}
```

**安全性**：

- 必须校验filePath在允许的范围内
- 复用 ReadFileTool 的白名单校验逻辑（`knowledge.allowed-paths` + `pathBlacklist`）

**实现逻辑**：

```
1. 校验filePath是否在允许的知识库路径内
2. 使用Java NIO读取文件内容（按行读取）
3. 查找包含keyword的行
4. 收集匹配行及前后2行上下文
5. 返回匹配结果（最多返回20个匹配）
```

**异常处理**：

| 异常场景 | 处理方式 | 返回给大模型的内容 |
|---------|---------|----------------|
| 文件不存在 | `NoSuchFileException` | `{"error": "文件不存在: {filePath}"}` |
| 路径不在白名单 | `SecurityException` | `{"error": "路径不在允许范围内: {filePath}"}` |
| 关键词为空 | 参数校验 | `{"error": "搜索关键字不能为空"}` |
| 无匹配结果 | 正常返回 | `{"matches": [], "message": "未找到匹配内容"}` |
| 读取IO异常 | `IOException` | `{"error": "文件读取失败: {具体错误信息}"}` |

---

### 5.2.5 NacosConfigTool

**类名**：`NacosConfigTool`

**包路径**：`com.zmbdp.chat.tool.tool`

**职责**：查询Nacos配置项的内容

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class NacosConfigTool {

    @Tool(description = "查询Nacos配置项的内容，参数dataId为配置ID，group为分组（默认DEFAULT_GROUP），env为环境（dev/test/prd）。用于查看某个配置项的具体内容。")
    public String getConfig(String dataId, String group, String env) {
        // 实现逻辑见下文
    }
}
```

**依赖注入**：

| 依赖 | 类型 | 说明 |
|------|------|------|
| `nacosConfigService` | `INacosConfigService` | Nacos 配置查询服务，封装 Nacos OpenAPI 调用 |

> **说明**：`INacosConfigService` 封装了 Nacos 配置中心的 HTTP API 调用（通过 `NacosRestTemplate` 或 `WebClient` 实现），支持按 dataId/group/namespace 查询配置内容。

**参数**：

| 参数名      | 类型     | 必填  | 说明                           |
| -------- | ------ | --- | ---------------------------- |
| `dataId` | String | 是   | 配置ID（如 share-redis-dev.yaml） |
| `group`  | String | 否   | 分组（默认 DEFAULT_GROUP）         |
| `env`    | String | 否   | 环境（dev/test/prd）             |

**返回值**：

| 返回类型   | 说明     |
| ------ | ------ |
| String | 配置文件内容 |

**实现逻辑**：

```
1. 如果提供了env，自动构建dataId（如 share-redis-{env}.yaml）
2. 调用 INacosConfigService.getConfig(dataId, group, namespace) 查询配置
3. 如果配置不存在，返回提示信息
4. 返回配置内容
```

**异常处理**：

| 异常场景 | 处理方式 | 返回给大模型的内容 |
|---------|---------|----------------|
| 配置不存在 | 正常返回 | `{"error": "配置不存在: {dataId}"}` |
| dataId为空 | 参数校验 | `{"error": "dataId不能为空"}` |
| Nacos连接失败 | `Exception` | `{"error": "Nacos配置中心连接失败: {具体错误信息}"}` |

---

### 5.2.6 CompareConfigTool

**类名**：`CompareConfigTool`

**包路径**：`com.zmbdp.chat.tool.tool`

**职责**：对比不同环境的配置差异

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class CompareConfigTool {

    @Tool(description = "对比不同环境的配置差异，参数dataId为配置ID，env1和env2为要对比的两个环境（dev/test/prd）。用于排查环境差异问题。")
    public String compareConfig(String dataId, String env1, String env2) {
        // 实现逻辑见下文
    }
}
```

**依赖注入**：

| 依赖 | 类型 | 说明 |
|------|------|------|
| `nacosConfigService` | `INacosConfigService` | Nacos 配置查询服务（与 NacosConfigTool 共用） |

> **说明**：CompareConfigTool 依赖 `INacosConfigService` 分别获取两个环境的配置内容，然后在内存中解析 YAML 结构并对比差异。

**参数**：

| 参数名      | 类型     | 必填  | 说明                  |
| -------- | ------ | --- | ------------------- |
| `dataId` | String | 是   | 配置ID（如 share-redis） |
| `env1`   | String | 是   | 环境1（dev/test/prd）   |
| `env2`   | String | 是   | 环境2（dev/test/prd）   |

**返回值**：

```json
{
  "differences": [
    {
      "key": "spring.data.redis.host",
      "env1Value": "localhost",
      "env2Value": "redis-cluster",
      "description": "Redis地址不同"
    }
  ]
}
```

**实现逻辑**：

```
1. 构建两个环境的配置文件名（如 share-redis-dev.yaml 和 share-redis-prd.yaml）
2. 调用 INacosConfigService.getConfig() 分别读取两个环境的配置内容
3. 解析YAML结构，对比差异
4. 返回差异列表
```

**异常处理**：

| 异常场景 | 处理方式 | 返回给大模型的内容 |
|---------|---------|----------------|
| 配置不存在 | 正常返回 | `{"error": "配置不存在: {dataId}（{env}）"}` |
| 环境相同 | 参数校验 | `{"error": "对比的两个环境不能相同"}` |
| Nacos连接失败 | `Exception` | `{"error": "Nacos配置中心连接失败: {具体错误信息}"}` |
| YAML解析失败 | `Exception` | `{"error": "配置文件格式错误: {具体错误信息}"}` |

---

### 5.2.7 PreDeployCheckTool

**类名**：`PreDeployCheckTool`

**包路径**：`com.zmbdp.chat.tool.tool`

**职责**：部署前检查清单

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class PreDeployCheckTool {

    @Tool(description = "部署前检查清单，参数env为目标环境（dev/test/prd）。用于部署前自动检查配置是否完整、合理。")
    public String preDeployCheck(String env) {
        // 实现逻辑见下文
    }
}
```

**依赖注入**：

| 依赖 | 类型 | 说明 |
|------|------|------|
| `nacosConfigService` | `INacosConfigService` | Nacos 配置查询服务（用于读取各环境配置） |
| `allowedPaths` | `List<String>`（`@Value`注入） | 从 `knowledge.allowed-paths` 配置读取路径白名单（用于读取部署脚本） |

> **说明**：PreDeployCheckTool 依赖 `INacosConfigService` 读取各环境配置，同时通过 `@Value` 注入路径白名单以读取本地的部署脚本和 Dockerfile。

**参数**：

| 参数名   | 类型     | 必填  | 说明                 |
| ----- | ------ | --- | ------------------ |
| `env` | String | 是   | 目标环境（dev/test/prd） |

**返回值**：

```json
{
  "checkItems": [
    {
      "item": "Nacos配置检查",
      "status": "PASS/FAIL",
      "details": "配置项是否完整",
      "suggestion": "如果失败，建议的修复方案"
    }
  ],
  "summary": "检查结果汇总",
  "warnings": ["警告信息列表"],
  "errors": ["错误信息列表"]
}
```

**检查项清单**：

| 检查项 | 说明 |
|-------|------|
| Nacos配置检查 | 检查所有必需的配置项是否存在 |
| 数据库连接检查 | 检查数据库配置是否正确 |
| Redis配置检查 | 检查Redis连接池配置是否合理 |
| Docker配置检查 | 检查Dockerfile和docker-compose配置 |
| SkyWalking配置检查 | 检查SkyWalking配置是否正确 |
| 端口占用检查 | 检查服务端口是否被占用 |
| JVM参数检查 | 检查JVM参数是否合理 |

**实现逻辑**：

```
1. 根据目标环境读取对应的配置文件（通过 INacosConfigService）
2. 读取部署脚本和 Dockerfile（通过 Java NIO，受白名单限制）
3. 逐项检查配置项是否完整、合理
4. 记录检查结果、警告和错误
5. 返回检查报告
```

**异常处理**：

| 异常场景 | 处理方式 | 返回给大模型的内容 |
|---------|---------|----------------|
| env为空 | 参数校验 | `{"error": "目标环境不能为空"}` |
| 环境无效 | 参数校验 | `{"error": "无效的环境: {env}，支持 dev/test/prd"}` |
| Nacos连接失败 | `Exception` | `{"error": "Nacos配置中心连接失败: {具体错误信息}"}` |
| 配置文件读取失败 | `IOException` | `{"error": "部署配置文件读取失败: {具体错误信息}"}` |

---

## 5.3 工具调用流程

### 5.3.1 整体流程

```
用户提问 → PortalChatService（portal-service）
             ├─ Step 1: RAG 检索（Feign → chat-service /chat/retrieve）
             ├─ Step 2: 拼接 Prompt（system prompt + RAG 上下文 + 对话历史 + 用户提问）
             └─ Step 3: WebClient 调用 chat-service SSE 端点 /chat/completions/stream
                          ↓
               chat-service 的 ChatServiceImpl 接收请求
                          ↓
               Step 4: 调用 Spring AI 的 ChatClient.stream(prompt)
                          ↓
               Step 5: 大模型分析 Prompt，判断是否需要调用工具
                 ├─ 不需要工具 → 直接生成回答 → 流式返回
                 └─ 需要工具 → 返回工具调用指令（toolCall）
                          ↓
               Step 6: Spring AI 的 ToolExecutor 拦截工具调用指令
                 ├─ 根据 toolName 找到对应的 @Tool 方法
                 ├─ 解析参数（从 LLM 返回的 JSON 中提取）
                 ├─ 执行工具方法（如 ReadFileTool.readFile("/path/to/file.java")）
                 └─ 获取工具返回结果
                          ↓
               Step 7: 将工具返回结果注入到 LLM 上下文
                 ├─ 构建 toolResponse 消息（role: function/tool）
                 └─ 追加到对话历史
                          ↓
               Step 8: 大模型基于工具返回结果 + 原始上下文，生成最终回答
                          ↓
               Step 9: 流式返回回答给 portal-service → 透传给前端
```

### 5.3.2 AI 如何判断需要调用工具

**判断机制**：大模型通过 `@Tool(description="...")` 注解的 description 属性判断是否需要调用工具。

**Spring AI 工作原理**：
```
1. ChatServiceImpl 在调用 ChatClient 时，会传入所有 enabled=true 的工具描述
2. 大模型收到 Prompt + 工具描述列表，分析用户意图
3. 若用户问题需要实时源码信息（如 "ReadFileTool 在哪定义？"），大模型返回工具调用指令：
   {
     "tool_calls": [{
       "function": {
         "name": "ReadFileTool.readFile",
         "arguments": "{\"filePath\": \"/path/to/ReadFileTool.java\"}"
       }
     }]
   }
4. 若用户问题可通过 RAG 上下文回答（如 "三级缓存的原理是什么？"），大模型直接生成回答
```

**工具与 RAG 的协同关系**：
| 场景 | 使用 RAG | 使用工具 | 说明 |
|-----|---------|---------|------|
| 用户问"三级缓存怎么用？" | ✅ | ❌ | RAG 上下文已有答案 |
| 用户问"ReadFileTool 的 readFile 方法源码是什么？" | ❌ | ✅ ReadFileTool | 需要实时读取源码 |
| 用户问"项目里哪些地方用到了 @Idempotent？" | ❌ | ✅ SearchCodeTool | 需要搜索代码库 |
| 用户问"common-cache 模块的目录结构是什么？" | ❌ | ✅ ListDirTool | 需要遍历目录 |
| 用户问"ResultCode.java 里有哪些错误码？" | ❌ | ✅ SearchInFileTool | 需要搜索文件内容 |
| 用户问"dev 环境的 Redis 配置是什么？" | ❌ | ✅ NacosConfigTool | 需要读取 Nacos 配置 |

> **注意**：工具调用由大模型自主决策，开发者无法控制。但可以通过 system prompt 引导大模型优先使用 RAG 上下文。

### 5.3.3 工具调用结果如何返回给前端

**SSE 工具调用帧**：当大模型决定调用工具时，chat-service 会通过 SSE 发送工具调用帧给前端：

```
# 1. 工具调用帧（告知前端正在调用工具）
data: {"toolCall": {"name": "ReadFileTool", "args": {"filePath": "/path/to/file.java"}}, "done": false}

# 2. 内容帧（工具执行完成后，大模型基于工具结果生成回答）
data: {"chunk": "ReadFileTool 的 readFile 方法位于第34行", "done": false}

# 3. 结束帧
data: {"chunk": "", "done": true, "sessionId": "xxx", "sources": [...], "model": "qwen-max"}
```

**前端处理建议**：
- 收到工具调用帧时，显示"正在调用工具：ReadFileTool..."的加载状态
- 收到内容帧时，开始打字机效果展示回答
- 收到结束帧时，结束流式接收，保存 sessionId

### 5.3.4 工具执行失败处理

```
工具执行失败 → 返回错误信息给大模型
                    ↓
        大模型基于错误信息决定：
        ├─ 重试调用（修正参数后再次调用）
        ├─ 换用其他工具（如 ReadFileTool 失败，尝试 SearchCodeTool）
        └─ 放弃工具调用，基于已有上下文回答
                    ↓
        流式返回最终回答给前端
```

**常见失败场景**：
| 失败场景 | 错误信息 | 大模型可能的处理 |
|---------|---------|----------------|
| 文件不存在 | `{"error": "文件不存在: /path/to/file"}` | 换用 SearchCodeTool 搜索文件路径 |
| 路径不在白名单 | `{"error": "路径不在允许范围内"}` | 告知用户无法访问该路径 |
| 工具执行超时 | `{"error": "工具执行超时", "toolName": "ReadFileTool"}` | 基于已有上下文回答 |
| 搜索无结果 | `{"error": "未找到匹配的结果"}` | 告知用户未找到相关代码 |

### 5.3.5 源码读取功能完整示例

**场景**：用户提问 "ReadFileTool 的 readFile 方法源码是什么？"

**完整调用链路**：
```
1. 前端发送 POST /portal/chat/stream
   请求体: {"message": "ReadFileTool的readFile方法源码是什么？"}

2. PortalChatService.streamChat() 接收请求
   ├─ 解析 JWT Token，提取 userId
   ├─ RAG 检索（Feign → chat-service /chat/retrieve）
   │   └─ 返回 RAG 上下文（可能包含 ReadFileTool 的设计文档片段）
   ├─ 拼接 Prompt（system prompt + RAG 上下文 + 用户提问）
   └─ WebClient 调用 chat-service /chat/completions/stream

3. chat-service 的 ChatServiceImpl.streamChat() 接收请求
   ├─ 构建 Spring AI ChatClient 请求（含 Prompt + 工具描述列表）
   └─ 调用 ChatClient.stream(prompt)

4. 大模型分析 Prompt，判断需要调用 ReadFileTool
   └─ 返回工具调用指令: {"name": "ReadFileTool.readFile", "args": {"filePath": "/project/.../ReadFileTool.java"}}

5. Spring AI ToolExecutor 执行工具
   ├─ 找到 ReadFileTool 的 @Tool 注解方法 readFile(String filePath)
   ├─ 校验 filePath 在白名单范围内
   ├─ 使用 Java NIO 读取文件内容
   └─ 返回文件内容字符串

6. 工具返回结果注入到 LLM 上下文
   └─ 构建 toolResponse 消息追加到对话历史

7. 大模型基于源码内容生成回答
   └─ 流式返回: "readFile 方法的源码如下：\n```java\npublic String readFile(String filePath) {\n    ...\n}\n```"

8. 流式数据透传给前端
   ├─ 工具调用帧: {"toolCall": {"name": "ReadFileTool", "args": {...}}, "done": false}
   ├─ 内容帧: {"chunk": "readFile 方法的源码如下：\n```java\n...", "done": false}
   └─ 结束帧: {"chunk": "", "done": true, "sessionId": "xxx", "sources": [...], "model": "qwen-max"}

9. 流结束后，PortalChatService 异步保存对话历史
```

---

## 5.4 工具注册方式

所有工具通过 Spring AI 的 `@Tool` 注解注册，`@Tool` 的 `description` 属性用于向大模型描述工具用途（大模型据此判断是否调用该工具）：

```java
@Component
public class ReadFileTool {

    @Tool(description = "读取指定文件的内容，参数filePath为文件绝对路径")
    public String readFile(String filePath) {
        // 实现逻辑
    }
}
```

> **重要**：`@Tool` 注解的 `description` 属性会被发送给大模型，描述必须清晰准确，包含参数说明，以便大模型正确调用工具。

**工具注册机制**（基于 `@Component` + `@Tool` 自动扫描）：

所有工具类标注 `@Component`，Spring 容器自动扫描并创建 Bean 实例。Spring AI 的 `ToolExecutor` 会自动发现所有 `@Tool` 注解方法并注册为可调用工具。

**依赖注入方式**：
- **无依赖工具**（ReadFileTool、ListDirTool、SearchInFileTool）：通过 `@Value` 注入 Nacos 配置项，Spring 自动创建实例。
- **有依赖工具**（SearchCodeTool）：通过构造器注入 `ILuceneSearchService`，Spring 自动匹配 Bean。
  ```java
  @Component
  public class SearchCodeTool {
      private final ILuceneSearchService luceneSearchService;

      public SearchCodeTool(ILuceneSearchService luceneSearchService) {
          this.luceneSearchService = luceneSearchService;
      }
  }
  ```
- **Nacos 相关工具**（NacosConfigTool、CompareConfigTool、PreDeployCheckTool）：通过构造器注入 `INacosConfigService`，Spring 自动匹配 Bean。

**ToolConfig 的职责**（`@Service`，非 `@Bean` 注册类）：

```java
@Service
public class ToolConfig {

    private final SysAiToolConfigMapper toolConfigMapper;
    private final List<Object> toolBeans;  // Spring 自动注入所有 @Component 工具

    public ToolConfig(SysAiToolConfigMapper toolConfigMapper, List<Object> toolBeans) {
        this.toolConfigMapper = toolConfigMapper;
        this.toolBeans = toolBeans;
    }

    /**
     * 获取已启用的工具列表（根据 sys_ai_tool_config 表的 enabled 字段过滤）
     */
    public List<Object> getEnabledTools() {
        List<SysAiToolConfig> configs = toolConfigMapper.selectAll();
        Set<String> enabledNames = configs.stream()
            .filter(SysAiToolConfig::getEnabled)
            .map(SysAiToolConfig::getName)
            .collect(Collectors.toSet());
        return toolBeans.stream()
            .filter(tool -> enabledNames.contains(tool.getClass().getSimpleName()))
            .collect(Collectors.toList());
    }
}
```

> **说明**：`ToolConfig` 不再负责通过 `@Bean` 注册工具（工具通过 `@Component` 自动扫描注册）。`ToolConfig` 改为 `@Service`，职责是根据 `sys_ai_tool_config` 表的 `enabled` 字段过滤出已启用的工具，供 `IChatService` 调用 `ChatClient` 时传入。

**工具自动发现**：Spring AI 会自动扫描所有带有 `@Tool` 注解的方法，并注册到 `ToolExecutor` 中，Agent 可以自动调用这些工具。

**工具执行超时处理**：
- 每个工具调用设置 30 秒超时（通过 Spring AI 的 `ToolExecutionExceptionPredicate` 或自定义超时逻辑）
- 超时后返回错误信息：`{"error": "工具执行超时", "toolName": "ReadFileTool"}`
- 工具执行失败不中断对话流，错误信息会传递给大模型，由模型决定是否重试或换用其他工具

**工具启用/禁用机制**：
- 工具配置存储在 `sys_ai_tool_config` 表（见 07-项目架构设计.md 7.4.7节）
- `enabled=0` 的工具不会被注册到 `ToolExecutor`，大模型无法调用
- 支持运行时动态启用/禁用（修改数据库后刷新 ToolExecutor）

---

**文档版本**：v1.4  
**创建日期**：2026-07-12  
**最后更新**：2026-07-13  
**适用版本**：Scaffold AI Assistant v1.0