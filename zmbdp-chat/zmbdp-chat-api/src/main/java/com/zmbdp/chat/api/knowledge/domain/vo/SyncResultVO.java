package com.zmbdp.chat.api.knowledge.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识同步结果 VO
 * <p>
 * 知识同步完成后返回的统计信息，用于展示同步效果。
 *
 * @author 稚名不带撇
 */
@Data
public class SyncResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 处理的文档总数
     */
    private Integer totalDocuments;

    /**
     * 新增 + 更新的文档数
     */
    private Integer updatedDocuments;

    /**
     * 删除的文档数
     */
    private Integer deletedDocuments;

    /**
     * 跳过的文档数（哈希未变）
     */
    private Integer skippedDocuments;

    /**
     * 处理失败的文档数
     */
    private Integer failedDocuments;

    /**
     * 同步耗时（毫秒）
     */
    private Long duration;
}
