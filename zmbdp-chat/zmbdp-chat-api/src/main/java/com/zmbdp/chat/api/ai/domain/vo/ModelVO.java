package com.zmbdp.chat.api.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 可用模型 VO
 * <p>
 * 展示 chat-service 已注册的可用模型列表，含模型名称、提供商、类型、能力等。
 * 对应 ModelProvider、ModelType、ModelCapability 枚举。
 *
 * @author 稚名不带撇
 */
@Data
public class ModelVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模型名称（如 deepseek-v4-flash、qwen-vl-max）
     */
    private String name;

    /**
     * 模型提供商（如 alibaba，对应 ModelProvider 枚举）
     */
    private String provider;

    /**
     * 模型类型（text/vision，对应 ModelType 枚举）
     */
    private String type;

    /**
     * 模型能力列表（如 ["text", "image"]，对应 ModelCapability 枚举）
     */
    private List<String> capabilities;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 模型描述
     */
    private String description;
}
