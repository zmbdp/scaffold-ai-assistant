package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * 文档状态枚举
 * <p>
 * 表示文档在知识库中的可用状态，用于软删除机制（MySQL 标记 DELETED，Milvus 物理删除）。
 *
 * @author 稚名不带撇
 */
@Getter
public enum DocumentStatus {

    /**
     * 活跃状态，可检索
     */
    ACTIVE("ACTIVE", "活跃状态，可检索"),

    /**
     * 已删除，不可检索
     */
    DELETED("DELETED", "已删除，不可检索");

    /**
     * 状态编码（存入数据库 sys_ai_document.status 字段）
     */
    private final String code;

    /**
     * 状态描述
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code        状态编码
     * @param description 状态描述
     */
    DocumentStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 状态编码
     * @return 文档状态枚举，未匹配返回 null
     */
    public static DocumentStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (DocumentStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}