# 📄 Scaffold AI Assistant 设计文档

> **文档说明**：本文档是 Scaffold AI Assistant 项目的主设计文档，包含项目概述和各子模块的引用链接。详细设计请查看各子文档。

---

## 1. 项目概述

### 1.1 项目目标
打造一个"脚手架专家AI"，成为脚手架的"活文档"和"开发顾问"，能够回答与脚手架相关的所有问题。

### 1.2 定位
- **目标用户**：使用 scaffold-ai-assistant 脚手架的开发者
- **产品形态**：B端管理后台 + C端AI助手
- **核心价值**：降低学习成本，提高开发效率

### 1.3 版本规划

| 版本 | 阶段 | 目标 |
|-----|------|------|
| **v1.0** | C端AI助手 + B端完整管理 | 完整的RAG问答 + Agent工具调用 + 知识库管理 + 统计分析 + 用户管理（复用脚手架） + 回答反馈机制 + AI 可观测性（SkyWalking + Prometheus，不使用 LangSmith） |
| **v2.0** | C端增强 | 多智能体协作、伪代码示例生成（基于源码生成伪代码作为RAG知识库补充内容）、知识库版本管理、多模型切换、脚手架版本升级追踪 |

### 1.4 端侧划分

| 端侧 | 用户角色 | userFrom | 认证入口 | 核心功能 |
|-----|---------|---------|---------|---------|
| **C端（用户端）** | 使用脚手架的开发者 | `app` | portal-service `/login/code`（手机号/邮箱验证码）、`/login/wechat`（微信openId） | AI对话、历史记录、问题分类、引用查看、回答反馈 |
| **B端（管理端）** | 系统管理员 | `sys` | admin-service 现有登录接口（复用脚手架） | 知识库管理、AI配置、使用统计、系统管理、工具管理 |

> **说明**：`userFrom` 字段由后端根据登录入口硬编码设置（C端登录固定设为 `app`，B端登录固定设为 `sys`），前端不传入。Gateway 的 AuthFilter 通过 JWT 中的 `userFrom` 字段做来源校验，区分B端/C端访问权限。

---

## 1.5 脚手架复用策略

核心原则：**尽量复用现有脚手架组件，仅新增AI相关的独有功能**

| 类别 | 复用内容 | 新增内容 |
|-----|---------|---------|
| **公共模块** | Result、ResultCode、BaseDO、TokenService、RedisService、@LogAction、@RateLimit、@Idempotent、分页组件 | - |
| **业务模块** | AppUserApi、FileServiceApi、sys_user表、app_user表 | ChatApi、KnowledgeApi等Feign接口 |
| **基础组件** | MySQL、Redis、Nacos、Gateway、Prometheus、Grafana | Milvus向量数据库 |
| **认证权限** | JWT认证、Token管理 | 扩展Gateway AuthFilter（对 `/admin/knowledge/**`、`/admin/ai/**` 等新增B端路径做 `userFrom="sys"` 校验）、AI模型调用权限控制（`sys_ai_permission` 表） |

### 1.5.1 权限控制机制说明

> **重要说明**：脚手架当前**未实现细粒度权限控制**（如 `@RequiresPermission` 注解式权限校验在代码库中不存在），权限控制仅通过 Gateway 的 AuthFilter 做粗粒度的"用户来源"校验。AI 模块需在此机制上扩展 B 端接口的访问控制。

**现有脚手架的 AuthFilter 实际逻辑**（[AuthFilter.java](../../zmbdp-gateway/src/main/java/com/zmbdp/gateway/filter/AuthFilter.java)）：

```java
// 仅对路径包含 "sys_user" 字符串的请求做来源校验
if (url.contains(HttpConstants.SYS_USER_PATH) &&        // SYS_USER_PATH = "sys_user"
    !UserConstants.USER_FROM_TU_B.equals(userFrom)) {    // 要求 userFrom = "sys"
    return unauthorizedResponse(exchange, ResultCode.TOKEN_CHECK_FAILED);
}
```

| 控制维度 | 实现方式 | 实际控制范围 |
|---------|---------|------------|
| **白名单** | `IgnoreWhiteProperties` 读取 `security.ignore.whites` 配置 | 白名单路径完全放行（如 `/admin/codeLogin`、`/**/login/**` 等） |
| **Token 校验** | AuthFilter 检查 JWT 有效性 + Redis 中是否存在 | 所有非白名单请求都需要有效 Token |
| **用户来源校验** | AuthFilter 仅对**路径包含字符串 `sys_user`** 的请求校验 | 只阻止 C 端用户（userFrom=app）访问 `/admin/sys_user/**` 路径 |
| **用户身份标识** | sys_user 表的 `identity` 字段（`super_admin`/`platform_admin`） | 仅作标识存储，**未实现细粒度权限控制** |

**现有 AuthFilter 的实际行为**：

| 路径 | C 端用户（userFrom=app） | B 端用户（userFrom=sys） | 说明 |
|------|----------------------|----------------------|------|
| `/admin/sys_user/**` | ❌ 拒绝 | ✅ 放行 | 唯一受来源校验的路径 |
| `/admin/app_user/**` | ✅ 放行（只要有有效 token） | ✅ 放行 | **未做来源校验** |
| `/admin/dictionary/**`、`/admin/argument/**`、`/admin/map/**` | ✅ 放行 | ✅ 放行 | **未做来源校验** |
| `/portal/user/**` | ✅ 放行 | ✅ 放行 | **未做来源校验** |
| 白名单路径（如 `/admin/codeLogin`） | ✅ 放行（无需 token） | ✅ 放行 | 完全放行 |

> **⚠️ 关键差距（安全漏洞）**：当前 AuthFilter **不会**阻止 C 端用户访问 `/admin/app_user/**`、`/admin/dictionary/**` 等管理端接口。AI 模块新增的 `/admin/knowledge/**`、`/admin/ai/**`、`/admin/statistics/**`、`/admin/tools/**` 若不扩展 AuthFilter，**C 端用户也能直接访问**。

**🔒 v1.0 安全需求（必须实现）**：

扩展 Gateway AuthFilter，对 `/admin/**` 路径统一做 `userFrom="sys"` 校验（白名单路径如 `/admin/codeLogin` 除外），修复脚手架现有的来源校验缺口。具体规则：

| 路径匹配规则 | 校验逻辑 | 说明 |
|------------|---------|------|
| `/admin/sys_user/**` | `userFrom` 必须为 `sys`（现有逻辑，保持不变） | 系统用户管理 |
| `/admin/knowledge/**`、`/admin/ai/**`、`/admin/statistics/**`、`/admin/tools/**` | `userFrom` 必须为 `sys`（**新增**） | AI模块B端接口 |
| `/admin/app_user/**`、`/admin/dictionary/**`、`/admin/argument/**`、`/admin/map/**` | `userFrom` 必须为 `sys`（**新增**，修复现有缺口） | 其他B端管理接口 |
| `/admin/codeLogin`、`/**/login/**`、`/**/send_code/**` 等白名单路径 | 不校验（放行） | 登录/验证码等公共接口 |

> **实现方式**：将 AuthFilter 中 `url.contains("sys_user")` 的判断逻辑改为 `url.startsWith("/admin/") && !isWhitelist(url)`，统一对所有 `/admin/**` 路径（白名单除外）做 `userFrom="sys"` 校验。

**AI 模块的权限扩展方案**：

| 控制维度 | 实现方式 | 说明 |
|---------|---------|------|
| **B 端 AI 接口访问控制** | 扩展 AuthFilter，对 `/admin/knowledge/**`、`/admin/ai/**`、`/admin/statistics/**`、`/admin/tools/**` 路径增加 `userFrom="sys"` 校验 | **新增功能**，需修改 AuthFilter 代码 |
| **模型调用权限** | 新增 `sys_ai_permission` 表，关联用户与可用模型 | 控制用户可调用哪些大模型 |

**网关路由规则**（来自 `zmbdp-gateway-service-dev.yaml`，dev 和 prd 一致）：

| 路由前缀 | 目标服务 | 说明 |
|---------|---------|------|
| `/portal/**` | zmbdp-portal-service | C 端接口（StripPrefix=1） |
| `/admin/**` | zmbdp-admin-service | B 端接口（StripPrefix=1） |
| `/file/**` | zmbdp-file-service | 文件服务 |
| `/mstemplate/**` | zmbdp-mstemplate-service | 模板服务 |

> **注意**：AI 模块新增的 C 端接口（AI 对话）走 `/portal/chat/**`、`/portal/history/**`；B 端管理接口走 `/admin/knowledge/**`、`/admin/ai/**` 等。**不是** `/chat/**` 或 `/sys/**`。

---

## 1.6 非功能性需求

| 维度 | 指标 | 说明 |
|------|------|------|
| **性能-响应时间** | AI对话首字响应 < 3秒 | 从前端发起请求到收到第一个SSE chunk的时间（含RAG检索+Prompt拼接+LLM首token） |
| **性能-检索延迟** | RAG检索 < 500ms | Milvus向量检索 + Reranking重排序的总耗时 |
| **性能-完整对话** | 单轮对话完整响应 < 30秒 | 从请求到SSE流结束的总耗时（超时则返回超时帧，错误码500024） |
| **可用性-降级** | RAG检索失败时降级为纯对话 | RAG/Reranking失败不中断对话流程，记录warning日志，使用空上下文继续 |
| **可用性-降级** | 工具调用失败时降级为纯RAG回答 | Agent工具执行失败不中断对话，返回错误信息给LLM由其自行处理 |
| **安全-路径白名单** | ReadFileTool等工具仅允许访问项目目录 | 通过 `knowledge.allowed-paths` 动态白名单 + `knowledge.path-blacklist` 黑名单双重校验 |
| **安全-API Key存储** | 通义千问API Key加密存储 | 存储在Nacos配置中（不硬编码），B端展示时脱敏（前后4位） |
| **安全-限流** | C端单用户对话限流 | 通过 `@RateLimit` 注解限制单用户每分钟对话次数（默认100次/分钟） |
| **成本-Token控制** | 单次对话Token上限 | 通过 `max_tokens` 配置（默认4096）限制单次对话输出长度 |
| **成本-Embedding缓存** | 相同查询向量缓存 | 检索时对相同query的Embedding结果做Redis缓存（TTL 1小时），减少Embedding API调用 |
| **容量-向量库** | Milvus单节点支持百万级向量 | 生产环境初期单节点部署，每个分块1536维，单节点可支撑约100万分块 |
| **容量-对话历史** | 对话历史保留30天 | 定时任务 `cleanExpiredHistory` 每天凌晨3点清理超过30天的对话记录 |
| **容量-操作日志** | 操作日志保留90天 | 定时任务 `cleanExpiredLogs` 每天凌晨4点清理超过90天的操作日志 |

---

## 2. 技术栈选择

| 分类 | 技术 | 版本 | 说明 |
|-----|------|------|------|
| **语言** | Java | 21（运行时）/ 17（编译目标） | LTS版本，`<java.version>21</java.version>`，`<maven.compiler.source>17</maven.compiler.source>`，与脚手架 pom.xml 配置一致 |
| **框架** | Spring Boot | 3.3.3 | 与脚手架版本一致（`<version>3.3.3</version>`），稳定成熟 |
| **AI框架** | Spring AI Alibaba | 1.0.0 GA | 2026年5月13日正式发布的GA版本，基于Spring AI 1.0 GA，兼容Spring Boot 3.3+，支持Agent、RAG、工具调用 |
| **向量数据库** | Milvus | 2.4.x | 专用向量数据库，性能优秀 |
| **大模型** | 通义千问/OpenAI | - | 可配置切换 |
| **构建工具** | Maven | 3.9+ | 与脚手架一致 |
| **部署方式** | Docker + docker-compose | - | 容器化部署 |

> **版本选型说明**：
> - **Spring AI Alibaba 1.0.0 GA**（2026-05-13发布）是首个正式版，比候选版 RC2 更稳定，适合生产环境使用
> - 兼容性：Spring AI 1.0 GA 要求 Spring Boot 3.3+ / JDK 17+，与本项目（Spring Boot 3.3.3 / Java 21）完全兼容
> - **应对策略**：a) 锁定版本号 `1.0.0` 避免自动升级引入不兼容变更；b) 通过 `IModelService` 封装 AI 调用层，隔离框架内部API变动；c) 关注官方后续版本发布，经测试验证后再升级

---

## 3. C端功能设计

**详情请查看**：[03-C端功能设计.md](03-C端功能设计.md)

---

## 4. B端功能设计

**详情请查看**：[04-B端功能设计.md](04-B端功能设计.md)

---

## 5. Agent工具设计

**详情请查看**：[05-Agent工具设计.md](05-Agent工具设计.md)

---

## 6. RAG流程设计

**详情请查看**：[06-RAG流程设计.md](06-RAG流程设计.md)

---

## 7. 项目架构设计

**详情请查看**：[07-项目架构设计.md](07-项目架构设计.md)

---

## 8. API接口设计

**详情请查看**：[08-API接口设计.md](08-API接口设计.md)

---

## 9. 部署方案

**详情请查看**：[09-部署方案.md](09-部署方案.md)

---

## 10. 测试与开发计划

**详情请查看**：[10-测试与开发计划.md](10-测试与开发计划.md)

---

## 文档目录结构

```
scaffold项目设计docs/
├── Scaffold-AI-Assistant设计文档.md    # 主文档（概述+引用）
├── 03-C端功能设计.md                   # C端用户功能详细设计
├── 04-B端功能设计.md                   # B端管理功能详细设计
├── 05-Agent工具设计.md                 # Agent工具详细设计
├── 06-RAG流程设计.md                   # RAG检索流程设计
├── 07-项目架构设计.md                   # 项目架构、核心类、数据库设计
├── 08-API接口设计.md                   # API接口详细设计
├── 09-部署方案.md                       # Docker、docker-compose、Nacos配置
└── 10-测试与开发计划.md                 # 黄金测试集、开发计划、风险
```

---

**文档版本**：v1.2  
**创建日期**：2026-07-12  
**最后更新**：2026-07-14  
**适用版本**：Scaffold AI Assistant v1.0