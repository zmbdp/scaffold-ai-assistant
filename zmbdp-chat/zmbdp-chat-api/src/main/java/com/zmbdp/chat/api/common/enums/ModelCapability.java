package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * 模型能力枚举
 * <p>
 * 定义 AI 模型支持的能力维度，用于模型选择和路由。
 *
 * @author 稚名不带撇
 */
@Getter
public enum ModelCapability {

    /**
     * 文本生成
     */
    TEXT("TEXT", "文本生成"),

    /**
     * 图片理解
     */
    IMAGE("IMAGE", "图片理解"),

    /**
     * 向量嵌入
     */
    EMBEDDING("EMBEDDING", "向量嵌入"),

    /**
     * 函数调用（工具调用）
     */
    FUNCTION_CALL("FUNCTION_CALL", "函数调用");

    /**
     * 能力编码
     */
    private final String code;

    /**
     * 能力描述
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code        能力编码
     * @param description 能力描述
     */
    ModelCapability(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 能力编码
     * @return 模型能力枚举，未匹配返回 null
     */
    public static ModelCapability fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ModelCapability capability : values()) {
            if (capability.getCode().equals(code)) {
                return capability;
            }
        }
        return null;
    }
}