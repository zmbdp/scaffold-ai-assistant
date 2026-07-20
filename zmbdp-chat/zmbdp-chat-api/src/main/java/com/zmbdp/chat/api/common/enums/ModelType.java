package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * 模型类型枚举
 * <p>
 * 定义 AI 模型的能力类型，用于多模型管理和路由选择。
 *
 * @author 稚名不带撇
 */
@Getter
public enum ModelType {

    /**
     * 纯文本模型（仅支持文本对话）
     */
    TEXT_ONLY("TEXT_ONLY", "纯文本模型", "仅文本对话"),

    /**
     * 图文模型（支持文本 + 图片对话）
     */
    TEXT_AND_IMAGE("TEXT_AND_IMAGE", "图文模型", "文本+图片对话"),

    /**
     * 嵌入模型（用于向量生成）
     */
    EMBEDDING("EMBEDDING", "嵌入模型", "向量生成");

    /**
     * 类型编码
     */
    private final String code;

    /**
     * 类型描述
     */
    private final String description;

    /**
     * 支持能力说明
     */
    private final String capability;

    /**
     * 构造函数
     *
     * @param code        类型编码
     * @param description 类型描述
     * @param capability  支持能力说明
     */
    ModelType(String code, String description, String capability) {
        this.code = code;
        this.description = description;
        this.capability = capability;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 类型编码
     * @return 模型类型枚举，未匹配返回 null
     */
    public static ModelType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ModelType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}