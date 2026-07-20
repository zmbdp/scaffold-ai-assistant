package com.zmbdp.chat.service.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * AI 知识源表 sys_ai_knowledge_source
 * <p>
 * 用于管理 RAG 检索的知识源（文档目录、JavaDoc、配置文件、Java 源码），
 * 支持分块配置和增量同步。
 *
 * @author 稚名不带撇
 */
@Data
@TableName("sys_ai_knowledge_source")
public class SysAiKnowledgeSource {

    /**
     * 知识源ID（雪花算法）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 知识源名称（不可重复）
     */
    private String name;

    /**
     * 文件路径（知识源根目录绝对路径）
     */
    private String path;

    /**
     * 类型（doc/javadoc/config/code，code=Java源码）
     */
    private String type;

    /**
     * 是否启用（1=启用，0=禁用）
     */
    private Integer enabled;

    /**
     * 分块大小（字符数）
     */
    private Integer chunkSize;

    /**
     * 分块重叠大小（字符数）
     */
    private Integer chunkOverlap;

    /**
     * 最后同步日期（格式：20260712，首次同步前为空）
     */
    private Long lastSyncDate;

    /**
     * 创建日期（格式：20260712）
     */
    private Long createDate;

    /**
     * 更新日期（格式：20260712）
     */
    private Long updateDate;
}
