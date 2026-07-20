package com.zmbdp.chat.service.config;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 模型配置 DTO（chat-service 内部使用）
 * <p>
 * 对应 Nacos 配置 {@code spring.ai.models} 列表中的单个模型配置项，
 * 由 {@code ModelServiceImpl} 在 {@code @PostConstruct} 时从 Nacos 读取并解析。
 * <p>
 * <b>不放在 chat-api 模块</b>：本类仅在 chat-service 内部使用，不跨模块传递。
 * 跨模块返回的模型信息使用 {@link com.zmbdp.chat.api.ai.domain.vo.ModelVO}。
 *
 * @author 稚名不带撇
 */
@Data
public class ModelConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模型类型（TEXT_ONLY：纯文本，TEXT_AND_IMAGE：图文多模态）
     */
    private String type;

    /**
     * 模型名称（如 deepseek-v4-flash、qwen-vl-plus）
     */
    private String name;

    /**
     * 模型提供商（如 dashscope）
     */
    private String provider;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 是否为默认模型（同一 type 下仅一个 default=true）
     */
    private Boolean defaultModel;

    /**
     * 模型能力列表（如 ["TEXT"]、["TEXT", "IMAGE"]）
     */
    private List<String> capabilities;
}
