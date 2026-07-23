package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.chat.service.domain.entity.SysAiDocument;
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
     * 执行 12 步同步流程（含 Redisson 分布式锁），支持增量/全量同步、按知识源类型过滤。
     *
     * @param sourceType 知识源类型过滤（doc/javadoc/config/code，传 null 或 "all" 表示全部）
     * @param force      是否强制全量同步（true=全量，false=增量）
     * @return 同步结果统计
     */
    SyncResultVO syncKnowledge(String sourceType, boolean force);

    /**
     * 上传单个文档到指定知识源
     * <p>
     * 将文件内容保存到知识源 path 目录下，并立即对该文件执行分块、向量化、写入 Milvus
     * （复用 {@code processAddedFile} 逻辑），无需等待定时同步任务。
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *     <li>校验知识源存在且 enabled=1</li>
     *     <li>拼接完整文件路径：{@code resolveSourcePath(source.path) + File.separator + fileName}</li>
     *     <li>校验文件不存在（避免覆盖已有文件）</li>
     *     <li>保存文件内容到磁盘（自动创建父目录）</li>
     *     <li>调用 {@code processAddedFile} 处理新文件（读内容→算哈希→分块→插 MySQL→写 Milvus）</li>
     *     <li>返回新插入的 {@link SysAiDocument}（含生成的 ID）</li>
     * </ol>
     * <p>
     * <b>失败回滚</b>：若 Embedding 或 Milvus 写入失败，{@code processAddedFile} 内部会删除
     * sys_ai_document 记录，但磁盘文件保留（便于用户排查后重新上传）。
     *
     * @param knowledgeSourceId 知识源ID
     * @param fileName          文件名（含扩展名，如 ADD_NEW_MODULE.md）
     * @param content           文件文本内容
     * @return 新插入的文档记录（含生成的 ID、version=1、status=ACTIVE）
     * @throws com.zmbdp.common.domain.exception.ServiceException 知识源不存在、文件已存在、或向量化失败
     */
    SysAiDocument uploadDocument(Long knowledgeSourceId, String fileName, String content);
}
