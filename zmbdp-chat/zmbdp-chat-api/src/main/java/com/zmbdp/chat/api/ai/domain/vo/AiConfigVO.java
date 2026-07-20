package com.zmbdp.chat.api.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 配置 VO
 * <p>
 * 返回当前 AI 模块的运行时配置。
 * <p>
 * <b>apiKey 字段</b>：脱敏显示（仅展示前后 4 位），api-key 属基础设施配置，
 * 统一在 Nacos 管理，不可通过更新接口修改。
 *
 * @author 稚名不带撇
 */
@Data
public class AiConfigVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 默认文本模型名称（如 deepseek-v4-flash）
     */
    private String defaultModel;

    /**
     * 默认视觉模型名称（如 qwen-vl-plus）
     */
    private String defaultVisionModel;

    /**
     * 温度参数
     */
    private Double temperature;

    /**
     * 最大生成 Token 数
     */
    private Integer maxTokens;

    /**
     * Embedding 模型名称（如 text-embedding-v1，仅展示不可修改）
     */
    private String embeddingModel;

    /**
     * RAG 检索数量
     */
    private Integer topK;

    /**
     * 是否启用 RAG 检索
     */
    private Boolean enableRag;

    /**
     * 是否启用 Agent 工具调用
     */
    private Boolean enableTools;

    /**
     * API Key（脱敏显示，如 sk-1***wxyz）
     */
    private String apiKey;
}
