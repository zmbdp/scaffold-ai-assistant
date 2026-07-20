package com.zmbdp.chat.service.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * AI 文档表 sys_ai_document
 * <p>
 * 存储知识源下的具体文档元信息和完整内容，用于 RAG 检索的向量化处理。
 *
 * @author 稚名不带撇
 */
@Data
@TableName("sys_ai_document")
public class SysAiDocument {

    /**
     * 文档ID（雪花算法）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属知识源ID
     */
    private Long knowledgeSourceId;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文件路径
     */
    private String path;

    /**
     * 完整文档内容
     */
    private String content;

    /**
     * 类型（doc/javadoc/config/code，code=Java源码）
     */
    private String type;

    /**
     * 所属模块
     */
    private String module;

    /**
     * 功能类别
     */
    private String category;

    /**
     * 状态（ACTIVE/DELETED）
     */
    private String status;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 文件内容哈希（SHA-256，用于增量更新）
     */
    private String hash;

    /**
     * 分块数量
     */
    private Integer chunkCount;

    /**
     * 创建日期（格式：20260712）
     */
    private Long createDate;

    /**
     * 更新日期（格式：20260712）
     */
    private Long updateDate;
}
