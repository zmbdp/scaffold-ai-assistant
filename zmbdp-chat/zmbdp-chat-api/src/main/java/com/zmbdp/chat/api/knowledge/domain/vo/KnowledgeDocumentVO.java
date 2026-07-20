package com.zmbdp.chat.api.knowledge.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识文档 VO
 * <p>
 * 文档列表 / 详情的返回对象，对应 sys_ai_document 表。
 *
 * @author 稚名不带撇
 */
@Data
public class KnowledgeDocumentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文档ID（雪花算法）
     */
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
     * 完整文档内容（仅详情接口返回，列表接口不返回以减小响应体）
     */
    private String content;

    /**
     * 类型（doc/javadoc/config/code）
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
     * 状态（ACTIVE/DELETED/SYNCING，对应 DocumentStatus 枚举）
     */
    private String status;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 分块数量
     */
    private Integer chunkCount;

    /**
     * 文件大小（字节，仅列表接口返回）
     */
    private Long fileSize;

    /**
     * 创建日期（格式：20260712）
     */
    private Long createDate;

    /**
     * 更新日期（格式：20260712）
     */
    private Long updateDate;
}
