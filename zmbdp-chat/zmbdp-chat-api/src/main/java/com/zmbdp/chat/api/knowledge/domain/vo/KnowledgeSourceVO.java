package com.zmbdp.chat.api.knowledge.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识源 VO
 * <p>
 * 知识源列表 / 详情 / 新增后的返回对象。
 *
 * @author 稚名不带撇
 */
@Data
public class KnowledgeSourceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 知识源ID（雪花算法）
     */
    private Long id;

    /**
     * 知识源名称
     */
    private String name;

    /**
     * 知识源路径
     */
    private String path;

    /**
     * 类型（doc/javadoc/config/code）
     */
    private String type;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 分块大小
     */
    private Integer chunkSize;

    /**
     * 分块重叠大小
     */
    private Integer chunkOverlap;

    /**
     * 最近同步日期（格式：20260712）
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
