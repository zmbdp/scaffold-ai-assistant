package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 知识加载服务
 * <p>
 * 负责知识加载、分块处理；执行知识同步（增量/全量）。
 * <p>
 * <b>核心依赖</b>：SysAiKnowledgeSourceMapper、SysAiDocumentMapper、{@link IVectorStoreService}、
 * {@link IModelService}、RedissonLockService。
 * <p>
 * <b>加载范围</b>：docs/*.md 文档、JavaDoc HTML 文档、Nacos 配置和部署配置文件四类知识源，
 * 加载后同步写入 MySQL（sys_ai_document 表）和 Milvus（向量集合）。
 *
 * @author 稚名不带撇
 */
public interface IKnowledgeLoaderService {

    /*=============================================    内部调用    =============================================*/

    /**
     * 加载所有知识源
     * <p>
     * 依次加载 docs/*.md 文档、JavaDoc HTML 文档、Nacos 配置和部署配置文件，
     * 同步写入 MySQL 和 Milvus。
     */
    void loadAllKnowledge();

    /**
     * 加载 docs 目录下的 Markdown 文档
     * <p>
     * 扫描 docs 目录下所有 *.md 文件，解析为 Spring AI {@link Document} 列表。
     *
     * @return Markdown 文档列表
     */
    List<Document> loadDocs();

    /**
     * 加载 JavaDoc HTML 文档
     * <p>
     * 解析 JavaDoc HTML 文件，提取类/方法说明并转换为 Spring AI {@link Document} 列表。
     *
     * @return JavaDoc 文档列表
     */
    List<Document> loadJavaDoc();

    /**
     * 加载 Nacos 配置和部署配置文件
     * <p>
     * 通过 INacosConfigService 拉取 Nacos 配置中心内容，并读取本地部署配置文件，
     * 转换为 Spring AI {@link Document} 列表。
     *
     * @return 配置文件文档列表
     */
    List<Document> loadConfigs();

    /**
     * 根据文档类型分块处理
     * <p>
     * 根据文档类型（Markdown/JavaDoc/配置文件/Java 源码）选择对应的分块策略，
     * 将原文档拆分为多个 {@link Document} 分块。
     *
     * @param document 原始文档
     * @return 分块后的文档列表
     */
    List<Document> chunkDocument(Document document);

    /**
     * 执行知识同步
     * <p>
     * 执行 12 步同步流程（含 Redisson 分布式锁），支持增量/全量同步。
     *
     * @param force 是否强制全量同步（true=全量，false=增量）
     * @return 同步结果统计
     */
    SyncResultVO syncKnowledge(boolean force);
}
