package com.zmbdp.chat.api.knowledge.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识同步请求 DTO
 * <p>
 * 触发知识库同步：扫描知识源路径下文件 → 哈希比对增量更新 → 分块 → 向量化 → 写入 Milvus。
 *
 * @author 稚名不带撇
 */
@Data
public class SyncReqDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 同步类型（可选，all/doc/code，默认 all）
     */
    private String sourceType;

    /**
     * 是否强制重新同步（可选，默认 false，仅同步哈希变更的文件）
     */
    private Boolean force;
}
