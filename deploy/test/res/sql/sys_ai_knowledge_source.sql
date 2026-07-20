use scaffold-ai-assistant_test;

-- ======================================================================================
-- AI 知识源数据初始化（test 环境）
-- ======================================================================================
-- 说明：
-- 1. path 字段使用相对路径，运行时会与 Nacos 配置 knowledge.base-path 拼接成绝对路径
--    test 环境 base-path: /knowledge（由根 pom 的 knowledge.path 属性配置，挂载宿主机 /opt/scaffold-ai-assistant/frameworkjava 到容器 /knowledge）
-- 2. 仅清理脚本内置的 ID 段（9001-9050），不影响用户其他手动配置的记录
-- 3. 粒度设计参考 Dify/FastGPT 等主流 RAG 平台：
--    - doc 类型：每个 markdown 文件一条记录，便于单独启用/禁用、按文档差异化配置 chunk_size
--    - code 类型：按服务模块一条记录（不是每个文件），避免条目过多
--    - javadoc/config 类型：整个目录一条记录
-- 4. chunk_size 差异化策略（基于 RAG 最佳实践）：
--    - 指南类文档 1500/200（需完整上下文）
--    - 核心技术文档 1200/150（需精准检索）
--    - FAQ 问答类 800/100（小切片避免跨问题噪声）
--    - 源码 800/100（按类切分）
-- 5. 执行本 SQL 后，需通过以下任一方式触发知识同步（将文档切片后写入 Milvus 向量库）：
--    a) 在 XXL-JOB 控制台手动触发 knowledgeSyncJob 任务（推荐，参数传 true 强制全量同步）
--    b) 调用 admin-service 的"立即同步知识库"接口
-- 6. 同步完成后，可在 sys_ai_document 表查看已同步的文档清单
-- ======================================================================================

-- 清理脚本内置的 ID 段（9001-9050），保证脚本可重复执行
DELETE FROM sys_ai_knowledge_source WHERE id BETWEEN 9001 AND 9050;

-- 插入知识源数据
INSERT INTO sys_ai_knowledge_source (id, name, path, type, enabled, chunk_size, chunk_overlap, last_sync_date, create_date, update_date) VALUES
-- ===== doc 类型：脚手架使用文档（每个文件一条，便于精细化管理） =====
-- 指南类文档（1500/200，需完整上下文）
(9001, '新增业务模块指南',     'docs/ADD_NEW_MODULE.md',     'doc', 1, 1500, 200, NULL, 20260719, 20260719),
(9003, '配置中心与多环境管理', 'docs/CONFIGURATION.md',      'doc', 1, 1500, 200, NULL, 20260719, 20260719),
(9005, '部署指南',             'docs/DEPLOYMENT.md',         'doc', 1, 1500, 200, NULL, 20260719, 20260719),
(9013, '性能与并发设计',       'docs/PERFORMANCE.md',        'doc', 1, 1500, 200, NULL, 20260719, 20260719),
(9014, '项目结构与模块职责',   'docs/PROJECT_STRUCTURE.md',  'doc', 1, 1500, 200, NULL, 20260719, 20260719),
(9018, '工具类使用指南',       'docs/UTILS.md',              'doc', 1, 1500, 200, NULL, 20260719, 20260719),
-- 核心技术文档（1200/150，需精准检索）
(9002, '三级缓存架构',         'docs/CACHE_ARCHITECTURE.md', 'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9004, '数据权限控制',         'docs/DATAPERMISSION.md',     'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9006, 'Excel导入导出',        'docs/EXCEL.md',              'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9008, '网关服务',             'docs/GATEWAY.md',            'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9009, '分布式幂等性',         'docs/IDEMPOTENT.md',         'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9010, '操作日志',             'docs/LOG.md',                'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9011, '消息与验证码服务',     'docs/MESSAGE.md',            'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9012, '服务监控与告警',       'docs/MONITORING.md',         'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9015, '频控防刷',             'docs/RATELIMIT.md',          'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9016, '安全认证与异常处理',   'docs/SECURITY.md',           'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9017, '链路追踪',             'docs/TRACING.md',            'doc', 1, 1200, 150, NULL, 20260719, 20260719),
(9019, '分布式定时任务XXLJOB', 'docs/XXLJOB.md',             'doc', 1, 1200, 150, NULL, 20260719, 20260719),
-- 问答类（800/100，小切片避免跨问题噪声）
(9007, '常见问题FAQ',          'docs/FAQ.md',                'doc', 1,  800, 100, NULL, 20260719, 20260719),

-- ===== code 类型：服务源码（按模块一条，按 java 扩展名递归扫描） =====
(9021, 'Admin服务源码',         'zmbdp-admin',                   'code', 1, 800, 100, NULL, 20260719, 20260719),
(9022, 'Portal服务源码',        'zmbdp-portal',                  'code', 1, 800, 100, NULL, 20260719, 20260719),
(9023, 'File服务源码',          'zmbdp-file',                    'code', 1, 800, 100, NULL, 20260719, 20260719),
(9024, 'Gateway服务源码',       'zmbdp-gateway',                 'code', 1, 800, 100, NULL, 20260719, 20260719),
(9025, 'Common公共模块源码',    'zmbdp-common',                  'code', 1, 800, 100, NULL, 20260719, 20260719),

-- ===== javadoc 类型：JavaDoc API 文档（按 html/htm 扩展名递归扫描） =====
(9031, 'JavaDoc文档',           'javapro/javadoc',               'javadoc', 1, 1200, 150, NULL, 20260719, 20260719),

-- ===== config 类型：部署配置文件（按 yaml/yml/properties 扩展名递归扫描） =====
-- test 环境部署配置在 deploy/test/app 下（含 docker-compose、prometheus、redis、nacos 等配置）
(9041, '部署配置文件',           'deploy/test/app',               'config', 1, 1000, 150, NULL, 20260719, 20260719);
