package com.zmbdp.chat.api.chat.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * RAG 检索文档分块 VO
 * <p>
 * 表示一次 RAG 检索命中的文档分块，含内容、来源、相似度等信息。
 * 用于 {@code ChatApi.retrieveContext()} 和 {@code KnowledgeApi.retrieveTest()} 的返回结果。
 *
 * @author 稚名不带撇
 */
@Data
public class DocumentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分块内容（向量化前的文本）
     */
    private String content;

    /**
     * 文档ID（关联 sys_ai_document 表主键）
     */
    private Long documentId;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 所属模块（如 common-cache）
     */
    private String module;

    /**
     * 源文件路径
     */
    private String sourcePath;

    /**
     * 相似度分数（0-1，越高越相关，由 Reranking 模型给出）
     */
    private Double score;

    /**
     * 分块索引（在同一文档中的序号，从 0 开始）
     */
    private Integer chunkIndex;
}
