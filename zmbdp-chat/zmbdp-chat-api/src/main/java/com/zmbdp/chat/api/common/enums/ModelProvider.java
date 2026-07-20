package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * 模型供应商枚举
 * <p>
 * 定义 AI 模型的供应商，用于多模型扩展。v1.0 仅实现 DASHSCOPE（通义千问）。
 *
 * @author 稚名不带撇
 */
@Getter
public enum ModelProvider {

    /**
     * 通义千问（阿里云 DashScope）
     */
    DASHSCOPE("DASHSCOPE", "通义千问（阿里）"),

    /**
     * OpenAI
     */
    OPENAI("OPENAI", "OpenAI"),

    /**
     * 智谱 AI
     */
    ZHIPU("ZHIPU", "智谱AI"),

    /**
     * Google Gemini
     */
    GEMINI("GEMINI", "Google Gemini");

    /**
     * 供应商编码
     */
    private final String code;

    /**
     * 供应商描述
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code        供应商编码
     * @param description 供应商描述
     */
    ModelProvider(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 供应商编码
     * @return 模型供应商枚举，未匹配返回 null
     */
    public static ModelProvider fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ModelProvider provider : values()) {
            if (provider.getCode().equals(code)) {
                return provider;
            }
        }
        return null;
    }
}