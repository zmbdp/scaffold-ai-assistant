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
| **v1.0** | C端AI助手 + B端完整管理 | 完整的RAG问答 + Agent工具调用 + 知识库管理 + 统计分析 + 用户管理（复用脚手架） + LangSmith集成 |
| **v2.0** | C端增强 | 多智能体协作、代码生成能力、知识库版本管理、多模型切换 |

### 1.4 端侧划分

| 端侧 | 用户角色 | 核心功能 |
|-----|---------|---------|
| **C端（用户端）** | 使用脚手架的开发者 | AI对话、历史记录、问题分类、引用查看 |
| **B端（管理端）** | 系统管理员 | 知识库管理、AI配置、使用统计、系统管理、工具管理 |

---

## 1.5 脚手架复用策略

核心原则：**尽量复用现有脚手架组件，仅新增AI相关的独有功能**

| 类别 | 复用内容 | 新增内容 |
|-----|---------|---------|
| **公共模块** | Result、ResultCode、BaseDO、TokenService、RedisService、@LogAction、@RateLimit、@Idempotent、分页组件 | - |
| **业务模块** | AppUserApi、FileServiceApi、sys_user表、app_user表 | ChatApi、KnowledgeApi等Feign接口 |
| **基础组件** | MySQL、Redis、Nacos、Gateway、Prometheus、Grafana | Milvus向量数据库 |
| **认证权限** | JWT认证、Token管理、Gateway用户来源控制 | AI模型调用权限控制（新增） |

### 1.5.1 权限控制机制说明

> **重要说明**：脚手架当前**未实现细粒度权限控制**（如 `@RequiresPermission` 注解式权限校验在代码库中不存在），权限控制仅通过 Gateway 的 AuthFilter 做粗粒度的"用户来源"校验。AI 模块需在此机制上扩展 B 端接口的访问控制。

**现有脚手架的 AuthFilter 实际逻辑**（[AuthFilter.java](file:///d:/GitHub/scaffold-ai-assistant/zmbdp-gateway/src/main/java/com/zmbdp/gateway/filter/AuthFilter.java)）：

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

> **⚠️ 关键差距**：当前 AuthFilter **不会**阻止 C 端用户访问 `/admin/app_user/**`、`/admin/dictionary/**` 等管理端接口。AI 模块新增的 `/admin/knowledge/**`、`/admin/ai/**`、`/admin/statistics/**`、`/admin/tools/**` 若不扩展 AuthFilter，**C 端用户也能直接访问**。

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

## 2. 技术栈选择

| 分类 | 技术 | 版本 | 说明 |
|-----|------|------|------|
| **语言** | Java | 21（运行时）/ 17（编译目标） | LTS版本，`<java.version>21</java.version>`，`<maven.compiler.source>17</maven.compiler.source>`，与脚手架 pom.xml 配置一致 |
| **框架** | Spring Boot | 3.3.3 | 与脚手架版本一致（`<version>3.3.3</version>`），稳定成熟 |
| **AI框架** | Spring AI Alibaba | 1.1.0.0-RC2 | 基于Spring AI，支持Agent、RAG、工具调用 |
| **向量数据库** | Milvus | 2.4.x | 专用向量数据库，性能优秀 |
| **大模型** | 通义千问/OpenAI | - | 可配置切换 |
| **构建工具** | Maven | 3.9+ | 与脚手架一致 |
| **部署方式** | Docker + docker-compose | - | 容器化部署 |

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

**文档版本**：v1.1  
**创建日期**：2026-07-12  
**最后更新**：2026-07-12  
**适用版本**：Scaffold AI Assistant v1.0