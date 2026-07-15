# 📄 Agent工具设计文档

> **文档说明**：本文档详细描述 Scaffold AI Assistant 中所有Agent工具的设计。

---

## 5.1 工具清单

| 工具名称                   | 功能描述       | 参数                                           | 返回值          | 使用场景          |
| ---------------------- | ---------- | -------------------------------------------- | ------------ | ------------- |
| **ReadFileTool**       | 读取指定文件内容   | `filePath`: 文件绝对路径                           | 文件内容字符串      | 用户询问某个类的实现    |
| **SearchCodeTool**     | 搜索代码中的类/方法 | `keyword`: 搜索关键词<br>`limit`: 返回结果数量（可选，默认10） | 匹配的类/方法列表及位置 | 用户询问某个功能在哪里实现 |
| **ListDirTool**        | 列出目录结构     | `dirPath`: 目录路径                              | 目录树结构        | 用户询问项目结构      |
| **SearchInFileTool**   | 在文件中搜索关键字  | `filePath`: 文件路径<br>`keyword`: 关键字           | 匹配行及上下文      | 用户询问某个方法的具体实现 |
| **NacosConfigTool**    | 查询Nacos配置项 | `dataId`: 配置ID（不带环境后缀）<br>`group`: 分组<br>`env`: 环境 | 配置内容         | 用户询问配置项含义     |
| **CompareConfigTool**  | 对比不同环境配置   | `dataId`: 配置ID（不带环境后缀）<br>`env1`: 环境1<br>`env2`: 环境2 | 配置差异对比       | 用户询问环境差异      |
| **PreDeployCheckTool** | 部署前检查清单    | `env`: 目标环境                                  | 检查结果清单       | 用户部署前检查       |

---

## 5.2 工具详细设计

### 5.2.1 ReadFileTool

**类名**：`ReadFileTool`

**包路径**：`com.zmbdp.chat.service.tool`

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
| `pathBlacklist` | `List<String>`（`@Value`注入） | 从 `knowledge.path-blacklist` 配置读取黑名单目录 |
| `maxFileSize` | `Long`（`@Value`注入） | 从 `knowledge.max-file-size` 配置读取最大文件大小 |

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
4. 使用 `FileUtil`（复用 `zmbdp-common-core`，继承 Hutool FileUtil）读取文件内容
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

**配置项**（位于 `zmbdp-chat-service-${env}.yaml`，支持 `refresh: true` 动态刷新，统一使用 `knowledge.` 前缀）：

| 配置项 | 说明 | 默认值 |
|-------|------|--------|
| `knowledge.allowed-paths` | 允许访问的路径前缀列表（范围包含整个项目） | `["/path/to/scaffold-ai-assistant"]` |
| `knowledge.base-path` | 项目根路径 | `/path/to/scaffold-ai-assistant` |
| `knowledge.path-blacklist` | 禁止访问的路径列表 | `[".git", "/etc", "/tmp"]` |
| `knowledge.max-file-size` | 最大文件大小（字节） | `10485760`（10MB） |

> **命名规范**：所有工具相关配置统一使用 `knowledge.` 前缀 + kebab-case 命名（与 Spring Boot 松散绑定 `@ConfigurationProperties` 规范一致），ListDirTool、SearchInFileTool 等工具复用同一组配置。

---

### 5.2.2 SearchCodeTool

**类名**：`SearchCodeTool`

**包路径**：`com.zmbdp.chat.service.tool`

**职责**：在代码库中搜索类或方法，基于简单文件遍历 + 关键词匹配实现（不使用 Lucene 倒排索引，避免过度设计）

> **设计决策**：原设计使用 Lucene 倒排索引，但经审视存在以下问题：
> 1. **过度设计**：脚手架代码库规模有限（非百万级文件），简单文件搜索完全满足需求
> 2. **维护复杂**：需额外维护索引构建、增量更新、Analyzer 分词等逻辑（`ILuceneSearchService`、`LuceneSearchServiceImpl`、`LuceneConfig`）
> 3. **业内主流做法**：GitHub Code Search、ripgrep 等工具在中小规模代码库中使用简单文本搜索即可
> 4. **性能可接受**：遍历数千个 Java 文件 + 关键词匹配，耗时在秒级，对 AI 对话场景完全够用
>
> **已移除的依赖**：`ILuceneSearchService`、`LuceneSearchServiceImpl`、`LuceneConfig`（从 07-项目架构设计.md 7.0.3 和目录树中同步移除）

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class SearchCodeTool {

    @Value("${knowledge.base-path}")
    private String basePath;

    @Value("${knowledge.allowed-paths}")
    private List<String> allowedPaths;

    @Tool(description = "在代码库中搜索类或方法，参数keyword为搜索关键词（类名、方法名等），limit为返回结果数量。用于查找某个功能在哪里实现。")
    public String searchCode(String keyword, Integer limit) {
        // 实现逻辑见下文
    }
}
```

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
      "filePath": "zmbdp-common/zmbdp-common-core/src/main/java/com/zmbdp/common/core/utils/JsonUtil.java",
      "className": "JsonUtil",
      "matchedLine": "public static String toJson(Object obj)",
      "lineNumber": 45,
      "context": "public static String toJson(Object obj) {"
    }
  ],
  "total": 3
}
```

**安全性**：
- 搜索范围通过 `knowledge.allowed-paths` 配置白名单限制
- 不返回白名单外的文件路径
- 文件路径返回相对路径（相对于 `basePath`），不暴露宿主机绝对路径

**实现逻辑**：

```
1. 校验 keyword 非空
2. 遍历 allowed-paths 下的所有 .java 文件（使用 java.nio.file.Files.walkFileTree）
3. 逐行读取文件内容，匹配 keyword：
   ├─ 类名匹配：文件中包含 "class {keyword}" 或 "interface {keyword}"
   ├─ 方法名匹配：行中包含 keyword 且为方法签名格式（含括号）
   └─ 全文匹配：行中包含 keyword
4. 收集匹配结果（filePath, className, matchedLine, lineNumber, context）
5. 按匹配优先级排序（类名 > 方法名 > 全文）
6. 限制返回数量（limit，默认10）
7. 使用 `JsonUtil.toJson()`（复用 `zmbdp-common-core`）序列化结果为 JSON 字符串并返回
```

**异常处理**：

| 异常场景 | 处理方式 | 返回给大模型的内容 |
|---------|---------|----------------|
| 关键词为空 | 参数校验 | `{"error": "搜索关键词不能为空"}` |
| 搜索无结果 | 正常返回 | `{"results": [], "message": "未找到匹配的代码"}` |
| 文件读取异常 | 跳过该文件继续搜索 | 记录 warning 日志，不影响其他文件搜索 |
| 白名单路径不存在 | 返回错误 | `{"error": "搜索路径不存在，请检查 knowledge.allowed-paths 配置"}` |

---

### 5.2.3 ListDirTool

**类名**：`ListDirTool`

**包路径**：`com.zmbdp.chat.service.tool`

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
| `pathBlacklist` | `List<String>`（`@Value`注入） | 从 `knowledge.path-blacklist` 配置读取黑名单目录 |

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

**包路径**：`com.zmbdp.chat.service.tool`

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
| `pathBlacklist` | `List<String>`（`@Value`注入） | 从 `knowledge.path-blacklist` 配置读取黑名单目录 |

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

**包路径**：`com.zmbdp.chat.service.tool`

**职责**：查询Nacos配置项的内容

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class NacosConfigTool {

    @Tool(description = "查询Nacos配置项的内容，参数dataId为配置ID（不带环境后缀，如share-redis），group为分组（默认DEFAULT_GROUP），env为环境（dev/test/prd）。用于查看某个配置项的具体内容。")
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
| `dataId` | String | 是   | 配置ID（**不带环境后缀**，如 `share-redis`，工具内部自动拼接 `-{env}.yaml`） |
| `group`  | String | 否   | 分组（默认 DEFAULT_GROUP）         |
| `env`    | String | 是   | 环境（dev/test/prd，必填，用于拼接完整 dataId） |

> **dataId 统一约定**（与 CompareConfigTool 一致）：大模型传入的 dataId 为**不带环境后缀的 base dataId**（如 `share-redis`、`zmbdp-chat-service`），工具内部根据 env 参数自动拼接完整 dataId（如 `share-redis-dev.yaml`）。这样 NacosConfigTool 和 CompareConfigTool 对 dataId 的定义一致，避免大模型调用时困惑。

**返回值**：

| 返回类型   | 说明     |
| ------ | ------ |
| String | 配置文件内容 |

**实现逻辑**：

```
1. 根据 env 参数自动构建完整 dataId：{dataId}-{env}.yaml（如 share-redis-dev.yaml）
2. 调用 INacosConfigService.getConfig(fullDataId, group, namespace) 查询配置
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

**包路径**：`com.zmbdp.chat.service.tool`

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
4. 使用 `JsonUtil.toJson()`（复用 `zmbdp-common-core`）序列化差异结果为 JSON 字符串并返回
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

**包路径**：`com.zmbdp.chat.service.tool`

**职责**：部署前**静态配置检查**（仅检查配置文件完整性，不做运行时连接测试）

> **设计决策**：原设计包含"数据库连接检查"、"Redis配置检查"、"端口占用检查"等运行时检查项，但经审视存在以下问题：
> 1. **无法实现**：工具运行在 chat-service 容器内，无法检查目标部署服务器的端口占用
> 2. **风险高**：在部署前检查中创建数据库/Redis 连接可能影响生产环境
> 3. **职责不清**：运行时连接检查应由 Spring Boot Actuator 健康检查（`/actuator/health`）负责，而非 AI 工具
>
> **简化为静态配置检查**：仅读取配置文件（Nacos 配置、Dockerfile、docker-compose.yml），检查必需配置项是否存在、格式是否正确。

**方法签名**（Spring AI `@Tool` 注解）：

```java
@Component
public class PreDeployCheckTool {

    @Tool(description = "部署前静态配置检查，参数env为目标环境（dev/test/prd）。检查Nacos配置项完整性、Dockerfile和docker-compose配置是否合理。不做运行时连接测试。")
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

**参数**：

| 参数名   | 类型     | 必填  | 说明                 |
| ----- | ------ | --- | ------------------ |
| `env` | String | 是   | 目标环境（dev/test/prd） |

**返回值**：

```json
{
  "checkItems": [
    {
      "item": "Nacos配置完整性",
      "status": "PASS/FAIL",
      "details": "检查 share-mysql/share-redis/share-token 等共享配置是否存在",
      "suggestion": "如果失败，建议的修复方案"
    },
    {
      "item": "Dockerfile配置",
      "status": "PASS/FAIL",
      "details": "检查基础镜像、端口暴露、JVM参数是否配置",
      "suggestion": ""
    }
  ],
  "summary": "5项通过，2项失败",
  "warnings": ["share-caffeine-dev.yaml 未配置，Caffeine本地缓存将不可用"],
  "errors": ["share-token-dev.yaml 不存在，AuthFilter启动会失败"]
}
```

**检查项清单**（仅静态配置检查，不做运行时测试）：

| 检查项 | 说明 | 实现方式 |
|-------|------|---------|
| Nacos共享配置完整性 | 检查 share-mysql/share-redis/share-token 等共享配置是否存在 | 通过 `INacosConfigService` 读取 Nacos |
| chat-service专属配置 | 检查 zmbdp-chat-service-{env}.yaml 是否存在且包含必需配置项（port、spring.ai.dashscope、milvus） | 通过 `INacosConfigService` 读取 |
| Dockerfile配置 | 检查基础镜像、EXPOSE端口、ENTRYPOINT是否正确 | 读取 `zmbdp-chat-service/src/main/docker/Dockerfile` |
| docker-compose配置 | 检查服务定义、端口映射、环境变量、依赖关系 | 读取 `deploy/{env}/app/docker-compose-app.yml` |
| 环境变量完整性 | 检查 RUN_ENV、NACOS_ADDR 等必需环境变量是否在 docker-compose 中配置 | 解析 docker-compose.yml 的 environment 段 |

> **不在检查范围内**（应由其他机制负责）：
> - 数据库/Redis 连接测试 → 由 Spring Boot Actuator `/actuator/health` 负责
> - 端口占用检查 → 由部署脚本在目标服务器上执行 `netstat`/`ss` 命令负责
> - SkyWalking 运行时配置 → 由 SkyWalking 自身健康检查负责
> - JVM 运行时参数 → 由 JVM 启动日志和 Prometheus 监控负责

**实现逻辑**：

```
1. 根据 env 参数，通过 INacosConfigService 读取 Nacos 配置
   ├─ 检查共享配置是否存在（share-mysql/share-redis/share-token 等）
   └─ 检查 chat-service 专属配置（zmbdp-chat-service-{env}.yaml）
2. 通过 `FileUtil`（复用 `zmbdp-common-core`）读取部署文件（受 allowedPaths 白名单限制）
   ├─ 读取 Dockerfile，检查 FROM/EXPOSE/ENTRYPOINT
   └─ 读取 docker-compose-app.yml，检查 services/env/ports
3. 汇总检查结果（PASS/FAIL），生成建议
4. 返回检查报告
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
                 ├─ 获取工具返回结果
                 └─ 通过 SSE 发送工具调用结果帧（toolResult）给前端，让用户看到工具调用结果摘要
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
         "name": "readFile",
         "arguments": "{\"filePath\": \"/path/to/ReadFileTool.java\"}"
       }
     }]
   }
4. 若用户问题可通过 RAG 上下文回答（如 "三级缓存的原理是什么？"），大模型直接生成回答
```

> **工具名说明**：Spring AI 默认使用 `@Tool` 注解方法的**方法名**作为工具调用名称（如 `readFile`、`searchCode`），不带类名前缀。ToolRegistryService 维护方法名到工具 Bean 的映射关系。

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

**SSE 工具调用帧**：当大模型决定调用工具时，chat-service 会通过 SSE 发送工具调用相关帧给前端（与 03-C端功能设计.md 3.2.1 节 SSE 响应帧格式一致）：

```
# 1. 工具调用开始帧（告知前端正在调用工具，AI决定调用工具时立即发送）
data: {"toolCall": {"name": "ReadFileTool", "args": {"filePath": "/path/to/file.java"}}, "done": false}

# 2. 工具调用结果帧（工具执行完成后发送，让前端展示工具调用的结果摘要）
data: {"toolResult": {"name": "ReadFileTool", "success": true, "summary": "读取文件成功，共123行", "duration": 45}, "done": false}

# 3. 内容帧（大模型基于工具结果生成回答）
data: {"chunk": "ReadFileTool 的 readFile 方法位于第34行", "done": false}

# 4. 结束帧
data: {"chunk": "", "done": true, "sessionId": "xxx", "sources": [...], "model": "qwen-max"}
```

> **帧设计说明**（参考 OpenAI Assistants API、LangChain Streaming 的 tool_start/tool_end 事件对）：
> - **工具调用开始帧（toolCall）**：AI决定调用工具时立即发送，前端展示"正在调用 ReadFileTool..."的加载状态
> - **工具调用结果帧（toolResult）**：工具执行完成后发送，让用户看到工具调用的结果摘要（不返回完整结果，避免SSE流过大；完整结果由AI整合到后续回答中）
> - 工具执行失败时，toolResult 的 `success=false`，`summary` 字段记录错误摘要

**前端处理建议**：
- 收到工具调用开始帧（toolCall）时，显示"正在调用工具：ReadFileTool..."的加载状态
- 收到工具调用结果帧（toolResult）时，更新工具状态为"完成/失败"，展示结果摘要
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
1. 前端发送 POST /portal/chat/completions/stream
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
   └─ 返回工具调用指令: {"name": "readFile", "args": {"filePath": "/project/.../ReadFileTool.java"}}

5. Spring AI ToolExecutor 执行工具
   ├─ 找到 ReadFileTool 的 @Tool 注解方法 readFile(String filePath)
   ├─ 校验 filePath 在白名单范围内
   ├─ 使用 `FileUtil`（复用 `zmbdp-common-core`）读取文件内容
   └─ 返回文件内容字符串

6. 工具返回结果注入到 LLM 上下文，同时通过 SSE 发送工具调用结果帧给前端
   ├─ 构建 toolResponse 消息追加到对话历史
   └─ SSE 发送: {"toolResult": {"name": "ReadFileTool", "success": true, "summary": "读取文件成功，共123行", "duration": 45}, "done": false}

7. 大模型基于源码内容生成回答
   └─ 流式返回: "readFile 方法的源码如下：\n```java\npublic String readFile(String filePath) {\n    ...\n}\n```"

8. 流式数据透传给前端
   ├─ 工具调用开始帧: {"toolCall": {"name": "ReadFileTool", "args": {"filePath": "/project/.../ReadFileTool.java"}}, "done": false}
   ├─ 工具调用结果帧: {"toolResult": {"name": "ReadFileTool", "success": true, "summary": "读取文件成功，共123行", "duration": 45}, "done": false}
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
- **无依赖工具**（ReadFileTool、ListDirTool、SearchInFileTool、SearchCodeTool）：通过 `@Value` 注入 Nacos 配置项（`knowledge.base-path`、`knowledge.allowed-paths`），Spring 自动创建实例。
- **Nacos 相关工具**（NacosConfigTool、CompareConfigTool、PreDeployCheckTool）：通过构造器注入 `INacosConfigService`，Spring 自动匹配 Bean。

**ToolRegistryService 的职责**（`@Service`，非 `@Configuration` Bean 定义类；改名原因详见 07-项目架构设计.md 7.0.4 节）：

```java
@Service
public class ToolRegistryService {

    private final ArgumentServiceApi argumentServiceApi;  // Feign 调用 admin-service 的 sys_argument 表
    private final List<Object> toolBeans;  // Spring 自动注入所有 @Component 工具

    public ToolRegistryService(ArgumentServiceApi argumentServiceApi, List<Object> toolBeans) {
        this.argumentServiceApi = argumentServiceApi;
        this.toolBeans = toolBeans;
    }

    /**
     * 获取已启用的工具列表（根据 sys_argument 表的 ai.tool.*.enabled 配置项过滤）
     * 复用脚手架通用参数表 sys_argument，config_key 格式：ai.tool.{toolName}.enabled
     */
    public List<Object> getEnabledTools() {
        return toolBeans.stream()
            .filter(tool -> {
                String toolName = tool.getClass().getSimpleName();
                String configKey = "ai.tool." + toolName.substring(0, 1).toLowerCase() + toolName.substring(1) + ".enabled";
                String value = argumentServiceApi.getByConfigKey(configKey);
                return !"false".equalsIgnoreCase(value);  // 默认启用，仅显式配置 false 才禁用
            })
            .collect(Collectors.toList());
    }
}
```

> **说明**：`ToolRegistryService`（原 `ToolConfig`）不负责通过 `@Bean` 注册工具（工具通过 `@Component` 自动扫描注册）。作为 `@Service`，其职责是通过 Feign 调用 `ArgumentServiceApi` 查询 `sys_argument` 表中 `ai.tool.*.enabled` 配置项，过滤出已启用的工具，供 `IChatService` 调用 `ChatClient` 时传入。改用 `@Service` 而非 `@Configuration` 的原因：此类包含业务逻辑（Feign 调用 + 过滤 + 注册），不是纯 Bean 定义类。
>
> **复用说明**：工具启用状态复用脚手架已有的 `sys_argument` 通用参数表（通过 `ArgumentServiceApi` Feign 调用），无需新建 `sys_ai_tool_config` 表。

**工具自动发现**：Spring AI 会自动扫描所有带有 `@Tool` 注解的方法，并注册到 `ToolExecutor` 中，Agent 可以自动调用这些工具。

**工具执行超时处理**：
- 工具调用超时时间通过 Nacos 配置项 `scaffold.tool.timeout` 控制（默认 30 秒），支持 `@RefreshScope` 动态刷新
- 超时后返回错误信息：`{"error": "工具执行超时", "toolName": "ReadFileTool"}`
- 工具执行失败不中断对话流，错误信息会传递给大模型，由模型决定是否重试或换用其他工具

> **配置项**（位于 `zmbdp-chat-service-${env}.yaml`）：
> ```yaml
> scaffold:
>   tool:
>     timeout: 30  # 工具调用超时时间（秒）
> ```

**工具启用/禁用机制**：
- 工具启用状态复用脚手架 `sys_argument` 通用参数表，config_key 格式：`ai.tool.{toolName}.enabled`（值：`true`/`false`），详见 07-项目架构设计.md 7.4.6节
- chat-service 通过 Feign 调用 `ArgumentServiceApi`（`/argument/key?configKey=xxx`）读取启用状态
- 配置值为 `false` 的工具不会被注册到 `ToolExecutor`，大模型无法调用
- 支持运行时动态启用/禁用（B端通过 admin-service 修改 sys_argument 表后刷新 ToolExecutor）

---

**文档版本**：v1.5  
**创建日期**：2026-07-12  
**最后更新**：2026-07-14  
**适用版本**：Scaffold AI Assistant v1.0