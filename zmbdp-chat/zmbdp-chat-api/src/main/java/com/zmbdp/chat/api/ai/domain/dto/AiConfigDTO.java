package com.zmbdp.chat.api.ai.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 配置更新请求 DTO
 * <p>
 * 仅包含 B 端可动态调整的运行时业务参数（temperature、max_tokens、top_k、enable_rag、enable_tools），
 * 这些参数存储在 sys_argument 表的 ai.* 配置项中。
 * <p>
 * <b>不包含</b>：api-key、模型名称等基础设施配置，这些统一在 Nacos 管理，不通过本接口修改
 *
 * @author 稚名不带撇
 */
@Data
public class AiConfigDTO implements Serializable {

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
     * 温度参数（0.0-2.0，控制生成随机性）
     */
    @Min(value = 0, message = "温度参数最小为0")
    @Max(value = 2, message = "温度参数最大为2")
    private Double temperature;

    /**
     * 最大生成 Token 数
     */
    @Min(value = 1, message = "maxTokens最小为1")
    private Integer maxTokens;

    /**
     * RAG 检索数量（1-20）
     */
    @Min(value = 1, message = "topK最小为1")
    @Max(value = 20, message = "topK最大为20")
    private Integer topK;

    /**
     * 是否启用 RAG 检索
     */
    private Boolean enableRag;

    /**
     * 是否启用 Agent 工具调用
     */
    private Boolean enableTools;
}
